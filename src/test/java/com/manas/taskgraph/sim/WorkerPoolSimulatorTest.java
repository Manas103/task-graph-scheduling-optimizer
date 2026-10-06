package com.manas.taskgraph.sim;

import com.manas.taskgraph.sim.WorkerPoolSimulator.ScheduleUnit;
import com.manas.taskgraph.sim.WorkerPoolSimulator.SimResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerPoolSimulatorTest {

    @Test
    void threeIndependentUnitsOnTwoWorkersPacksTheSmallestTwoTogether() {
        // Three independent units with no overhead: costs 5, 3, 2 on 2
        // workers. By hand: put the two smallest (3, 2) on one worker
        // (finishes at 5) and the biggest (5) alone on the other (finishes
        // at 5); makespan is 5 either way the greedy packs them, since the
        // rank (= own cost here, no dependents) sorts 5 first, then 3, then 2.
        List<ScheduleUnit> units = List.of(
                new ScheduleUnit("x", List.of("x"), List.of(5.0), List.of()),
                new ScheduleUnit("y", List.of("y"), List.of(3.0), List.of()),
                new ScheduleUnit("z", List.of("z"), List.of(2.0), List.of()));

        SimResult r = WorkerPoolSimulator.simulate(units, 2, 0.0);
        assertThat(r.makespanMs()).isEqualTo(5.0);
    }

    @Test
    void linearChainMakespanIsExactlyTheSumRegardlessOfWorkerCount() {
        // a -> b -> c, costs 2, 3, 4: no matter how many workers are
        // available, a dependency chain cannot run faster than the sum of
        // its own costs plus the overhead paid per unit.
        List<ScheduleUnit> units = List.of(
                new ScheduleUnit("a", List.of("a"), List.of(2.0), List.of()),
                new ScheduleUnit("b", List.of("b"), List.of(3.0), List.of("a")),
                new ScheduleUnit("c", List.of("c"), List.of(4.0), List.of("b")));

        SimResult r = WorkerPoolSimulator.simulate(units, 16, 1.0);
        assertThat(r.makespanMs()).isEqualTo(2.0 + 1.0 + 3.0 + 1.0 + 4.0 + 1.0);
    }

    @Test
    void fusingTheChainIntoOneUnitRemovesTwoOfThreeOverheadPaymentsButNotTheWork() {
        List<ScheduleUnit> fused = List.of(
                new ScheduleUnit("abc", List.of("a", "b", "c"), List.of(2.0, 3.0, 4.0), List.of()));

        SimResult r = WorkerPoolSimulator.simulate(fused, 16, 1.0);
        assertThat(r.makespanMs()).isEqualTo(1.0 + 2.0 + 3.0 + 4.0); // one overhead payment, not three
        assertThat(r.taskCompletions()).hasSize(3);
        // Internal member order is preserved: a finishes before b finishes before c.
        var byTask = r.taskCompletions().stream()
                .collect(java.util.stream.Collectors.toMap(WorkerPoolSimulator.TaskCompletion::taskId, WorkerPoolSimulator.TaskCompletion::finishTimeMs));
        assertThat(byTask.get("a")).isLessThan(byTask.get("b"));
        assertThat(byTask.get("b")).isLessThan(byTask.get("c"));
    }

    @Test
    void referenceBruteForceAgreesWithGreedyOnAnAllIndependentSmallCase() {
        // For units with no dependencies at all, the optimal makespan is a
        // classic multiprocessor scheduling bound: brute force every
        // assignment of units to workers for a small case and confirm the
        // greedy simulator finds the same optimum (it will not in general
        // for larger or dependency-heavy cases, since the greedy heuristic
        // is not an exact solver, but it must on an instance this small).
        List<ScheduleUnit> units = List.of(
                new ScheduleUnit("u1", List.of("u1"), List.of(4.0), List.of()),
                new ScheduleUnit("u2", List.of("u2"), List.of(4.0), List.of()),
                new ScheduleUnit("u3", List.of("u3"), List.of(4.0), List.of()),
                new ScheduleUnit("u4", List.of("u4"), List.of(4.0), List.of()));
        int workers = 2;

        double optimalByBruteForce = bruteForceMinimalMakespan(units, workers);
        double greedy = WorkerPoolSimulator.simulate(units, workers, 0.0).makespanMs();

        assertThat(greedy).isEqualTo(optimalByBruteForce);
        assertThat(optimalByBruteForce).isEqualTo(8.0); // 2 units of cost 4 per worker
    }

    /** Deliberately naive: tries every assignment of units to workers (independent units only). */
    private static double bruteForceMinimalMakespan(List<ScheduleUnit> units, int workers) {
        double best = Double.MAX_VALUE;
        int n = units.size();
        int[] assignment = new int[n];
        best = Math.min(best, recurse(units, workers, assignment, 0));
        return best;
    }

    private static double recurse(List<ScheduleUnit> units, int workers, int[] assignment, int idx) {
        if (idx == units.size()) {
            double[] load = new double[workers];
            for (int i = 0; i < units.size(); i++) load[assignment[i]] += units.get(i).totalMemberCostMs();
            double max = 0;
            for (double l : load) max = Math.max(max, l);
            return max;
        }
        double best = Double.MAX_VALUE;
        for (int w = 0; w < workers; w++) {
            assignment[idx] = w;
            best = Math.min(best, recurse(units, workers, assignment, idx + 1));
        }
        return best;
    }
}
