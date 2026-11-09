package com.example.library;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class ApiClient {
    record Result(int status, JsonNode body, String requestId) {}
    private final String base;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    ApiClient(int port, ObjectMapper mapper) { this.base = "http://127.0.0.1:" + port; this.mapper = mapper; }

    Result request(String method, String path, Long accountId, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json");
        if (accountId != null) builder.header("X-Library-Account-Id", accountId.toString());
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body));
        if (body != null) builder.header("Content-Type", "application/json");
        var response = client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), mapper.readTree(response.body()),
                response.headers().firstValue("X-Request-Id").orElse(null));
    }
}
