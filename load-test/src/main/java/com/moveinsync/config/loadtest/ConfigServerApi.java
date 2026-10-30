package com.moveinsync.config.loadtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

// the three server calls the load tests make as an operator or a service: log in, publish a change, read the version
public final class ConfigServerApi {

    private final String base;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public ConfigServerApi(String base) {
        this.base = base;
    }

    public String login(String username, String password) {
        String body = mapper.createObjectNode().put("username", username).put("password", password).toString();
        return call(post("/api/v1/auth/token", null, body), 200).path("token").asText();
    }

    // one SET at OFFICE level; returns the version the server committed it as
    public long publish(String operatorToken, String tenantId, String officeId, String key, String value) {
        var json = mapper.createObjectNode();
        json.put("reason", "load test");
        json.putArray("operations").addObject()
                .put("op", "SET")
                .put("level", "OFFICE")
                .put("scopeId", officeId)
                .put("key", key)
                .put("value", value);
        String path = "/api/v1/tenants/" + tenantId + "/changes";
        return call(post(path, operatorToken, json.toString()), 201).path("version").asLong();
    }

    public long version(String serviceToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v1/config/version"))
                .header("Authorization", "Bearer " + serviceToken)
                .GET()
                .build();
        return call(request, 200).path("version").asLong();
    }

    private HttpRequest post(String path, String token, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    private JsonNode call(HttpRequest request, int expectedStatus) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != expectedStatus) {
                throw new IllegalStateException(request.method() + " " + request.uri() + " answered HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during " + request.uri(), e);
        }
    }
}
