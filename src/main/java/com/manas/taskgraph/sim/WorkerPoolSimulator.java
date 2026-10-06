package com.manas.taskgraph.sim;

import java.util.*;

/**
 * A greedy list scheduler over a fixed worker pool: highest critical-path
 * rank first, assigned to whichever worker becomes free soonest, started no
 * earlier than its own dependencies finish. This is a heuristic, the same
 * family as HEFT without processor heterogeneity, not an optimal scheduler;
 * it is used identically for the baseline (unfused) and fused schedules, so
 * the makespan delta between them isolates what fusion changed and not a
 * change in scheduling policy.
 */
public final class WorkerPoolSimulator {
    private WorkerPoolSimulator() {}

    /**
     * One thing the scheduler places on a worker. For an unfused task this
     * has exactly one member; for a fused group it has every member task,
     * in the exact order they will run back to back on the same worker.
     * {@code overheadMs} is paid once per unit, which is the entire
     * mechanism by which fusion can reduce makespan: a unit of k members
     * pays the per-unit dispatch overhead once instead of k times.
     */
    public record ScheduleUnit(
            String unitId,
            List<String> memberTaskIds,
            List<Double> memberCostsMs,
            List<String> dependsOnUnitIds) {
        public double totalMemberCostMs() {
            double sum = 0;
            for (double c : memberCostsMs) sum += c;
            return sum;
        }
    }

    public record TaskCompletion(String taskId, double finishTimeMs) {}

    public record SimResult(
            double makespanMs,
            Map<String, Double> unitStartMs,
            Map<String, Double> unitFinishMs,
            List<TaskCompletion> taskCompletions) {}

    public static SimResult simulate(List<ScheduleUnit> units, int workerCount, double overheadMs) {
        if (workerCount <= 0) throw new IllegalArgumentException("workerCount must be positive");

        Map<String, ScheduleUnit> byId = new LinkedHashMap<>();
        for (ScheduleUnit u : units) byId.put(u.unitId(), u);

        Map<String, List<String>> unitChildren = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (ScheduleUnit u : units) {
            indegree.put(u.unitId(), u.dependsOnUnitIds().size());
            unitChildren.computeIfAbsent(u.unitId(), k -> new ArrayList<>());
        }
        for (ScheduleUnit u : units) {
            for (String dep : u.dependsOnUnitIds()) {
                unitChildren.computeIfAbsent(dep, k -> new ArrayList<>()).add(u.unitId());
            }
        }

        Map<String, Double> rank = computeUpwardRank(units, byId, unitChildren, overheadMs);

        double[] workerFree = new double[workerCount];
        Map<String, Double> unitStart = new LinkedHashMap<>();
        Map<String, Double> unitFinish = new LinkedHashMap<>();
        Map<String, Integer> remainingIndegree = new HashMap<>(indegree);
        Set<String> scheduled = new HashSet<>();
        List<TaskCompletion> completions = new ArrayList<>();

        int total = units.size();
        while (scheduled.size() < total) {
            String chosen = null;
            double bestRank = Double.NEGATIVE_INFINITY;
            for (ScheduleUnit u : units) {
                if (scheduled.contains(u.unitId())) continue;
                if (remainingIndegree.get(u.unitId()) != 0) continue;
                double r = rank.get(u.unitId());
                if (r > bestRank || (r == bestRank && (chosen == null || u.unitId().compareTo(chosen) < 0))) {
                    bestRank = r;
                    chosen = u.unitId();
                }
            }
            if (chosen == null) {
                throw new IllegalStateException("no ready unit found; the unit graph has a cycle");
            }

            ScheduleUnit u = byId.get(chosen);
            double earliestStart = 0;
            for (String dep : u.dependsOnUnitIds()) earliestStart = Math.max(earliestStart, unitFinish.get(dep));

            int bestWorker = 0;
            for (int w = 1; w < workerCount; w++) if (workerFree[w] < workerFree[bestWorker]) bestWorker = w;

            double start = Math.max(earliestStart, workerFree[bestWorker]);
            double cursor = start + overheadMs;
            for (int i = 0; i < u.memberTaskIds().size(); i++) {
                cursor += u.memberCostsMs().get(i);
                completions.add(new TaskCompletion(u.memberTaskIds().get(i), cursor));
            }
            double finish = cursor;

            workerFree[bestWorker] = finish;
            unitStart.put(chosen, start);
            unitFinish.put(chosen, finish);
            scheduled.add(chosen);
            for (String child : unitChildren.get(chosen)) {
                remainingIndegree.merge(child, -1, Integer::sum);
            }
        }

        double makespan = 0;
        for (double f : unitFinish.values()) makespan = Math.max(makespan, f);
        return new SimResult(makespan, unitStart, unitFinish, completions);
    }

    private static Map<String, Double> computeUpwardRank(
            List<ScheduleUnit> units, Map<String, ScheduleUnit> byId,
            Map<String, List<String>> unitChildren, double overheadMs) {
        Map<String, Double> rank = new HashMap<>();
        List<String> order = reverseTopoOrder(units, byId);
        for (String id : order) {
            ScheduleUnit u = byId.get(id);
            double own = overheadMs + u.totalMemberCostMs();
            double maxChild = 0;
            for (String child : unitChildren.getOrDefault(id, List.of())) {
                maxChild = Math.max(maxChild, rank.getOrDefault(child, 0.0));
            }
            rank.put(id, own + maxChild);
        }
        return rank;
    }

    /** Topological order of the unit graph, children before parents is reversed from a normal topo sort. */
    private static List<String> reverseTopoOrder(List<ScheduleUnit> units, Map<String, ScheduleUnit> byId) {
        Map<String, Integer> outdegree = new HashMap<>();
        Map<String, List<String>> parentsOf = new HashMap<>();
        for (ScheduleUnit u : units) {
            parentsOf.computeIfAbsent(u.unitId(), k -> new ArrayList<>());
            outdegree.putIfAbsent(u.unitId(), 0);
        }
        for (ScheduleUnit u : units) {
            for (String dep : u.dependsOnUnitIds()) {
                outdegree.merge(dep, 1, Integer::sum);
                parentsOf.computeIfAbsent(u.unitId(), k -> new ArrayList<>());
            }
        }
        // Reuse Kahn's algorithm on the reversed edges (sinks first).
        Map<String, Integer> remaining = new HashMap<>(outdegree);
        Deque<String> ready = new ArrayDeque<>();
        for (var e : remaining.entrySet()) if (e.getValue() == 0) ready.add(e.getKey());
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String dep : byId.get(id).dependsOnUnitIds()) {
                int d = remaining.merge(dep, -1, Integer::sum);
                if (d == 0) ready.add(dep);
            }
        }
        return order;
    }
}
