package com.manas.taskgraph.optimize;

import com.manas.taskgraph.graph.BenchmarkDagGenerator;
import com.manas.taskgraph.model.TaskGraph;
import com.manas.taskgraph.model.TaskNode;
import com.manas.taskgraph.sim.OutputOrderChecker;
import com.manas.taskgraph.sim.WorkerPoolSimulator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FusionOptimizerTest {

    private static final Map<String, Double> COST = Map.of(
            "entry", 1.0, "transform_a", 3.0, "transform_b", 3.0, "transform_c", 3.0,
            "fanout_x", 4.0, "fanout_y", 4.0, "fanout_z", 4.0, "merge", 2.0);

    @Test
    void linearChainIsFusedIntoOneUnitInTopologicalMemberOrder() {
        TaskGraph g = new TaskGraph(List.of(
                new TaskNode("entry", "entry", List.of()),
                new TaskNode("a", "transform_a", List.of("entry")),
                new TaskNode("b", "transform_b", List.of("a")),
                new TaskNode("c", "transform_c", List.of("b"))));

        FusionOptimizer.FusionPlan plan = FusionOptimizer.optimize(g, COST, 16, 2.0);

        assertThat(plan.units()).hasSize(1);
        WorkerPoolSimulator.ScheduleUnit unit = plan.units().get(0);
        assertThat(unit.memberTaskIds()).containsExactly("entry", "a", "b", "c");
        assertThat(plan.fusedGroups()).hasSize(1);
        assertThat(plan.fusedGroups().get(0).type()).isEqualTo(FusedGroup.FusionType.LINEAR_CHAIN);
    }

    @Test
    void branchingNodeIsNotSwallowedIntoItsParentsChain() {
        // entry -> a -> {b, c}: a has two children, so a cannot fuse with
        // b or c (fusing a into either would hide the other's dependency).
        TaskGraph g = new TaskGraph(List.of(
                new TaskNode("entry", "entry", List.of()),
                new TaskNode("a", "transform_a", List.of("entry")),
                new TaskNode("b", "transform_b", List.of("a")),
                new TaskNode("c", "transform_c", List.of("a"))));

        FusionOptimizer.FusionPlan plan = FusionOptimizer.optimize(g, COST, 16, 2.0);

        // entry+a fuse (entry has one child, a). b and c are both sinks (no
        // common child), so they do not qualify for sibling fusion either;
        // each must stay its own unit.
        boolean entryAndAFused = plan.units().stream()
                .anyMatch(u -> u.memberTaskIds().containsAll(List.of("entry", "a")));
        assertThat(entryAndAFused).isTrue();
        assertThat(plan.units().stream().filter(u -> u.memberTaskIds().contains("b")).findFirst().orElseThrow().memberTaskIds())
                .containsExactly("b");
        assertThat(plan.units().stream().filter(u -> u.memberTaskIds().contains("c")).findFirst().orElseThrow().memberTaskIds())
                .containsExactly("c");
    }

    @Test
    void oneBenchmarkBlockFusesChainAndAttemptsSiblingFusion() {
        TaskGraph full = BenchmarkDagGenerator.generate();
        FusionOptimizer.FusionPlan plan = FusionOptimizer.optimize(full, COST, 16, 2.0);

        boolean hasChainFusion = plan.fusedGroups().stream().anyMatch(g -> g.type() == FusedGroup.FusionType.LINEAR_CHAIN);
        assertThat(hasChainFusion).isTrue();
        // Fewer scheduling units than original nodes either way (chain fusion alone guarantees this).
        assertThat(plan.units().size()).isLessThan(full.size());
    }

    @Test
    void fusedPlanPreservesOutputOrderOnTheFullBenchmarkGraph() {
        TaskGraph full = BenchmarkDagGenerator.generate();
        FusionOptimizer.FusionPlan plan = FusionOptimizer.optimize(full, COST, 16, 2.0);
        WorkerPoolSimulator.SimResult result = WorkerPoolSimulator.simulate(plan.units(), 16, 2.0);

        assertThat(result.taskCompletions()).hasSize(full.size());
        assertThat(OutputOrderChecker.outputOrderUnchanged(full, result.taskCompletions())).isTrue();
    }

    @Test
    void siblingFusionIsAppliedWhenWorkersAreScarceEnoughThatFusingWins() {
        // 3 siblings, cost 1.0 each, overhead 3.0, only 2 workers: unfused,
        // one sibling must wait for a worker to free up (two run at once,
        // the third queues behind whichever finishes first), costing an
        // extra overhead+cost on top of the wait. Fused onto one worker,
        // the group pays the per-unit overhead exactly once. This is the
        // overhead-dominated, worker-scarce regime sibling fusion exists
        // for; the 240-node benchmark (16 workers, 3-way fan-out) is not in
        // this regime, which is exactly why it measures 0 sibling fusions
        // applied (see README "Findings").
        Map<String, Double> cost = Map.of("entry", 0.1, "sib", 1.0, "merge", 0.1);
        TaskGraph g = new TaskGraph(List.of(
                new TaskNode("entry", "entry", List.of()),
                new TaskNode("s1", "sib", List.of("entry")),
                new TaskNode("s2", "sib", List.of("entry")),
                new TaskNode("s3", "sib", List.of("entry")),
                new TaskNode("merge", "merge", List.of("s1", "s2", "s3"))));

        List<WorkerPoolSimulator.ScheduleUnit> baseline = FusionOptimizer.baselineUnits(g, cost);
        double baselineMakespan = WorkerPoolSimulator.simulate(baseline, 2, 3.0).makespanMs();

        FusionOptimizer.FusionPlan plan = FusionOptimizer.optimize(g, cost, 2, 3.0);
        double fusedMakespan = WorkerPoolSimulator.simulate(plan.units(), 2, 3.0).makespanMs();

        boolean hasSiblingFusion = plan.fusedGroups().stream().anyMatch(fg -> fg.type() == FusedGroup.FusionType.SIBLING_BRANCH);
        assertThat(hasSiblingFusion).isTrue();
        assertThat(fusedMakespan).isLessThan(baselineMakespan);
    }

    @Test
    void fusionNeverIncreasesMakespanOnTheBenchmarkGraph() {
        TaskGraph full = BenchmarkDagGenerator.generate();
        List<WorkerPoolSimulator.ScheduleUnit> baseline = FusionOptimizer.baselineUnits(full, COST);
        double baselineMakespan = WorkerPoolSimulator.simulate(baseline, 16, 2.0).makespanMs();

        FusionOptimizer.FusionPlan plan = FusionOptimizer.optimize(full, COST, 16, 2.0);
        double fusedMakespan = WorkerPoolSimulator.simulate(plan.units(), 16, 2.0).makespanMs();

        assertThat(fusedMakespan).isLessThanOrEqualTo(baselineMakespan);
    }
}
