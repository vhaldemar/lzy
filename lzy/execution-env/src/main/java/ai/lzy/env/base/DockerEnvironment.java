package ai.lzy.env.base;

import ai.lzy.env.EnvironmentInstallationException;
import ai.lzy.env.base.DockerEnvDescription.ContainerRegistryCredentials;
import ai.lzy.env.logs.LogHandle;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallbackTemplate;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import jakarta.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class DockerEnvironment extends BaseEnvironment {

    private static final Logger LOG = LogManager.getLogger(DockerEnvironment.class);
    private static final long GB_AS_BYTES = 1073741824;

    @Nullable
    public String containerId = null;
    private final DockerEnvDescription config;
    private final DockerClient client;
    private final Retry retry;

    public DockerEnvironment(DockerEnvDescription config) {
        this.config = config;
        this.client = generateClient(config.credentials());
        var retryConfig = new RetryConfig.Builder<>()
            .maxAttempts(3)
            .intervalFunction(IntervalFunction.ofExponentialBackoff(1000))
            .retryExceptions(DockerException.class, DockerClientException.class)
            .build();
        retry = Retry.of("docker-client-retry", retryConfig);
    }

    @Override
    public void install(LogHandle handle) throws EnvironmentInstallationException {
        if (containerId != null) {
            handle.logSysOut("Using already running container from cache");
            LOG.info("Using already running container from cache; containerId: {}", containerId);
            return;
        }

        String sourceImage = config.image();
        try {
            prepareImage(sourceImage, handle);
        } catch (InterruptedException e) {
            LOG.error("Image pulling was interrupted");
            handle.logSysErr("Image pulling was interrupted");
            throw new RuntimeException(e);
        } catch (Exception e) {
            LOG.error("Error while pulling image {}", sourceImage, e);
            handle.logSysErr("Error while pulling image: " + e.getMessage());
            throw new RuntimeException(e);
        }

        LOG.info("Creating container from image {} ...", sourceImage);
        handle.logSysOut("Creating container from image %s ...".formatted(sourceImage));

        final List<Mount> dockerMounts = new ArrayList<>();
        config.mounts().forEach(m -> {
            var mount = new Mount().withType(MountType.BIND).withSource(m.source()).withTarget(m.target());
            if (m.isRshared()) {
                mount.withBindOptions(new BindOptions().withPropagation(BindPropagation.R_SHARED));
            }
            dockerMounts.add(mount);
        });

        final HostConfig hostConfig = new HostConfig();
        hostConfig.withMounts(dockerMounts);

        // --gpus all --ipc=host --shm-size=1G
        if (config.needGpu()) {
            hostConfig.withDeviceRequests(
                List.of(
                    new DeviceRequest()
                        .withDriver("nvidia")
                        .withCapabilities(List.of(List.of("gpu")))))
                .withIpcMode("host")
                .withShmSize(GB_AS_BYTES);
        }

        AtomicInteger containerCreatingAttempt = new AtomicInteger(0);
        final var container = retry.executeSupplier(() -> {
            LOG.info("Creating container... (attempt {}); image: {}, config: {}",
                containerCreatingAttempt.incrementAndGet(), sourceImage, config);
            return client.createContainerCmd(sourceImage)
                .withHostConfig(hostConfig)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withTty(true)
                .withEnv(config.envVars())
                .exec();
        });

        final String containerId = container.getId();
        handle.logSysOut("Creating container from image %s done".formatted(sourceImage));
        LOG.info("Creating container done; containerId: {}, image: {}", containerId, sourceImage);

        handle.logSysOut("Environment container starting ...");
        AtomicInteger containerStartingAttempt = new AtomicInteger(0);
        retry.executeSupplier(() -> {
            LOG.info("Starting env container... (attempt {}); containerId: {}, image: {}",
                containerStartingAttempt.incrementAndGet(), containerId, sourceImage);
            return client.startContainerCmd(containerId).exec();
        });
        handle.logSysOut("Environment container started");
        LOG.info("Starting env container done; containerId: {}, image: {}", containerId, sourceImage);

        this.containerId = containerId;
    }

    @Override
    public LzyProcess runProcess(String[] command, @Nullable String[] envp) {
        assert containerId != null;

        final int bufferSize = 4096;
        final PipedInputStream stdoutPipe = new PipedInputStream(bufferSize);
        final PipedInputStream stderrPipe = new PipedInputStream(bufferSize);
        final PipedOutputStream stdout;
        final PipedOutputStream stderr;
        try {
            stdout = new PipedOutputStream(stdoutPipe);
            stderr = new PipedOutputStream(stderrPipe);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        LOG.info("Creating cmd {}", String.join(" ", command));
        final ExecCreateCmd execCmd = client.execCreateCmd(containerId)
            .withCmd(command)
            .withAttachStdout(true)
            .withAttachStderr(true);

        if (envp != null && envp.length > 0) {
            execCmd.withEnv(List.of(envp));
        }
        final ExecCreateCmdResponse exec = retry.executeSupplier(execCmd::exec);
        LOG.info("Executing cmd {}", String.join(" ", command));

        var feature = new CompletableFuture<>();

        var startCmd = retry.executeSupplier(() -> client.execStartCmd(exec.getId())
            .exec(new ResultCallbackTemplate<>() {
                @Override
                public void onComplete() {
                    LOG.info("Closing stdout, stderr of cmd {}", String.join(" ", command));
                    try {
                        stdout.close();
                        stderr.close();
                    } catch (IOException e) {
                        LOG.error("Cannot close stderr/stdout slots", e);
                    } catch (Exception e) {
                        LOG.error("Error while completing docker env process: ", e);
                    } finally {
                        feature.complete(null);
                    }
                }

                @Override
                public void onNext(Frame item) {
                    switch (item.getStreamType()) {
                        case STDOUT -> {
                            try {
                                stdout.write(item.getPayload());
                                stdout.flush();
                            } catch (IOException e) {
                                LOG.error("Error while write into stdout log", e);
                            }
                        }
                        case STDERR -> {
                            try {
                                stderr.write(item.getPayload());
                                stderr.flush();
                            } catch (IOException e) {
                                LOG.error("Error while write into stderr log", e);
                            }
                        }
                        default -> LOG.info("Got frame "
                            + new String(item.getPayload(), StandardCharsets.UTF_8)
                            + " from unknown stream type "
                            + item.getStreamType());
                    }
                }
            }));

        return new LzyProcess() {
            @Override
            public OutputStream in() {
                return OutputStream.nullOutputStream();
            }

            @Override
            public InputStream out() {
                return stdoutPipe;
            }

            @Override
            public InputStream err() {
                return stderrPipe;
            }

            @Override
            public int waitFor() throws InterruptedException {
                try {
                    feature.get();
                    return Math.toIntExact(retry.executeSupplier(() -> client.inspectExecCmd(exec.getId()).exec())
                        .getExitCodeLong());
                } catch (InterruptedException e) {
                    try {
                        startCmd.close();
                    } catch (IOException ex) {
                        LOG.error("Error while closing cmd: ", ex);
                    }
                    throw e;
                } catch (ExecutionException e) {
                    // ignored
                    return 1;
                }
            }

            @Override
            public void signal(int sigValue) {
                if (containerId != null) {
                    retry.executeSupplier(() -> client.killContainerCmd(containerId)
                        .withSignal(String.valueOf(sigValue))
                        .exec());
                }
            }
        };
    }

    @Override
    public void close() throws Exception {
        if (containerId != null) {
            retry.executeSupplier(() -> client.killContainerCmd(containerId).exec());
        }
    }

    private void prepareImage(String image, LogHandle handle) throws Exception {
        var msg = "Pulling image %s ...".formatted(image);
        LOG.info(msg);
        handle.logSysOut(msg);
        AtomicInteger pullingAttempt = new AtomicInteger(0);
        retry.executeCallable(() -> {
            LOG.info("Pulling image {}, attempt {}", image, pullingAttempt.incrementAndGet());
            final var pullingImage = client
                .pullImageCmd(config.image())
                .exec(new PullImageResultCallback());
            return pullingImage.awaitCompletion();
        });
        msg = "Pulling image %s done".formatted(image);
        LOG.info(msg);
        handle.logSysOut(msg);
    }

    private static DockerClient generateClient(@Nullable ContainerRegistryCredentials credentials) {
        if (credentials != null) {
            return DockerClientBuilder.getInstance(new DefaultDockerClientConfig.Builder()
                .withRegistryUrl(credentials.url())
                .withRegistryUsername(credentials.username())
                .withRegistryPassword(credentials.password())
                .build()
            ).build();
        } else {
            return DockerClientBuilder.getInstance().build();
        }
    }
}
