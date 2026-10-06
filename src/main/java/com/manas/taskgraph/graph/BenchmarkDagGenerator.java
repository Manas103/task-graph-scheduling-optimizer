package com.manas.taskgraph.graph;

import com.manas.taskgraph.model.TaskGraph;
import com.manas.taskgraph.model.TaskNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the 240-node benchmark pipeline: 30 repetitions of an 8-node block
 * chained end to end. Each block is deliberately shaped to contain both
 * fusion patterns this project claims to handle in one place:
 *
 * <pre>
 *   entry -> chainA -> chainB -> chainC -+-> fanoutX -+
 *                                        +-> fanoutY -+-> merge -> (next block's entry)
 *                                        +-> fanoutZ -+
 * </pre>
 *
 * entry..chainC is a 4-node linear chain (one predecessor, one successor,
 * all the way down); fanoutX/Y/Z share the single parent chainC and the
 * single child merge with no edges between them, which is exactly the
 * sibling-branch fusion shape. 30 blocks x 8 nodes = 240 nodes.
 */
public final class BenchmarkDagGenerator {
    public static final int NODES_PER_BLOCK = 8;
    public static final int BLOCK_COUNT = 30;
    public static final int TOTAL_NODES = NODES_PER_BLOCK * BLOCK_COUNT;

    private BenchmarkDagGenerator() {}

    public static TaskGraph generate() {
        List<TaskNode> nodes = new ArrayList<>();
        String prevMerge = null;
        for (int b = 0; b < BLOCK_COUNT; b++) {
            String entry = "b" + b + "_entry";
            String chainA = "b" + b + "_chainA";
            String chainB = "b" + b + "_chainB";
            String chainC = "b" + b + "_chainC";
            String fanX = "b" + b + "_fanoutX";
            String fanY = "b" + b + "_fanoutY";
            String fanZ = "b" + b + "_fanoutZ";
            String merge = "b" + b + "_merge";

            nodes.add(new TaskNode(entry, "entry", prevMerge == null ? List.of() : List.of(prevMerge)));
            nodes.add(new TaskNode(chainA, "transform_a", List.of(entry)));
            nodes.add(new TaskNode(chainB, "transform_b", List.of(chainA)));
            nodes.add(new TaskNode(chainC, "transform_c", List.of(chainB)));
            nodes.add(new TaskNode(fanX, "fanout_x", List.of(chainC)));
            nodes.add(new TaskNode(fanY, "fanout_y", List.of(chainC)));
            nodes.add(new TaskNode(fanZ, "fanout_z", List.of(chainC)));
            nodes.add(new TaskNode(merge, "merge", List.of(fanX, fanY, fanZ)));

            prevMerge = merge;
        }
        return new TaskGraph(nodes);
    }
}
