package turnos.catedra.infrastructure.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;
import turnos.FakeHttpServer;
import turnos.catedra.domain.model.AppointmentCancellationResult;
import turnos.catedra.domain.model.CatedraResponse;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Sin grabaciones: la cátedra real solo se pudo ver rechazando procesos sin confirmar, así que las respuestas se arman
// con el formato de sus errores reales
@ExtendWith(OutputCaptureExtension.class)
class CatedraAppointmentClientAdapterTest {

    private static final String TOKEN = "eyJ.jwt-tecnico.firma";
    private static final String PROCESS = "process-1";
    private static final String PATH = "/api/appointments/" + PROCESS + "/cancel";
    private static final String CANCELLED = """
            {"reservationId":8251,"reservationProcessId":"process-1","status":"CANCELLED","cancelledAt":"2026-11-04T15:10:00Z"}
            """;

    private final FakeHttpServer catedra = FakeHttpServer.start();

    @AfterEach
    void stop() {
        catedra.close();
    }

    @Test
    void theCancellationIsRequestedWithTheProcessTheReasonAndTheTechnicalToken() {
        catedra.respond(PATH, FakeHttpServer.Response.of(200, CANCELLED));

        adapter(catedra.baseUrl()).cancelAppointment(PROCESS, "Cambio solicitado por el usuario", TOKEN);

        FakeHttpServer.ReceivedRequest request = catedra.receivedRequests().getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo(PATH);
        assertThat(request.header("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(request.contentType()).startsWith("application/json");
        assertThat(JsonMapper.builder().build().readValue(request.body(), Map.class))
                .isEqualTo(Map.of("reason", "Cambio solicitado por el usuario"));
    }

    @Test
    void withoutAReasonTheRequestHasNoBody() {
        catedra.respond(PATH, FakeHttpServer.Response.of(200, CANCELLED));

        adapter(catedra.baseUrl()).cancelAppointment(PROCESS, null, TOKEN);

        assertThat(catedra.receivedRequests().getFirst().body()).isEmpty();
    }

    @Test
    void aCancelledReservationAnswersTheDateOfTheCatedra() {
        assertThat(answerTo(FakeHttpServer.Response.of(200, CANCELLED))).isEqualTo(new CatedraResponse.Answered<>(
                new AppointmentCancellationResult.Cancelled(Instant.parse("2026-11-04T15:10:00Z"))));
    }

    @Test
    void aCancelledProcessWithoutAReservationIdIsAlsoCancelled() {
        String withoutReservation = """
                {"reservationProcessId":"process-1","status":"CANCELLED","cancelledAt":"2026-11-04T15:10:00Z"}
                """;

        assertThat(answerTo(FakeHttpServer.Response.of(200, withoutReservation))).isInstanceOf(CatedraResponse.Answered.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\":\"CONFIRMED\",\"cancelledAt\":\"2026-11-04T15:10:00Z\"}", "{\"status\":\"CANCELLED\"}", "{}", "not json"})
    void aSuccessThatCannotBeUsedIsAnUnexpectedAnswer(String body) {
        assertThat(answerTo(FakeHttpServer.Response.of(200, body))).isInstanceOf(CatedraResponse.Failed.class);
    }

    @Test
    void aReservationThatDoesNotExistThereIsADefinitiveAnswer() {
        assertThat(answerTo(rejection(404, "RESERVATION_NOT_FOUND")))
                .isEqualTo(new CatedraResponse.Answered<>(new AppointmentCancellationResult.NotFound()));
    }

    @Test
    void aReservationOfAnotherAccountIsADefinitiveAnswer() {
        assertThat(answerTo(rejection(403, "RESERVATION_OWNER_MISMATCH")))
                .isEqualTo(new CatedraResponse.Answered<>(new AppointmentCancellationResult.OwnerMismatch()));
    }

    @Test
    void aReservationThatCannotBeCancelledIsADefinitiveAnswer() {
        assertThat(answerTo(rejection(409, "RESERVATION_NOT_CANCELLABLE")))
                .isEqualTo(new CatedraResponse.Answered<>(new AppointmentCancellationResult.NotCancellable()));
    }

    @Test
    void aMissingIntegrationIsAConfigurationError() {
        assertThat(answerTo(rejection(403, "STUDENT_INTEGRATION_NOT_FOUND"))).isInstanceOf(CatedraResponse.ConfigurationError.class);
    }

    @Test
    void aRejectedTokenWithoutBodyIsReported() {
        assertThat(answerTo(FakeHttpServer.Response.of(401, ""))).isInstanceOf(CatedraResponse.TokenRejected.class);
    }

    @ParameterizedTest
    @CsvSource({"500", "503"})
    void aFailureOfTheCatedraCanBeRepeated(int status) {
        assertThat(answerTo(FakeHttpServer.Response.of(status, "{}"))).isInstanceOf(CatedraResponse.Unavailable.class);
    }

    @ParameterizedTest
    @CsvSource({"404, OTHER_CODE", "403, OTHER_CODE", "409, OTHER_CODE", "400, VALIDATION_ERROR", "418, TEAPOT"})
    void anyOtherAnswerIsUnexpected(int status, String code) {
        assertThat(answerTo(rejection(status, code))).isInstanceOf(CatedraResponse.Failed.class);
    }

    @Test
    void anAnswerThatTakesTooLongCanBeRepeated() {
        assertThat(answerTo(FakeHttpServer.Response.of(200, CANCELLED).after(Duration.ofMillis(800))))
                .isInstanceOf(CatedraResponse.Unavailable.class);
    }

    @Test
    void aCatedraThatIsNotListeningCanBeRepeated() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        assertThat(adapter("http://localhost:" + freePort).cancelAppointment(PROCESS, null, TOKEN))
                .isInstanceOf(CatedraResponse.Unavailable.class);
    }

    @Test
    void aConnectionThatIsCutCanBeRepeated() throws IOException {
        try (ServerSocket dropper = new ServerSocket(0)) {
            Thread.ofVirtual().start(() -> {
                try (Socket _ = dropper.accept()) {
                    // Se cierra sin responder
                } catch (IOException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(adapter("http://localhost:" + dropper.getLocalPort()).cancelAppointment(PROCESS, null, TOKEN))
                    .isInstanceOf(CatedraResponse.Unavailable.class);
        }
    }

    @Test
    void theTechnicalTokenNeverReachesTheLog(CapturedOutput output) {
        answerTo(rejection(403, "STUDENT_INTEGRATION_NOT_FOUND"));
        answerTo(FakeHttpServer.Response.of(200, "not json"));

        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    private CatedraResponse<AppointmentCancellationResult> answerTo(FakeHttpServer.Response response) {
        catedra.respond(PATH, response);
        return adapter(catedra.baseUrl()).cancelAppointment(PROCESS, null, TOKEN);
    }

    private static FakeHttpServer.Response rejection(int status, String code) {
        return FakeHttpServer.Response.of(status, """
                {"title":"Rejected","status":%d,"code":"%s"}
                """.formatted(status, code)).withContentType("application/problem+json");
    }

    private static CatedraAppointmentClientAdapter adapter(String baseUrl) {
        return new CatedraAppointmentClientAdapter(new CatedraProperties(baseUrl, "user", "pass", Duration.ofSeconds(5),
                Duration.ofSeconds(10), new CatedraProperties.Login(5, Duration.ofSeconds(1), 2, Duration.ofSeconds(30),
                Duration.ofSeconds(60)),
                new CatedraProperties.Operations(Duration.ofMillis(200), Duration.ofMillis(500), 3, Duration.ofMillis(5), 2)));
    }
}
