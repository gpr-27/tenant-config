package com.moveinsync.config.core.definition;

import java.time.LocalTime;

// one value, so start and end can never be read from two different versions
public record TimeWindow(LocalTime start, LocalTime end, boolean overnight) {
}
