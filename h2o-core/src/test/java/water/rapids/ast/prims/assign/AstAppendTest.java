package water.rapids.ast.prims.assign;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import water.fvec.Frame;
import water.fvec.TestFrameBuilder;
import water.fvec.Vec;
import water.rapids.Rapids;
import water.rapids.Val;

import water.runner.CloudSize;
import water.runner.H2ORunner;


import static org.junit.Assert.assertEquals;
import static water.TestUtil.ard;

@RunWith(H2ORunner.class)
@CloudSize(1)
public class AstAppendTest {

  private Frame fr = null;

  @Test
  public void AppendColumnTest() {

    fr = new TestFrameBuilder()
            .withName("testFrame")
            .withColNames("ColA", "ColB")
            .withVecTypes(Vec.T_NUM, Vec.T_NUM)
            .withDataForCol(0, ard(1, 2))
            .withDataForCol(1, ard(2, 5))
            .build();

    String tree = "( append testFrame ( / (cols testFrame [0]) (cols testFrame [1])) 'appended' )";
    Val val = Rapids.exec(tree);
    Frame res = val.getFrame();

    assertEquals(2, res.numRows());

    assertEquals(0.5, res.vec(2).at(0L), 1e-6);
    assertEquals(0.4, res.vec(2).at(1L), 1e-6);

    res.delete();
  }

  @After
  public void afterEach() {
    fr.delete();
  }
}
