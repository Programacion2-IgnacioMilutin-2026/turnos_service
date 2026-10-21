package turnos.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

// Cátedra simulada para los tests: un servidor HTTP real en un puerto aleatorio que responde lo que el test le indique
public final class FakeCatedraServer implements AutoCloseable {

    public static final String TOKEN = "eyJhbGciOiJIUzUxMiJ9.token-tecnico-de-test.firma";

    private final HttpServer server;
    private final List<ReceivedRequest> receivedRequests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = provisionedLoginResponse("PROVISIONED");
    private volatile Duration delay = Duration.ZERO;

    public record ReceivedRequest(String method, String path, String contentType, String body) {
    }

    private FakeCatedraServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public static FakeCatedraServer start() {
        try {
            return new FakeCatedraServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void respond(int status, String body) {
        respondAfter(Duration.ZERO, status, body);
    }

    public void respondAfter(Duration delay, int status, String body) {
        this.delay = delay;
        this.status = status;
        this.body = body;
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public List<ReceivedRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // La respuesta del login técnico del anexo, incluidos los campos de Redis que turnos debe descartar
    public static String provisionedLoginResponse(String provisioningStatus) {
        return """
                {"id_token":"%s","integration":{"groupId":"proyecto-juan-perez","redisHost":"redis.catedra.internal",
                 "redisPort":6379,"redisUsername":"grp_proyecto-juan-perez","redisPassword":"secreto-redis",
                 "redisReadNamespace":"catedra:sync:*","redisWriteNamespace":"alumnos:proyecto-juan-perez:*",
                 "kafkaBootstrapServers":"kafka.catedra.internal:9092","kafkaConsumerGroupId":"alumnos-proyecto-juan-perez",
                 "kafkaCatalogTopic":"catedra.catalog.proyecto-juan-perez",
                 "kafkaAppointmentActionsTopic":"alumnos.turnos.acciones.proyecto-juan-perez",
                 "kafkaAppointmentPhoneTopic":"catedra.turnos.telefono.proyecto-juan-perez",
                 "provisioningStatus":"%s"}}
                """.formatted(TOKEN, provisioningStatus);
    }

    public static String problem(int status, String code) {
        return code == null
                ? "{\"status\":%d}".formatted(status)
                : "{\"status\":%d,\"code\":\"%s\"}".formatted(status, code);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        receivedRequests.add(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Content-Type"), requestBody));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", status < 400 ? "application/json" : "application/problem+json");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        }
    }
}
