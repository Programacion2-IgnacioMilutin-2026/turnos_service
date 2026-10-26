package turnos.availability;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import turnos.FakeHttpServer;
import turnos.TestcontainersConfiguration;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

// Sin una URL válida de catálogo el servicio arranca, avisa una vez y la consulta de turnos responde 503 sin enviar
// ningún request; el registro y el login siguen funcionando
class FreeSlotsWithoutCatalogIntegrationTest {

    private static final FakeHttpServer CATALOG = FakeHttpServer.start();
    private static final String REGISTER_BODY = """
            {"login":"juan.perez","password":"secreta123","firstName":"Juan","lastName":"Pérez",
             "email":"juan.perez@example.com","langKey":"es"}
            """;

    @AfterAll
    static void stopCatalog() {
        CATALOG.close();
    }

    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureRestTestClient
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(OutputCaptureExtension.class)
    abstract static class Scenario {

        @Autowired
        private RestTestClient restTestClient;
        @Autowired
        private JdbcTemplate jdbcTemplate;

        abstract String expectedWarning();

        @Test
        void theServiceStartsWarnsAndAnswersCatalogUnavailable(CapturedOutput output) {
            jdbcTemplate.update("DELETE FROM user_authorities");
            jdbcTemplate.update("DELETE FROM users");
            restTestClient.post().uri("/api/register").contentType(MediaType.APPLICATION_JSON).body(REGISTER_BODY)
                    .exchange().expectStatus().isCreated();
            String body = restTestClient.post().uri("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                    .body("{\"username\":\"juan.perez\",\"password\":\"secreta123\"}")
                    .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
            String token = com.jayway.jsonpath.JsonPath.read(body, "$.id_token");
            LocalDate tomorrow = LocalDate.now(ZoneId.of("America/Argentina/Mendoza")).plusDays(1);

            restTestClient.get().uri("/api/free-slots?professionalId=201&date={date}", tomorrow)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                    .expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("CATALOG_UNAVAILABLE");

            assertThat(output.getAll()).contains("WARN").contains(expectedWarning());
            assertThat(CATALOG.receivedRequests()).isEmpty();
        }
    }

    @Nested
    class MissingUrl extends Scenario {

        @DynamicPropertySource
        static void catalogUrl(DynamicPropertyRegistry registry) {
            registry.add("TURNOS_CATALOG_BASE_URL", () -> "");
        }

        @Override
        String expectedWarning() {
            return "Catalog client disabled: missing configuration property turnos.catalog.base-url";
        }
    }

    @Nested
    class QuotedUrl extends Scenario {

        @DynamicPropertySource
        static void catalogUrl(DynamicPropertyRegistry registry) {
            registry.add("TURNOS_CATALOG_BASE_URL", () -> "\"" + CATALOG.baseUrl() + "\"");
        }

        @Override
        String expectedWarning() {
            return "turnos.catalog.base-url is not a valid http or https URL (remove the quotes from the .env value)";
        }
    }
}
