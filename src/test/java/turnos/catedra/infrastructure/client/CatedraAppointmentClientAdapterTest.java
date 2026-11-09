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
import turnos.catedra.infrastructure.client.mapper.AppointmentMapper;
import turnos.catedra.domain.model.Appointment;
import turnos.catedra.domain.model.AppointmentCancellationResult;
import turnos.catedra.domain.model.AppointmentStatus;
import turnos.catedra.domain.model.CatedraResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// La cancelación no tiene grabaciones: la cátedra real solo se pudo ver rechazando procesos sin confirmar, así que sus
// respuestas se arman con el formato de sus errores reales. El listado usa las respuestas reales grabadas
@ExtendWith(OutputCaptureExtension.class)
class CatedraAppointmentClientAdapterTest {

    private static final String TOKEN = "eyJ.jwt-tecnico.firma";
    private static final String PROCESS = "process-1";
    private static final String PATH = "/api/appointments/" + PROCESS + "/cancel";
    private static final String LIST_PATH = "/api/appointments";
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

    // Listado de reservas

    @Test
    void theListIsRequestedByPatientAndPageWithoutDatesAndWithTheTechnicalToken() {
        catedra.respond(LIST_PATH, FakeHttpServer.Response.of(200, "[]"));

        adapter(catedra.baseUrl()).listAppointments("1042", 2, 20, TOKEN);

        FakeHttpServer.ReceivedRequest request = catedra.receivedRequests().getFirst();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo(LIST_PATH);
        assertThat(request.query()).isEqualTo("externalPatientId=1042&page=2&size=20");
        assertThat(request.header("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void aRecentlyConfirmedReservationComesWithoutPhoneAndBeforeTheOlderOnes() {
        CatedraResponse<List<Appointment>> response = listingAnswerTo(
                FakeHttpServer.Response.of(200, recording("appointments-200-phone-pending.json")));

        assertThat(response).isInstanceOf(CatedraResponse.Answered.class);
        List<Appointment> appointments = ((CatedraResponse.Answered<List<Appointment>>) response).content();
        assertThat(appointments).containsExactly(
                new Appointment("process-d62d22ad-8b62-49e6-b6d6-296b99248000", "999999", AppointmentStatus.PHONE_PENDING,
                        1664L, null, null, null, 203L, LocalDate.of(2026, 11, 6), LocalTime.of(12, 0)),
                new Appointment("process-bfcb30eb-6e39-402f-bd92-95e73bfec54d", "999999", AppointmentStatus.FAILED,
                        1252L, null, null, null, 201L, LocalDate.of(2026, 11, 2), LocalTime.of(12, 0)));
    }

    @Test
    void aReservationThatExpiredWithoutPhoneFailed() {
        CatedraResponse<List<Appointment>> response = listingAnswerTo(
                FakeHttpServer.Response.of(200, recording("appointments-200-failed.json")));

        assertThat(((CatedraResponse.Answered<List<Appointment>>) response).content())
                .extracting(Appointment::status).containsExactly(AppointmentStatus.FAILED);
    }

    @Test
    void confirmedAndCancelledReservationsKeepTheirDatesAndReason() {
        String body = """
                [{"reservationId":8251,"reservationProcessId":"process-a","externalPatientId":"1042","appointmentDate":"2026-10-20",
                  "startTime":"09:30:00","endTime":"10:00:00","professionalId":15,"professionalFirstName":"Ana",
                  "patientFirstName":"María","patientPhone":"+5492615551234","status":"CONFIRMED",
                  "createdAt":"2026-10-12T14:35:00Z","confirmedAt":"2026-10-12T14:36:00Z","cancelledAt":null,"cancellationReason":null},
                 {"reservationId":8252,"reservationProcessId":"process-b","externalPatientId":"1042","appointmentDate":"2026-10-21",
                  "startTime":"10:00","professionalId":15,"status":"CANCELLED","confirmedAt":"2026-10-12T15:00:00Z",
                  "cancelledAt":"2026-10-13T09:00:00Z","cancellationReason":"Paciente no asiste"}]
                """;

        List<Appointment> appointments = ((CatedraResponse.Answered<List<Appointment>>) listingAnswerTo(
                FakeHttpServer.Response.of(200, body))).content();

        assertThat(appointments.getFirst().confirmedAt()).isEqualTo(Instant.parse("2026-10-12T14:36:00Z"));
        assertThat(appointments.getFirst().status()).isEqualTo(AppointmentStatus.CONFIRMED);
        assertThat(appointments.get(1).cancelledAt()).isEqualTo(Instant.parse("2026-10-13T09:00:00Z"));
        assertThat(appointments.get(1).cancellationReason()).isEqualTo("Paciente no asiste");
        assertThat(appointments.get(1).startTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    void anEmptyPageIsAnAnswer() {
        assertThat(listingAnswerTo(FakeHttpServer.Response.of(200, "[]"))).isEqualTo(new CatedraResponse.Answered<>(List.of()));
    }

    @Test
    void anUnknownStatusDoesNotRejectThePage() {
        String body = """
                [{"reservationProcessId":"process-a","status":"ON_HOLD"},{"reservationProcessId":"process-b","status":"FAILED"},
                 {"reservationProcessId":"process-c"}]
                """;

        assertThat(((CatedraResponse.Answered<List<Appointment>>) listingAnswerTo(FakeHttpServer.Response.of(200, body))).content())
                .extracting(Appointment::status)
                .containsExactly(AppointmentStatus.UNKNOWN, AppointmentStatus.FAILED, AppointmentStatus.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{}", "null", "[{\"confirmedAt\":\"ayer\"}]"})
    void aListThatCannotBeReadIsAnUnexpectedAnswer(String body) {
        assertThat(listingAnswerTo(FakeHttpServer.Response.of(200, body))).isInstanceOf(CatedraResponse.Failed.class);
    }

    @Test
    void theRealFailureWithDatesCanBeRepeated() {
        assertThat(listingAnswerTo(FakeHttpServer.Response.of(500, recording("appointments-500-date-filter.json"))
                .withContentType("application/problem+json"))).isInstanceOf(CatedraResponse.Unavailable.class);
    }

    @Test
    void anUnavailableCatedraCanBeRepeated() {
        assertThat(listingAnswerTo(FakeHttpServer.Response.of(503, "{}"))).isInstanceOf(CatedraResponse.Unavailable.class);
    }

    @Test
    void aListThatTakesTooLongCanBeRepeated() {
        assertThat(listingAnswerTo(FakeHttpServer.Response.of(200, "[]").after(Duration.ofMillis(800))))
                .isInstanceOf(CatedraResponse.Unavailable.class);
    }

    @Test
    void aListFromACatedraThatIsNotListeningCanBeRepeated() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }

        assertThat(adapter("http://localhost:" + freePort).listAppointments("1042", 0, 20, TOKEN))
                .isInstanceOf(CatedraResponse.Unavailable.class);
    }

    @Test
    void aRejectedTokenWhileListingIsReported() {
        assertThat(listingAnswerTo(FakeHttpServer.Response.of(401, "").withContentType(null)))
                .isInstanceOf(CatedraResponse.TokenRejected.class);
    }

    @Test
    void aMissingIntegrationWhileListingIsAConfigurationError() {
        assertThat(listingAnswerTo(rejection(403, "STUDENT_INTEGRATION_NOT_FOUND"))).isInstanceOf(CatedraResponse.ConfigurationError.class);
    }

    @ParameterizedTest
    @CsvSource({"403, OTHER_CODE", "400, VALIDATION_ERROR", "404, NOT_FOUND"})
    void anyOtherAnswerToTheListIsUnexpected(int status, String code) {
        assertThat(listingAnswerTo(rejection(status, code))).isInstanceOf(CatedraResponse.Failed.class);
    }

    @Test
    void neitherTheTokenNorThePatientDataOfTheListReachTheLog(CapturedOutput output) {
        listingAnswerTo(FakeHttpServer.Response.of(200, recording("appointments-200-phone-pending.json")));
        listingAnswerTo(FakeHttpServer.Response.of(200, "not json"));

        assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain("Grabacion");
    }

    private CatedraResponse<List<Appointment>> listingAnswerTo(FakeHttpServer.Response response) {
        catedra.respond(LIST_PATH, response);
        return adapter(catedra.baseUrl()).listAppointments("999999", 0, 20, TOKEN);
    }

    private static String recording(String name) {
        try (InputStream input = CatedraAppointmentClientAdapterTest.class.getResourceAsStream("/catedra/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
                new CatedraProperties.Operations(Duration.ofMillis(200), Duration.ofMillis(500), 3, Duration.ofMillis(5), 2, Duration.ofSeconds(1))),
                new AppointmentMapper());
    }
}
