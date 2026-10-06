package com.manas.taskgraph.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One historical observation of how long a task of a given type actually
 * took, as if emitted by a real worker after it finished a task. The cost
 * percentiles the optimizer plans against come from aggregating rows of
 * this table in PostgreSQL, not from a config file.
 */
@Entity
@Table(name = "task_executions", indexes = @Index(name = "idx_task_type", columnList = "taskType"))
public class ExecutionRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String taskType;

    @Column(nullable = false)
    private long durationMs;

    @Column(nullable = false)
    private Instant recordedAt;

    protected ExecutionRecord() {}

    public ExecutionRecord(String taskType, long durationMs, Instant recordedAt) {
        this.taskType = taskType;
        this.durationMs = durationMs;
        this.recordedAt = recordedAt;
    }

    public Long getId() { return id; }
    public String getTaskType() { return taskType; }
    public long getDurationMs() { return durationMs; }
    public Instant getRecordedAt() { return recordedAt; }
}
