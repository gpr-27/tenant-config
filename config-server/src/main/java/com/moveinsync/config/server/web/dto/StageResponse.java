package com.moveinsync.config.server.web.dto;

import java.util.List;

public record StageResponse(long version, List<String> offices) {
}
