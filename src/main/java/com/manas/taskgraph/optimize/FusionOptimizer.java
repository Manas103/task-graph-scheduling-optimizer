package com.manas.taskgraph.optimize;

import com.manas.taskgraph.graph.GraphValidator;
import com.manas.taskgraph.model.TaskGraph;
import com.manas.taskgraph.model.TaskNode;
import com.manas.taskgraph.sim.WorkerPoolSimulator;
import com.manas.taskgraph.sim.WorkerPoolSimulator.ScheduleUnit;

import java.util.*;

/**
 * Turns a task graph into scheduling units, applying two fusion patterns:
 *
 * <ul>
 *   <li><b>Linear-chain fusion</b> is always applied. A maximal run of nodes
 *   where each node has exactly one dependency and that dependency has
 *   exactly one child cannot lose any parallelism by being fused (there was
 *   never a second thing it could have run next to), so fusing it only
 *   removes per-unit dispatch overhead and never increases makespan in this
 *   cost model.</li>
 *   <li><b>Sibling-branch fusion</b> is conditional. Fusing a group of
 *   sibling tasks (same single parent, same single child, no edges between
 *   them) onto one worker removes overhead but can also remove the
 *   parallelism those siblings would have had across separate workers, so
 *   each candidate group is accepted only if simulating the plan with it
 *   fused does not increase the overall makespan versus the plan without
 *   it (see "Design before comparing" in the project playbook: compare,
 *   then keep the fusion only if it ties or wins).</li>
 * </ul>
 */
public final class FusionOptimizer {
    private FusionOptimizer() {}

    public record FusionPlan(List<ScheduleUnit> units, List<FusedGroup> fusedGroups) {}

    public static List<ScheduleUnit> baselineUnits(TaskGraph g, Map<String, Double> costByType) {
        List<ScheduleUnit> units = new ArrayList<>();
        for (TaskNode n : g.nodes()) {
            double cost = requireCost(costByType, n.taskType());
            units.add(new ScheduleUnit(n.id(), List.of(n.id()), List.of(cost), List.copyOf(n.dependsOn())));
        }
        return units;
    }

    public static FusionPlan optimize(TaskGraph g, Map<String, Double> costByType, int workerCount, double overheadMs) {
        ChainFusion chain = fuseLinearChains(g, costByType);
        return fuseSiblingBranches(g, chain, workerCount, overheadMs);
    }

    // ---- Step 1: linear-chain fusion ----------------------------------

    private record ChainFusion(Map<String, ScheduleUnit> units, List<FusedGroup> chainGroups) {}

    private static ChainFusion fuseLinearChains(TaskGraph g, Map<String, Double> costByType) {
        List<String> topo = GraphValidator.validate(g).topoOrder();
        Map<String, String> taskToGroup = new HashMap<>();
        Map<String, List<String>> groupMembers = new LinkedHashMap<>();

        for (String id : topo) {
            TaskNode n = g.get(id);
            String parent = n.dependsOn().size() == 1 ? n.dependsOn().get(0) : null;
            boolean extendsChain = parent != null && g.childrenOf(parent).size() == 1;
            if (extendsChain) {
                String groupId = taskToGroup.get(parent);
                taskToGroup.put(id, groupId);
                groupMembers.get(groupId).add(id);
            } else {
                String groupId = "unit_" + id;
                groupMembers.put(groupId, new ArrayList<>(List.of(id)));
                taskToGroup.put(id, groupId);
            }
        }

        Map<String, ScheduleUnit> units = new LinkedHashMap<>();
        List<FusedGroup> chainGroups = new ArrayList<>();
        for (var e : groupMembers.entrySet()) {
            String groupId = e.getKey();
            List<String> members = e.getValue();
            List<Double> costs = new ArrayList<>();
            Set<String> externalDeps = new LinkedHashSet<>();
            for (String m : members) {
                costs.add(requireCost(costByType, g.get(m).taskType()));
                for (String dep : g.get(m).dependsOn()) {
                    if (!members.contains(dep)) externalDeps.add(taskToGroup.get(dep));
                }
            }
            units.put(groupId, new ScheduleUnit(groupId, List.copyOf(members), List.copyOf(costs), List.copyOf(externalDeps)));
            if (members.size() > 1) {
                chainGroups.add(new FusedGroup(groupId, FusedGroup.FusionType.LINEAR_CHAIN, List.copyOf(members)));
            }
        }
        return new ChainFusion(units, chainGroups);
    }

    // ---- Step 2: sibling-branch fusion, simulate and keep ties-or-better ----

    private static FusionPlan fuseSiblingBranches(TaskGraph g, ChainFusion chain, int workerCount, double overheadMs) {
        Map<String, ScheduleUnit> working = new LinkedHashMap<>(chain.units());
        List<FusedGroup> applied = new ArrayList<>(chain.chainGroups());

        Map<String, List<String>> candidateKeyToUnits = groupSiblingCandidates(working);
        double currentMakespan = WorkerPoolSimulator.simulate(toList(working), workerCount, overheadMs).makespanMs();

        List<String> keysInOrder = new ArrayList<>(candidateKeyToUnits.keySet());
        Collections.sort(keysInOrder);

        for (String key : keysInOrder) {
            List<String> siblingUnitIds = candidateKeyToUnits.get(key);
            if (siblingUnitIds.size() < 2) continue;

            Map<String, ScheduleUnit> trial = new LinkedHashMap<>(working);
            String fusedId = "sib_" + siblingUnitIds.stream().min(String::compareTo).orElseThrow();
            ScheduleUnit fused = mergeSiblingUnits(fusedId, siblingUnitIds, trial);
            String childUnitId = findCommonChild(siblingUnitIds, working);

            for (String sid : siblingUnitIds) trial.remove(sid);
            trial.put(fusedId, fused);
            if (childUnitId != null) {
                ScheduleUnit childUnit = trial.get(childUnitId);
                List<String> newDeps = replaceAll(childUnit.dependsOnUnitIds(), siblingUnitIds, fusedId);
                trial.put(childUnitId, new ScheduleUnit(childUnit.unitId(), childUnit.memberTaskIds(),
                        childUnit.memberCostsMs(), newDeps));
            }

            double trialMakespan = WorkerPoolSimulator.simulate(toList(trial), workerCount, overheadMs).makespanMs();
            if (trialMakespan <= currentMakespan) {
                working = trial;
                currentMakespan = trialMakespan;
                List<String> flatMembers = new ArrayList<>();
                for (String sid : siblingUnitIds) flatMembers.addAll(unitMembersBeforeFusion(sid, chain.units(), working));
                applied.add(new FusedGroup(fusedId, FusedGroup.FusionType.SIBLING_BRANCH, flatMembers));
            }
        }

        return new FusionPlan(toList(working), List.copyOf(applied));
    }

    /** Groups chain-fused units by (single parent unit, single child unit) so each value list is a sibling-fusion candidate. */
    private static Map<String, List<String>> groupSiblingCandidates(Map<String, ScheduleUnit> units) {
        Map<String, List<String>> unitChildren = new HashMap<>();
        for (ScheduleUnit u : units.values()) {
            for (String dep : u.dependsOnUnitIds()) unitChildren.computeIfAbsent(dep, k -> new ArrayList<>()).add(u.unitId());
        }
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        for (ScheduleUnit u : units.values()) {
            if (u.dependsOnUnitIds().size() != 1) continue;
            List<String> children = unitChildren.get(u.unitId());
            if (children == null || children.size() != 1) continue;
            String childUnitId = children.get(0);
            String key = u.dependsOnUnitIds().get(0) + "->" + childUnitId;
            candidates.computeIfAbsent(key, k -> new ArrayList<>()).add(u.unitId());
        }
        return candidates;
    }

    private static ScheduleUnit mergeSiblingUnits(String fusedId, List<String> siblingUnitIds, Map<String, ScheduleUnit> units) {
        List<String> sorted = new ArrayList<>(siblingUnitIds);
        Collections.sort(sorted);
        List<String> members = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        String parent = units.get(sorted.get(0)).dependsOnUnitIds().get(0);
        for (String sid : sorted) {
            ScheduleUnit u = units.get(sid);
            members.addAll(u.memberTaskIds());
            costs.addAll(u.memberCostsMs());
        }
        return new ScheduleUnit(fusedId, List.copyOf(members), List.copyOf(costs), List.of(parent));
    }

    private static String findCommonChild(List<String> siblingUnitIds, Map<String, ScheduleUnit> units) {
        Map<String, List<String>> unitChildren = new HashMap<>();
        for (ScheduleUnit u : units.values()) {
            for (String dep : u.dependsOnUnitIds()) unitChildren.computeIfAbsent(dep, k -> new ArrayList<>()).add(u.unitId());
        }
        String first = siblingUnitIds.get(0);
        List<String> children = unitChildren.get(first);
        return (children != null && children.size() == 1) ? children.get(0) : null;
    }

    private static List<String> unitMembersBeforeFusion(String unitId, Map<String, ScheduleUnit> chainUnits, Map<String, ScheduleUnit> working) {
        ScheduleUnit u = chainUnits.get(unitId);
        return u != null ? u.memberTaskIds() : working.get(unitId).memberTaskIds();
    }

    private static List<String> replaceAll(List<String> original, List<String> toReplace, String replacement) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : original) out.add(toReplace.contains(s) ? replacement : s);
        return List.copyOf(out);
    }

    private static List<ScheduleUnit> toList(Map<String, ScheduleUnit> units) {
        return new ArrayList<>(units.values());
    }

    private static double requireCost(Map<String, Double> costByType, String taskType) {
        Double c = costByType.get(taskType);
        if (c == null) throw new IllegalStateException("no cost known for task type " + taskType);
        return c;
    }
}
