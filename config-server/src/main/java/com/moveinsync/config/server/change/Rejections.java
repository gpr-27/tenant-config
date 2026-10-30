package com.moveinsync.config.server.change;

import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.error.ValidationError;
import com.moveinsync.config.server.metrics.ServerMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// every refused write passes through here, so each one gives exactly one WARN line and one rejected count.
// Only codes are logged: an error message can quote the operator's value.
@Slf4j
@Component
@RequiredArgsConstructor
public class Rejections {

    private final ServerMetrics metrics;

    public void invalid(ChangeKind kind, String tenantId, List<ValidationError> errors) {
        metrics.changeRejected(kind, errors);
        List<String> codes = new ArrayList<>();
        for (ValidationError error : errors) {
            codes.add(error.code());
        }
        log.warn("change set rejected tenant={} kind={} errors={}", tenantId, kind, codes);
    }

    // a refusal judged against the stored state (409) rather than against the request's own operations
    public void conflict(ChangeKind kind, String tenantId, String code) {
        metrics.changeRejected(kind, List.of());
        log.warn("change set rejected tenant={} kind={} errors=[{}]", tenantId, kind, code);
    }
}
