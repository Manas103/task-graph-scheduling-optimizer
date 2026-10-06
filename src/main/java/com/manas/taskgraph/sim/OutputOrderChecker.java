package com.manas.taskgraph.sim;

import com.manas.taskgraph.graph.GraphValidator;
import com.manas.taskgraph.model.TaskGraph;
import com.manas.taskgraph.sim.WorkerPoolSimulator.TaskCompletion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Checks the actual claim, not a proxy for it: whichever order tasks finish
 * in (and so whichever order a downstream consumer observes their outputs
 * in) must still be a valid topological order of the <em>original,
 * unfused</em> graph's dependencies. Fusing a,b,c into one scheduled unit is
 * invisible to this check as long as a still finishes before anything that
 * depends on a, and so on; a fusion that let a dependent finish before its
 * dependency would fail here even though the simulator "ran" without error.
 */
public final class OutputOrderChecker {
    private OutputOrderChecker() {}

    public static boolean outputOrderUnchanged(TaskGraph original, List<TaskCompletion> completions) {
        List<TaskCompletion> sorted = new ArrayList<>(completions);
        sorted.sort(Comparator.comparingDouble(TaskCompletion::finishTimeMs).thenComparing(TaskCompletion::taskId));
        List<String> order = sorted.stream().map(TaskCompletion::taskId).toList();
        return GraphValidator.isConsistentTopoOrder(original, order);
    }
}
