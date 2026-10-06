package com.manas.taskgraph.bench;

import com.manas.taskgraph.ExecutionHistorySeeder;
import com.manas.taskgraph.api.GraphOptimizationService;
import com.manas.taskgraph.cost.CostPercentileService;
import com.manas.taskgraph.graph.BenchmarkDagGenerator;
import com.manas.taskgraph.model.TaskGraph;
import com.manas.taskgraph.model.TaskNode;
import com.manas.taskgraph.optimize.FusedGroup;
import com.manas.taskgraph.optimize.FusionOptimizer;
import com.manas.taskgraph.persistence.ExecutionRecordRepository;
import com.manas.taskgraph.sim.OutputOrderChecker;
import com.manas.taskgraph.sim.WorkerPoolSimulator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 240-node measurement this project's README numbers come from. Tagged
 * {@code benchmark} and excluded from the default {@code mvn test} (see
 * pom.xml); run explicitly with
 * {@code mvn test -Dtest=BenchmarkRunnerTest -Dsurefire.excludedGroups=}.
 *
 * Seeds its own recorded-execution history (deterministic RNG, clearly
 * synthetic, documented in the README) rather than depending on whatever
 * happens to already be in the database, so the measurement is reproducible.
 * The seeded rows are left in place afterward so the REST API's
 * {@code /api/graphs/benchmark/plan} endpoint has real recorded data to
 * query against.
 */
@SpringBootTest
class BenchmarkRunnerTest {
    // taskType -> (meanMs, stddevMs). Each role in the benchmark block gets
    // its own distribution so fusion has a real, non-uniform cost model to
    // plan against, not eight copies of the same number.
    private static final Map<String, double[]> TYPE_STATS = Map.of(
            "entry", new double[]{4.0, 0.5},
            "transform_a", new double[]{12.0, 2.0},
            "transform_b", new double[]{10.0, 1.5},
            "transform_c", new double[]{14.0, 2.5},
            "fanout_x", new double[]{20.0, 3.0},
            "fanout_y", new double[]{18.0, 3.0},
            "fanout_z", new double[]{22.0, 4.0},
            "merge", new double[]{6.0, 1.0});
    private static final int SAMPLES_PER_TYPE = 2000;

    @Autowired
    private ExecutionRecordRepository repo;
    @Autowired
    private CostPercentileService costService;

    @Test
    @Tag("benchmark")
    void measureFusionSavingsOnThe240NodeBenchmark() throws IOException {
        try {
            repo.count();
        } catch (Exception e) {
            Assumptions.abort("PostgreSQL is not reachable: " + e.getMessage());
        }

        reseed();

        TaskGraph graph = BenchmarkDagGenerator.generate();
        assertThat(graph.size()).isEqualTo(240);

        Map<String, Double> p50ByType = new LinkedHashMap<>();
        Map<String, CostPercentileService.Percentiles> allPercentiles = new LinkedHashMap<>();
        for (String type : TYPE_STATS.keySet()) {
            CostPercentileService.Percentiles p = costService.percentilesFor(type);
            allPercentiles.put(type, p);
            p50ByType.put(type, p.p50());
        }

        List<WorkerPoolSimulator.ScheduleUnit> baselineUnits = FusionOptimizer.baselineUnits(graph, p50ByType);
        WorkerPoolSimulator.SimResult baseline =
                WorkerPoolSimulator.simulate(baselineUnits, GraphOptimizationService.SIMULATED_WORKER_COUNT, GraphOptimizationService.OVERHEAD_MS_PER_UNIT);

        FusionOptimizer.FusionPlan plan = FusionOptimizer.optimize(graph, p50ByType,
                GraphOptimizationService.SIMULATED_WORKER_COUNT, GraphOptimizationService.OVERHEAD_MS_PER_UNIT);
        WorkerPoolSimulator.SimResult fused =
                WorkerPoolSimulator.simulate(plan.units(), GraphOptimizationService.SIMULATED_WORKER_COUNT, GraphOptimizationService.OVERHEAD_MS_PER_UNIT);

        boolean orderUnchanged = OutputOrderChecker.outputOrderUnchanged(graph, fused.taskCompletions());
        assertThat(fused.taskCompletions()).hasSize(240);
        assertThat(orderUnchanged).isTrue();
        assertThat(fused.makespanMs()).isLessThanOrEqualTo(baseline.makespanMs());

        long chainGroups = plan.fusedGroups().stream().filter(g -> g.type() == FusedGroup.FusionType.LINEAR_CHAIN).count();
        long siblingGroups = plan.fusedGroups().stream().filter(g -> g.type() == FusedGroup.FusionType.SIBLING_BRANCH).count();
        assertThat(chainGroups).isGreaterThan(0);

        double savingsPercent = 100.0 * (baseline.makespanMs() - fused.makespanMs()) / baseline.makespanMs();

        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        out.println("=== Task-Graph Scheduling Optimizer -- 240-node benchmark run ===");
        out.println("Date: " + LocalDate.now());
        out.println("Graph: " + graph.size() + " nodes (" + BenchmarkDagGenerator.BLOCK_COUNT + " blocks x " + BenchmarkDagGenerator.NODES_PER_BLOCK + ")");
        out.println("Simulated workers: " + GraphOptimizationService.SIMULATED_WORKER_COUNT);
        out.println("Per-unit scheduling overhead: " + GraphOptimizationService.OVERHEAD_MS_PER_UNIT + " ms (modeling assumption, not a measured system property)");
        out.println();
        out.println("-- Per-task-type cost percentiles, from PostgreSQL percentile_cont over " + SAMPLES_PER_TYPE + " recorded executions each --");
        for (var e : allPercentiles.entrySet()) {
            var p = e.getValue();
            out.printf("  %-14s n=%-5d p50=%7.2fms  p90=%7.2fms  p99=%7.2fms%n", e.getKey(), p.sampleCount(), p.p50(), p.p90(), p.p99());
        }
        out.println();
        out.println("-- Fusion plan --");
        out.println("  baseline units (1 per task): " + baselineUnits.size());
        out.println("  fused units: " + plan.units().size());
        out.println("  linear-chain groups fused: " + chainGroups);
        out.println("  sibling-branch groups fused: " + siblingGroups);
        out.println();
        out.println("-- Replayed makespan, simulated 16-worker pool --");
        out.printf("  baseline makespan: %.2f ms%n", baseline.makespanMs());
        out.printf("  fused makespan:    %.2f ms%n", fused.makespanMs());
        out.printf("  makespan cut:      %.2f%%%n", savingsPercent);
        out.println("  task output order unchanged (fused schedule is a valid topological order of the original 240-node DAG): " + orderUnchanged);
        out.flush();

        String report = sw.toString();
        System.out.print(report);
        Path docsDir = Path.of("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("benchmark_output.txt"), report);
    }

    private void reseed() {
        for (String type : TYPE_STATS.keySet()) {
            repo.findByTaskType(type).forEach(repo::delete);
        }
        long seed = 1000;
        for (var e : TYPE_STATS.entrySet()) {
            ExecutionHistorySeeder.seed(repo, e.getKey(), SAMPLES_PER_TYPE, seed++, e.getValue()[0], e.getValue()[1]);
        }
    }
}
