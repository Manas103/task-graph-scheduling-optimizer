package com.manas.taskgraph.graph;

import com.manas.taskgraph.model.TaskGraph;
import com.manas.taskgraph.model.TaskNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphValidatorTest {

    @Test
    void acyclicDiamondProducesConsistentTopoOrder() {
        TaskGraph g = new TaskGraph(List.of(
                new TaskNode("a", "t", List.of()),
                new TaskNode("b", "t", List.of("a")),
                new TaskNode("c", "t", List.of("a")),
                new TaskNode("d", "t", List.of("b", "c"))));

        GraphValidator.Result r = GraphValidator.validate(g);
        assertThat(r.isAcyclic()).isTrue();
        assertThat(r.topoOrder()).hasSize(4);
        assertThat(GraphValidator.isConsistentTopoOrder(g, r.topoOrder())).isTrue();
    }

    @Test
    void cycleIsDetectedAndReportedExactly() {
        // a -> b -> c -> a: all three nodes are in the cycle, nothing else is.
        TaskGraph g = new TaskGraph(List.of(
                new TaskNode("a", "t", List.of("c")),
                new TaskNode("b", "t", List.of("a")),
                new TaskNode("c", "t", List.of("b"))));

        GraphValidator.Result r = GraphValidator.validate(g);
        assertThat(r.isAcyclic()).isFalse();
        assertThat(r.cycleNodes()).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(r.topoOrder()).isEmpty();
    }

    @Test
    void cycleDetectionIsolatesOnlyTheCyclicPart() {
        // d -> a -> b -> a is a 2-cycle (a,b); d and c are acyclic.
        TaskGraph g = new TaskGraph(List.of(
                new TaskNode("d", "t", List.of()),
                new TaskNode("a", "t", List.of("d", "b")),
                new TaskNode("b", "t", List.of("a")),
                new TaskNode("c", "t", List.of("d"))));

        GraphValidator.Result r = GraphValidator.validate(g);
        assertThat(r.isAcyclic()).isFalse();
        assertThat(r.cycleNodes()).containsExactlyInAnyOrder("a", "b");
        assertThat(r.topoOrder()).containsExactlyInAnyOrder("d", "c");
    }

    @Test
    void inconsistentOrderIsRejected() {
        TaskGraph g = new TaskGraph(List.of(
                new TaskNode("a", "t", List.of()),
                new TaskNode("b", "t", List.of("a"))));
        assertThat(GraphValidator.isConsistentTopoOrder(g, List.of("b", "a"))).isFalse();
        assertThat(GraphValidator.isConsistentTopoOrder(g, List.of("a", "b"))).isTrue();
    }
}
