package ai.lzy.service.graph;

import ai.lzy.service.BaseTest;
import ai.lzy.test.IdempotencyUtils.TestScenario;
import ai.lzy.v1.common.LMST;
import ai.lzy.v1.workflow.LWF;
import ai.lzy.v1.workflow.LWF.Graph;
import ai.lzy.v1.workflow.LWFS;
import ai.lzy.v1.workflow.LzyWorkflowServiceGrpc.LzyWorkflowServiceBlockingStub;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public abstract class AbstractGraphExecutionTest extends BaseTest {
    Graph buildSimpleGraph(LMST.StorageConfig storageConfig) {
        var operations = List.of(
            LWF.Operation.newBuilder()
                .setName("first task prints string 'i-am-hacker' to variable")
                .setCommand("echo 'i-am-a-hacker' > $LZY_MOUNT/a")
                .addOutputSlots(LWF.Operation.SlotDescription.newBuilder()
                    .setPath("/a")
                    .setStorageUri(buildSlotUri("snapshot_a_1", storageConfig))
                    .build())
                .setPoolSpecName("s")
                .build(),
            LWF.Operation.newBuilder()
                .setName("second task reads string 'i-am-hacker' from variable and prints it to another one")
                .setCommand("$LZY_MOUNT/sbin/cat $LZY_MOUNT/a > $LZY_MOUNT/b")
                .addInputSlots(LWF.Operation.SlotDescription.newBuilder()
                    .setPath("/a")
                    .setStorageUri(buildSlotUri("snapshot_a_1", storageConfig))
                    .build())
                .addOutputSlots(LWF.Operation.SlotDescription.newBuilder()
                    .setPath("/b")
                    .setStorageUri(buildSlotUri("snapshot_b_1", storageConfig))
                    .build())
                .setPoolSpecName("s")
                .build()
        );

        return Graph.newBuilder()
            .setName("simple-graph")
            .setZone("ru-central1-a")
            .addAllOperations(operations)
            .build();
    }

    TestScenario<LzyWorkflowServiceBlockingStub, Map.Entry<String, Graph>, String> executeSimpleGraphScenario() {
        var workflowName = "workflow_1";
        return new TestScenario<>(authorizedWorkflowClient,
            stub -> {
                LMST.StorageConfig storageConfig = stub.getOrCreateDefaultStorage(
                    LWFS.GetOrCreateDefaultStorageRequest.newBuilder().build()).getStorage();
                LWFS.StartWorkflowResponse workflow = stub.startWorkflow(LWFS.StartWorkflowRequest.newBuilder()
                    .setWorkflowName(workflowName).setSnapshotStorage(storageConfig).build());
                LWF.Graph graph = buildSimpleGraph(storageConfig);
                var executionId = workflow.getExecutionId();

                return new AbstractMap.SimpleEntry<>(executionId, graph);
            },
            /* action */
            (stub, input) ->
                stub.executeGraph(LWFS.ExecuteGraphRequest.newBuilder()
                    .setWorkflowName(workflowName)
                    .setExecutionId(input.getKey())
                    .setGraph(input.getValue())
                    .build()).getGraphId(),
            graphId -> assertFalse(graphId.isBlank()));
    }

    TestScenario<LzyWorkflowServiceBlockingStub, Map.Entry<String, Graph>, Status.Code> emptyGraphScenario() {
        var workflowName = "workflow_1";
        return new TestScenario<>(authorizedWorkflowClient,
            stub -> {
                LMST.StorageConfig storageConfig = stub.getOrCreateDefaultStorage(
                    LWFS.GetOrCreateDefaultStorageRequest.newBuilder().build()).getStorage();
                LWFS.StartWorkflowResponse workflow = stub.startWorkflow(LWFS.StartWorkflowRequest.newBuilder()
                    .setWorkflowName(workflowName).setSnapshotStorage(storageConfig).build());

                var executionId = workflow.getExecutionId();
                var graph = LWF.Graph.newBuilder()
                    .setName("simple-graph")
                    .build();

                return new AbstractMap.SimpleEntry<>(executionId, graph);
            },
            /* action */
            (stub, input) ->
                assertThrows(StatusRuntimeException.class, () -> {
                    //noinspection ResultOfMethodCallIgnored
                    stub.executeGraph(LWFS.ExecuteGraphRequest.newBuilder()
                        .setWorkflowName(workflowName)
                        .setExecutionId(input.getKey())
                        .setGraph(input.getValue())
                        .build());
                }).getStatus().getCode(),
            errorCode -> assertEquals(Status.INVALID_ARGUMENT.getCode(), errorCode));
    }

    TestScenario<LzyWorkflowServiceBlockingStub, Map.Entry<String, Graph>, Status.Code> duplicatedSlotScenario() {
        var workflowName = "workflow_1";
        return new TestScenario<>(authorizedWorkflowClient,
            stub -> {
                LMST.StorageConfig storageConfig = stub.getOrCreateDefaultStorage(
                    LWFS.GetOrCreateDefaultStorageRequest.newBuilder().build()).getStorage();
                LWFS.StartWorkflowResponse workflow = stub.startWorkflow(LWFS.StartWorkflowRequest.newBuilder()
                    .setWorkflowName(workflowName).setSnapshotStorage(storageConfig).build());

                var operation =
                    LWF.Operation.newBuilder()
                        .setName("prints strings to variables")
                        .setCommand(
                            "echo 'i-am-a-hacker' > $LZY_MOUNT/a && echo 'hello' > $LZY_MOUNT/b")
                        .addOutputSlots(LWF.Operation.SlotDescription.newBuilder()
                            .setPath("/a")
                            .setStorageUri(buildSlotUri("snapshot_a_1", storageConfig))
                            .build())
                        .addOutputSlots(LWF.Operation.SlotDescription.newBuilder()
                            .setPath("/b")
                            .setStorageUri(buildSlotUri("snapshot_a_1", storageConfig)))
                        .setPoolSpecName("s")
                        .build();

                var executionId = workflow.getExecutionId();
                var graph = LWF.Graph.newBuilder()
                    .setName("simple-graph")
                    .addOperations(operation)
                    .build();

                return new AbstractMap.SimpleEntry<>(executionId, graph);
            },
            /* action */
            (stub, input) ->
                assertThrows(StatusRuntimeException.class,
                    () -> {
                        //noinspection ResultOfMethodCallIgnored
                        stub.executeGraph(LWFS.ExecuteGraphRequest.newBuilder()
                            .setWorkflowName(workflowName)
                            .setExecutionId(input.getKey())
                            .setGraph(input.getValue())
                            .build());
                    }).getStatus().getCode(),
            errorCode -> assertEquals(Status.INVALID_ARGUMENT.getCode(), errorCode));
    }

    TestScenario<LzyWorkflowServiceBlockingStub, Map.Entry<String, Graph>, Status.Code> cyclicDataflowGraphScenario() {
        var workflowName = "workflow_1";
        return new TestScenario<>(authorizedWorkflowClient,
            stub -> {
                LMST.StorageConfig storageConfig = stub.getOrCreateDefaultStorage(
                    LWFS.GetOrCreateDefaultStorageRequest.newBuilder().build()).getStorage();
                LWFS.StartWorkflowResponse workflow = stub.startWorkflow(LWFS.StartWorkflowRequest.newBuilder()
                    .setWorkflowName(workflowName).setSnapshotStorage(storageConfig).build());

                var operationsWithCycleDependency = List.of(
                    LWF.Operation.newBuilder()
                        .setName("first operation")
                        .setCommand("echo '42' > $LZY_MOUNT/a && " +
                            "$LZY_MOUNT/sbin/cat $LZY_MOUNT/c > $LZY_MOUNT/b")
                        .addInputSlots(LWF.Operation.SlotDescription.newBuilder()
                            .setPath("/c")
                            .setStorageUri(buildSlotUri("snapshot_c_1", storageConfig))
                            .build())
                        .addOutputSlots(LWF.Operation.SlotDescription.newBuilder()
                            .setPath("/a")
                            .setStorageUri(buildSlotUri("snapshot_a_1", storageConfig))
                            .build())
                        .addOutputSlots(LWF.Operation.SlotDescription.newBuilder()
                            .setPath("/b")
                            .setStorageUri(buildSlotUri("snapshot_b_1", storageConfig))
                            .build())
                        .setPoolSpecName("s")
                        .build(),
                    LWF.Operation.newBuilder()
                        .setName("second operation")
                        .setCommand("$LZY_MOUNT/sbin/cat $LZY_MOUNT/a > $LZY_MOUNT/d &&" +
                            " $LZY_MOUNT/sbin/cat $LZY_MOUNT/d > $LZY_MOUNT/c")
                        .addInputSlots(LWF.Operation.SlotDescription.newBuilder()
                            .setPath("/a")
                            .setStorageUri(buildSlotUri("snapshot_a_1", storageConfig))
                            .build())
                        .addOutputSlots(LWF.Operation.SlotDescription.newBuilder()
                            .setPath("/d")
                            .setStorageUri(buildSlotUri("snapshot_d_1", storageConfig))
                            .build())
                        .addOutputSlots(LWF.Operation.SlotDescription.newBuilder()
                            .setPath("/c")
                            .setStorageUri(buildSlotUri("snapshot_c_1", storageConfig))
                            .build())
                        .setPoolSpecName("s")
                        .build());

                var executionId = workflow.getExecutionId();
                var graph = LWF.Graph.newBuilder()
                    .setName("simple-graph")
                    .addAllOperations(operationsWithCycleDependency)
                    .build();

                return new AbstractMap.SimpleEntry<>(executionId, graph);
            },
            /* action */
            (stub, input) ->
                assertThrows(StatusRuntimeException.class,
                    () -> {
                        //noinspection ResultOfMethodCallIgnored
                        stub.executeGraph(
                            LWFS.ExecuteGraphRequest.newBuilder()
                                .setWorkflowName(workflowName)
                                .setExecutionId(input.getKey())
                                .setGraph(input.getValue())
                                .build());
                    }).getStatus().getCode(),
            errorCode -> assertEquals(Status.INVALID_ARGUMENT.getCode(), errorCode));
    }

    TestScenario<LzyWorkflowServiceBlockingStub, Map.Entry<String, Graph>, Status.Code> unknownInputSlotUriScenario() {
        var workflowName = "workflow_1";
        return new TestScenario<>(authorizedWorkflowClient,
            stub -> {
                LMST.StorageConfig storageConfig = stub.getOrCreateDefaultStorage(
                    LWFS.GetOrCreateDefaultStorageRequest.newBuilder().build()).getStorage();
                LWFS.StartWorkflowResponse workflow = stub.startWorkflow(LWFS.StartWorkflowRequest.newBuilder()
                    .setWorkflowName(workflowName).setSnapshotStorage(storageConfig).build());

                var unknownStorageUri = buildSlotUri("snapshot_a_1", storageConfig);

                var operation =
                    LWF.Operation.newBuilder()
                        .setName("prints strings to variable")
                        .setCommand("$LZY_MOUNT/sbin/cat $LZY_MOUNT/a > $LZY_MOUNT/b")
                        .addInputSlots(LWF.Operation.SlotDescription.newBuilder()
                            .setPath("/a")
                            .setStorageUri(unknownStorageUri)
                            .build())
                        .addOutputSlots(LWF.Operation.SlotDescription.newBuilder()
                            .setPath("/b")
                            .setStorageUri(buildSlotUri("snapshot_b_1", storageConfig))
                            .build())
                        .setPoolSpecName("s")
                        .build();

                var graph = LWF.Graph.newBuilder()
                    .setName("simple-graph-2")
                    .addOperations(operation)
                    .build();

                var executionId = workflow.getExecutionId();

                return new AbstractMap.SimpleEntry<>(executionId, graph);
            },
            /* action */
            (stub, input) ->
                assertThrows(StatusRuntimeException.class,
                    () -> {
                        //noinspection ResultOfMethodCallIgnored
                        stub.executeGraph(
                            LWFS.ExecuteGraphRequest.newBuilder()
                                .setWorkflowName(workflowName)
                                .setExecutionId(input.getKey())
                                .setGraph(input.getValue())
                                .build());
                    }).getStatus().getCode(),
            errorCode -> assertEquals(Status.NOT_FOUND.getCode(), errorCode));
    }

    TestScenario<LzyWorkflowServiceBlockingStub, Map.Entry<String, Graph>, Status.Code> withoutSuitableZoneScenario() {
        var workflowName = "workflow_1";
        return new TestScenario<>(authorizedWorkflowClient,
            stub -> {
                LMST.StorageConfig storageConfig = stub.getOrCreateDefaultStorage(
                    LWFS.GetOrCreateDefaultStorageRequest.newBuilder().build()).getStorage();
                LWFS.StartWorkflowResponse workflow = stub.startWorkflow(LWFS.StartWorkflowRequest.newBuilder()
                    .setWorkflowName(workflowName).setSnapshotStorage(storageConfig).build());

                var operation =
                    LWF.Operation.newBuilder()
                        .setName("prints string to variable")
                        .setCommand("echo 'i-am-a-hacker' > $LZY_MOUNT/a")
                        .addOutputSlots(LWF.Operation.SlotDescription.newBuilder()
                            .setPath("/a")
                            .setStorageUri(buildSlotUri("snapshot_a_1", storageConfig))
                            .build())
                        .setPoolSpecName("not-existing-spec")
                        .build();

                var executionId = workflow.getExecutionId();
                var graph = LWF.Graph.newBuilder()
                    .setName("simple-graph")
                    .addOperations(operation)
                    .build();

                return new AbstractMap.SimpleEntry<>(executionId, graph);
            },
            /* action */
            (stub, input) -> assertThrows(StatusRuntimeException.class,
                () -> {
                    //noinspection ResultOfMethodCallIgnored
                    stub.executeGraph(LWFS.ExecuteGraphRequest.newBuilder()
                        .setWorkflowName(workflowName)
                        .setExecutionId(input.getKey())
                        .setGraph(input.getValue())
                        .build());
                }).getStatus().getCode(),
            errorCode -> assertEquals(Status.INVALID_ARGUMENT.getCode(), errorCode));
    }

    TestScenario<LzyWorkflowServiceBlockingStub, Map.Entry<String, Graph>, Status.Code> nonSuitableZoneScenario() {
        var workflowName = "workflow_1";
        return new TestScenario<>(authorizedWorkflowClient,
            stub -> {
                LMST.StorageConfig storageConfig = stub.getOrCreateDefaultStorage(
                    LWFS.GetOrCreateDefaultStorageRequest.newBuilder().build()).getStorage();
                LWFS.StartWorkflowResponse workflow = stub.startWorkflow(LWFS.StartWorkflowRequest.newBuilder()
                    .setWorkflowName(workflowName).setSnapshotStorage(storageConfig).build());

                var operation =
                    LWF.Operation.newBuilder()
                        .setName("prints strings to variables")
                        .setCommand("echo 'i-am-a-hacker' > $LZY_MOUNT/a && echo 'hi' > $LZY_MOUNT/b")
                        .addOutputSlots(LWF.Operation.SlotDescription.newBuilder()
                            .setPath("/a")
                            .setStorageUri(buildSlotUri("snapshot_a_1", storageConfig))
                            .build())
                        .addOutputSlots(LWF.Operation.SlotDescription.newBuilder()
                            .setPath("/b")
                            .setStorageUri(buildSlotUri("snapshot_b_1", storageConfig)))
                        .setPoolSpecName("l")
                        .build();

                var executionId = workflow.getExecutionId();
                var graph = Graph.newBuilder()
                    .setName("simple-graph")
                    .setZone("ru-central1-a")
                    .addOperations(operation)
                    .build();

                return new AbstractMap.SimpleEntry<>(executionId, graph);
            },
            /* action */
            (stub, input) -> assertThrows(StatusRuntimeException.class,
                () -> {
                    //noinspection ResultOfMethodCallIgnored
                    stub.executeGraph(LWFS.ExecuteGraphRequest.newBuilder()
                        .setWorkflowName(workflowName)
                        .setExecutionId(input.getKey())
                        .setGraph(input.getValue())
                        .build());
                }).getStatus().getCode(),
            errorCode -> assertEquals(Status.INVALID_ARGUMENT.getCode(), errorCode));
    }
}
