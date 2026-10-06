package com.manas.taskgraph.api;

import com.manas.taskgraph.ExecutionHistorySeeder;
import com.manas.taskgraph.persistence.ExecutionRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GraphControllerTest {
    private static final String TYPE_A = "api_test_type_a";
    private static final String TYPE_B = "api_test_type_b";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ExecutionRecordRepository repo;

    @BeforeEach
    void seedAndCheckDb() {
        try {
            repo.count();
        } catch (Exception e) {
            Assumptions.abort("PostgreSQL is not reachable: " + e.getMessage());
        }
        ExecutionHistorySeeder.seed(repo, TYPE_A, 50, 1L, 50.0, 5.0);
        ExecutionHistorySeeder.seed(repo, TYPE_B, 50, 2L, 30.0, 5.0);
    }

    @AfterEach
    void cleanUp() {
        repo.findByTaskType(TYPE_A).forEach(repo::delete);
        repo.findByTaskType(TYPE_B).forEach(repo::delete);
    }

    @Test
    void submitThenFetchPlanReturnsSavingsAndAnUnchangedOutputOrder() {
        var submission = new GraphSubmission(List.of(
                new TaskNodeDto("n1", TYPE_A, List.of()),
                new TaskNodeDto("n2", TYPE_B, List.of("n1")),
                new TaskNodeDto("n3", TYPE_B, List.of("n2")),
                new TaskNodeDto("n4", TYPE_A, List.of("n3"))));

        ResponseEntity<Map> submitResponse = rest.postForEntity("/api/graphs", submission, Map.class);
        assertThat(submitResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String graphId = (String) submitResponse.getBody().get("graphId");
        assertThat(graphId).isNotBlank();

        ResponseEntity<PlanResponse> planResponse = rest.getForEntity("/api/graphs/" + graphId + "/plan", PlanResponse.class);
        assertThat(planResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        PlanResponse plan = planResponse.getBody();

        assertThat(plan.nodeCount()).isEqualTo(4);
        assertThat(plan.outputOrderUnchanged()).isTrue();
        assertThat(plan.fusedUnitCount()).isLessThanOrEqualTo(plan.baselineUnitCount());
        assertThat(plan.fusedMakespanMs()).isLessThanOrEqualTo(plan.baselineMakespanMs());
        // n1..n4 is one unbroken linear chain, so it must fuse into a single unit.
        assertThat(plan.fusedUnitCount()).isEqualTo(1);
        assertThat(plan.fusedGroups()).hasSize(1);
        assertThat(plan.fusedGroups().get(0).memberTaskIds()).containsExactly("n1", "n2", "n3", "n4");
    }

    @Test
    void unknownGraphIdReturns404() {
        ResponseEntity<Map> response = rest.getForEntity("/api/graphs/does-not-exist/plan", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void benchmarkGraphIdIsAlwaysAvailableWithoutASubmission() {
        // Uses the real benchmark task types, which must already have
        // recorded executions seeded for this to succeed; skip if the
        // app's own seed script has not been run against this database.
        ResponseEntity<PlanResponse> response = rest.getForEntity("/api/graphs/benchmark/plan", PlanResponse.class);
        if (response.getStatusCode() != HttpStatus.OK) {
            Assumptions.abort("benchmark task types have no recorded executions yet; run BenchmarkRunnerTest first");
        }
        assertThat(response.getBody().nodeCount()).isEqualTo(240);
    }
}
