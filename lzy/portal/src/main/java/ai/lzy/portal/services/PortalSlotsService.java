package ai.lzy.portal.services;

import ai.lzy.fs.SlotsManager;
import ai.lzy.fs.fs.LzyInputSlot;
import ai.lzy.fs.fs.LzyOutputSlot;
import ai.lzy.fs.fs.LzySlot;
import ai.lzy.longrunning.IdempotencyUtils;
import ai.lzy.longrunning.LocalOperationService;
import ai.lzy.longrunning.LocalOperationUtils;
import ai.lzy.longrunning.Operation;
import ai.lzy.model.grpc.ProtoConverter;
import ai.lzy.model.slot.SlotInstance;
import ai.lzy.portal.config.PortalConfig;
import ai.lzy.portal.slots.SnapshotSlots;
import ai.lzy.util.grpc.ContextAwareTask;
import ai.lzy.util.grpc.ProtoPrinter;
import ai.lzy.v1.channel.LzyChannelManagerGrpc;
import ai.lzy.v1.longrunning.LongRunning;
import ai.lzy.v1.longrunning.LongRunningServiceGrpc;
import ai.lzy.v1.slots.LSA;
import ai.lzy.v1.slots.LzySlotsApiGrpc;
import com.google.common.net.HostAndPort;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.ApplicationContext;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

import static ai.lzy.util.grpc.GrpcUtils.newBlockingClient;
import static ai.lzy.util.grpc.GrpcUtils.newGrpcChannel;

@Singleton
@Named("PortalSlotsService")
public class PortalSlotsService extends LzySlotsApiGrpc.LzySlotsApiImplBase {
    private static final Logger LOG = LogManager.getLogger(PortalSlotsService.class);

    private final ApplicationContext ctx;
    private final String portalId;
    private final Supplier<String> token;

    private SnapshotSlots snapshots;
    private final SlotsManager slotsManager;

    private final LocalOperationService operationService;
    private final ExecutorService workersPool;

    public PortalSlotsService(ApplicationContext ctx, PortalConfig config,
                              @Named("PortalTokenSupplier") Supplier<String> tokenFactory,
                              @Named("PortalChannelManagerChannel") ManagedChannel channelManagerChannel,
                              @Named("PortalOperationsService") LocalOperationService operationService,
                              @Named("PortalServiceExecutor") ExecutorService workersPool)
    {
        this.ctx = ctx;
        this.portalId = config.getPortalId();

        final var channelManagerClient = newBlockingClient(
            LzyChannelManagerGrpc.newBlockingStub(channelManagerChannel), "LzyPortal", tokenFactory);

        final var channelManagerOperationClient = newBlockingClient(
            LongRunningServiceGrpc.newBlockingStub(channelManagerChannel), "LzyPortal", tokenFactory);

        this.slotsManager = new SlotsManager(channelManagerClient, channelManagerOperationClient,
            HostAndPort.fromParts(config.getHost(), config.getSlotsApiPort()), true);

        this.operationService = operationService;
        this.workersPool = workersPool;

        this.token = tokenFactory;
    }

    public void start() {
        this.snapshots = ctx.getBean(SnapshotSlots.class);
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        slotsManager.slots().forEach(slot -> slot.destroy("stop service"));
        slotsManager.stop();
    }

    @Override
    public void createSlot(LSA.CreateSlotRequest request, StreamObserver<LSA.CreateSlotResponse> response) {
        response.onError(Status.UNIMPLEMENTED.withDescription("Not supported in portal").asException());
    }

    @Override
    public synchronized void connectSlot(LSA.ConnectSlotRequest request,
                                         StreamObserver<LongRunning.Operation> response)
    {
        LOG.debug("PortalSlotsApi::connectSlot { portalId: {}, request: {} }", portalId,
            ProtoPrinter.safePrinter().printToString(request));

        Operation.IdempotencyKey idempotencyKey = IdempotencyUtils.getIdempotencyKey(request);
        if (idempotencyKey != null && loadExistingOp(idempotencyKey, response)) {
            return;
        }

        final SlotInstance from = ProtoConverter.fromProto(request.getFrom());
        final SlotInstance to = ProtoConverter.fromProto(request.getTo());

        BiConsumer<LzyInputSlot, String> startConnect = (inputSlot, opId) -> {
            LOG.info("Connect portal slot, taskId: {}, slotName: {}, remoteSlotUri: {}",
                from.taskId(), from.name(), to.uri());

            try {
                // TODO: MDC & GrpcConntext
                workersPool.submit(new ContextAwareTask() {
                    @Override
                    protected void execute() {
                        LOG.info("[{}] Trying to connect slots, {} -> {}...", opId, from.shortDesc(), to.shortDesc());

                        try {
                            var channel = newGrpcChannel(to.uri().getHost(), to.uri().getPort(),
                                LzySlotsApiGrpc.SERVICE_NAME);
                            var client = newBlockingClient(LzySlotsApiGrpc.newBlockingStub(channel), "PortalSlots",
                                token);

                            var req = LSA.SlotDataRequest.newBuilder()
                                .setSlotInstance(request.getTo())
                                .setOffset(0)
                                .build();

                            var msgIter = client.openOutputSlot(req);

                            var dataProvider = StreamSupport
                                .stream(Spliterators.spliteratorUnknownSize(msgIter, Spliterator.NONNULL), false)
                                .map(msg -> msg.hasChunk() ? msg.getChunk() : ByteString.EMPTY)
                                .onClose(channel::shutdownNow);

                            inputSlot.connect(to.uri(), dataProvider);

                            operationService.updateResponse(opId, LSA.ConnectSlotResponse.getDefaultInstance());

                            LOG.info("[{}] ... connected", opId);
                        } catch (Exception e) {
                            LOG.error("[{}] Cannot connect slots, {} -> {}: {}",
                                opId, from.shortDesc(), to.shortDesc(), e.getMessage(), e);

                            operationService.updateError(opId, Status.INTERNAL.withDescription(e.getMessage()));
                        }
                    }
                });
            } catch (StatusRuntimeException e) {
                LOG.error("Failed to connect to remote slot: {}", e.getMessage(), e);
                response.onError(Status.CANCELLED.withCause(e).asException());
            }
        };

        var op = Operation.create(
            portalId,
            "ConnectSlot: %s -> %s".formatted(from.shortDesc(), to.shortDesc()),
            null,
            idempotencyKey,
            null);

        var opSnapshot = operationService.registerOperation(op);

        response.onNext(opSnapshot.toProto());
        response.onCompleted();

        if (op.id().equals(opSnapshot.id())) {
            var lzyInputSlot = snapshots.getInputSlot(from.name());
            if (lzyInputSlot != null) {
                if (lzyInputSlot.name().equals(from.name())) {
                    startConnect.accept(lzyInputSlot, op.id());
                    return;
                }

                LOG.error("Got connect to unexpected slot '{}', expected input slot '{}'",
                    from.name(), lzyInputSlot.name());

                var errorStatus = Status.INVALID_ARGUMENT.withDescription("Unexpected slot");
                operationService.updateError(op.id(), errorStatus);
                response.onError(errorStatus.asException());
                return;
            }

            var errorStatus = Status.INVALID_ARGUMENT.withDescription("Only snapshot is supported now");

            LOG.error("Only snapshot is supported now, got connect from `{}` to `{}`", from, to);
            operationService.updateError(op.id(), errorStatus);
            response.onError(errorStatus.asRuntimeException());
        }
    }

    @Override
    public synchronized void disconnectSlot(LSA.DisconnectSlotRequest request,
                                            StreamObserver<LSA.DisconnectSlotResponse> response)
    {
        LOG.debug("PortalSlotsApi::disconnectSlot { portalId: {}, request: {} }", portalId,
            ProtoPrinter.safePrinter().printToString(request));
        var idempotencyKey = IdempotencyUtils.getIdempotencyKey(request);

        if (idempotencyKey != null &&
            loadExistingOpResult(idempotencyKey, LSA.DisconnectSlotResponse.class, response, "Cannot disconnect slot"))
        {
            return;
        }

        final SlotInstance slotInstance = ProtoConverter.fromProto(request.getSlotInstance());
        LOG.info("Disconnect portal slot, taskId: {}, slotName: {}", slotInstance.taskId(), slotInstance.name());

        var slotName = slotInstance.name();

        var op = Operation.create(portalId, "DisconnectSlot: " + slotName, null, idempotencyKey, null);
        var opSnapshot = operationService.registerOperation(op);

        if (op.id().equals(opSnapshot.id())) {

            try {
                disconnectSlot(slotName);
                operationService.updateResponse(op.id(), LSA.DisconnectSlotResponse.getDefaultInstance());
                response.onNext(LSA.DisconnectSlotResponse.getDefaultInstance());
                response.onCompleted();
            } catch (StatusException e) {
                operationService.updateError(op.id(), e.getStatus());
                response.onError(e);
            } catch (Exception e) {
                operationService.updateError(op.id(), Status.INTERNAL.withDescription("Error while disconnect slot"));
                LOG.error("Error while disconnection slot {}", slotInstance, e);
            }

        } else {
            LOG.info("Found operation by idempotency key: {}", opSnapshot.toString());

            awaitOpAndReply(opSnapshot.id(), LSA.DisconnectSlotResponse.class, response, "Cannot disconnect slot");
        }
    }

    private void disconnectSlot(String slotName) throws StatusException {
        LzyInputSlot inputSlot = snapshots.getInputSlot(slotName);
        LzyOutputSlot outputSlot = snapshots.getOutputSlot(slotName);
        if (inputSlot != null) {
            inputSlot.disconnect();
        }
        if (outputSlot != null) {
            outputSlot.suspend();
        }

        if (outputSlot != null || inputSlot != null) {
            return;
        }

        LOG.error("Only snapshots are supported now, got {}", slotName);
        throw Status.NOT_FOUND.withDescription("Cannot find slot " + slotName).asException();
    }

    @Override
    public synchronized void statusSlot(LSA.StatusSlotRequest request,
                                        StreamObserver<LSA.StatusSlotResponse> response)
    {
        LOG.debug("PortalSlotsApi::statusSlot { portalId: {}, request: {} }", portalId,
            ProtoPrinter.safePrinter().printToString(request));
        final SlotInstance slotInstance = ProtoConverter.fromProto(request.getSlotInstance());
        LOG.info("Status portal slot, taskId: {}, slotName: {}", slotInstance.taskId(), slotInstance.name());

        if (!portalId.equals(slotInstance.taskId())) {
            LOG.error("Unknown task " + slotInstance.taskId());
            response.onError(Status.INVALID_ARGUMENT
                .withDescription("Unknown task " + slotInstance.taskId()).asException());
            return;
        }

        Consumer<LzySlot> reply = slot -> {
            response.onNext(
                LSA.StatusSlotResponse.newBuilder()
                    .setStatus(slot.status())
                    .build());
            response.onCompleted();
        };

        for (var slot : snapshots.getInputSlots()) {
            if (slot.name().equals(slotInstance.name())) {
                reply.accept(slot);
                return;
            }
        }

        for (var slot : snapshots.getOutputSlots()) {
            reply.accept(slot);
            return;
        }

        LOG.error("Slot '" + slotInstance.name() + "' not found");
        response.onError(Status.NOT_FOUND
            .withDescription("Slot '" + slotInstance.name() + "' not found").asException());
    }

    @Override
    public synchronized void destroySlot(LSA.DestroySlotRequest request,
                                         StreamObserver<LSA.DestroySlotResponse> response)
    {
        LOG.debug("PortalSlotsApi::destroySlot { portalId: {}, request: {} }", portalId,
            ProtoPrinter.safePrinter().printToString(request));
        var idempotencyKey = IdempotencyUtils.getIdempotencyKey(request);

        if (idempotencyKey != null &&
            loadExistingOpResult(idempotencyKey, LSA.DestroySlotResponse.class, response, "Cannot destroy slot"))
        {
            return;
        }

        final SlotInstance slotInstance = ProtoConverter.fromProto(request.getSlotInstance());
        LOG.info("Destroy portal slot, taskId: {}, slotName: {}", slotInstance.taskId(), slotInstance.name());
        var slotName = slotInstance.name();

        var op = Operation.create(portalId, "DestroySlot: " + slotName, null, idempotencyKey, null);
        var opSnapshot = operationService.registerOperation(op);

        if (op.id().equals(opSnapshot.id())) {
            boolean done = false;

            var error = request.getReason().isEmpty() ? null : request.getReason();
            LzyInputSlot inputSlot = snapshots.getInputSlot(slotName);
            LzyOutputSlot outputSlot = snapshots.getOutputSlot(slotName);

            if (inputSlot != null) {
                inputSlot.destroy(error);
                if (error != null) {
                    snapshots.removeInputSlot(slotName);
                }
                done = true;
            }
            if (outputSlot != null) {
                outputSlot.destroy(error);
                if (error != null) {
                    snapshots.removeOutputSlot(slotName);
                }
                done = true;
            }

            if (done) {
                operationService.updateResponse(op.id(), LSA.DestroySlotResponse.getDefaultInstance());
                response.onNext(LSA.DestroySlotResponse.getDefaultInstance());
                response.onCompleted();
                return;
            }

            LOG.error("Only snapshots are supported now, got {}", slotInstance);
            response.onError(Status.NOT_FOUND
                .withDescription("Cannot find slot " + slotName).asRuntimeException());
        } else {
            LOG.info("Found operation by idempotency key: {}", opSnapshot.toString());

            awaitOpAndReply(opSnapshot.id(), LSA.DestroySlotResponse.class, response, "Cannot destroy slot");
        }
    }

    @Override
    public void openOutputSlot(LSA.SlotDataRequest request, StreamObserver<LSA.SlotDataChunk> response) {
        LOG.debug("PortalSlotsApi::openOutputSlot { portalId: {}, request: {} }", portalId,
            ProtoPrinter.safePrinter().printToString(request));
        final SlotInstance slotInstance = ProtoConverter.fromProto(request.getSlotInstance());
        final var slotName = slotInstance.name();

        LzyOutputSlot outputSlot;

        synchronized (this) {
            outputSlot = getSnapshots().getOutputSlot(slotName);
        }

        if (outputSlot != null) {
            outputSlot.readFromPosition(request.getOffset(), response);
            return;
        }

        LOG.error("Only snapshots are supported now, got {}", slotInstance);
        response.onError(Status.INVALID_ARGUMENT
            .withDescription("Only snapshots are supported now").asException());
    }

    private <T extends Message> boolean loadExistingOpResult(Operation.IdempotencyKey key, Class<T> respType,
                                                             StreamObserver<T> response, String errorMsg)
    {
        return IdempotencyUtils.loadExistingOpResult(operationService, key, respType, response, errorMsg, LOG);
    }

    private boolean loadExistingOp(Operation.IdempotencyKey key, StreamObserver<LongRunning.Operation> response) {
        return IdempotencyUtils.loadExistingOp(operationService, key, response, LOG);
    }

    private <T extends Message> void awaitOpAndReply(String opId, Class<T> respType,
                                                     StreamObserver<T> response, String errorMsg)
    {
        LocalOperationUtils.awaitOpAndReply(operationService, opId, response, respType, errorMsg, LOG);
    }

    public SlotsManager getSlotsManager() {
        return slotsManager;
    }

    public SnapshotSlots getSnapshots() {
        return snapshots;
    }
}
