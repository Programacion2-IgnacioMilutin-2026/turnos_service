package turnos.catedra.infrastructure.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;
import turnos.FakeHttpServer;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.Hold;
import turnos.catedra.domain.model.HoldConfirmation;
import turnos.catedra.domain.model.HoldConfirmationResult;
import turnos.catedra.domain.model.HoldResult;
import turnos.catedra.infrastructure.client.mapper.HoldMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CatedraHoldClientAdapterTest {

    private static final String TOKEN = "eyJ.jwt-tecnico.firma";
    private static final String PATH = "/api/appointment-holds";
    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final LocalTime NOON = LocalTime.of(12, 0);
    // Armado a mano: hace falta una reserva confirmada para provocarlo en la cátedra real
    private static final String SLOT_ALREADY_RESERVED = """
            {"detail":"Slot already reserved","status":409,"title":"Conflict","code":"SLOT_ALREADY_RESERVED"}
            """;
    private static final String MISSING_INTEGRATION = """
            {"status":403,"title":"Forbidden","code":"STUDENT_INTEGRATION_NOT_FOUND"}
            """;

    private final FakeHttpServer catedra = FakeHttpServer.start();

    @AfterEach
    void stop() {
        catedra.close();
    }

    @Test
    void theHoldIsRequestedWithTheSlotThePatientAndTheTechnicalToken() {
        catedra.respond(PATH, FakeHttpServer.Response.of(201, recording("hold-201-created.json")));

        adapter().createHold(201L, DATE, NOON, "42", TOKEN);

        FakeHttpServer.ReceivedRequest request = catedra.receivedRequests().getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo(PATH);
        assertThat(request.header("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(request.contentType()).startsWith("application/json");
        assertThat(JsonMapper.builder().build().readValue(request.body(), Map.class)).isEqualTo(Map.of(
                "professionalId", 201, "date", "2026-11-02", "startTime", "12:00", "externalPatientId", "42"));
    }

    @Test
    void theRecordedHoldIsCreatedWithTheIdsAndExpirationOfTheCatedra() {
        catedra.respond(PATH, FakeHttpServer.Response.of(201, recording("hold-201-created.json")));

        CatedraResponse<HoldResult> response = adapter().createHold(201L, DATE, NOON, "42", TOKEN);

        Hold hold = ((HoldResult.Created) ((CatedraResponse.Answered<HoldResult>) response).content()).hold();
        assertThat(hold.getHoldId()).isEqualTo("hold-fbeebd61-9202-443f-ac9f-6d54ff090adc");
        assertThat(hold.getReservationProcessId()).isEqualTo("process-445b6066-e657-40ad-9a7e-e87a0e803e54");
        assertThat(hold.getExpiresAt()).isEqualTo(Instant.parse("2026-10-27T04:44:38.627744064Z"));
    }

    @Test
    void eachRecordedRejectionIsADefinitiveAnswer() {
        assertThat(answerTo(FakeHttpServer.Response.of(409, recording("hold-409-slot-already-held.json"))))
                .isEqualTo(new CatedraResponse.Answered<>(new HoldResult.SlotNotAvailable()));
        assertThat(answerTo(FakeHttpServer.Response.of(409, SLOT_ALREADY_RESERVED)))
                .isEqualTo(new CatedraResponse.Answered<>(new HoldResult.SlotNotAvailable()));
        assertThat(answerTo(FakeHttpServer.Response.of(400, recording("hold-400-invalid-slot.json"))))
                .isEqualTo(new CatedraResponse.Answered<>(new HoldResult.InvalidSlot()));
        assertThat(answerTo(FakeHttpServer.Response.of(400, recording("hold-400-professional-disabled.json"))))
                .isEqualTo(new CatedraResponse.Answered<>(new HoldResult.ProfessionalDisabled()));
        assertThat(answerTo(FakeHttpServer.Response.of(404, recording("hold-404-professional-not-found.json"))))
                .isEqualTo(new CatedraResponse.Answered<>(new HoldResult.ProfessionalNotFound()));
    }

    @Test
    void theRecordedRejectionWithoutBodyIsATokenRejection() {
        assertThat(answerTo(FakeHttpServer.Response.of(401, recording("hold-401-invalid-token.json")).withContentType(null)))
                .isInstanceOf(CatedraResponse.TokenRejected.class);
    }

    @Test
    void aMissingStudentIntegrationIsAConfigurationError() {
        assertThat(answerTo(FakeHttpServer.Response.of(403, MISSING_INTEGRATION)))
                .isInstanceOf(CatedraResponse.ConfigurationError.class);
    }

    @Test
    void unknownCodesAndOtherStatusesAreUnexpectedAnswers() {
        assertThat(answerTo(FakeHttpServer.Response.of(400, "{\"status\":400,\"code\":\"VALIDATION_ERROR\"}")))
                .isInstanceOf(CatedraResponse.Failed.class);
        assertThat(answerTo(FakeHttpServer.Response.of(403, "{\"status\":403,\"code\":\"OTHER\"}")))
                .isInstanceOf(CatedraResponse.Failed.class);
        assertThat(answerTo(FakeHttpServer.Response.of(409, ""))).isInstanceOf(CatedraResponse.Failed.class);
        assertThat(answerTo(FakeHttpServer.Response.of(502, "{}"))).isInstanceOf(CatedraResponse.Failed.class);
    }

    @Test
    void anIncompleteOrUnreadableHoldIsAnUnexpectedAnswer() {
        assertThat(answerTo(FakeHttpServer.Response.of(201, "not json"))).isInstanceOf(CatedraResponse.Failed.class);
        assertThat(answerTo(FakeHttpServer.Response.of(201, "{\"holdId\":\"h\",\"status\":\"HELD\"}")))
                .isInstanceOf(CatedraResponse.Failed.class);
        assertThat(answerTo(FakeHttpServer.Response.of(201,
                "{\"holdId\":\"h\",\"reservationProcessId\":\"p\",\"status\":\"CONFIRMED\",\"expiresAt\":\"2026-10-27T04:44:38Z\"}")))
                .isInstanceOf(CatedraResponse.Failed.class);
    }

    @Test
    void whatSurelyDidNotReachTheCatedraIsUnavailable() {
        assertThat(answerTo(FakeHttpServer.Response.of(503, "{}"))).isInstanceOf(CatedraResponse.Unavailable.class);

        FakeHttpServer closed = FakeHttpServer.start();
        String closedUrl = closed.baseUrl();
        closed.close();
        assertThat(adapter(closedUrl).createHold(201L, DATE, NOON, "42", TOKEN)).isInstanceOf(CatedraResponse.Unavailable.class);

        // Una dirección que no responde: vence el tiempo de conexión (en otras redes puede rechazarse al instante; las dos son no disponible)
        assertThat(adapter("http://10.255.255.1:81").createHold(201L, DATE, NOON, "42", TOKEN))
                .isInstanceOf(CatedraResponse.Unavailable.class);
    }

    @Test
    void whatMayHaveReachedTheCatedraIsUncertain() throws IOException {
        assertThat(answerTo(FakeHttpServer.Response.of(500, "{}"))).isInstanceOf(CatedraResponse.Uncertain.class);
        assertThat(answerTo(FakeHttpServer.Response.of(201, recording("hold-201-created.json")).after(Duration.ofMillis(800))))
                .isInstanceOf(CatedraResponse.Uncertain.class);

        // Un servidor que recibe el pedido y corta la conexión sin responder
        try (ServerSocket dropper = new ServerSocket(0)) {
            Thread.ofVirtual().start(() -> {
                try (Socket socket = dropper.accept()) {
                    socket.getInputStream().read(new byte[4096]);
                } catch (IOException ignored) {
                    // el test solo necesita que la conexión se corte
                }
            });
            assertThat(adapter("http://localhost:" + dropper.getLocalPort()).createHold(201L, DATE, NOON, "42", TOKEN))
                    .isInstanceOf(CatedraResponse.Uncertain.class);
        }
    }

    @Test
    void onlyAFailureToConnectProvesTheRequestWasNotSent() {
        assertThat(CatedraHoldClientAdapter.wasNotSent(new ResourceAccessException("x", new ConnectException()))).isTrue();
        assertThat(CatedraHoldClientAdapter.wasNotSent(
                new ResourceAccessException("x", new HttpConnectTimeoutException("HTTP connect timed out")))).isTrue();
        assertThat(CatedraHoldClientAdapter.wasNotSent(
                new ResourceAccessException("x", new HttpTimeoutException("Request cancelled")))).isFalse();
        assertThat(CatedraHoldClientAdapter.wasNotSent(
                new ResourceAccessException("x", new IOException("header parser received no bytes")))).isFalse();
    }

    @Test
    void theTokenNeverReachesTheOutput(CapturedOutput output) {
        catedra.respond(PATH, FakeHttpServer.Response.of(401, ""), FakeHttpServer.Response.of(500, "{}"),
                FakeHttpServer.Response.of(201, recording("hold-201-created.json")));
        CatedraHoldClientAdapter adapter = adapter();

        for (int i = 0; i < 3; i++) {
            adapter.createHold(201L, DATE, NOON, "42", TOKEN);
        }

        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    // La confirmación del hold de la grabación de la confirmación
    private static final String HOLD_ID = "hold-6798700e-87fe-4f94-8b2e-0a57054ff10d";
    private static final String CONFIRM_PATH = PATH + "/" + HOLD_ID + "/confirm";
    // Armados a mano: la cátedra real no los produjo (el vencido respondió HOLD_NOT_FOUND; el dueño es siempre el mismo)
    private static final String HOLD_EXPIRED = """
            {"detail":"Hold expired","status":404,"title":"Not Found","code":"HOLD_EXPIRED"}
            """;
    private static final String HOLD_OWNER_MISMATCH = """
            {"detail":"Hold owner mismatch","status":403,"title":"Forbidden","code":"HOLD_OWNER_MISMATCH"}
            """;

    @Test
    void theConfirmationIsRequestedForTheHoldWithThePatientAndTheTechnicalToken() {
        catedra.respond(CONFIRM_PATH, FakeHttpServer.Response.of(202, recording("confirm-202-accepted.json")));

        confirm();

        FakeHttpServer.ReceivedRequest request = catedra.receivedRequests().getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo(CONFIRM_PATH);
        assertThat(request.header("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(request.contentType()).startsWith("application/json");
        assertThat(JsonMapper.builder().build().readValue(request.body(), Map.class)).isEqualTo(Map.of(
                "externalPatientId", "42", "patientFirstName", "María", "patientLastName", "Gómez"));
    }

    @Test
    void theRecordedConfirmationKeepsTheProcessAndTheSameExpirationOfTheHold() {
        catedra.respond(CONFIRM_PATH, FakeHttpServer.Response.of(202, recording("confirm-202-accepted.json")));

        CatedraResponse<HoldConfirmationResult> response = confirm();

        HoldConfirmation confirmation = ((HoldConfirmationResult.Confirmed)
                ((CatedraResponse.Answered<HoldConfirmationResult>) response).content()).confirmation();
        assertThat(confirmation.getReservationProcessId()).isEqualTo("process-bfcb30eb-6e39-402f-bd92-95e73bfec54d");
        assertThat(confirmation.getExpiresAt()).isEqualTo(Instant.parse("2026-10-28T05:04:54.812004682Z"));
    }

    @Test
    void eachConfirmationRejectionIsADefinitiveAnswer() {
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(409, recording("confirm-409-already-confirmed.json"))))
                .isEqualTo(new CatedraResponse.Answered<>(new HoldConfirmationResult.AlreadyConfirmed()));
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(404, recording("confirm-404-hold-not-found.json"))))
                .isEqualTo(new CatedraResponse.Answered<>(new HoldConfirmationResult.HoldNotFound()));
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(404, recording("confirm-404-expired-hold-not-found.json"))))
                .isEqualTo(new CatedraResponse.Answered<>(new HoldConfirmationResult.HoldNotFound()));
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(400, recording("confirm-400-external-patient-mismatch.json"))))
                .isEqualTo(new CatedraResponse.Answered<>(new HoldConfirmationResult.PatientMismatch()));
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(404, HOLD_EXPIRED)))
                .isEqualTo(new CatedraResponse.Answered<>(new HoldConfirmationResult.HoldExpired()));
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(403, HOLD_OWNER_MISMATCH)))
                .isEqualTo(new CatedraResponse.Answered<>(new HoldConfirmationResult.OwnerMismatch()));
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(404, recording("hold-404-professional-not-found.json"))))
                .isEqualTo(new CatedraResponse.Answered<>(new HoldConfirmationResult.ProfessionalNotFound()));
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(400, recording("hold-400-invalid-slot.json"))))
                .isEqualTo(new CatedraResponse.Answered<>(new HoldConfirmationResult.InvalidSlot()));
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(409, SLOT_ALREADY_RESERVED)))
                .isEqualTo(new CatedraResponse.Answered<>(new HoldConfirmationResult.SlotNotAvailable()));
    }

    @Test
    void theRecordedConfirmationRejectionWithoutBodyIsATokenRejection() {
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(401, recording("confirm-401-invalid-token.json"))
                .withContentType(null))).isInstanceOf(CatedraResponse.TokenRejected.class);
    }

    @Test
    void aMissingStudentIntegrationWhileConfirmingIsAConfigurationError() {
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(403, MISSING_INTEGRATION)))
                .isInstanceOf(CatedraResponse.ConfigurationError.class);
    }

    @Test
    void unknownConfirmationCodesAndOtherStatusesAreUnexpectedAnswers() {
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(409, recording("hold-409-slot-already-held.json"))))
                .isInstanceOf(CatedraResponse.Failed.class);
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(400, "{\"status\":400,\"code\":\"VALIDATION_ERROR\"}")))
                .isInstanceOf(CatedraResponse.Failed.class);
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(502, "{}"))).isInstanceOf(CatedraResponse.Failed.class);
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(200, "{}"))).isInstanceOf(CatedraResponse.Failed.class);
    }

    @Test
    void anIncompleteOrUnreadableConfirmationIsAnUnexpectedAnswer() {
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(202, "not json"))).isInstanceOf(CatedraResponse.Failed.class);
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(202,
                "{\"reservationProcessId\":\"p\",\"status\":\"WAITING_FOR_PHONE\"}"))).isInstanceOf(CatedraResponse.Failed.class);
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(202,
                "{\"reservationProcessId\":\"p\",\"status\":\"HELD\",\"expiresAt\":\"2026-10-28T05:04:54Z\"}")))
                .isInstanceOf(CatedraResponse.Failed.class);
    }

    // Repetir una confirmación es seguro: también lo que pudo haber llegado se informa como no disponible
    @Test
    void everyFailureWhileConfirmingIsUnavailable() throws IOException {
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(503, "{}"))).isInstanceOf(CatedraResponse.Unavailable.class);
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(500, "{}"))).isInstanceOf(CatedraResponse.Unavailable.class);
        assertThat(confirmationAnswerTo(FakeHttpServer.Response.of(202, recording("confirm-202-accepted.json"))
                .after(Duration.ofMillis(800)))).isInstanceOf(CatedraResponse.Unavailable.class);

        FakeHttpServer closed = FakeHttpServer.start();
        String closedUrl = closed.baseUrl();
        closed.close();
        assertThat(adapter(closedUrl).confirmHold(HOLD_ID, "42", "María", "Gómez", TOKEN))
                .isInstanceOf(CatedraResponse.Unavailable.class);

        try (ServerSocket dropper = new ServerSocket(0)) {
            Thread.ofVirtual().start(() -> {
                try (Socket socket = dropper.accept()) {
                    socket.getInputStream().read(new byte[4096]);
                } catch (IOException ignored) {
                    // el test solo necesita que la conexión se corte
                }
            });
            assertThat(adapter("http://localhost:" + dropper.getLocalPort()).confirmHold(HOLD_ID, "42", "María", "Gómez", TOKEN))
                    .isInstanceOf(CatedraResponse.Unavailable.class);
        }
    }

    // El holdId lo asigna la cátedra: si trajera caracteres especiales, se codifican y no cambian el pedido.
    // El servidor simulado muestra la ruta ya decodificada: que el signo quede en la ruta y no en la consulta lo prueba
    @Test
    void theHoldIdIsSentEncodedInThePath() {
        catedra.respondToAnyPath(FakeHttpServer.Response.of(404, recording("confirm-404-hold-not-found.json")));

        adapter().confirmHold("hold-1?x=2", "42", "María", "Gómez", TOKEN);

        FakeHttpServer.ReceivedRequest request = catedra.receivedRequests().getFirst();
        assertThat(request.path()).isEqualTo(PATH + "/hold-1?x=2/confirm");
        assertThat(request.query()).isNull();
    }

    @Test
    void theTokenNeverReachesTheOutputWhileConfirming(CapturedOutput output) {
        catedra.respond(CONFIRM_PATH, FakeHttpServer.Response.of(401, ""), FakeHttpServer.Response.of(500, "{}"),
                FakeHttpServer.Response.of(202, recording("confirm-202-accepted.json")));
        CatedraHoldClientAdapter adapter = adapter();

        for (int i = 0; i < 3; i++) {
            adapter.confirmHold(HOLD_ID, "42", "María", "Gómez", TOKEN);
        }

        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    private CatedraResponse<HoldConfirmationResult> confirm() {
        return adapter().confirmHold(HOLD_ID, "42", "María", "Gómez", TOKEN);
    }

    private CatedraResponse<HoldConfirmationResult> confirmationAnswerTo(FakeHttpServer.Response response) {
        catedra.respond(CONFIRM_PATH, response);
        return confirm();
    }

    private CatedraResponse<HoldResult> answerTo(FakeHttpServer.Response response) {
        catedra.respond(PATH, response);
        return adapter().createHold(201L, DATE, NOON, "42", TOKEN);
    }

    private CatedraHoldClientAdapter adapter() {
        return adapter(catedra.baseUrl());
    }

    // El timeout de conexión es más corto que el de respuesta, como en los valores por defecto
    private static CatedraHoldClientAdapter adapter(String baseUrl) {
        CatedraProperties properties = new CatedraProperties(baseUrl, "user", "pass", Duration.ofSeconds(5),
                Duration.ofSeconds(10), new CatedraProperties.Login(5, Duration.ofSeconds(1), 2, Duration.ofSeconds(30),
                Duration.ofSeconds(60)),
                new CatedraProperties.Operations(Duration.ofMillis(200), Duration.ofMillis(500), 3, Duration.ofMillis(5), 2));
        return new CatedraHoldClientAdapter(properties, new HoldMapper());
    }

    private static String recording(String name) {
        try (InputStream input = CatedraHoldClientAdapterTest.class.getResourceAsStream("/catedra/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
