package ai.lzy.env;

import ai.lzy.env.logs.LogHandle;
import jakarta.annotation.Nullable;

import java.io.InputStream;
import java.io.OutputStream;

public interface Environment extends AutoCloseable {

    /**
     * Install environment. Must be called before execution
     * Consumes stream queues to add stdout and stderr to
     */
    void install(LogHandle logHandle) throws EnvironmentInstallationException;

    default LzyProcess runProcess(String... command) {
        return runProcess(command, null);
    }

    LzyProcess runProcess(String[] command, @Nullable String[] envp);

    interface LzyProcess {

        LzyProcess EMPTY = new LzyProcess() {
            @Override
            public OutputStream in() {
                return OutputStream.nullOutputStream();
            }

            @Override
            public InputStream out() {
                return InputStream.nullInputStream();
            }

            @Override
            public InputStream err() {
                return InputStream.nullInputStream();
            }

            @Override
            public int waitFor() {
                return 0;
            }

            @Override
            public void signal(int sigValue) {}
        };

        OutputStream in();

        InputStream out();

        InputStream err();

        int waitFor() throws InterruptedException;

        void signal(int sigValue);
    }
}
