package turnos.catedra;

import turnos.FakeHttpServer;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

// Cátedra simulada para los tests, sobre el servidor HTTP de test.
// Por defecto responde a cualquier ruta con un login técnico exitoso; http() da acceso a las respuestas por ruta
public final class FakeCatedraServer implements AutoCloseable {

    public static final String APPOINTMENTS_PATH = "/api/appointments";
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
        return provisionedLoginResponse(provisioningStatus, "kafka.catedra.internal:9092",
                "alumnos.turnos.acciones.proyecto-juan-perez", "alumnos-proyecto-juan-perez");
    }

    // La misma respuesta, con el Kafka que use el test (por ejemplo, el de Testcontainers)
    public static String provisionedLoginResponse(String provisioningStatus, String kafkaBootstrapServers,
                                                  String actionsTopic, String consumerGroup) {
        return provisionedLoginResponse(provisioningStatus, kafkaBootstrapServers, actionsTopic, consumerGroup,
                "catedra.turnos.telefono.proyecto-juan-perez");
    }

    // También con el topic de teléfono del test, para leer lo que publica el servicio
    public static String provisionedLoginResponse(String provisioningStatus, String kafkaBootstrapServers,
                                                  String actionsTopic, String consumerGroup, String phoneTopic) {
        return """
                {"id_token":"%s","integration":{"groupId":"proyecto-juan-perez","redisHost":"redis.catedra.internal",
                 "redisPort":6379,"redisUsername":"grp_proyecto-juan-perez","redisPassword":"secreto-redis",
                 "redisReadNamespace":"catedra:sync:*","redisWriteNamespace":"alumnos:proyecto-juan-perez:*",
                 "kafkaBootstrapServers":"%s","kafkaConsumerGroupId":"%s",
                 "kafkaCatalogTopic":"catedra.catalog.proyecto-juan-perez",
                 "kafkaAppointmentActionsTopic":"%s",
                 "kafkaAppointmentPhoneTopic":"%s",
                 "provisioningStatus":"%s"}}
                """.formatted(TOKEN, kafkaBootstrapServers, consumerGroup, actionsTopic, phoneTopic, provisioningStatus);
    }

    // Contesta el listado de reservas con lo que corresponda al paciente pedido
    public void respondToListings(Function<String, FakeHttpServer.Response> byPatient) {
        server.respondTo(APPOINTMENTS_PATH, request -> byPatient.apply(patientOf(request)));
    }

    public List<FakeHttpServer.ReceivedRequest> listings() {
        return server.receivedRequests(APPOINTMENTS_PATH);
    }

    public static String patientOf(FakeHttpServer.ReceivedRequest request) {
        return Arrays.stream(request.query().split("&"))
                .filter(parameter -> parameter.startsWith("externalPatientId="))
                .map(parameter -> URLDecoder.decode(parameter.substring("externalPatientId=".length()), StandardCharsets.UTF_8))
                .findFirst().orElse(null);
    }

    // Una reserva del listado con el formato de la cátedra real; los datos personales son de prueba
    public static String appointment(String reservationProcessId, String externalPatientId, String status,
                                     Long reservationId, Instant confirmedAt, Instant cancelledAt, String cancellationReason) {
        return """
                {"appointmentDate":"2026-11-09","cancellationReason":%s,"cancelledAt":%s,"confirmedAt":%s,
                 "createdAt":"2026-11-05T12:00:00Z","endTime":"11:00:00","externalPatientId":"%s","patientFirstName":"Prueba",
                 "patientLastName":"Grabacion","patientPhone":null,"professionalFirstName":"Ana","professionalId":201,
                 "professionalLastName":"Gomez","reservationId":%s,"reservationProcessId":"%s","startTime":"10:30:00",
                 "status":"%s"}
                """.formatted(quoted(cancellationReason), quoted(cancelledAt), quoted(confirmedAt), externalPatientId,
                reservationId, reservationProcessId, status);
    }

    private static String quoted(Object value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    public static String problem(int status, String code) {
        return code == null
                ? "{\"status\":%d}".formatted(status)
                : "{\"status\":%d,\"code\":\"%s\"}".formatted(status, code);
    }
}
