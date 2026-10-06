package com.manas.taskgraph.api;

import java.util.List;

/** The "owners who do not read code" view: what got fused, and the savings it bought. */
public record PlanResponse(
        String graphId,
        int nodeCount,
        int baselineUnitCount,
        int fusedUnitCount,
        double baselineMakespanMs,
        double fusedMakespanMs,
        double savingsPercent,
        boolean outputOrderUnchanged,
        List<FusedGroupDto> fusedGroups) {}
