package turnos.catedra;

import turnos.FakeHttpServer;

import java.time.Duration;
import java.util.List;

// Cátedra simulada para los tests, sobre el servidor HTTP de test.
// Por defecto responde a cualquier ruta con un login técnico exitoso; http() da acceso a las respuestas por ruta
public final class FakeCatedraServer implements AutoCloseable {

    public static final String TOKEN = "eyJhbGciOiJIUzUxMiJ9.token-tecnico-de-test.firma";

    private final FakeHttpServer server;

    private FakeCatedraServer() {
        server = FakeHttpServer.start();
        server.respondToAnyPath(FakeHttpServer.Response.of(200, provisionedLoginResponse("PROVISIONED")));
    }

    public static FakeCatedraServer start() {
        return new FakeCatedraServer();
    }

    public void respond(int status, String body) {
        respondAfter(Duration.ZERO, status, body);
    }

    public void respondAfter(Duration delay, int status, String body) {
        server.respondToAnyPath(FakeHttpServer.Response.of(status, body).after(delay));
    }

    public FakeHttpServer http() {
        return server;
    }

    public String baseUrl() {
        return server.baseUrl();
    }

    public List<FakeHttpServer.ReceivedRequest> receivedRequests() {
        return server.receivedRequests();
    }

    @Override
    public void close() {
        server.close();
    }

    // La respuesta del login técnico, incluidos los campos de Redis que turnos debe descartar
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
}
