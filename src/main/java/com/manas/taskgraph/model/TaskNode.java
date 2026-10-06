package com.manas.taskgraph.model;

import java.util.List;

/**
 * One node of a task-graph DAG. {@code taskType} names the recorded
 * execution-history bucket this node's cost comes from (many nodes can
 * share a type, the way "parse_chunk" or "merge_partition" would repeat
 * across a real pipeline); {@code id} is unique per node.
 */
public record TaskNode(String id, String taskType, List<String> dependsOn) {
    public TaskNode {
        dependsOn = List.copyOf(dependsOn);
    }
}
