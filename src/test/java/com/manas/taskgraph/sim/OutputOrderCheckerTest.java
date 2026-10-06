package com.manas.taskgraph.sim;

import com.manas.taskgraph.model.TaskGraph;
import com.manas.taskgraph.model.TaskNode;
import com.manas.taskgraph.sim.WorkerPoolSimulator.TaskCompletion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutputOrderCheckerTest {

    private final TaskGraph g = new TaskGraph(List.of(
            new TaskNode("a", "t", List.of()),
            new TaskNode("b", "t", List.of("a")),
            new TaskNode("c", "t", List.of("b"))));

    @Test
    void acceptsCompletionsThatRespectDependencyOrder() {
        List<TaskCompletion> completions = List.of(
                new TaskCompletion("a", 1.0),
                new TaskCompletion("b", 2.0),
                new TaskCompletion("c", 3.0));
        assertThat(OutputOrderChecker.outputOrderUnchanged(g, completions)).isTrue();
    }

    @Test
    void rejectsCompletionsWhereADependentFinishesBeforeItsDependency() {
        // b finishing before a is exactly the bug this check exists to catch:
        // a fusion or scheduling error that reordered a's output behind b's.
        List<TaskCompletion> completions = List.of(
                new TaskCompletion("b", 1.0),
                new TaskCompletion("a", 2.0),
                new TaskCompletion("c", 3.0));
        assertThat(OutputOrderChecker.outputOrderUnchanged(g, completions)).isFalse();
    }

    @Test
    void isIndifferentToTieBreaksAmongIncomparableTasks() {
        TaskGraph diamond = new TaskGraph(List.of(
                new TaskNode("a", "t", List.of()),
                new TaskNode("b", "t", List.of("a")),
                new TaskNode("c", "t", List.of("a")),
                new TaskNode("d", "t", List.of("b", "c"))));
        // b and c are incomparable (neither depends on the other); either
        // finishing first is a valid topological order.
        List<TaskCompletion> cFirst = List.of(
                new TaskCompletion("a", 1.0), new TaskCompletion("c", 2.0),
                new TaskCompletion("b", 3.0), new TaskCompletion("d", 4.0));
        assertThat(OutputOrderChecker.outputOrderUnchanged(diamond, cFirst)).isTrue();
    }
}
