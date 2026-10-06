package com.manas.taskgraph.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExecutionRecordRepository extends JpaRepository<ExecutionRecord, Long> {

    List<ExecutionRecord> findByTaskType(String taskType);

    @Query(value = """
            SELECT percentile_cont(:p) WITHIN GROUP (ORDER BY duration_ms)
            FROM task_executions
            WHERE task_type = :taskType
            """, nativeQuery = true)
    Double percentile(@Param("taskType") String taskType, @Param("p") double p);

    long countByTaskType(String taskType);
}
