from __future__ import print_function
from builtins import range
import sys
sys.path.insert(1,"../../../")
import h2o
from tests import pyunit_utils
from h2o.transforms.decomposition import H2OPCA



def pca_car():
  num_runs = 10
  run_time_c = []

  for run_index in range(num_runs):  # multiple runs to get an idea of run time info
    car = h2o.import_file(path=pyunit_utils.locate("smalldata/pca_test/car.arff.txt"))  # Nidhi: import may not work
    carPCA = H2OPCA(k=car.ncols, transform="STANDARDIZE")
    carPCA.train(x=list(range(0, car.ncols)), training_frame=car)
    run_time_c.append(carPCA._model_json['output']['end_time']-carPCA._model_json['output']['start_time'])
    print("PCA model training time with car.arff.txt data in ms is {0}".format(run_time_c[run_index]))

    h2o.remove_all()

  assert (max(run_time_c)) < 1000, "PCA runs for car.arff.txt take too much time!"

  # compare that our results are the same with NA rows preserved and removed for PCA
  car = h2o.import_file(path=pyunit_utils.locate("smalldata/pca_test/car.arff.txt"))  # Nidhi: import may not work
  carPCA = H2OPCA(k=car.ncols, transform="NONE", seed=12345)
  carPCA.train(x=list(range(0, car.ncols)), training_frame=car)

  # run again with na rows removed
  car2=car.na_omit()
  carPCA_noNAs = H2OPCA(k=car2.ncols, transform="NONE", seed=12345)
  carPCA_noNAs.train(x=list(range(0, car2.ncols)), training_frame=car2)

  # compare the eignvalues and eigenvectors
  assert pyunit_utils.equal_2D_tables(carPCA._model_json["output"]["importance"]._cell_values,
                                    carPCA_noNAs._model_json["output"]["importance"]._cell_values, tolerance=1e-6)
  assert pyunit_utils.equal_2D_tables(carPCA._model_json["output"]["eigenvectors"]._cell_values,
                                    carPCA_noNAs._model_json["output"]["eigenvectors"]._cell_values, tolerance=1e-6)
  h2o.remove_all()

if __name__ == "__main__":
  pyunit_utils.standalone_test(pca_car)
else:
  pca_car()
