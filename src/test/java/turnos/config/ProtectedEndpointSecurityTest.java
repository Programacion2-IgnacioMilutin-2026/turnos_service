package turnos.config;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import turnos.TestRsaKeys;
import turnos.TestSecurityConfiguration;
import turnos.TestTokens;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@WebMvcTest(ProbeController.class)
@Import({TestSecurityConfiguration.class, ProbeController.class})
class ProtectedEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProbeController probeController;

    @BeforeEach
    void resetCalls() {
        probeController.reset();
    }

    @Test
    void aRequestWithoutTokenIsRejectedBeforeReachingTheEndpoint() throws Exception {
        MockHttpServletResponse response = perform(get("/api/probe"));

        assertUnauthorized(response);
        assertThat(probeController.calls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic abc", "Bearer", "Bearer ", "Bearer xxx", "xxx"})
    void aMalformedAuthorizationHeaderIsRejected(String header) throws Exception {
        assertUnauthorized(perform(get("/api/probe").header(HttpHeaders.AUTHORIZATION, header)));
        assertThat(probeController.calls()).isZero();
    }

    @Test
    void invalidTokensAreRejected() throws Exception {
        for (String token : invalidTokens()) {
            assertUnauthorized(withToken(token));
        }
        assertThat(probeController.calls()).isZero();
    }

    @Test
    void everyRejectionForAMissingOrInvalidTokenHasTheSameBody() throws Exception {
        String withoutToken = perform(get("/api/probe")).getContentAsString();

        assertThat(perform(get("/api/probe").header(HttpHeaders.AUTHORIZATION, "Bearer xxx")).getContentAsString())
                .isEqualTo(withoutToken);
        for (String token : invalidTokens()) {
            assertThat(withToken(token).getContentAsString()).isEqualTo(withoutToken);
        }
    }

    @Test
    void theRejectionHasTheFormatOfTheOtherErrorsWithoutCode() throws Exception {
        String body = perform(get("/api/probe")).getContentAsString();

        Map<String, Object> problem = JsonPath.read(body, "$");
        assertThat(problem).containsOnlyKeys("title", "status", "detail", "instance");
        assertThat(problem).containsEntry("title", "Unauthorized").containsEntry("status", 401)
                .containsEntry("instance", "/api/probe");
    }

    @Test
    void usersWithAnyRoleReachTheEndpoint() throws Exception {
        assertThat(withToken(TestTokens.forUser("42", "ROLE_USER")).getStatus()).isEqualTo(200);
        assertThat(withToken(TestTokens.forUser("7", "ROLE_ADMIN")).getStatus()).isEqualTo(200);
        assertThat(probeController.calls()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ROLE_OTRO"})
    void aTokenWithoutAKnownRoleIsForbidden(String authorities) throws Exception {
        assertForbidden(withToken(TestTokens.forUser("42", authorities)));
        assertThat(probeController.calls()).isZero();
    }

    @Test
    void aTokenWithoutTheRolesClaimIsForbidden() throws Exception {
        Instant now = Instant.now();
        String token = TestTokens.signed(claims -> claims.subject("42").issuedAt(now).expiresAt(now.plusSeconds(3600)));

        assertForbidden(withToken(token));
    }

    @Test
    void everyForbiddenResponseHasTheSameBody() throws Exception {
        String emptyRoles = withToken(TestTokens.forUser("42", "")).getContentAsString();

        assertThat(withToken(TestTokens.forUser("7", "ROLE_OTRO")).getContentAsString()).isEqualTo(emptyRoles);
        Map<String, Object> problem = JsonPath.read(emptyRoles, "$");
        assertThat(problem).containsOnlyKeys("title", "status", "detail", "instance");
        assertThat(problem).containsEntry("title", "Forbidden").containsEntry("status", 403);
    }

    @Test
    void theIdentityComesFromTheTokenAndNotFromTheRequest() throws Exception {
        String token = TestTokens.forUser("42", "ROLE_USER");

        MockHttpServletResponse response = perform(get("/api/probe").param("userId", "99")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(response.getContentAsString(), "$.userId")).isEqualTo(42);
        assertThat(JsonPath.<String>read(response.getContentAsString(), "$.token")).isEqualTo(token);
    }

    // Ningún usuario tiene este id: la validación no consulta la base
    @Test
    void aValidTokenOfAnUnknownUserReachesTheEndpoint() throws Exception {
        MockHttpServletResponse response = withToken(TestTokens.forUser("987654321", "ROLE_USER"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Integer>read(response.getContentAsString(), "$.userId")).isEqualTo(987654321);
    }

    @Test
    void rejectionsNeverContainTheToken() throws Exception {
        for (String token : invalidTokens()) {
            MockHttpServletResponse response = withToken(token);
            assertThat(response.getContentAsString()).doesNotContain(token);
            assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).doesNotContain(token);
        }
        String forbidden = TestTokens.forUser("42", "ROLE_OTRO");
        assertThat(withToken(forbidden).getContentAsString()).doesNotContain(forbidden);
    }

    private List<String> invalidTokens() {
        Instant now = Instant.now();
        String valid = TestTokens.forUser("42", "ROLE_USER");
        return List.of(
                TestTokens.withPayload(valid, "{\"sub\":\"7\",\"auth\":\"ROLE_ADMIN\",\"exp\":" + now.plusSeconds(3600).getEpochSecond() + "}"),
                TestTokens.signed(claims -> claims.subject("42").claim("auth", "ROLE_USER")
                        .issuedAt(now.minusSeconds(3600)).expiresAt(now.minusSeconds(1))),
                TestTokens.signed(claims -> claims.subject("42").claim("auth", "ROLE_USER").issuedAt(now)),
                TestTokens.signed(claims -> claims.claim("auth", "ROLE_USER").issuedAt(now).expiresAt(now.plusSeconds(3600))),
                TestTokens.forUser("abc", "ROLE_USER"),
                TestTokens.signedWith(TestRsaKeys.generate(2048), claims -> claims.subject("42").claim("auth", "ROLE_USER")
                        .issuedAt(now).expiresAt(now.plusSeconds(3600))),
                TestTokens.signedWithSecret(claims -> claims.subject("42").claim("auth", "ROLE_USER")
                        .issuedAt(now).expiresAt(now.plusSeconds(3600))),
                TestTokens.unsigned("{\"sub\":\"42\",\"auth\":\"ROLE_USER\",\"exp\":" + now.plusSeconds(3600).getEpochSecond() + "}"));
    }

    private MockHttpServletResponse withToken(String token) throws Exception {
        return perform(get("/api/probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private void assertUnauthorized(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(response.getContentAsString()).doesNotContain("code");
    }

    private void assertForbidden(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).doesNotContain("code");
    }
}
