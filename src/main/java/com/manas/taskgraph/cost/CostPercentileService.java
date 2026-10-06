package com.manas.taskgraph.cost;

import com.manas.taskgraph.persistence.ExecutionRecord;
import com.manas.taskgraph.persistence.ExecutionRecordRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Per-task-type cost percentiles computed from recorded executions in
 * PostgreSQL via {@code percentile_cont}, the database's own continuous
 * (linear-interpolation) percentile. {@link #referencePercentile} is an
 * independently written, deliberately naive Java implementation of the same
 * definition, used only to check the SQL result in tests; nothing in the
 * optimizer calls it.
 */
@Service
public class CostPercentileService {
    private final ExecutionRecordRepository repo;

    public CostPercentileService(ExecutionRecordRepository repo) {
        this.repo = repo;
    }

    public record Percentiles(String taskType, long sampleCount, double p50, double p90, double p99) {}

    public Percentiles percentilesFor(String taskType) {
        long n = repo.countByTaskType(taskType);
        if (n == 0) {
            throw new IllegalStateException("no recorded executions for task type " + taskType);
        }
        double p50 = repo.percentile(taskType, 0.50);
        double p90 = repo.percentile(taskType, 0.90);
        double p99 = repo.percentile(taskType, 0.99);
        return new Percentiles(taskType, n, p50, p90, p99);
    }

    public Map<String, Percentiles> percentilesForAll(Iterable<String> taskTypes) {
        Map<String, Percentiles> out = new java.util.LinkedHashMap<>();
        for (String t : taskTypes) out.put(t, percentilesFor(t));
        return out;
    }

    /**
     * Independent reference implementation of PostgreSQL's
     * {@code percentile_cont}: sort, then linearly interpolate between the
     * two bracketing order statistics at rank {@code p * (n - 1)}. This is
     * the exact definition percentile_cont uses; a nearest-rank percentile
     * (round instead of interpolate) looks right but disagrees with it on
     * most sample sizes, which is what the first version of this method did
     * (see README "Findings").
     */
    public static double referencePercentile(List<Long> durationsMs, double p) {
        if (durationsMs.isEmpty()) throw new IllegalArgumentException("empty sample");
        List<Long> sorted = new java.util.ArrayList<>(durationsMs);
        sorted.sort(null);
        int n = sorted.size();
        double rank = p * (n - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) return sorted.get(lo);
        double frac = rank - lo;
        return sorted.get(lo) + frac * (sorted.get(hi) - sorted.get(lo));
    }

    public List<Long> rawDurations(String taskType) {
        return repo.findByTaskType(taskType).stream().map(ExecutionRecord::getDurationMs).toList();
    }
}
