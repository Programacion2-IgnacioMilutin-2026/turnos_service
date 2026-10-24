package turnos.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import turnos.TestSecurityConfiguration;
import turnos.TestTokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

@WebMvcTest(ProbeController.class)
@Import({TestSecurityConfiguration.class, ProbeController.class})
class CorsSecurityTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";
    private static final String OTHER_ORIGIN = "https://otro-sitio.example.com";

    @Autowired
    private MockMvc mockMvc;

    @Nested
    class WithoutConfiguredOrigins {

        @Test
        void aPreflightToAProtectedEndpointIsRejected() throws Exception {
            assertRejected(perform(preflight("/api/probe", ALLOWED_ORIGIN, "GET")));
        }

        @Test
        void aPreflightToAPublicEndpointIsRejected() throws Exception {
            assertRejected(perform(preflight("/api/register", ALLOWED_ORIGIN, "POST")));
        }

        @Test
        void requestsWithoutOriginBehaveAsUsual() throws Exception {
            assertRequestsWithoutOriginBehaveAsUsual();
        }
    }

    @Nested
    @TestPropertySource(properties = "turnos.cors.allowed-origins=" + ALLOWED_ORIGIN)
    class WithAnAllowedOrigin {

        @Test
        void aPreflightFromTheAllowedOriginIsAnsweredWithoutAToken() throws Exception {
            MockHttpServletResponse response = perform(preflight("/api/probe", ALLOWED_ORIGIN, "GET"));

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN);
            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)).containsIgnoringCase("authorization");
            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).contains("GET");
            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
        }

        @Test
        void aPreflightToAPublicEndpointFromTheAllowedOriginIsAnswered() throws Exception {
            MockHttpServletResponse response = perform(preflight("/api/register", ALLOWED_ORIGIN, "POST"));

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN);
        }

        // Sin los encabezados CORS, el navegador no dejaría leer el 401 y la app web no sabría que debe iniciar sesión
        @Test
        void aRequestWithoutTokenFromTheAllowedOriginGetsTheUnauthorizedAnswerWithCorsHeaders() throws Exception {
            MockHttpServletResponse response = perform(get("/api/probe").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN));

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN);
            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)).contains(HttpHeaders.RETRY_AFTER);
        }

        @Test
        void aRequestWithAValidTokenFromTheAllowedOriginReachesTheEndpoint() throws Exception {
            MockHttpServletResponse response = perform(get("/api/probe")
                    .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forUser("42", "ROLE_USER")));

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED_ORIGIN);
        }

        @Test
        void aPreflightFromAnotherOriginIsRejected() throws Exception {
            assertRejected(perform(preflight("/api/probe", OTHER_ORIGIN, "GET")));
        }

        @Test
        void requestsWithoutOriginBehaveAsUsual() throws Exception {
            assertRequestsWithoutOriginBehaveAsUsual();
        }
    }

    // Lo que hace la app nativa: sin encabezado Origin, CORS no interviene
    private void assertRequestsWithoutOriginBehaveAsUsual() throws Exception {
        MockHttpServletResponse withoutToken = perform(get("/api/probe"));
        MockHttpServletResponse withToken = perform(get("/api/probe")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forUser("42", "ROLE_USER")));

        assertThat(withoutToken.getStatus()).isEqualTo(401);
        assertThat(withToken.getStatus()).isEqualTo(200);
        assertThat(withoutToken.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        assertThat(withToken.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    private MockHttpServletRequestBuilder preflight(String path, String origin, String method) {
        return options(path)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type");
    }

    private void assertRejected(MockHttpServletResponse response) {
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }
}
