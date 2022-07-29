package ai.lzy.test.scenarios;

import java.nio.file.Paths;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class LzyDryStartupTest extends LocalScenario {

    @Before
    public void setUp() {
        super.setUp();
        startTerminalWithDefaultConfig();
    }

    @After
    public void tearDown() {
        super.tearDown();
    }

    @Test
    public void testFuseWorks() {
        //Assert
        Assert.assertTrue(terminal.pathExists(Paths.get(Config.LZY_MOUNT + "/sbin")));
        Assert.assertTrue(terminal.pathExists(Paths.get(Config.LZY_MOUNT + "/bin")));
        Assert.assertTrue(terminal.pathExists(Paths.get(Config.LZY_MOUNT + "/dev")));
    }

    @Ignore
    @Test
    public void testTerminalDiesAfterServerDied() {
        serverContext.close();

        //Assert
        Assert.assertTrue(terminal.waitForShutdown());
    }
}
