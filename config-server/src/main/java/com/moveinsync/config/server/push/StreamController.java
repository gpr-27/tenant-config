package com.moveinsync.config.server.push;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class StreamController {

    private final PushHub pushHub;

    // Last-Event-ID is what an SSE client sends on reconnect; ?since is for callers that cannot set headers
    @GetMapping(path = "/api/v1/config/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
    public SseEmitter stream(@RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId,
                             @RequestParam(required = false) Long since) {
        long lastVersion = lastEventId != null ? lastEventId : since != null ? since : 0;
        return pushHub.subscribe(lastVersion);
    }
}
