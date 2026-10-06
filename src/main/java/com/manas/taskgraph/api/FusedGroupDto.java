package com.manas.taskgraph.api;

import java.util.List;

public record FusedGroupDto(String groupId, String type, List<String> memberTaskIds) {}
