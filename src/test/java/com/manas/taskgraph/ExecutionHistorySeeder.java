package com.manas.taskgraph;

import com.manas.taskgraph.persistence.ExecutionRecord;
import com.manas.taskgraph.persistence.ExecutionRecordRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates synthetic, seeded-RNG "recorded execution" rows standing in for
 * what a real worker fleet would have reported after running each task type
 * many times. There is no real production telemetry behind these numbers;
 * that is stated here and in the README rather than implied.
 */
public final class ExecutionHistorySeeder {
    private ExecutionHistorySeeder() {}

    public static void seed(ExecutionRecordRepository repo, String taskType, int count, long seed, double meanMs, double stddevMs) {
        Random rng = new Random(seed);
        List<ExecutionRecord> rows = new ArrayList<>(count);
        Instant now = Instant.now();
        for (int i = 0; i < count; i++) {
            double sample = meanMs + rng.nextGaussian() * stddevMs;
            long durationMs = Math.max(1, Math.round(sample));
            rows.add(new ExecutionRecord(taskType, durationMs, now.minusSeconds(count - i)));
        }
        repo.saveAll(rows);
    }
}
