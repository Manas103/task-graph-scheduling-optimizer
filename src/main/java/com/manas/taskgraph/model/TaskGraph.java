package com.manas.taskgraph.model;

import java.util.*;

/**
 * An immutable DAG of {@link TaskNode}s, indexed for the lookups the
 * optimizer and simulator need (children, parents) without recomputing an
 * adjacency map on every call.
 */
public final class TaskGraph {
    private final Map<String, TaskNode> nodes;
    private final Map<String, List<String>> children;

    public TaskGraph(Collection<TaskNode> nodeList) {
        Map<String, TaskNode> byId = new LinkedHashMap<>();
        for (TaskNode n : nodeList) {
            if (byId.put(n.id(), n) != null) {
                throw new IllegalArgumentException("duplicate task id: " + n.id());
            }
        }
        for (TaskNode n : nodeList) {
            for (String dep : n.dependsOn()) {
                if (!byId.containsKey(dep)) {
                    throw new IllegalArgumentException(n.id() + " depends on unknown task " + dep);
                }
            }
        }
        this.nodes = Map.copyOf(byId);

        Map<String, List<String>> kids = new LinkedHashMap<>();
        for (String id : byId.keySet()) kids.put(id, new ArrayList<>());
        for (TaskNode n : nodeList) {
            for (String dep : n.dependsOn()) kids.get(dep).add(n.id());
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        for (var e : kids.entrySet()) frozen.put(e.getKey(), List.copyOf(e.getValue()));
        this.children = Map.copyOf(frozen);
    }

    public Collection<TaskNode> nodes() { return nodes.values(); }
    public TaskNode get(String id) { return nodes.get(id); }
    public int size() { return nodes.size(); }
    public List<String> childrenOf(String id) { return children.getOrDefault(id, List.of()); }
    public List<String> dependsOn(String id) { return nodes.get(id).dependsOn(); }
    public boolean contains(String id) { return nodes.containsKey(id); }

    public List<String> roots() {
        List<String> r = new ArrayList<>();
        for (TaskNode n : nodes.values()) if (n.dependsOn().isEmpty()) r.add(n.id());
        return r;
    }

    public List<String> sinks() {
        List<String> s = new ArrayList<>();
        for (TaskNode n : nodes.values()) if (childrenOf(n.id()).isEmpty()) s.add(n.id());
        return s;
    }
}
