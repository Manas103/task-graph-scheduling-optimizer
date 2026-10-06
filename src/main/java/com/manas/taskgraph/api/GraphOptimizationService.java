package com.manas.taskgraph.api;

import com.manas.taskgraph.cost.CostPercentileService;
import com.manas.taskgraph.graph.BenchmarkDagGenerator;
import com.manas.taskgraph.model.TaskGraph;
import com.manas.taskgraph.model.TaskNode;
import com.manas.taskgraph.optimize.FusedGroup;
import com.manas.taskgraph.optimize.FusionOptimizer;
import com.manas.taskgraph.sim.OutputOrderChecker;
import com.manas.taskgraph.sim.WorkerPoolSimulator;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GraphOptimizationService {
    public static final int SIMULATED_WORKER_COUNT = 16;
    public static final double OVERHEAD_MS_PER_UNIT = 2.0;
    public static final String BENCHMARK_GRAPH_ID = "benchmark";

    private final CostPercentileService costService;
    private final Map<String, TaskGraph> submittedGraphs = new ConcurrentHashMap<>();

    public GraphOptimizationService(CostPercentileService costService) {
        this.costService = costService;
    }

    public String submit(GraphSubmission submission) {
        List<TaskNode> nodes = submission.nodes().stream()
                .map(n -> new TaskNode(n.id(), n.taskType(), n.dependsOn()))
                .toList();
        TaskGraph graph = new TaskGraph(nodes);
        String id = "g" + Integer.toHexString(System.identityHashCode(graph)) + Long.toHexString(System.nanoTime());
        submittedGraphs.put(id, graph);
        return id;
    }

    public PlanResponse plan(String graphId) {
        TaskGraph graph = resolve(graphId);
        Set<String> taskTypes = new LinkedHashSet<>();
        for (TaskNode n : graph.nodes()) taskTypes.add(n.taskType());

        Map<String, Double> p50ByType = new HashMap<>();
        for (String t : taskTypes) p50ByType.put(t, costService.percentilesFor(t).p50());

        List<WorkerPoolSimulator.ScheduleUnit> baselineUnits = FusionOptimizer.baselineUnits(graph, p50ByType);
        WorkerPoolSimulator.SimResult baselineResult =
                WorkerPoolSimulator.simulate(baselineUnits, SIMULATED_WORKER_COUNT, OVERHEAD_MS_PER_UNIT);

        FusionOptimizer.FusionPlan fusionPlan = FusionOptimizer.optimize(graph, p50ByType, SIMULATED_WORKER_COUNT, OVERHEAD_MS_PER_UNIT);
        WorkerPoolSimulator.SimResult fusedResult =
                WorkerPoolSimulator.simulate(fusionPlan.units(), SIMULATED_WORKER_COUNT, OVERHEAD_MS_PER_UNIT);

        boolean orderUnchanged = OutputOrderChecker.outputOrderUnchanged(graph, fusedResult.taskCompletions());

        double savingsPercent = baselineResult.makespanMs() == 0 ? 0
                : 100.0 * (baselineResult.makespanMs() - fusedResult.makespanMs()) / baselineResult.makespanMs();

        List<FusedGroupDto> groups = fusionPlan.fusedGroups().stream()
                .map(g -> new FusedGroupDto(g.groupId(), g.type().name(), g.memberTaskIds()))
                .toList();

        return new PlanResponse(
                graphId,
                graph.size(),
                baselineUnits.size(),
                fusionPlan.units().size(),
                baselineResult.makespanMs(),
                fusedResult.makespanMs(),
                savingsPercent,
                orderUnchanged,
                groups);
    }

    private TaskGraph resolve(String graphId) {
        if (BENCHMARK_GRAPH_ID.equals(graphId)) return BenchmarkDagGenerator.generate();
        TaskGraph g = submittedGraphs.get(graphId);
        if (g == null) throw new NoSuchElementException("unknown graph id " + graphId);
        return g;
    }
}
