package hex;

import hex.ensemble.StackedEnsemble;
import hex.genmodel.utils.DistributionFamily;
import hex.glm.GLMModel;
import hex.tree.drf.DRFModel;
import water.*;
import water.exceptions.H2OIllegalArgumentException;
import water.fvec.Frame;
import water.fvec.Vec;
import water.nbhm.NonBlockingHashSet;
import water.util.Log;
import water.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.Arrays;

import static hex.Model.Parameters.FoldAssignmentScheme.Modulo;

/**
 * An ensemble of other models, created by <i>stacking</i> with the SuperLearner algorithm or a variation.
 */
public class StackedEnsembleModel extends Model<StackedEnsembleModel,StackedEnsembleModel.StackedEnsembleParameters,StackedEnsembleModel.StackedEnsembleOutput> {

  public ModelCategory modelCategory;
  public Frame commonTrainingFrame = null;
  public String responseColumn = null;
  private NonBlockingHashSet<String> names = null;  // keep columns as a set for easier comparison
  private NonBlockingHashSet<String> ignoredColumns = null;  // keep ignored_columns as a set for easier comparison
  public int nfolds = -1;
  // TODO: add a separate holdout dataset for the ensemble
  // TODO: add a separate overall cross-validation for the ensemble, including _fold_column and FoldAssignmentScheme / _fold_assignment

  public StackedEnsembleModel(Key selfKey, StackedEnsembleParameters parms, StackedEnsembleOutput output) {
    super(selfKey, parms, output);
  }

  public static class StackedEnsembleParameters extends Model.Parameters {
    public String algoName() { return "StackedEnsemble"; }
    public String fullName() { return "Stacked Ensemble"; }
    public String javaName() { return StackedEnsembleModel.class.getName(); }
    @Override public long progressUnits() { return 1; }  // TODO

    public static enum SelectionStrategy { choose_all }

    // TODO: make _selection_strategy an object:
    /** How do we choose which models to stack? */
    public SelectionStrategy _selection_strategy;

    /** Which models can we choose from? */
    public Key<Model> _base_models[] = new Key[0];
  }

  public static class StackedEnsembleOutput extends Model.Output {
    public StackedEnsembleOutput() { super(); }
    public StackedEnsembleOutput(StackedEnsemble b) { super(b); }

    public StackedEnsembleOutput(Job job) { _job = job; }
    // The metalearner model (e.g., a GLM that has a coefficient for each of the base_learners).
    public Model _metalearner;
  }

  /**
   * For StackedEnsemble we call score on all the base_models and then combine the results
   * with the metalearner to create the final predictions frame.
   *
   * @see Model#predictScoreImpl(Frame, Frame, String, Job, boolean)
   * @param adaptFrm Already adapted frame
   * @param computeMetrics
   * @return A Frame containing the prediction column, and class distribution
   */
  protected Frame predictScoreImpl(Frame fr, Frame adaptFrm, String destination_key, Job j, boolean computeMetrics) {
    // Build up the names & domains.
    String[] names = makeScoringNames();
    String[][] domains = new String[names.length][];
    domains[0] = names.length == 1 ? null : !computeMetrics ? _output._domains[_output._domains.length-1] : adaptFrm.lastVec().domain();

    // TODO: optimize these DKV lookups:
    Frame levelOneFrame = new Frame(Key.<Frame>make("preds_levelone_" + this._key.toString() + fr._key));
    int baseIdx = 0;
    Frame[] base_prediction_frames = new Frame[this._parms._base_models.length];

    // TODO: don't score models that have 0 coefficients / aren't used by the metalearner.
    for (Key<Model> baseKey : this._parms._base_models) {
      Model base = baseKey.get();  // TODO: cacheme!

      // adapt fr for each base_model

      // TODO: cache: don't need to call base.adaptTestForTrain() if the
      // base_model has the same names and domains as one we've seen before.
      // Such base_models can share their adapted frame.
      Frame adaptedFrame = new Frame(fr);
      base.adaptTestForTrain(adaptedFrame, true, computeMetrics);

      // TODO: parallel scoring for the base_models
      BigScore baseBs = (BigScore)base.makeBigScore(domains, names, adaptedFrame, computeMetrics, j).doAll(names.length, Vec.T_NUM, adaptedFrame);
      Frame basePreds = baseBs.outputFrame(Key.<Frame>make("preds_base_" + this._key.toString() + fr._key), names, domains);
      base_prediction_frames[baseIdx] = basePreds;
      StackedEnsemble.addModelPredictionsToLevelOneFrame(base, basePreds, levelOneFrame);

      Model.cleanup_adapt(adaptedFrame, fr);

      baseIdx++;
    }

    levelOneFrame.add(this.responseColumn, adaptFrm.vec(this.responseColumn));

    // TODO: what if we're running multiple in parallel and have a name collision?
    DKV.put(levelOneFrame);
    Log.info("Finished creating \"level one\" frame for scoring: " + levelOneFrame.toString());

    // Score the dataset, building the class distribution & predictions

    Model metalearner = this._output._metalearner;
    Frame levelOneAdapted = new Frame(levelOneFrame);
    metalearner.adaptTestForTrain(levelOneAdapted, true, computeMetrics);

    DKV.put(levelOneAdapted);

    String[] metaNames = metalearner.makeScoringNames();
    String[][] metaDomains = new String[metaNames.length][];
    metaDomains[0] = metaNames.length == 1 ? null : !computeMetrics ? metalearner._output._domains[metalearner._output._domains.length-1] : levelOneAdapted.lastVec().domain();

    BigScore metaBs = (BigScore)metalearner.makeBigScore(metaDomains, metaNames, levelOneAdapted, computeMetrics, j).
            doAll(metaNames.length, Vec.T_NUM, levelOneAdapted);

    if (computeMetrics) {
      ModelMetrics mmMetalearner = metaBs._mb.makeModelMetrics(metalearner, levelOneFrame, levelOneAdapted, metaBs.outputFrame());

      // This has just stored a ModelMetrics object for the (metalearner, preds_levelone) Model/Frame pair.
      // We need to be able to look it up by the (this, fr) pair.
      // The ModelMetrics object for the metalearner will be removed when the metalearner is removed.
      ModelMetrics mmStackedEnsemble = mmMetalearner.deepCloneWithDifferentModelAndFrame(this, fr);
      this.addModelMetrics(mmStackedEnsemble);
    }

    Model.cleanup_adapt(levelOneAdapted, levelOneFrame);
    return metaBs.outputFrame(Key.<Frame>make(destination_key), metaNames, metaDomains);
  }



  /**
   * Should never be called: the code paths that normally go here should call predictScoreImpl().
   * @see Model#score0(double[], double[])
   */
  @Override
  protected double[] score0(double data[/*ncols*/], double preds[/*nclasses+1*/]) {
    throw new UnsupportedOperationException("StackedEnsembleModel.score0() should never be called: the code paths that normally go here should call predictScoreImpl().");
  }

  @Override public ModelMetrics.MetricBuilder makeMetricBuilder(String[] domain) {
    switch (_output.getModelCategory()) {
      case Binomial:
        return new ModelMetricsBinomial.MetricBuilderBinomial(domain);
      // case Multinomial: return new ModelMetricsMultinomial.MetricBuilderMultinomial(_output.nclasses(),domain);
      case Regression:
        return new ModelMetricsRegression.MetricBuilderRegression();
      default:
        throw H2O.unimpl();
    }
  }

  public ModelMetrics doScoreMetricsOneFrame(Frame frame, Job job) {
      this.predictScoreImpl(frame, new Frame(frame), null, job, true);
      return ModelMetrics.getFromDKV(this, frame);
  }

  public void doScoreMetrics(Job job) {

    this._output._training_metrics = doScoreMetricsOneFrame(this._parms.train(), job);
    if (null != this._parms.valid()) {
      this._output._validation_metrics = doScoreMetricsOneFrame(this._parms.valid(), job);
    }
  }

  private DistributionFamily distributionFamily(Model aModel) {
    // TODO: hack alert: In DRF, _parms._distribution is always set to multinomial.  Yay.
    if (aModel instanceof DRFModel)
      if (aModel._output.isBinomialClassifier())
        return DistributionFamily.bernoulli;
      else if (aModel._output.isClassifier())
        throw new H2OIllegalArgumentException("Don't know how to set the distribution for a multinomial Random Forest classifier.");
      else
        return DistributionFamily.gaussian;

    try {
      Field familyField = ReflectionUtils.findNamedField(aModel._parms, "_family");
      Field distributionField = (familyField != null ? null : ReflectionUtils.findNamedField(aModel, "_dist"));
      if (null != familyField) {
        // GLM only, for now
        GLMModel.GLMParameters.Family thisFamily = (GLMModel.GLMParameters.Family) familyField.get(aModel._parms);
        if (thisFamily == GLMModel.GLMParameters.Family.binomial) {
          return DistributionFamily.bernoulli;
        }

        try {
          return Enum.valueOf(DistributionFamily.class, thisFamily.toString());
        }
        catch (IllegalArgumentException e) {
          throw new H2OIllegalArgumentException("Don't know how to find the right DistributionFamily for Family: " + thisFamily);
        }
      }

      if (null != distributionField) {
        Distribution distribution = ((Distribution)distributionField.get(aModel));
        DistributionFamily distributionFamily;
        if (null != distribution)
          distributionFamily = distribution.distribution;
        else
          distributionFamily = aModel._parms._distribution;

        // NOTE: If the algo does smart guessing of the distribution family we need to duplicate the logic here.
        if (distributionFamily == DistributionFamily.AUTO) {
          if (aModel._output.isBinomialClassifier())
            distributionFamily = DistributionFamily.bernoulli;
          else if (aModel._output.isClassifier())
            throw new H2OIllegalArgumentException("Don't know how to determine the distribution for a multinomial classifier.");
          else
            distributionFamily = DistributionFamily.gaussian;
        } // DistributionFamily.AUTO

        return distributionFamily;
      }

      throw new H2OIllegalArgumentException("Don't know how to stack models that have neither a distribution hyperparameter nor a family hyperparameter.");
    }
    catch (Exception e) {
      throw new H2OIllegalArgumentException(e.toString(), e.toString());
    }
  }

  public void checkAndInheritModelProperties() {
    if (null == _parms._base_models || 0 == _parms._base_models.length)
      throw new H2OIllegalArgumentException("When creating a StackedEnsemble you must specify one or more models; found 0.");

    Model aModel = null;
    boolean beenHere = false;

    for (Key<Model> k : _parms._base_models) {
      aModel = DKV.getGet(k);
      if (null == aModel) {
        Log.warn("Failed to find base model; skipping: " + k);
        continue;
      }

      if (beenHere) {
        // check that the base models are all consistent
        if (_output._isSupervised ^ aModel.isSupervised())
          throw new H2OIllegalArgumentException("Base models are inconsistent: there is a mix of supervised and unsupervised models: " + Arrays.toString(_parms._base_models));

        if (modelCategory != aModel._output.getModelCategory())
          throw new H2OIllegalArgumentException("Base models are inconsistent: there is a mix of different categories of models: " + Arrays.toString(_parms._base_models));

        Frame aTrainingFrame = aModel._parms.train();
        if (! commonTrainingFrame._key.equals(aTrainingFrame._key))
          throw new H2OIllegalArgumentException("Base models are inconsistent: they use different training frames.  Found: " + commonTrainingFrame._key + " and: " + aTrainingFrame._key + ".");

        NonBlockingHashSet<String> aNames = new NonBlockingHashSet<>();
        aNames.addAll(Arrays.asList(aModel._output._names));
        if (! aNames.equals(this.names))
          throw new H2OIllegalArgumentException("Base models are inconsistent: they use different column lists.  Found: " + this.names + " and: " + aNames + ".");

        NonBlockingHashSet<String> anIgnoredColumns = new NonBlockingHashSet<>();
        if (null != aModel._parms._ignored_columns)
          anIgnoredColumns.addAll(Arrays.asList(aModel._parms._ignored_columns));
        if (! anIgnoredColumns.equals(this.ignoredColumns))
          throw new H2OIllegalArgumentException("Base models are inconsistent: they use different ignored_column lists.  Found: " + this.ignoredColumns + " and: " + aModel._parms._ignored_columns + ".");

        if (! responseColumn.equals(aModel._parms._response_column))
          throw new H2OIllegalArgumentException("Base models are inconsistent: they use different response columns.  Found: " + responseColumn + " and: " + aModel._parms._response_column + ".");

        if (_output._domains.length != aModel._output._domains.length)
          throw new H2OIllegalArgumentException("Base models are inconsistent: there is a mix of different numbers of domains (categorical levels): " + Arrays.toString(_parms._base_models));

        if (nfolds != aModel._parms._nfolds)
          throw new H2OIllegalArgumentException("Base models are inconsistent: they use different values for nfolds.");

        // TODO: loosen this iff _parms._valid or if we add a separate holdout dataset for the ensemble
        if (aModel._parms._nfolds < 2)
          throw new H2OIllegalArgumentException("Base model does not use cross-validation: " + aModel._parms._nfolds);

        // TODO: loosen this iff it's consistent, like if we have a _fold_column
        if (aModel._parms._fold_assignment != Modulo)
          throw new H2OIllegalArgumentException("Base model does not use Modulo for cross-validation: " + aModel._parms._nfolds);

        if (! aModel._parms._keep_cross_validation_predictions)
          throw new H2OIllegalArgumentException("Base model does not keep cross-validation predictions: " + aModel._parms._nfolds);

        // In GLM, we get _family instead of _distribution.
        // Further, we have Family.binomial instead of DistributionFamily.bernoulli.
        // We also handle DistributionFamily.AUTO in distributionFamily()
        //
        // Hack alert: DRF only does Bernoulli and Gaussian, so only compare _domains.length above.
        if (! (aModel instanceof DRFModel) && distributionFamily(aModel) != distributionFamily(this))
          Log.warn("Base models are inconsistent; they use different distributions: " + distributionFamily(this) + " and: " + distributionFamily(aModel) + ". Is this intentional?");

        // TODO: If we're set to DistributionFamily.AUTO then GLM might auto-conform the response column
        // giving us inconsistencies.
      } else {
        // !beenHere: this is the first base_model
        _output._isSupervised = aModel.isSupervised();
        this.modelCategory = aModel._output.getModelCategory();
        this._dist = new Distribution(distributionFamily(aModel));
        _output._domains = Arrays.copyOf(aModel._output._domains, aModel._output._domains.length);
        commonTrainingFrame = aModel._parms.train();

        if (! commonTrainingFrame._key.equals(_parms._train))
          throw  new H2OIllegalArgumentException("StackedModel training_frame must match the training_frame of each base model.  Found: " + commonTrainingFrame._key + " and: " + _parms._train);

        // TODO: set _parms._train to aModel._parms.train()

        _output._names = aModel._output._names;
        this.names = new NonBlockingHashSet<>();
        this.names.addAll(Arrays.asList(aModel._output._names));

        this.ignoredColumns = new NonBlockingHashSet<>();
        if (null != aModel._parms._ignored_columns)
          this.ignoredColumns.addAll(Arrays.asList(aModel._parms._ignored_columns));

        // If the client has set _ignored_columns for the StackedEnsemble make sure it's
        // consistent with the base_models:
        if (null != this._parms._ignored_columns) {
          NonBlockingHashSet<String> ensembleIgnoredColumns = new NonBlockingHashSet<>();
          ensembleIgnoredColumns.addAll(Arrays.asList(this._parms._ignored_columns));
          if (! ensembleIgnoredColumns.equals(this.ignoredColumns))
            throw new H2OIllegalArgumentException("A StackedEnsemble takes its ignored_columns list from the base models.  An inconsistent list of ignored_columns was specified for the ensemble model.");
        }

        responseColumn = aModel._parms._response_column;

        if (! responseColumn.equals(_parms._response_column))
          throw  new H2OIllegalArgumentException("StackedModel response_column must match the response_column of each base model.  Found: " + responseColumn + " and: " + _parms._response_column);

        nfolds = aModel._parms._nfolds;
        _parms._distribution = aModel._parms._distribution;
        beenHere = true;
      }

    } // for all base_models

    if (null == aModel)
      throw new H2OIllegalArgumentException("When creating a StackedEnsemble you must specify one or more models; " + _parms._base_models.length + " were specified but none of those were found: " + Arrays.toString(_parms._base_models));

  }

  // TODO: Are we leaking anything?
  @Override protected Futures remove_impl(Futures fs ) {
    if (_output._metalearner != null)
        DKV.remove(_output._metalearner._key, fs);

    return super.remove_impl(fs);
  }
}

