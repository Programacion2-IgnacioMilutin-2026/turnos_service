package turnos.catalog.infrastructure.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.FakeHttpServer;
import turnos.catalog.domain.model.Professional;
import turnos.catalog.domain.model.ProfessionalResult;
import turnos.catalog.domain.model.WeeklySchedule;
import turnos.catalog.infrastructure.client.mapper.ProfessionalMapper;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CatalogClientAdapterTest {

    private static final String USER_TOKEN = "eyJ.token-del-usuario.firma";
    private static final String PATH = "/api/professionals/15";
    // Un profesional como el que devuelve catálogo: una hora con segundos y otra sin ellos
    private static final String PROFESSIONAL = """
            {"id":15,"firstName":"Ana","lastName":"Gomez","enabled":true,
             "category":{"id":1,"name":"Clinica medica","enabled":true},
             "weeklySchedules":[
               {"id":21,"dayOfWeek":"MONDAY","startTime":"09:00:00","endTime":"12:00:00","slotDurationMinutes":30,"enabled":true},
               {"id":22,"dayOfWeek":"FRIDAY","startTime":"14:00","endTime":"16:30","slotDurationMinutes":20,"enabled":false}]}
            """;
    private static final FakeHttpServer.Response UNAVAILABLE =
            FakeHttpServer.Response.of(503, "{\"status\":503,\"code\":\"CATALOG_NOT_INITIALIZED\"}");

    private final FakeHttpServer catalog = FakeHttpServer.start();

    @AfterEach
    void stop() {
        catalog.close();
    }

    @Test
    void theUserTokenIsForwardedToTheProfessionalRoute() {
        catalog.respond(PATH, FakeHttpServer.Response.of(200, PROFESSIONAL));

        adapter(catalog.baseUrl()).fetchProfessional(15L, USER_TOKEN);

        FakeHttpServer.ReceivedRequest request = catalog.receivedRequests().getFirst();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo(PATH);
        assertThat(request.header("Authorization")).isEqualTo("Bearer " + USER_TOKEN);
    }

    @Test
    void aProfessionalIsReadWithItsSchedules() {
        catalog.respond(PATH, FakeHttpServer.Response.of(200, PROFESSIONAL));

        ProfessionalResult result = adapter(catalog.baseUrl()).fetchProfessional(15L, USER_TOKEN);

        Professional professional = ((ProfessionalResult.Found) result).professional();
        assertThat(professional.getId()).isEqualTo(15L);
        assertThat(professional.isEnabled()).isTrue();
        WeeklySchedule monday = professional.getWeeklySchedules().get(0);
        assertThat(monday.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(monday.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(monday.getEndTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(monday.getSlotDurationMinutes()).isEqualTo(30);
        assertThat(monday.isEnabled()).isTrue();
        WeeklySchedule friday = professional.getWeeklySchedules().get(1);
        assertThat(friday.getStartTime()).isEqualTo(LocalTime.of(14, 0));
        assertThat(friday.getEndTime()).isEqualTo(LocalTime.of(16, 30));
        assertThat(friday.isEnabled()).isFalse();
    }

    @Test
    void aProfessionalWithoutSchedulesHasAnEmptyList() {
        catalog.respond(PATH, FakeHttpServer.Response.of(200, "{\"id\":15,\"enabled\":true,\"weeklySchedules\":[]}"));

        ProfessionalResult result = adapter(catalog.baseUrl()).fetchProfessional(15L, USER_TOKEN);

        assertThat(((ProfessionalResult.Found) result).professional().getWeeklySchedules()).isEmpty();
    }

    @Test
    void anUnavailableCatalogIsRetriedUpToTheMaximum() {
        catalog.respond(PATH, UNAVAILABLE);

        ProfessionalResult result = adapter(catalog.baseUrl()).fetchProfessional(15L, USER_TOKEN);

        assertThat(result).isInstanceOf(ProfessionalResult.Unavailable.class);
        assertThat(catalog.receivedRequests(PATH)).hasSize(3);
    }

    @Test
    void aLaterSuccessfulAttemptEndsTheRetries() {
        catalog.respond(PATH, UNAVAILABLE, FakeHttpServer.Response.of(200, PROFESSIONAL));

        ProfessionalResult result = adapter(catalog.baseUrl()).fetchProfessional(15L, USER_TOKEN);

        assertThat(result).isInstanceOf(ProfessionalResult.Found.class);
        assertThat(catalog.receivedRequests(PATH)).hasSize(2);
    }

    @Test
    void aSlowAnswerAndAClosedPortAreRetriedAndEndUnavailable() {
        catalog.respond(PATH, FakeHttpServer.Response.of(200, PROFESSIONAL).after(Duration.ofMillis(800)));
        assertThat(adapter(catalog.baseUrl()).fetchProfessional(15L, USER_TOKEN)).isInstanceOf(ProfessionalResult.Unavailable.class);
        assertThat(catalog.receivedRequests(PATH)).hasSize(3);

        FakeHttpServer closed = FakeHttpServer.start();
        String closedUrl = closed.baseUrl();
        closed.close();
        assertThat(adapter(closedUrl).fetchProfessional(15L, USER_TOKEN)).isInstanceOf(ProfessionalResult.Unavailable.class);
    }

    @Test
    void aRejectedTokenIsReportedAsRejectedWithoutRetrying() {
        catalog.respond(PATH, FakeHttpServer.Response.of(401, ""), FakeHttpServer.Response.of(403, ""));

        assertThat(adapter(catalog.baseUrl()).fetchProfessional(15L, USER_TOKEN)).isEqualTo(new ProfessionalResult.Rejected(401));
        assertThat(adapter(catalog.baseUrl()).fetchProfessional(15L, USER_TOKEN)).isEqualTo(new ProfessionalResult.Rejected(403));
        assertThat(catalog.receivedRequests(PATH)).hasSize(2);
    }

    @Test
    void anUnknownProfessionalIsReportedAsNotFoundWithoutRetrying() {
        catalog.respond(PATH, FakeHttpServer.Response.of(404, "{\"status\":404,\"code\":\"PROFESSIONAL_NOT_FOUND\"}"));

        assertThat(adapter(catalog.baseUrl()).fetchProfessional(15L, USER_TOKEN)).isInstanceOf(ProfessionalResult.NotFound.class);
        assertThat(catalog.receivedRequests(PATH)).hasSize(1);
    }

    @Test
    void anyOtherAnswerIsAFailureWithoutRetrying() {
        catalog.respond(PATH, FakeHttpServer.Response.of(400, "{\"status\":400}"), FakeHttpServer.Response.of(500, "{\"status\":500}"));

        assertThat(adapter(catalog.baseUrl()).fetchProfessional(15L, USER_TOKEN)).isInstanceOf(ProfessionalResult.Failed.class);
        assertThat(adapter(catalog.baseUrl()).fetchProfessional(15L, USER_TOKEN)).isInstanceOf(ProfessionalResult.Failed.class);
        assertThat(catalog.receivedRequests(PATH)).hasSize(2);
    }

    @Test
    void anUnreadableProfessionalIsAFailure() {
        catalog.respond(PATH, FakeHttpServer.Response.of(200, "not json"), FakeHttpServer.Response.of(200, "{}"));

        assertThat(adapter(catalog.baseUrl()).fetchProfessional(15L, USER_TOKEN)).isInstanceOf(ProfessionalResult.Failed.class);
        assertThat(adapter(catalog.baseUrl()).fetchProfessional(15L, USER_TOKEN)).isInstanceOf(ProfessionalResult.Failed.class);
    }

    // Un error propio (acá, al traducir una respuesta válida) no es un catálogo caído: no se reintenta
    @Test
    void anUnexpectedErrorIsAFailureWithoutRetrying() {
        catalog.respond(PATH, FakeHttpServer.Response.of(200, PROFESSIONAL));
        ProfessionalMapper brokenMapper = new ProfessionalMapper() {
            @Override
            public Professional toDomain(turnos.catalog.infrastructure.client.dto.ProfessionalResponse response) {
                throw new IllegalStateException("broken mapper");
            }
        };

        ProfessionalResult result = new CatalogClientAdapter(properties(catalog.baseUrl()), brokenMapper)
                .fetchProfessional(15L, USER_TOKEN);

        assertThat(result).isInstanceOf(ProfessionalResult.Failed.class);
        assertThat(catalog.receivedRequests(PATH)).hasSize(1);
    }

    @Test
    void eachFailedAttemptIsLoggedWithoutTheUserToken(CapturedOutput output) {
        catalog.respond(PATH, UNAVAILABLE);

        adapter(catalog.baseUrl()).fetchProfessional(15L, USER_TOKEN);

        assertThat(output.getAll())
                .contains("Catalog request for professional 15 attempt 1/3 failed: Catalog answered HTTP 503")
                .contains("attempt 3/3 failed")
                .doesNotContain(USER_TOKEN);
    }

    @Test
    void withoutABaseUrlNothingIsSentAndAWarningNamesTheProperty(CapturedOutput output) {
        assertThat(adapter("").fetchProfessional(15L, USER_TOKEN)).isInstanceOf(ProfessionalResult.Unavailable.class);
        assertThat(adapter(null).fetchProfessional(15L, USER_TOKEN)).isInstanceOf(ProfessionalResult.Unavailable.class);

        assertThat(output.getAll()).contains("WARN").contains("missing configuration property turnos.catalog.base-url");
    }

    @Test
    void anInvalidBaseUrlIsTreatedAsMissing(CapturedOutput output) {
        CatalogClientAdapter quoted = adapter("\"" + catalog.baseUrl() + "\"");
        CatalogClientAdapter withoutScheme = adapter("localhost:8081");
        CatalogClientAdapter otherScheme = adapter("ftp://localhost:8081");

        assertThat(quoted.fetchProfessional(15L, USER_TOKEN)).isInstanceOf(ProfessionalResult.Unavailable.class);
        assertThat(withoutScheme.fetchProfessional(15L, USER_TOKEN)).isInstanceOf(ProfessionalResult.Unavailable.class);
        assertThat(otherScheme.fetchProfessional(15L, USER_TOKEN)).isInstanceOf(ProfessionalResult.Unavailable.class);
        assertThat(catalog.receivedRequests()).isEmpty();
        assertThat(output.getAll()).contains("turnos.catalog.base-url is not a valid http or https URL (remove the quotes from the .env value)");
    }

    @Test
    void theUserTokenNeverReachesTheOutput(CapturedOutput output) {
        catalog.respond(PATH, FakeHttpServer.Response.of(401, ""), FakeHttpServer.Response.of(500, "{}"), FakeHttpServer.Response.of(200, PROFESSIONAL));
        CatalogClientAdapter adapter = adapter(catalog.baseUrl());

        adapter.fetchProfessional(15L, USER_TOKEN);
        adapter.fetchProfessional(15L, USER_TOKEN);
        adapter.fetchProfessional(15L, USER_TOKEN);

        assertThat(output.getAll()).doesNotContain(USER_TOKEN);
    }

    private CatalogClientAdapter adapter(String baseUrl) {
        return new CatalogClientAdapter(properties(baseUrl), new ProfessionalMapper());
    }

    private CatalogProperties properties(String baseUrl) {
        return new CatalogProperties(baseUrl, Duration.ofMillis(500), Duration.ofMillis(500), 3, Duration.ofMillis(5), 2);
    }
}
