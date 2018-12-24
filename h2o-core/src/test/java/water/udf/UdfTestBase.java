package water.udf;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import water.Scope;
import water.TestUtil;
import water.fvec.NFSFileVec;
import water.fvec.Vec;
import water.runner.CloudSize;
import water.runner.H2ORunner;
import water.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * All test functionality specific for udf (not actually), 
 * not kosher enough to be allowed for the general public
 */
@RunWith(H2ORunner.class)
@CloudSize(1)
public class UdfTestBase{

  {
    ClassLoader loader = getClass().getClassLoader();
    loader.setDefaultAssertionStatus(true);
  }
  

  @Before
  public void hi() {
    Scope.enter();
  }

  @After
  public void bye() { 
    Scope.exit(); 
  }

  protected static Vec willDrop(Vec v) { return Scope.track(v); }

  public static <T> T willDrop(T vh) {
    try { // using reflection so that Paula Bean's code is intact
      Method vec = vh.getClass().getMethod("vec");
      Scope.track((Vec)vec.invoke(vh));
    } catch (Exception e) {
      // just ignore
    }
    return vh;
  }


  public static Vec loadFile(String fname) throws IOException {
    File f = FileUtils.getFile(fname);
    return NFSFileVec.make(f);
  }

  // the following code exists or else gradlew will complain; also, it checks assertions
  @Test
  public void testAssertionsEnabled() throws Exception {
    try {
      assert false : "Should throw";
      Assert.fail("Expected an assertion error");
    } catch(AssertionError ae) {
      // as designed
    }
  }

}
