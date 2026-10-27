package turnos.catedra.infrastructure.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.FakeHttpServer;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.Occupancy;
import turnos.catedra.domain.model.OccupancyStatus;
import turnos.catedra.infrastructure.client.mapper.OccupancyMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CatedraOccupancyClientAdapterTest {

    private static final String TOKEN = "eyJ.jwt-tecnico.firma";
    private static final String PATH = "/api/appointment-occupancies";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 26);
    // Armada a mano: hace falta una reserva confirmada para grabar una ocupación CONFIRMED real. La hora sin segundos
    // comprueba que se aceptan las dos formas
    private static final String CONFIRMED_OCCUPANCY = """
            {"professionalId":201,"from":"2026-11-02","to":"2026-11-02","occupancies":[
              {"date":"2026-11-02","startTime":"12:30","status":"CONFIRMED","expiresAt":null}]}
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
    void oneDayIsQueriedWithTheTechnicalToken() {
        catedra.respond(PATH, FakeHttpServer.Response.of(200, recording("occupancies-200-empty.json")));

        adapter().fetchOccupancies(201L, DATE, TOKEN);

        FakeHttpServer.ReceivedRequest request = catedra.receivedRequests().getFirst();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo(PATH);
        assertThat(request.query()).isEqualTo("professionalId=201&from=2026-10-26&to=2026-10-26");
        assertThat(request.header("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void theRecordedEmptyListsAreFoundWithoutOccupancies() {
        catedra.respond(PATH, FakeHttpServer.Response.of(200, recording("occupancies-200-empty.json")),
                FakeHttpServer.Response.of(200, recording("occupancies-200-unknown-professional.json")));

        assertThat(adapter().fetchOccupancies(201L, DATE, TOKEN)).isEqualTo(new CatedraResponse.Answered<>(List.of()));
        assertThat(adapter().fetchOccupancies(999999L, DATE, TOKEN)).isEqualTo(new CatedraResponse.Answered<>(List.of()));
    }

    @Test
    void theRecordedHeldOccupancyIsReadWithItsTimeAndStatus() {
        catedra.respond(PATH, FakeHttpServer.Response.of(200, recording("occupancies-200-held.json")));

        List<Occupancy> occupancies = ((CatedraResponse.Answered<List<Occupancy>>) adapter().fetchOccupancies(201L, DATE, TOKEN)).content();

        assertThat(occupancies).extracting(Occupancy::getStartTime, Occupancy::getStatus).containsExactly(
                org.assertj.core.groups.Tuple.tuple(LocalTime.of(12, 0), OccupancyStatus.HELD));
    }

    @Test
    void aConfirmedOccupancyIsReadWithItsTimeAndStatus() {
        catedra.respond(PATH, FakeHttpServer.Response.of(200, CONFIRMED_OCCUPANCY));

        List<Occupancy> occupancies = ((CatedraResponse.Answered<List<Occupancy>>) adapter().fetchOccupancies(201L, DATE, TOKEN)).content();

        assertThat(occupancies).extracting(Occupancy::getStartTime, Occupancy::getStatus).containsExactly(
                org.assertj.core.groups.Tuple.tuple(LocalTime.of(12, 30), OccupancyStatus.CONFIRMED));
    }

    @Test
    void theRecordedRejectionWithoutBodyIsATokenRejection() {
        catedra.respond(PATH, FakeHttpServer.Response.of(401, recording("occupancies-401-invalid-token.json")).withContentType(null));

        assertThat(adapter().fetchOccupancies(201L, DATE, TOKEN)).isInstanceOf(CatedraResponse.TokenRejected.class);
    }

    @Test
    void aMissingStudentIntegrationIsAConfigurationError() {
        catedra.respond(PATH, FakeHttpServer.Response.of(403, MISSING_INTEGRATION));

        assertThat(adapter().fetchOccupancies(201L, DATE, TOKEN)).isInstanceOf(CatedraResponse.ConfigurationError.class);
    }

    @Test
    void anyOtherForbiddenAnswerIsAFailure() {
        catedra.respond(PATH, FakeHttpServer.Response.of(403, "{\"status\":403,\"code\":\"OTHER\"}"),
                FakeHttpServer.Response.of(403, ""));

        assertThat(adapter().fetchOccupancies(201L, DATE, TOKEN)).isInstanceOf(CatedraResponse.Failed.class);
        assertThat(adapter().fetchOccupancies(201L, DATE, TOKEN)).isInstanceOf(CatedraResponse.Failed.class);
    }

    @Test
    void theRecordedBadRequestIsAnUnexpectedAnswer() {
        catedra.respond(PATH, FakeHttpServer.Response.of(400, recording("occupancies-400-invalid-date-range.json")));

        assertThat(adapter().fetchOccupancies(201L, DATE, TOKEN)).isInstanceOf(CatedraResponse.Failed.class);
    }

    @Test
    void serverErrorsASlowAnswerAndAClosedPortAreUnavailable() {
        catedra.respond(PATH, FakeHttpServer.Response.of(503, "{}"), FakeHttpServer.Response.of(500, "{}"),
                FakeHttpServer.Response.of(200, recording("occupancies-200-held.json")).after(Duration.ofMillis(800)));
        CatedraOccupancyClientAdapter adapter = adapter();

        assertThat(adapter.fetchOccupancies(201L, DATE, TOKEN)).isInstanceOf(CatedraResponse.Unavailable.class);
        assertThat(adapter.fetchOccupancies(201L, DATE, TOKEN)).isInstanceOf(CatedraResponse.Unavailable.class);
        assertThat(adapter.fetchOccupancies(201L, DATE, TOKEN)).isInstanceOf(CatedraResponse.Unavailable.class);

        FakeHttpServer closed = FakeHttpServer.start();
        String closedUrl = closed.baseUrl();
        closed.close();
        assertThat(adapter(closedUrl).fetchOccupancies(201L, DATE, TOKEN)).isInstanceOf(CatedraResponse.Unavailable.class);
    }

    @Test
    void otherServerErrorsAreUnexpectedAnswers() {
        catedra.respond(PATH, FakeHttpServer.Response.of(502, "{}"));

        assertThat(adapter().fetchOccupancies(201L, DATE, TOKEN)).isInstanceOf(CatedraResponse.Failed.class);
    }

    @Test
    void unreadableOrIncompleteAnswersAreFailures() {
        catedra.respond(PATH, FakeHttpServer.Response.of(200, "not json"), FakeHttpServer.Response.of(200, "{}"),
                FakeHttpServer.Response.of(200, "{\"occupancies\":[{\"startTime\":\"12:00\",\"status\":\"CANCELLED\"}]}"),
                FakeHttpServer.Response.of(200, "{\"occupancies\":[{\"startTime\":\"12:00\"}]}"));
        CatedraOccupancyClientAdapter adapter = adapter();

        for (int i = 0; i < 4; i++) {
            assertThat(adapter.fetchOccupancies(201L, DATE, TOKEN)).isInstanceOf(CatedraResponse.Failed.class);
        }
    }

    @Test
    void theTokenNeverReachesTheOutput(CapturedOutput output) {
        catedra.respond(PATH, FakeHttpServer.Response.of(401, ""), FakeHttpServer.Response.of(503, "{}"),
                FakeHttpServer.Response.of(200, recording("occupancies-200-held.json")));
        CatedraOccupancyClientAdapter adapter = adapter();

        for (int i = 0; i < 3; i++) {
            adapter.fetchOccupancies(201L, DATE, TOKEN);
        }

        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    private CatedraOccupancyClientAdapter adapter() {
        return adapter(catedra.baseUrl());
    }

    private static CatedraOccupancyClientAdapter adapter(String baseUrl) {
        CatedraProperties properties = new CatedraProperties(baseUrl, "user", "pass", Duration.ofSeconds(5),
                Duration.ofSeconds(10), new CatedraProperties.Login(5, Duration.ofSeconds(1), 2, Duration.ofSeconds(30),
                Duration.ofSeconds(60)),
                new CatedraProperties.Operations(Duration.ofMillis(500), Duration.ofMillis(500), 3, Duration.ofMillis(5), 2));
        return new CatedraOccupancyClientAdapter(properties, new OccupancyMapper());
    }

    private static String recording(String name) {
        try (InputStream input = CatedraOccupancyClientAdapterTest.class.getResourceAsStream("/catedra/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
