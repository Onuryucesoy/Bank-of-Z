package com.ibm.bankofz.migration.goldenmaster;

import com.fasterxml.jackson.databind.JsonNode;
import com.ibm.bankofz.migration.Main;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** Runs the Java stub service on an ephemeral port, or points at {@code -Dbankofz.replay.target=<baseUrl>}. */
final class StubServer implements AutoCloseable {

    record Reply(int status, Map<String, java.util.List<String>> headers, JsonNode body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    private final HttpServer server;
    private final String baseUrl;
    private final HttpClient client = HttpClient.newHttpClient();

    private StubServer(HttpServer server, String baseUrl) {
        this.server = server;
        this.baseUrl = baseUrl;
    }

    static StubServer start() {
        String target = System.getProperty("bankofz.replay.target");
        if (target != null && !target.isBlank()) {
            return new StubServer(null, target.replaceAll("/+$", ""));
        }
        HttpServer s = Main.start(0);
        return new StubServer(s, "http://localhost:" + s.getAddress().getPort() + "/api");
    }

    Reply call(String method, String path, JsonNode body) {
        HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString());
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .method(method, pub).build();
        try {
            HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = r.body() == null || r.body().isBlank() ? null : RepoFiles.JSON.readTree(r.body());
            return new Reply(r.statusCode(), r.headers().map(), parsed);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }
}
