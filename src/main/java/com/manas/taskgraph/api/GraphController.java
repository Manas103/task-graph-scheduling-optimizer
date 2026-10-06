package com.manas.taskgraph.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/graphs")
public class GraphController {
    private final GraphOptimizationService service;

    public GraphController(GraphOptimizationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> submit(@RequestBody GraphSubmission submission) {
        String id = service.submit(submission);
        return ResponseEntity.ok(Map.of("graphId", id));
    }

    @GetMapping("/{graphId}/plan")
    public ResponseEntity<PlanResponse> plan(@PathVariable String graphId) {
        return ResponseEntity.ok(service.plan(graphId));
    }
}
