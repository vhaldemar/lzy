package ai.lzy.service.graph;

import ai.lzy.model.db.DbHelper;
import ai.lzy.service.data.KafkaTopicDesc;
import ai.lzy.service.data.dao.ExecutionDao;
import ai.lzy.util.kafka.KafkaConfig;
import ai.lzy.v1.channel.LzyChannelManagerPrivateGrpc.LzyChannelManagerPrivateBlockingStub;
import ai.lzy.v1.common.LME;
import ai.lzy.v1.common.LMO;
import ai.lzy.v1.common.LMS;
import ai.lzy.v1.common.LMST;
import ai.lzy.v1.graph.GraphExecutor;
import ai.lzy.v1.graph.GraphExecutor.SlotToChannelAssignment;
import ai.lzy.v1.graph.GraphExecutor.TaskDesc;
import ai.lzy.v1.portal.LzyPortal;
import ai.lzy.v1.portal.LzyPortalApi;
import ai.lzy.v1.portal.LzyPortalGrpc;
import ai.lzy.v1.workflow.LWF;
import ai.lzy.v1.workflow.LWF.Operation.SlotDescription;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static ai.lzy.channelmanager.ProtoConverter.makeCreateChannelCommand;
import static ai.lzy.model.db.DbHelper.withRetries;
import static ai.lzy.model.grpc.ProtoConverter.*;
import static ai.lzy.portal.grpc.ProtoConverter.makePortalInputSlot;
import static ai.lzy.portal.grpc.ProtoConverter.makePortalOutputSlot;
import static ai.lzy.portal.services.PortalService.PORTAL_SLOT_PREFIX;

class GraphBuilder {
    private static final Logger LOG = LogManager.getLogger(GraphBuilder.class);

    private final ExecutionDao executionDao;
    private final LzyChannelManagerPrivateBlockingStub channelManagerClient;
    private final KafkaConfig kafkaConfig;

    public GraphBuilder(ExecutionDao executionDao, LzyChannelManagerPrivateBlockingStub channelManagerClient,
                        KafkaConfig kafkaConfig)
    {
        this.executionDao = executionDao;
        this.channelManagerClient = channelManagerClient;
        this.kafkaConfig = kafkaConfig;
    }

    public void build(GraphExecutionState state, LzyPortalGrpc.LzyPortalBlockingStub portalClient) {
        var workflowName = state.getWorkflowName();
        var executionId = state.getExecutionId();
        var slot2description = state.getDescriptions().stream()
            .collect(Collectors.toMap(LWF.DataDescription::getStorageUri, Function.identity()));

        LOG.debug("Preparing portal for tasks: " + state);

        Map<String, String> slot2channelId;
        try {
            slot2channelId = createChannelsForDataFlow(slot2description, portalClient, state);
        } catch (StatusRuntimeException e) {
            state.fail(e.getStatus(), "Cannot build graph");
            LOG.error("Cannot assign slots to channels for execution: " +
                "{ executionId: {}, workflowName: {} }, error: {} ", executionId, workflowName, e.getMessage());
            return;
        } catch (Exception e) {
            state.fail(Status.INTERNAL, "Cannot build graph");
            LOG.error("Cannot assign slots to channels for execution: " +
                "{ executionId: {}, workflowName: {} }, error: {} ", executionId, workflowName, e.getMessage());
            return;
        }

        final KafkaTopicDesc desc;
        try {
            desc = DbHelper.withRetries(LOG, () -> executionDao.getKafkaTopicDesc(executionId, null));
        } catch (Exception e) {
            LOG.error("Cannot get topic description from db for execution {}: ", executionId, e);
            state.fail(Status.INTERNAL, "Cannot build graph");
            return;
        }

        final LMO.KafkaTopicDescription kafkaTopicDescription;

        kafkaTopicDescription = LMO.KafkaTopicDescription.newBuilder()
            .setTopic(desc.topicName())
            .setUsername(desc.username())
            .setPassword(desc.password())
            .addAllBootstrapServers(kafkaConfig.getBootstrapServers())
            .build();

        LOG.debug("Building tasks of graph: " + state);

        List<TaskDesc> tasks;
        try {
            tasks = buildTasksWithZone(state, slot2channelId, slot2description, kafkaTopicDescription);
        } catch (StatusRuntimeException e) {
            state.fail(e.getStatus(), "Cannot build graph");
            LOG.error("Cannot build tasks for execution: { executionId: {}, workflowName: {} }, error: {} ",
                executionId, workflowName, e.getMessage());
            return;
        } catch (Exception e) {
            state.fail(Status.INTERNAL, "Cannot build graph");
            LOG.error("Cannot build tasks for execution: { executionId: {}, workflowName: {} }, error: {} ",
                executionId, workflowName, e.getMessage());
            return;
        }

        state.setTasks(tasks);

        var channelIds = new HashSet<>(slot2channelId.values());

        var channelsDescriptions = channelIds.stream()
            .map(id -> GraphExecutor.ChannelDesc
                .newBuilder()
                .setId(id)
                .setDirect(GraphExecutor.ChannelDesc.DirectChannel.getDefaultInstance())
                .build())
            .toList();

        state.setChannels(channelsDescriptions);
    }

    private Map<String, String> createChannelsForDataFlow(Map<String, LWF.DataDescription> slot2dataDescription,
                                                          LzyPortalGrpc.LzyPortalBlockingStub portalClient,
                                                          GraphExecutionState state)
    {
        var slotName2channelId = new HashMap<String, String>();
        var partitionBySupplier = state.getDataFlowGraph().getDataflow().stream().collect(
            Collectors.partitioningBy(data -> data.supplier() != null));
        var fromOutput = partitionBySupplier.get(true);
        var fromPortal = partitionBySupplier.get(false);

        LOG.debug("Obtain storage config to open portal slots for tasks: " + state);

        LMST.StorageConfig storageConfig;
        try {
            storageConfig = withRetries(LOG, () -> executionDao.getStorageConfig(state.getExecutionId()));
        } catch (Exception e) {
            LOG.error("Cannot obtain information about snapshots storage for execution: { executionId: {} } " +
                e.getMessage(), state.getExecutionId());
            throw new RuntimeException(e);
        }

        var portalSlotToOpen = new ArrayList<LzyPortal.PortalSlotDesc>();
        var inputSlotNames = new ArrayList<String>();

        LOG.debug("Create channels and portal slots for tasks output slots: " + state);
        for (var data : fromOutput) {
            var slotUri = data.slotUri();
            var channelId = channelManagerClient.create(makeCreateChannelCommand(state.getUserId(),
                state.getWorkflowName(), state.getExecutionId(), "channel_" + slotUri)).getChannelId();
            var portalInputSlotName = PORTAL_SLOT_PREFIX + "_" + UUID.randomUUID();
            var dataDescription = slot2dataDescription.get(slotUri);

            inputSlotNames.add(portalInputSlotName);
            portalSlotToOpen.add(makePortalInputSlot(slotUri, portalInputSlotName, channelId, storageConfig));

            slotName2channelId.put(data.supplier(), channelId);
            if (data.consumers() != null) {
                for (var consumer : data.consumers()) {
                    slotName2channelId.put(consumer, channelId);
                }
            }
        }
        state.setPortalInputSlots(inputSlotNames);

        LOG.debug("Create channels and portal slots for tasks input slots: " + state);
        for (var data : fromPortal) {
            var slotUri = data.slotUri();
            var portalOutputSlotName = PORTAL_SLOT_PREFIX + "_" + UUID.randomUUID();
            var channelId = channelManagerClient.create(makeCreateChannelCommand(state.getUserId(),
                state.getWorkflowName(), state.getExecutionId(), "portal_channel_" + slotUri +
                    "_" + UUID.randomUUID())).getChannelId();

            if (data.consumers() != null) {
                for (var consumer : data.consumers()) {
                    slotName2channelId.put(consumer, channelId);
                }
            }

            portalSlotToOpen.add(makePortalOutputSlot(slotUri, portalOutputSlotName, channelId, storageConfig));
        }

        LOG.debug("Open created portal slots for tasks: " + state);
        //noinspection ResultOfMethodCallIgnored
        portalClient.openSlots(LzyPortalApi.OpenSlotsRequest.newBuilder().addAllSlots(portalSlotToOpen).build());
        return slotName2channelId;
    }

    private List<TaskDesc> buildTasksWithZone(GraphExecutionState state, Map<String, String> slot2Channel,
                                              Map<String, LWF.DataDescription> slot2description,
                                              @Nullable LMO.KafkaTopicDescription kafkaTopic)
    {
        int count = state.getOperations().size();
        var taskBuilders = new ArrayList<TaskDesc.Builder>(count);

        LOG.debug("Build tasks: " + state);
        for (int i = 0; i < count; i++) {
            var taskBuilder = TaskDesc.newBuilder().setId(UUID.randomUUID().toString());
            taskBuilders.add(taskBuilder);
            buildTaskWithZone(
                taskBuilder,
                state.getOperations().get(i),
                state.getZone(),
                slot2Channel,
                slot2description,
                kafkaTopic);
        }

        return taskBuilders.stream().map(TaskDesc.Builder::build).toList();
    }

    private void buildTaskWithZone(TaskDesc.Builder taskBuilder, LWF.Operation operation,
                                   String zoneName, Map<String, String> slot2Channel,
                                   Map<String, LWF.DataDescription> slot2description,
                                   @Nullable LMO.KafkaTopicDescription kafkaTopic)
    {
        var inputSlots = new ArrayList<LMS.Slot>();
        var outputSlots = new ArrayList<LMS.Slot>();
        var slotToChannelAssignments = new ArrayList<SlotToChannelAssignment>();

        BiConsumer<Collection<SlotDescription>, Boolean> slotsDescriptionsConsumer = (slotDescriptions, isInput) -> {
            for (var slotDescription : slotDescriptions) {
                var uri = slotDescription.getStorageUri();
                var slotName = slotDescription.getPath();
                var description = slot2description.get(uri);

                var hasDataScheme = description != null && description.hasDataScheme();

                if (isInput) {
                    var slot = hasDataScheme ? buildFileInputSlot(slotName, description.getDataScheme()) :
                        buildFileInputPlainContentSlot(slotName);
                    inputSlots.add(slot);
                } else {
                    var slot = hasDataScheme ? buildFileOutputSlot(slotName, description.getDataScheme()) :
                        buildFileOutputPlainContentSlot(slotName);
                    outputSlots.add(slot);
                }

                slotToChannelAssignments.add(SlotToChannelAssignment.newBuilder()
                    .setSlotName(slotName)
                    .setChannelId(slot2Channel.get(slotName))
                    .build());
            }
        };

        slotsDescriptionsConsumer.accept(operation.getInputSlotsList(), true);
        slotsDescriptionsConsumer.accept(operation.getOutputSlotsList(), false);

        var requirements = LMO.Requirements.newBuilder()
            .setZone(zoneName).setPoolLabel(operation.getPoolSpecName()).build();
        var env = LME.EnvSpec.newBuilder();

        env.setDockerImage(operation.getDockerImage());

        if (operation.hasDockerCredentials()) {
            env.setDockerCredentials(LME.DockerCredentials.newBuilder()
                .setUsername(operation.getDockerCredentials().getUsername())
                .setPassword(operation.getDockerCredentials().getPassword())
                .setRegistryName(operation.getDockerCredentials().getRegistryName())
                .build());
        }

        var policy = switch (operation.getDockerPullPolicy()) {
            case ALWAYS -> LME.DockerPullPolicy.ALWAYS;
            case IF_NOT_EXISTS -> LME.DockerPullPolicy.IF_NOT_EXISTS;
            case UNSPECIFIED -> LME.DockerPullPolicy.IF_NOT_EXISTS;  // default
            case UNRECOGNIZED -> throw Status.INVALID_ARGUMENT
                .withDescription("Wrong docker pull policy")
                .asRuntimeException();
        };

        env.setDockerPullPolicy(policy);

        if (operation.hasPython()) {
            env.setPyenv(
                LME.PythonEnv.newBuilder()
                    .setYaml(operation.getPython().getYaml())
                    .addAllLocalModules(
                        operation.getPython().getLocalModulesList().stream()
                            .map(module -> LME.LocalModule.newBuilder()
                                .setName(module.getName())
                                .setUri(module.getUrl())
                                .build()
                            ).toList()
                    ).build()
            );
        } else {
            env.setProcessEnv(LME.ProcessEnv.newBuilder().build());
        }

        env.putAllEnv(operation.getEnvMap());

        var taskOperation = LMO.Operation.newBuilder()
            .setEnv(env.build())
            .setRequirements(requirements)
            .setCommand(operation.getCommand())
            .addAllSlots(inputSlots)
            .addAllSlots(outputSlots)
            .setName(operation.getName());

        if (kafkaTopic != null) {
            taskOperation.setKafkaTopic(kafkaTopic);
        }

        taskBuilder.setOperation(taskOperation).addAllSlotAssignments(slotToChannelAssignments).build();
    }
}
