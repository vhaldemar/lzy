package ru.yandex.cloud.ml.platform.lzy.servant.agents;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.yandex.cloud.ml.platform.lzy.model.ReturnCodes;
import ru.yandex.cloud.ml.platform.lzy.model.Slot;
import ru.yandex.cloud.ml.platform.lzy.model.Zygote;
import ru.yandex.cloud.ml.platform.lzy.model.gRPCConverter;
import ru.yandex.cloud.ml.platform.lzy.model.graph.AtomicZygote;
import ru.yandex.cloud.ml.platform.lzy.model.graph.PythonEnv;
import ru.yandex.cloud.ml.platform.lzy.model.slots.TextLinesInSlot;
import ru.yandex.cloud.ml.platform.lzy.model.slots.TextLinesOutSlot;
import ru.yandex.cloud.ml.platform.lzy.servant.env.CondaEnvironment;
import ru.yandex.cloud.ml.platform.lzy.servant.env.Environment;
import ru.yandex.cloud.ml.platform.lzy.servant.env.SimpleBashEnvironment;
import ru.yandex.cloud.ml.platform.lzy.servant.fs.LzyInputSlot;
import ru.yandex.cloud.ml.platform.lzy.servant.fs.LzyOutputSlot;
import ru.yandex.cloud.ml.platform.lzy.servant.fs.LzySlot;
import ru.yandex.cloud.ml.platform.lzy.servant.slots.*;
import ru.yandex.cloud.ml.platform.lzy.servant.snapshot.EmptyExecutionSnapshot;
import ru.yandex.cloud.ml.platform.lzy.servant.snapshot.ExecutionSnapshot;
import ru.yandex.cloud.ml.platform.lzy.servant.snapshot.S3ExecutionSnapshot;
import ru.yandex.cloud.ml.platform.lzy.whiteboard.WhiteboardManager;
import ru.yandex.cloud.ml.platform.lzy.whiteboard.WhiteboardMeta;
import ru.yandex.cloud.ml.platform.lzy.whiteboard.impl.LocalWhiteboardManager;
import ru.yandex.cloud.ml.platform.model.util.lock.LocalLockManager;
import ru.yandex.cloud.ml.platform.model.util.lock.LockManager;
import yandex.cloud.priv.datasphere.v2.lzy.Operations;
import yandex.cloud.priv.datasphere.v2.lzy.Servant;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.stream.Stream;

@SuppressWarnings("WeakerAccess")
public class LzyExecution {
    private static final Logger LOG = LogManager.getLogger(LzyExecution.class);

    @SuppressWarnings("FieldCanBeLocal")
    private final String taskId;
    private final AtomicZygote zygote;
    private final LineReaderSlot stdoutSlot;
    private final LineReaderSlot stderrSlot;
    private final URI servantUri;
    private final WriterSlot stdinSlot;
    private Process exec;
    private String arguments = "";
    private final Map<String, LzySlot> slots = new ConcurrentHashMap<>();
    private final List<Consumer<Servant.ExecutionProgress>> listeners = new ArrayList<>();
    private final LockManager lockManager = new LocalLockManager();
    private final ExecutionSnapshot executionSnapshot;
    private final WhiteboardManager wbManager = new LocalWhiteboardManager();
    private final WhiteboardMeta meta;

    public LzyExecution(String taskId, AtomicZygote zygote, URI servantUri, @Nullable WhiteboardMeta meta) {
        this.taskId = taskId;
        this.zygote = zygote;
        if (meta != null) {
            executionSnapshot = new S3ExecutionSnapshot(taskId);
        } else {
            executionSnapshot = new EmptyExecutionSnapshot();
        }
        stdinSlot = new WriterSlot(taskId, new TextLinesInSlot("/dev/stdin"), executionSnapshot);
        stdoutSlot = new LineReaderSlot(taskId, new TextLinesOutSlot("/dev/stdout"), executionSnapshot);
        stderrSlot = new LineReaderSlot(taskId, new TextLinesOutSlot("/dev/stderr"), executionSnapshot);
        this.servantUri = servantUri;
        this.meta = meta;
    }

    public LzySlot configureSlot(Slot spec, String binding) {
        LOG.info("LzyExecution::configureSlot " + spec.name() + " binding: " + binding);
        final Lock lock = lockManager.getOrCreate(spec.name());
        lock.lock();
        try {
            if (slots.containsKey(spec.name())) {
                return slots.get(spec.name());
            }
            try {
                final LzySlot slot = createSlot(spec, binding);
                if (meta != null) {
                    URI uri = executionSnapshot.getSlotUri(slot.definition());
                    wbManager.prepareToSaveData(meta.getWbId(), meta.getOperationName(), slot.definition(), uri);
                }
                if (slot.state() != Operations.SlotStatus.State.DESTROYED) {
                    LOG.info("LzyExecution::Slots.put(\n" + spec.name() + ",\n" + slot + "\n)");
                    if (spec.name().startsWith("local://")) { // No scheme in slot name
                        slots.put(spec.name().substring("local://".length()), slot);
                    } else {
                        slots.put(spec.name(), slot);
                    }
                }

                slot.onState(
                    Operations.SlotStatus.State.SUSPENDED,
                    () -> {
                        //not terminal or input slot
                        if (zygote != null || spec.direction() == Slot.Direction.INPUT) {
                            progress(Servant.ExecutionProgress.newBuilder()
                                .setDetach(Servant.SlotDetach.newBuilder()
                                    .setSlot(gRPCConverter.to(spec))
                                    .setUri(servantUri.toString() + spec.name())
                                    .build()
                                ).build()
                            );
                        }
                    }
                );
                slot.onState(Operations.SlotStatus.State.DESTROYED, () -> {
                    synchronized (slots) {
                        LOG.info("LzyExecution::Slots.remove(\n" + slot.name() + "\n)");
                        if (meta != null) {
                            wbManager.commit(meta.getWbId(), meta.getOperationName(), slot.definition());
                        }
                        slots.remove(slot.name());
                        slots.notifyAll();
                    }
                });
                if (binding == null) {
                    binding = "";
                } else if (binding.startsWith("channel:")) {
                    binding = binding.substring("channel:".length());
                }

                final String slotPath = URI.create(spec.name()).getPath();
                progress(Servant.ExecutionProgress.newBuilder().setAttach(
                    Servant.SlotAttach.newBuilder()
                        .setChannel(binding)
                        .setSlot(gRPCConverter.to(spec))
                        .setUri(servantUri.toString() + slotPath)
                        .build()
                ).build());
                LOG.info("Configured slot " + spec.name() + " " + slot);
                return slot;
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
        } finally {
            lock.unlock();
        }
    }

    public LzySlot createSlot(Slot spec, String binding) throws IOException {
        final Lock lock = lockManager.getOrCreate(spec.name());
        lock.lock();
        try {
            if (spec.equals(Slot.STDIN)) {
                return stdinSlot;
            } else if (spec.equals(Slot.STDOUT)) {
                return stdoutSlot;
            } else if (spec.equals(Slot.STDERR)) {
                return stderrSlot;
            }

            switch (spec.media()) {
                case PIPE:
                case FILE: {
                    switch (spec.direction()) {
                        case INPUT:
                            return new InFileSlot(taskId, spec, executionSnapshot);
                        case OUTPUT:
                            if (spec.name().startsWith("local://")) {
                                return new LocalOutFileSlot(taskId, spec, URI.create(spec.name()), executionSnapshot);
                            }
                            return new OutFileSlot(taskId, spec, executionSnapshot);
                    }
                    break;
                }
                case ARG:
                    arguments = binding;
                    return new LzySlotBase(spec, executionSnapshot) {};
            }
            throw new UnsupportedOperationException("Not implemented yet");
        } finally {
            lock.unlock();
        }
    }

    public void start() {
        if (zygote == null) {
            throw new IllegalStateException("Unable to start execution while in terminal mode");
        } else if (exec != null) {
            throw new IllegalStateException("LzyExecution has been already started");
        }
        try {
            progress(Servant.ExecutionProgress.newBuilder()
                .setStarted(Servant.ExecutionStarted.newBuilder().build())
                .build()
            );
            Environment session;
            if (zygote.env() instanceof PythonEnv) {
                session = new CondaEnvironment((PythonEnv) zygote.env());
                LOG.info("Conda environment is provided, using CondaEnvironment");
            } else {
                session = new SimpleBashEnvironment();
                LOG.info("No environment provided, using SimpleBashEnvironment");
            }

            if (meta != null) {
                LOG.info("Saving dependencies to whiteboard with id " + meta.getWbId());
                wbManager.addDependencies(meta.getWbId(), meta.getDependencies(), meta.getOperationName());
            }

            String command = zygote.fuze() + " " + arguments;
            LOG.info("Going to exec command " + command);
            int rc;
            String resultDescription;
            try {
                this.exec = session.exec(command);
                stdinSlot.setStream(new OutputStreamWriter(exec.getOutputStream(), StandardCharsets.UTF_8));
                stdoutSlot.setStream(new LineNumberReader(new InputStreamReader(
                    exec.getInputStream(),
                    StandardCharsets.UTF_8
                )));
                stderrSlot.setStream(new LineNumberReader(new InputStreamReader(
                    exec.getErrorStream(),
                    StandardCharsets.UTF_8
                )));
                rc = exec.waitFor();
                resultDescription = (rc == 0) ? "Success" : "Failure";
            } catch (EnvironmentInstallationException e) {
                resultDescription = "Error during environment installation:\n" + e;
                rc = ReturnCodes.ENVIRONMENT_INSTALLATION_ERROR.getRc();
            } catch (LzyExecutionException e) {
                resultDescription = "Error during task execution:\n" + e;
                rc = ReturnCodes.EXECUTION_ERROR.getRc();
            }

            Set.copyOf(slots.values()).stream().filter(s -> s instanceof LzyInputSlot).forEach(LzySlot::suspend);
            if (rc != 0) {
                Set.copyOf(slots.values()).stream()
                    .filter(s -> s instanceof LzyOutputSlot)
                    .map(s -> (LzyOutputSlot)s)
                    .forEach(LzyOutputSlot::forceClose);
            }
            synchronized (slots) {
                LOG.info("Slots: " + Arrays.toString(slots().map(LzySlot::name).toArray()));
                while (!slots.isEmpty()) {
                    slots.wait();
                }
            }
            LOG.info("Result description: " + resultDescription);
            progress(Servant.ExecutionProgress.newBuilder()
                .setExit(Servant.ExecutionConcluded.newBuilder()
                    .setRc(rc)
                    .setDescription(resultDescription)
                    .build())
                .build()
            );
        } catch (InterruptedException e) {
            final String exceptionDescription = "InterruptedException during task execution" + e;
            LOG.warn(exceptionDescription);
            progress(Servant.ExecutionProgress.newBuilder()
                .setExit(Servant.ExecutionConcluded.newBuilder()
                    .setRc(-1)
                    .setDescription(exceptionDescription)
                    .build())
                .build()
            );
        }
    }

    public Stream<LzySlot> slots() {
        return slots.values().stream();
    }

    public synchronized void progress(Servant.ExecutionProgress progress) {
        listeners.forEach(l -> l.accept(progress));
    }

    public synchronized void onProgress(Consumer<Servant.ExecutionProgress> listener) {
        listeners.add(listener);
    }

    public LzySlot slot(String name) {
        return slots.get(name);
    }

    public String taskId() {
        return taskId;
    }

    public void signal(int sigValue) {
        if (exec == null) {
            LOG.warn("Attempt to kill not started process");
        }
        try {
            Runtime.getRuntime().exec("kill -" + sigValue + " " + exec.pid());
        } catch (Exception e) {
            LOG.warn("Unable to send signal to process", e);
        }
    }

    @SuppressWarnings("unused")
    public Zygote zygote() {
        return zygote;
    }
}
