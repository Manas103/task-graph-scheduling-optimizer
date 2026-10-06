package com.manas.taskgraph.cost;

import com.manas.taskgraph.ExecutionHistorySeeder;
import com.manas.taskgraph.persistence.ExecutionRecordRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
class CostPercentileServiceTest {
    private static final String TYPE = "test_percentile_type";

    @Autowired
    private CostPercentileService service;
    @Autowired
    private ExecutionRecordRepository repo;

    @BeforeEach
    void checkDatabaseReachable() {
        try {
            repo.count();
        } catch (Exception e) {
            Assumptions.abort("PostgreSQL is not reachable at the configured datasource URL: " + e.getMessage());
        }
    }

    @AfterEach
    @Transactional
    void cleanUp() {
        repo.findByTaskType(TYPE).forEach(repo::delete);
    }

    @Test
    void percentileFromPostgresMatchesTheIndependentJavaReferenceImplementation() {
        // 1000 samples, deterministic seed, so this test is reproducible
        // without depending on any pre-existing data in the database.
        ExecutionHistorySeeder.seed(repo, TYPE, 1000, 42L, 100.0, 15.0);

        List<Long> raw = service.rawDurations(TYPE);
        assertThat(raw).hasSize(1000);

        CostPercentileService.Percentiles fromDb = service.percentilesFor(TYPE);

        double refP50 = CostPercentileService.referencePercentile(raw, 0.50);
        double refP90 = CostPercentileService.referencePercentile(raw, 0.90);
        double refP99 = CostPercentileService.referencePercentile(raw, 0.99);

        // percentile_cont and the Java reference use the exact same
        // linear-interpolation definition, so they must agree exactly, not
        // just approximately.
        assertThat(fromDb.p50()).isCloseTo(refP50, within(1e-9));
        assertThat(fromDb.p90()).isCloseTo(refP90, within(1e-9));
        assertThat(fromDb.p99()).isCloseTo(refP99, within(1e-9));
        assertThat(fromDb.sampleCount()).isEqualTo(1000);
    }

    @Test
    void missingTaskTypeFailsLoudlyInsteadOfReturningAZeroCost() {
        assertThatMissingTypeThrows();
    }

    private void assertThatMissingTypeThrows() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.percentilesFor("no_such_task_type_recorded_anywhere"));
    }
}
