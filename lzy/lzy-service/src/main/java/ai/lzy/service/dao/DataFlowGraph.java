package ai.lzy.service.dao;

import ai.lzy.v1.workflow.LWF;
import ai.lzy.v1.workflow.LWF.Operation.SlotDescription;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.*;
import java.util.stream.Collectors;

@JsonSerialize
@JsonDeserialize
@NoArgsConstructor
public class DataFlowGraph {
    private static final String edge = " -> ";

    @JsonIgnore
    private List<List<Integer>> graph;
    @JsonIgnore
    private ArrayList<LWF.Operation> operations;

    // slot uri ---> (slot name, operation id)
    @JsonIgnore
    private Map<String, Map.Entry<String, Integer>> dataSuppliers;
    @JsonIgnore
    private Map<String, List<Map.Entry<String, Integer>>> dataConsumers;

    // slot uri ---> slots name
    @JsonIgnore
    private Map<String, List<String>> danglingInputSlots;

    @JsonIgnore
    private List<Integer> cycle = null;

    @JsonInclude
    @Getter
    @Setter
    private List<Data> dataflow;

    @JsonInclude
    @Getter
    @Setter
    private String dotNotation;

    public DataFlowGraph(Collection<LWF.Operation> operations) {
        this.operations = new ArrayList<>();

        dataSuppliers = new HashMap<>();
        dataConsumers = new HashMap<>();

        var i = 0;
        for (var op : operations) {
            var outputAlreadyInGraph = !op.getOutputSlotsList().isEmpty() && op.getOutputSlotsList().stream()
                .map(SlotDescription::getStorageUri).allMatch(dataSuppliers::containsKey);

            if (outputAlreadyInGraph) {
                continue;
            }

            for (SlotDescription slot : op.getOutputSlotsList()) {
                var supplier = dataSuppliers.get(slot.getStorageUri());
                if (supplier != null) {
                    throw new RuntimeException("Output slot with uri '" + slot.getStorageUri() + "' already exists");
                }
                dataSuppliers.put(slot.getStorageUri(), new AbstractMap.SimpleEntry<>(slot.getPath(), i));
            }
            for (SlotDescription slot : op.getInputSlotsList()) {
                var consumers = dataConsumers.computeIfAbsent(slot.getStorageUri(), k -> new ArrayList<>());
                consumers.add(new AbstractMap.SimpleImmutableEntry<>(slot.getPath(), i));
            }
            i++;

            this.operations.add(op);
        }

        danglingInputSlots = new HashMap<>();
        graph = new ArrayList<>(this.operations.size());

        for (var operation : this.operations) {
            for (SlotDescription slot : operation.getInputSlotsList()) {
                if (!dataSuppliers.containsKey(slot.getStorageUri())) {
                    danglingInputSlots.compute(slot.getStorageUri(), (slotUri, consumers) -> {
                        consumers = (consumers != null) ? consumers : new ArrayList<>();
                        consumers.add(slot.getPath());
                        return consumers;
                    });
                }
            }

            var to = new ArrayList<Integer>();
            for (SlotDescription slot : operation.getOutputSlotsList()) {
                var consumers = dataConsumers.get(slot.getStorageUri());
                if (consumers != null) {
                    for (var consumer : consumers) {
                        to.add(consumer.getValue());
                    }
                }
            }

            graph.add(to);
        }

        dataflow = calculateDataFlow();
        dotNotation = printDotNotation();
    }

    public ArrayList<LWF.Operation> getOperations() {
        return operations;
    }

    private int[] dfs(int v, int[] colors, int[] prev) {
        colors[v] = 1;

        for (var u : graph.get(v)) {
            if (colors[u] == 0) {
                // not visited yet
                prev[u] = v;
                int[] cycleEnds = dfs(u, colors, prev);
                if (cycleEnds != null) {
                    return cycleEnds;
                }
            } else if (colors[u] == 1) {
                // cycle found
                return new int[] {u, v};
            }
        }

        colors[v] = 2;
        return null;
    }

    public boolean hasCycle() {
        var n = operations.size();
        var colors = new int[n];
        var prev = new int[n];

        int[] cycleEnds = null;
        for (var i = 0; i < n; i++) {
            if (colors[i] == 0) {
                cycleEnds = dfs(i, colors, prev);
            }
            if (cycleEnds != null) {
                break;
            }
        }

        if (cycleEnds != null) {
            // fill the cycle
            var cycle = new LinkedList<Integer>();
            cycle.add(cycleEnds[0]);
            for (var v = cycleEnds[1]; v != cycleEnds[0]; v = prev[v]) {
                cycle.addFirst(v);
            }
            cycle.addFirst(cycleEnds[0]);

            this.cycle = cycle;
            return true;
        }

        return false;
    }

    public String printCycle() {
        if (cycle == null) {
            throw new IllegalStateException("Cycle not found");
        }
        return cycle.stream().map(i -> operations.get(i).getName()).collect(Collectors.joining(edge));
    }

    private String printDotNotation() {
        List<Data> dataflow = getDataflow();
        var stringBuilder = new StringBuilder("digraph {");

        for (var data : dataflow) {
            var slotUri = data.storageUri;
            var in = findSupplierOperationIdBy(slotUri);

            if (data.consumers != null) {
                for (String outSlotName : data.consumers) {
                    var out = findConsumerOperationIdBy(slotUri, outSlotName);
                    stringBuilder
                        .append("\t")
                        .append('"').append(in).append('"')
                        .append(edge)
                        .append('"').append(out).append('"')
                        .append(";");
                }
            } else {
                stringBuilder
                    .append("\t")
                    .append('"').append(in).append('"')
                    .append(";");
            }
        }

        stringBuilder.append("\n}");
        return stringBuilder.toString();
    }

    @Override
    public String toString() {
        // prints operations graph in dot notation
        return dotNotation;
    }

    private String findSupplierOperationIdBy(String slotUri) {
        if (danglingInputSlots.containsKey(slotUri)) {
            return "storage";
        }

        int operationId = dataSuppliers.get(slotUri).getValue();
        return operations.get(operationId).getName();
    }

    private String findConsumerOperationIdBy(String slotUri, String slotName) {
        List<Map.Entry<String, Integer>> consumersSlotNames = dataConsumers.get(slotUri);
        Map.Entry<String, Integer> slotNameAndOpId = consumersSlotNames.stream()
            .filter(slot -> slot.getKey().contentEquals(slotName))
            .findFirst()
            .orElseThrow();
        return operations.get(slotNameAndOpId.getValue()).getName();
    }

    /**
     * Returns data which transfer through channel. The data are identified by slot uri.
     *
     * @param storageUri -- uri which identifies data
     * @param producer   -- the producer slot id, null if data has only storage producer
     * @param consumers  -- ids of input slots which consumes data by slot uri
     */
    public record Data(String storageUri, @Nullable String producer, List<String> consumers) {}

    private List<Data> calculateDataFlow() {
        var dataFromOutput = dataSuppliers.entrySet().stream()
            .map(pair -> {
                var supplierSlotUri = pair.getKey();
                var supplierSlotName = pair.getValue().getKey();
                var consumerSlotNamesAndOpIds = dataConsumers.get(supplierSlotUri);

                List<String> consumerSlotNames = new ArrayList<>();

                if (consumerSlotNamesAndOpIds != null) {
                    for (var consumerSlotNamesAndOpId : consumerSlotNamesAndOpIds) {
                        consumerSlotNames.add(consumerSlotNamesAndOpId.getKey());
                    }
                }

                return new Data(supplierSlotUri, supplierSlotName, consumerSlotNames);
            }).toList();

        var notFoundInOutput = danglingInputSlots.entrySet().stream()
            .map(pair -> new Data(pair.getKey(), null, pair.getValue()))
            .toList();

        return new ArrayList<>() {
            {
                addAll(dataFromOutput);
                addAll(notFoundInOutput);
            }
        };
    }
}
