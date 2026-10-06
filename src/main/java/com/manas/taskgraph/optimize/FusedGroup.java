package com.manas.taskgraph.optimize;

import java.util.List;

/** One group of original tasks the optimizer decided to run as a single scheduled unit. */
public record FusedGroup(String groupId, FusionType type, List<String> memberTaskIds) {
    public enum FusionType { LINEAR_CHAIN, SIBLING_BRANCH }
}
