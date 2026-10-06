package com.manas.taskgraph.api;

import java.util.List;

public record TaskNodeDto(String id, String taskType, List<String> dependsOn) {}
