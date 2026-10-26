package turnos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FakeHttpServerTest {

    private final FakeHttpServer server = FakeHttpServer.start();
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void eachPathAnswersWithItsOwnResponse() throws Exception {
        server.respond("/api/a", FakeHttpServer.Response.of(200, "{\"a\":1}"));
        server.respond("/api/b", FakeHttpServer.Response.of(503, "{\"status\":503}"));

        HttpResponse<String> a = get("/api/a");
        HttpResponse<String> b = get("/api/b");

        assertThat(a.statusCode()).isEqualTo(200);
        assertThat(a.body()).isEqualTo("{\"a\":1}");
        assertThat(a.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(b.statusCode()).isEqualTo(503);
        assertThat(b.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    }

    @Test
    void responsesAreUsedInOrderAndTheLastOneRepeats() throws Exception {
        server.respond("/api/a",
                FakeHttpServer.Response.of(401, "{\"status\":401}"),
                FakeHttpServer.Response.of(200, "{}"));

        assertThat(get("/api/a").statusCode()).isEqualTo(401);
        assertThat(get("/api/a").statusCode()).isEqualTo(200);
        assertThat(get("/api/a").statusCode()).isEqualTo(200);
    }

    @Test
    void aPathWithoutResponsesGetsTheDefaultOne() throws Exception {
        assertThat(get("/api/unknown").statusCode()).isEqualTo(404);

        server.respondToAnyPath(FakeHttpServer.Response.of(200, "{}"));

        assertThat(get("/api/unknown").statusCode()).isEqualTo(200);
    }

    @Test
    void aResponseCanBeDelayed() throws Exception {
        server.respond("/api/slow", FakeHttpServer.Response.of(200, "{}").after(Duration.ofMillis(300)));

        Instant start = Instant.now();
        get("/api/slow");

        assertThat(Duration.between(start, Instant.now())).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void everyRequestIsRecordedWithItsQueryHeadersAndBody() throws Exception {
        server.respondToAnyPath(FakeHttpServer.Response.of(200, "{}"));
        client.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/items?professionalId=15&from=2026-10-20"))
                .header("Authorization", "Bearer abc")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"x\":1}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        get("/api/other");

        FakeHttpServer.ReceivedRequest request = server.receivedRequests("/api/items").getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.query()).isEqualTo("professionalId=15&from=2026-10-20");
        assertThat(request.header("Authorization")).isEqualTo("Bearer abc");
        assertThat(request.contentType()).isEqualTo("application/json");
        assertThat(request.body()).isEqualTo("{\"x\":1}");
        assertThat(server.receivedRequests()).hasSize(2);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
