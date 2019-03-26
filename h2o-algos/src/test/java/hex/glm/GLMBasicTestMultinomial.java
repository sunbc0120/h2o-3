package hex.glm;

import hex.CreateFrame;
import hex.DataInfo;
import hex.FrameSplitter;
import hex.ModelMetricsBinomialGLM.ModelMetricsMultinomialGLM;
import hex.SplitFrame;
import hex.deeplearning.DeepLearningModel;
import hex.glm.GLMModel.GLMParameters;
import hex.glm.GLMModel.GLMParameters.Family;
import hex.glm.GLMModel.GLMParameters.Solver;
import hex.optimization.ADMM;
import org.junit.*;
import org.junit.rules.ExpectedException;
import water.*;
import water.exceptions.H2OIllegalArgumentException;
import water.fvec.Frame;
import water.fvec.Vec;
import water.util.ArrayUtils;
import java.util.Random;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by tomasnykodym on 10/28/15.
 */
public class GLMBasicTestMultinomial extends TestUtil {
  static Frame _covtype;
  static Frame _train;
  static Frame _test;
  double _tol = 1e-10;

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @BeforeClass
  public static void setup() {
    stall_till_cloudsize(1);
    _covtype = parse_test_file("smalldata/covtype/covtype.20k.data");
    _covtype.replace(_covtype.numCols()-1,_covtype.lastVec().toCategoricalVec()).remove();
    Key[] keys = new Key[]{Key.make("train"),Key.make("test")};
    H2O.submitTask(new FrameSplitter(_covtype, new double[]{.8},keys,null)).join();
    _train = DKV.getGet(keys[0]);
    _test = DKV.getGet(keys[1]);
  }

  @AfterClass
  public static void cleanUp() {
    if(_covtype != null)  _covtype.delete();
    if(_train != null) _train.delete();
    if(_test != null) _test.delete();
  }

  @Test
  public void testMultinomialPredMojoPojo() {
    try {
      Scope.enter();
      CreateFrame cf = new CreateFrame();
      Random generator = new Random();
      int numRows = generator.nextInt(10000)+15000+200;
      int numCols = generator.nextInt(17)+3;
      int response_factors = generator.nextInt(7)+3;
      cf.rows= numRows;
      cf.cols = numCols;
      cf.factors=10;
      cf.has_response=true;
      cf.response_factors = response_factors;
      cf.positive_response=true;
      cf.missing_fraction = 0;
      cf.seed = System.currentTimeMillis();
      System.out.println("Createframe parameters: rows: "+numRows+" cols:"+numCols+" response number:"
              +response_factors+" seed: "+cf.seed);

      Frame trainMultinomial = Scope.track(cf.execImpl().get());
      SplitFrame sf = new SplitFrame(trainMultinomial, new double[]{0.8,0.2}, new Key[] {Key.make("train.hex"), Key.make("test.hex")});
      sf.exec().get();
      Key[] ksplits = sf._destination_frames;
      Frame tr = DKV.get(ksplits[0]).get();
      Frame te = DKV.get(ksplits[1]).get();
      Scope.track(tr);
      Scope.track(te);

      GLMModel.GLMParameters paramsO = new GLMModel.GLMParameters(GLMModel.GLMParameters.Family.multinomial,
              Family.multinomial.defaultLink, new double[]{0}, new double[]{0}, 0, 0);
      paramsO._train = tr._key;
      paramsO._lambda_search = false;
      paramsO._response_column = "response";
      paramsO._lambda = new double[]{0};
      paramsO._alpha = new double[]{0.001};  // l1pen
      paramsO._objective_epsilon = 1e-6;
      paramsO._beta_epsilon = 1e-4;
      paramsO._standardize = false;

      GLMModel model = new GLM(paramsO).trainModel().get();
      Scope.track_generic(model);

      Frame pred = model.score(te);
      Scope.track(pred);
      Assert.assertTrue(model.testJavaScoring(te, pred, _tol));
    } finally {
      Scope.exit();
    }
  }

  @Test
  public void testCovtypeNoIntercept(){
    GLMParameters params = new GLMParameters(Family.multinomial);
    GLMModel model = null;
    Frame preds = null;
    Vec weights = _covtype.anyVec().makeCon(1);
    Key k = Key.<Frame>make("cov_with_weights");
    Frame f = new Frame(k,_covtype.names(),_covtype.vecs());
    f.add("weights",weights);
    DKV.put(f);
    try {
      params._response_column = "C55";
      params._train = k;
      params._valid = _covtype._key;
      params._objective_epsilon = 1e-6;
      params._beta_epsilon = 1e-4;
      params._weights_column = "weights";
      params._missing_values_handling = DeepLearningModel.DeepLearningParameters.MissingValuesHandling.Skip;
      params._intercept = false;
      double[] alpha = new double[]{0,.5,.1};
      Solver s = Solver.L_BFGS;
      System.out.println("solver = " + s);
      params._solver = s;
      params._max_iterations = 5000;
      for (int i = 0; i < alpha.length; ++i) {
        params._alpha = new double[]{alpha[i]};
//        params._lambda[0] = lambda[i];
        model = new GLM(params).trainModel().get();
        System.out.println(model.coefficients());
//        Assert.assertEquals(0,model.coefficients().get("Intercept"),0);
        double [][] bs = model._output.getNormBetaMultinomial();
        for(double [] b:bs)
          Assert.assertEquals(0,b[b.length-1],0);
        System.out.println(model._output._model_summary);
        System.out.println(model._output._training_metrics);
        System.out.println(model._output._validation_metrics);
        preds = model.score(_covtype);
        ModelMetricsMultinomialGLM mmTrain = (ModelMetricsMultinomialGLM) hex.ModelMetricsMultinomial.getFromDKV(model, _covtype);
        assertTrue(model._output._training_metrics.equals(mmTrain));
        model.delete();
        model = null;
        preds.delete();
        preds = null;
      }
    } finally{
      weights.remove();
      DKV.remove(k);
      if(model != null)model.delete();
      if(preds != null)preds.delete();
    }
  }


  @Test
  public void testCovtypeBasic(){
    GLMParameters params = new GLMParameters(Family.multinomial);
    GLMModel model = null;
    Frame preds = null;
    Vec weights = _covtype.anyVec().makeCon(1);
    Key k = Key.<Frame>make("cov_with_weights");
    Frame f = new Frame(k,_covtype.names(),_covtype.vecs());
    f.add("weights",weights);
    DKV.put(f);
    try {
      params._response_column = "C55";
      params._train = k;
      params._valid = _covtype._key;
      params._lambda = new double[]{4.881e-05};
      params._alpha = new double[]{1};
      params._objective_epsilon = 1e-6;
      params._beta_epsilon = 1e-4;
      params._weights_column = "weights";
      params._missing_values_handling = DeepLearningModel.DeepLearningParameters.MissingValuesHandling.Skip;
      double[] alpha = new double[]{1};
      double[] expected_deviance = new double[]{25499.76};
      double[] lambda = new double[]{2.544750e-05};
      for (Solver s : new Solver[]{Solver.IRLSM, Solver.COORDINATE_DESCENT, Solver.L_BFGS}) {
        System.out.println("solver = " + s);
        params._solver = s;
        params._max_iterations = params._solver == Solver.L_BFGS?300:10;
        for (int i = 0; i < alpha.length; ++i) {
          params._alpha[0] = alpha[i];
          params._lambda[0] = lambda[i];
          model = new GLM(params).trainModel().get();
          System.out.println(model._output._model_summary);
          System.out.println(model._output._training_metrics);
          System.out.println(model._output._validation_metrics);
          assertTrue(model._output._training_metrics.equals(model._output._validation_metrics));
          assertTrue(((ModelMetricsMultinomialGLM) model._output._training_metrics)._resDev <= expected_deviance[i] * 1.1);
          preds = model.score(_covtype);
          ModelMetricsMultinomialGLM mmTrain = (ModelMetricsMultinomialGLM) hex.ModelMetricsMultinomial.getFromDKV(model, _covtype);
          assertTrue(model._output._training_metrics.equals(mmTrain));
          model.delete();
          model = null;
          preds.delete();
          preds = null;
        }
      }
    } finally{
      weights.remove();
      DKV.remove(k);
      if(model != null)model.delete();
      if(preds != null)preds.delete();
    }
  }

  /*
  I have manually derived the coefficient updates for COD and they are more accurate than what is currently
  implemented because I update all the probabilities after a coefficient has been changed.  In reality, this will
  be very slow and an approximation may be more appropriate.  The coefficients generated here is the golden standard.
   */
  @Test
  public void testCODGradients(){
    Scope.enter();
    Frame train;
    GLMParameters params = new GLMParameters(Family.multinomial);
    GLMModel model = null;
    double[] oldGLMCoeffs = new double[] {0.059094274726151426, 0.013361781886804975, -0.00798977427248744,
            0.007467359562151555, 0.06737827548293934, -1.002393430927568, -0.04066511294457045, -0.018960901996125427,
            0.07330281133353159, -0.02285669809606731, 0.002805290931441751, -1.1394632268347782, 0.021976767313534512,
            0.01013967640490087, -0.03999288928633559, 0.012385348397898913, -0.0017922461738315199,
            -1.159667420372168};
    try {
      train = parse_test_file("smalldata/glm_test/multinomial_3_class.csv");
      Scope.track(train);
      params._response_column = "response";
      params._train = train._key;
      params._lambda = new double[]{4.881e-05};
      params._alpha = new double[]{0.5};
      params._objective_epsilon = 1e-6;
      params._beta_epsilon = 1e-4;
      params._max_iterations = 1; // one iteration
      params._seed = 12345; // don't think this one matters but set it anyway
      Solver s = Solver.COORDINATE_DESCENT;
      System.out.println("solver = " + s);
      params._solver = s;
      model = new GLM(params).trainModel().get();
      Scope.track_generic(model);
      DataInfo tinfo = new DataInfo(train.clone(), null, 0, true, DataInfo.TransformType.STANDARDIZE,
              DataInfo.TransformType.NONE, false, false, false,
              /* weights */ false, /* offset */ false, /* fold */ false);
      double[] manualCoeff = getCODCoeff(train, params._alpha[0], params._lambda[0], model._ymu, tinfo);
      Scope.track_generic(tinfo);

      compareGLMCoeffs(manualCoeff, model._output._submodels[0].beta, 2e-2);  // compare two sets of coeffs
      compareGLMCoeffs(model._output._submodels[0].beta, oldGLMCoeffs, 1e-10);  // compare to original GLM

    } finally{
      Scope.exit();
    }
  }

  public void compareGLMCoeffs(double[] coeff1, double[] coeff2, double tol) {

    assertTrue(coeff1.length==coeff2.length); // assert coefficients having the same length first
    for (int index=0; index < coeff1.length; index++) {
      assert Math.abs(coeff1[index]-coeff2[index]) < tol :
              "coefficient difference "+Math.abs(coeff1[index]-coeff2[index])+" exceeded tolerance of "+tol;
    }
  }

  public double[] getCODCoeff(Frame train, double alpha, double lambda, double[] ymu, DataInfo tinfo) {
    int numClass = train.vec("response").domain().length;
    int numPred = train.numCols() - 1;
    int numRow = (int) train.numRows();
    double[] beta = new double[numClass * (numPred + 1)];
    double reg = 1.0/train.numRows();

    // initialize beta
    for (int index = 0; index < numClass; index++) {
      beta[(index + 1) * (numPred + 1) - 1] = Math.log(ymu[index]);
    }

    for (int iter = 0; iter < 3; iter++) {
      // update beta
      for (int cindex = 0; cindex < numClass; cindex++) {
        for (int pindex = 0; pindex < numPred; pindex++) {
          double grad = 0;
          double hess = 0;

          for (int rindex = 0; rindex < numRow; rindex++) {
            int resp = (int) train.vec("response").at(rindex);
            double predProb = calProb(train, rindex, beta, numClass, numPred, cindex, tinfo);
            double entry = (train.vec(pindex).at(rindex) - tinfo._numMeans[pindex]) * tinfo._normMul[pindex];
            grad -= entry * ((resp == cindex ? 1 : 0) - predProb);
            hess += entry * entry * (predProb - predProb * predProb); // hess calculation is correct
          }
          grad = grad * reg + lambda * (1 - alpha) * beta[cindex * (numPred + 1) + pindex]; // add l2 penalty
          hess = hess * reg + lambda * (1 - alpha);
          beta[cindex * (numPred + 1) + pindex] -= ADMM.shrinkage(grad, lambda * alpha) / hess;
        }

        double grad = 0;
        double hess = 0;
        // change the intercept term here
        for (int rindex = 0; rindex < numRow; rindex++) {
          int resp = (int) train.vec("response").at(rindex);


          double predProb = calProb(train, rindex, beta, numClass, numPred, cindex, tinfo);
          grad -= ((resp == cindex ? 1 : 0) - predProb);
          hess += (predProb - predProb * predProb);

        }
        grad *= reg;
        hess *= reg;
        beta[(cindex + 1) * (numPred + 1) - 1] -= grad / hess;
      }
    }
    return beta;
  }

  public double calProb(Frame train, int rowIndex, double[] beta, int numClass, int numPred, int classNo, DataInfo tinfo) {
    double prob = 0.0;
    double sum = 0.0;
    for (int cindex = 0; cindex < numClass; cindex++) {
      double temp = 0;
      for (int pindex = 0; pindex < numPred; pindex++) {
        double entry = (train.vec(pindex).at(rowIndex)-tinfo._numMeans[pindex])*tinfo._normMul[pindex];
        temp += entry*beta[cindex*(numPred+1)+pindex];
      }
      temp+= beta[(cindex+1)*(numPred+1)-1];
      if (classNo == cindex) {
        prob = Math.exp(temp);
      }
      sum+= Math.exp(temp);
    }
    return (prob/sum);
  }


  @Test
  public void testCovtypeMinActivePredictors(){
    GLMParameters params = new GLMParameters(Family.multinomial);
    GLMModel model = null;
    Frame preds = null;
    try {
      params._response_column = "C55";
      params._train = _covtype._key;
      params._valid = _covtype._key;
      params._lambda = new double[]{4.881e-05};
      params._alpha = new double[]{1};
      params._objective_epsilon = 1e-6;
      params._beta_epsilon = 1e-4;
      params._max_active_predictors = 50;
      params._max_iterations = 10;
      double[] alpha = new double[]{.99};
      double expected_deviance = 33000;
      double[] lambda = new double[]{2.544750e-05};
      Solver s = Solver.COORDINATE_DESCENT;
      System.out.println("solver = " + s);
      params._solver = s;
      model = new GLM(params).trainModel().get();
      System.out.println(model._output._model_summary);
      System.out.println(model._output._training_metrics);
      System.out.println(model._output._validation_metrics);
      System.out.println("rank = " + model._output.rank() + ", max active preds = " + (params._max_active_predictors + model._output.nclasses()));
      assertTrue(model._output.rank() <= params._max_active_predictors + model._output.nclasses());
      assertTrue(model._output._training_metrics.equals(model._output._validation_metrics));
      assertTrue(((ModelMetricsMultinomialGLM) model._output._training_metrics)._resDev <= expected_deviance * 1.1);
      preds = model.score(_covtype);
      ModelMetricsMultinomialGLM mmTrain = (ModelMetricsMultinomialGLM) hex.ModelMetricsMultinomial.getFromDKV(model, _covtype);
      assertTrue(model._output._training_metrics.equals(mmTrain));
      model.delete();
      model = null;
      preds.delete();
      preds = null;
    } finally{
      if(model != null)model.delete();
      if(preds != null)preds.delete();
    }
  }


  @Test
  public void testCovtypeLS(){
    GLMParameters params = new GLMParameters(Family.multinomial);
    GLMModel model = null;
    Frame preds = null;
    try {
      double expected_deviance = 33000;
      params._nlambdas = 3;
      params._response_column = "C55";
      params._train = _covtype._key;
      params._valid = _covtype._key;
      params._alpha = new double[]{.99};
      params._objective_epsilon = 1e-6;
      params._beta_epsilon = 1e-4;
      params._max_active_predictors = 50;
      params._max_iterations = 500;
      params._solver = Solver.AUTO;
      params._lambda_search = true;
      model = new GLM(params).trainModel().get();
      System.out.println(model._output._training_metrics);
      System.out.println(model._output._validation_metrics);
      assertTrue(model._output._training_metrics.equals(model._output._validation_metrics));
      preds = model.score(_covtype);
      ModelMetricsMultinomialGLM mmTrain = (ModelMetricsMultinomialGLM) hex.ModelMetricsMultinomial.getFromDKV(model, _covtype);
      assertTrue(model._output._training_metrics.equals(mmTrain));
      assertTrue(((ModelMetricsMultinomialGLM) model._output._training_metrics)._resDev <= expected_deviance);
      System.out.println(model._output._model_summary);
      model.delete();
      model = null;
      preds.delete();
      preds = null;
    } finally{
      if(model != null)model.delete();
      if(preds != null)preds.delete();
    }
  }

  @Test
  public void testCovtypeNAs(){
    GLMParameters params = new GLMParameters(Family.multinomial);
    GLMModel model = null;
    Frame preds = null;
    Frame covtype_subset = null, covtype_copy = null;
    try {
      double expected_deviance = 26000;
      covtype_copy = _covtype.deepCopy("covtype_copy");
      DKV.put(covtype_copy);
      Vec.Writer w = covtype_copy.vec(54).open();
      w.setNA(10);
      w.setNA(20);
      w.setNA(30);
      w.close();
      covtype_subset = new Frame(Key.<Frame>make("covtype_subset"),new String[]{"C51","C52","C53","C54","C55"},covtype_copy.vecs(new int[]{50,51,52,53,54}));
      DKV.put(covtype_subset);
//      params._nlambdas = 3;
      params._response_column = "C55";
      params._train = covtype_copy._key;
      params._valid = covtype_copy._key;
      params._alpha = new double[]{.99};
      params._objective_epsilon = 1e-6;
      params._beta_epsilon = 1e-4;
      params._max_active_predictors = 50;
      params._max_iterations = 500;
      params._solver = Solver.L_BFGS;
      params._missing_values_handling = DeepLearningModel.DeepLearningParameters.MissingValuesHandling.Skip;
//      params._lambda_search = true;
      model = new GLM(params).trainModel().get();
      assertEquals(covtype_copy.numRows()-3-1,model._nullDOF);
      System.out.println(model._output._training_metrics);
      System.out.println(model._output._validation_metrics);
      assertTrue(model._output._training_metrics.equals(model._output._validation_metrics));
      preds = model.score(covtype_copy);
      ModelMetricsMultinomialGLM mmTrain = (ModelMetricsMultinomialGLM) hex.ModelMetricsMultinomial.getFromDKV(model, covtype_copy);
      assertTrue(model._output._training_metrics.equals(mmTrain));
      assertTrue(((ModelMetricsMultinomialGLM) model._output._training_metrics)._resDev <= expected_deviance);
      System.out.println(model._output._model_summary);
      model.delete();
      model = null;
      preds.delete();
      preds = null;
      // now run the same on the subset
      params._train = covtype_subset._key;
      model = new GLM(params).trainModel().get();
      assertEquals(covtype_copy.numRows()-3-1,model._nullDOF);
      System.out.println(model._output._training_metrics);
      System.out.println(model._output._validation_metrics);
      assertTrue(model._output._training_metrics.equals(model._output._validation_metrics));
      preds = model.score(_covtype);
      System.out.println(model._output._model_summary);
      assertTrue(((ModelMetricsMultinomialGLM) model._output._training_metrics)._resDev <= 66000);
      model.delete();
      model = null;
      preds.delete();
      preds = null;

    } finally{
      if(covtype_subset != null) covtype_subset.delete();
      if(covtype_copy != null)covtype_copy.delete();
      if(model != null)model.delete();
      if(preds != null)preds.delete();
    }
  }

  @Test
  public void testNaiveCoordinateDescent() {
    expectedException.expect(H2OIllegalArgumentException.class);
    expectedException.expectMessage("Naive coordinate descent is not supported for multinomial.");
    GLMParameters params = new GLMParameters(Family.multinomial);
    params._solver = Solver.COORDINATE_DESCENT_NAIVE;

    // Should throw exception with information about unsupported message
    new GLM(params);
  }

  @Test
  public void testNaiveCoordinateDescent_families() {
    GLMParameters params = new GLMParameters(Family.binomial);
    params._solver = Solver.COORDINATE_DESCENT_NAIVE;
    final Family[] families = {Family.binomial, Family.gaussian, Family.gamma, Family.tweedie, Family.poisson, Family.ordinal,
    Family.quasibinomial};
    GLMParameters.Link[] linkingfuncs = {GLMParameters.Link.logit, GLMParameters.Link.identity, GLMParameters.Link.log,
            GLMParameters.Link.tweedie, GLMParameters.Link.log, GLMParameters.Link.ologit, GLMParameters.Link.logit};

    for (int i = 0; i < families.length; i++) {
      params._family = families[i];
      params._link = linkingfuncs[i];
      new GLM(params);
    }
  }

  @Test
  public void testMultinomialGradientSpeedUp(){
    Scope.enter();
    Key parsed = Key.make("covtype");
    Frame fr, f1, f2, f3;
    Vec origRes = null;
    // get new coefficients, 7 classes and 53 predictor+intercept
    Random rand = new Random();
    rand.setSeed(12345);
    int nclass = 4;
    double threshold = 1e-10;
    DataInfo dinfo=null;
    int numRows = 4000;
    
    try {
      f1 = TestUtil.generate_enum_only(2, numRows, nclass, 0);
      Scope.track(f1);
      f2 = TestUtil.generate_real_only(2, numRows, 0);
      Scope.track(f2);
      f3 = TestUtil.generate_enum_only(1, numRows, nclass, 0);
      Scope.track(f3);
      fr = f1.add(f2).add(f3);  // complete frame generation
      Scope.track(fr);
      GLMParameters params = new GLMParameters(Family.multinomial);
      params._response_column = f1._names[4];
      params._ignored_columns = new String[]{};
      params._train = parsed;
      params._lambda = new double[]{0.5};
      params._alpha = new double[]{0.5};
      
      dinfo = new DataInfo(fr, null, 1, true, DataInfo.TransformType.STANDARDIZE, DataInfo.TransformType.NONE, true, false, false, false, false, false);
      int ncoeffPClass = dinfo.fullN()+1;
      double[] beta = new double[nclass*ncoeffPClass];
      for (int ind = 0; ind < beta.length; ind++) {
        beta[ind] = rand.nextDouble();
      }
      double l2pen = (1.0-params._lambda[0])*params._alpha[0];
      GLMTask.GLMMultinomialGradientSpeedUpTask gmt = new GLMTask.GLMMultinomialGradientSpeedUpTask(null,dinfo,l2pen,beta,1.0/fr.numRows()).doAll(dinfo._adaptedFrame);
      // calculate gradient and likelihood manually
      double[] manualGrad = new double[beta.length];
      double manualLLH = manualLikelihoodGradient(beta, manualGrad, 1.0/fr.numRows(), l2pen, dinfo, nclass,
              ncoeffPClass);
      // check likelihood calculation;
      assertEquals(manualLLH, gmt._likelihood, threshold);
      // check gradient
      TestUtil.checkArrays(gmt.gradient(), manualGrad, threshold);
    } finally {
      if (dinfo!=null)
        dinfo.remove();
      Scope.exit();
    }
  }
  
  public double manualLikelihoodGradient(double[] initialBeta, double[] gradient, double reg, double l2pen, 
                                         DataInfo dinfo, int nclass, int ncoeffPClass) {
    double likelihood = 0;
    int numRows = (int) dinfo._adaptedFrame.numRows();
    int respInd = dinfo._adaptedFrame.numCols()-1;
    double[][] etas = new double[numRows][nclass];
    double[] coeffs = new double[ncoeffPClass];
    double[] probs = new double[nclass+1];
    
    // calculate the etas for each class
    for (int rowInd=0; rowInd < numRows; rowInd++) {
      for (int classInd = 0; classInd < nclass; classInd++) { // calculate beta*coeff+beta0
        System.arraycopy(initialBeta, classInd*ncoeffPClass, coeffs, 0, ncoeffPClass);  // copy over coefficient for class classInd
        etas[rowInd][classInd] = getInnerProduct(rowInd, coeffs, dinfo);
      }
      int yresp = (int) dinfo._adaptedFrame.vec(respInd).at(rowInd);
      double logSumExp = computeMultinomialEtasSpeedUp(etas[rowInd], probs);
      likelihood += logSumExp-etas[rowInd][yresp];
      for (int classInd = 0; classInd < nclass; classInd++) { // calculate the multiplier here
        etas[rowInd][classInd] = classInd==yresp?(probs[classInd]-1):probs[classInd];
      }
      // apply the multiplier and update the gradient accordingly
      updateGradient(gradient, nclass, ncoeffPClass, dinfo, rowInd, etas[rowInd]);
    }
    
    // apply learning rate and regularization constant
    ArrayUtils.mult(gradient,reg);
    if (l2pen > 0) {
      for (int classInd=0; classInd < nclass; classInd++) {
        for (int predInd = 0; predInd < dinfo.fullN(); predInd++) {  // loop through all coefficients for predictors only
          gradient[classInd*ncoeffPClass+predInd] += l2pen*initialBeta[classInd*ncoeffPClass+predInd];
        }
      }
    }
    return likelihood;
  }
  
  public void updateGradient(double[] gradient, int nclass, int ncoeffPclass, DataInfo dinfo, int rowInd, 
                          double[] multiplier) {
    for (int classInd = 0; classInd < nclass; classInd++) {
      for (int cid = 0; cid < dinfo._cats; cid++) {
        int id = dinfo.getCategoricalId(cid, dinfo._adaptedFrame.vec(cid).at(rowInd));
        gradient[id + classInd * ncoeffPclass] += multiplier[classInd];
      }
      int numOff = dinfo.numStart();
      int cidOff = dinfo._cats;
      for (int cid = 0; cid < dinfo._nums; cid++) {
        double scale = dinfo._normMul != null ? dinfo._normMul[cid] : 1;
        double off = dinfo._normSub != null ? dinfo._normSub[cid] : 0;
        gradient[numOff + cid + classInd * ncoeffPclass] += multiplier[classInd] * 
                (dinfo._adaptedFrame.vec(cid + cidOff).at(rowInd)-off)*scale;
      }
      // fix the intercept term
      gradient[(classInd + 1) * ncoeffPclass - 1] += multiplier[classInd];
    }
  }

  public double getInnerProduct(int rowInd, double[] coeffs, DataInfo dinfo) {
    double innerP = coeffs[coeffs.length-1];  // add the intercept term;

    for (int predInd = 0; predInd < dinfo._cats; predInd++) { // categorical columns
      int id = dinfo.getCategoricalId(predInd, (int) dinfo._adaptedFrame.vec(predInd).at(rowInd));
      innerP += coeffs[id];
    }

    int numOff = dinfo.numStart();
    int cidOff = dinfo._cats;
    for (int cid=0; cid < dinfo._nums; cid++) {
      double scale = dinfo._normMul!=null?dinfo._normMul[cid]:1;
      double off = dinfo._normSub != null?dinfo._normSub[cid]:0;
      innerP += coeffs[cid+numOff]*(dinfo._adaptedFrame.vec(cid+cidOff).at(rowInd)-off)*scale;
    }
    
    return innerP;
  }


  // This method needs to calculate Pr(yi=c) for each class c and to 1/(sum of all exps
  public double  computeMultinomialEtasSpeedUp(double [] etas, double [] exps) {
    double sumExp = 0;
    int K = etas.length;
    for(int c = 0; c < K; ++c) { // calculate pr(yi=c) for each class
      double x = Math.exp(etas[c]);
      sumExp += x;
      exps[c] = x;
    }
    double reg = 1.0/(sumExp);
    exps[K] = reg;  // store 1/(sum of exp)
    for(int c = 0; c < K; ++c)  // calculate pr(yi=c) for each class
      exps[c] *= reg;
    return Math.log(sumExp);
  }
}
