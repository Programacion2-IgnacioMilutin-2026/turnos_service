package turnos;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

// Servidor HTTP real para simular sistemas externos en los tests, en un puerto aleatorio.
// Cada ruta tiene su cola de respuestas: cada request consume la siguiente y la última se repite.
// Guarda todo lo que recibe para que el test lo revise
public final class FakeHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final List<ReceivedRequest> receivedRequests = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Response>> responsesByPath = new ConcurrentHashMap<>();
    private volatile Deque<Response> defaultResponses = queueOf(List.of(Response.of(404, "{\"status\":404}")));

    // Una respuesta: estado, cuerpo, tipo de contenido y una demora opcional antes de responder
    public record Response(int status, String body, String contentType, Duration delay) {

        public static Response of(int status, String body) {
            return new Response(status, body, status < 400 ? "application/json" : "application/problem+json", Duration.ZERO);
        }

        public Response after(Duration delay) {
            return new Response(status, body, contentType, delay);
        }

        public Response withContentType(String contentType) {
            return new Response(status, body, contentType, delay);
        }
    }

    // Lo que llegó: los encabezados se guardan con el nombre en minúscula
    public record ReceivedRequest(String method, String path, String query, Map<String, String> headers, String body) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String contentType() {
            return header("Content-Type");
        }
    }

    private FakeHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public static FakeHttpServer start() {
        try {
            return new FakeHttpServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Respuestas para una ruta exacta, en orden
    public void respond(String path, Response... responses) {
        responsesByPath.put(path, queueOf(List.of(responses)));
    }

    // Respuestas para cualquier ruta que no tenga las suyas
    public void respondToAnyPath(Response... responses) {
        defaultResponses = queueOf(List.of(responses));
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public List<ReceivedRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }

    public List<ReceivedRequest> receivedRequests(String path) {
        return receivedRequests.stream().filter(request -> request.path().equals(path)).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        receivedRequests.add(new ReceivedRequest(exchange.getRequestMethod(), path,
                exchange.getRequestURI().getRawQuery(), headersOf(exchange), requestBody));
        Response response = nextResponse(path);
        sleep(response.delay());
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        if (response.contentType() != null) {
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
        }
        exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    // La última respuesta de la cola no se consume: se repite para los requests siguientes
    private Response nextResponse(String path) {
        Deque<Response> queue = responsesByPath.getOrDefault(path, defaultResponses);
        synchronized (queue) {
            return queue.size() > 1 ? queue.pollFirst() : queue.peekFirst();
        }
    }

    private static Map<String, String> headersOf(HttpExchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
        return Map.copyOf(headers);
    }

    private static Deque<Response> queueOf(List<Response> responses) {
        if (responses.isEmpty()) {
            throw new IllegalArgumentException("At least one response is needed");
        }
        return new ArrayDeque<>(responses);
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
