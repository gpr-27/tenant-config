package com.moveinsync.config.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.server.push.TooManyConnectionsException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class PushCapacityResponseTest {

    @Test
    void fullPushHubAnswers503WithRetryAfterFiveSeconds() {
        ApiExceptionHandler handler = new ApiExceptionHandler(new ObjectMapper());

        ResponseEntity<ProblemDetail> response =
                handler.pushCapacity(new TooManyConnectionsException(), new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getBody().getProperties()).containsEntry("errorCode", "PUSH_CAPACITY");
    }
}
