package com.manas.taskgraph.graph;

import com.manas.taskgraph.model.TaskGraph;

import java.util.*;

/**
 * Cycle detection and topological ordering. Kahn's algorithm gives both at
 * once: if it cannot place every node, whatever is left unplaced is
 * necessarily inside a cycle (its in-degree never reached zero), so the
 * remaining node set doubles as the cycle report without a second pass.
 */
public final class GraphValidator {
    private GraphValidator() {}

    public record Result(List<String> topoOrder, Set<String> cycleNodes) {
        public boolean isAcyclic() { return cycleNodes.isEmpty(); }
    }

    public static Result validate(TaskGraph g) {
        Map<String, Integer> indegree = new HashMap<>();
        for (var n : g.nodes()) indegree.put(n.id(), n.dependsOn().size());

        Deque<String> ready = new ArrayDeque<>();
        for (var e : indegree.entrySet()) if (e.getValue() == 0) ready.add(e.getKey());

        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String child : g.childrenOf(id)) {
                int d = indegree.merge(child, -1, Integer::sum);
                if (d == 0) ready.add(child);
            }
        }

        Set<String> remaining = new HashSet<>();
        for (var e : indegree.entrySet()) if (e.getValue() > 0) remaining.add(e.getKey());
        return new Result(List.copyOf(order), Set.copyOf(remaining));
    }

    /** True iff every edge a->b in the graph has a appearing before b in {@code order}. */
    public static boolean isConsistentTopoOrder(TaskGraph g, List<String> order) {
        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < order.size(); i++) position.put(order.get(i), i);
        if (position.size() != g.size()) return false;
        for (var n : g.nodes()) {
            for (String dep : n.dependsOn()) {
                if (position.get(dep) >= position.get(n.id())) return false;
            }
        }
        return true;
    }
}
