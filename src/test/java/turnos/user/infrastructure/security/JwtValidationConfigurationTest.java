package turnos.user.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import turnos.TestRsaKeys;
import turnos.TestTokens;
import turnos.user.domain.model.Authority;
import turnos.user.domain.model.User;

import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtValidationConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    private final JwtValidationConfiguration configuration = new JwtValidationConfiguration();
    private final JwtSigningKeys keys = keysAt(TestRsaKeys.privateKeyLocation());
    private final JwtDecoder decoder = configuration.jwtDecoder(keys, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsATokenIssuedAtLogin() {
        JwtProperties properties = new JwtProperties(TestRsaKeys.privateKeyLocation(), Duration.ofHours(24));
        String token = new JwtTokenIssuerAdapter(keys, properties).issue(
                User.builder().id(42L).authorities(Set.of(Authority.ROLE_USER)).build());

        Jwt jwt = configuration.jwtDecoder(keys, Clock.systemUTC()).decode(token);

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getTokenValue()).isEqualTo(token);
    }

    @Test
    void acceptsAValidToken() {
        Jwt jwt = decoder.decode(validToken("42"));

        assertThat(jwt.getSubject()).isEqualTo("42");
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        KeyPair otherPair = TestRsaKeys.generate(2048);
        String token = TestTokens.signedWith(otherPair, claims -> claims.subject("42").claim("auth", "ROLE_USER")
                .issuedAt(NOW.minusSeconds(60)).expiresAt(NOW.plusSeconds(3600)));

        assertRejected(token);
    }

    @Test
    void rejectsATokenSignedWithAnotherAlgorithm() {
        String token = TestTokens.signedWithSecret(claims -> claims.subject("42").claim("auth", "ROLE_USER")
                .issuedAt(NOW.minusSeconds(60)).expiresAt(NOW.plusSeconds(3600)));

        assertRejected(token);
    }

    @Test
    void rejectsAnUnsignedToken() {
        String token = TestTokens.unsigned(
                "{\"sub\":\"42\",\"auth\":\"ROLE_USER\",\"exp\":" + NOW.plusSeconds(3600).getEpochSecond() + "}");

        assertRejected(token);
    }

    @Test
    void rejectsATokenWhosePayloadWasChangedAfterSigning() {
        String token = TestTokens.withPayload(validToken("42"),
                "{\"sub\":\"7\",\"auth\":\"ROLE_ADMIN\",\"exp\":" + NOW.plusSeconds(3600).getEpochSecond() + "}");

        assertRejected(token);
    }

    @Test
    void rejectsATokenThatExpiredOneSecondAgo() {
        assertRejected(tokenExpiringAt(NOW.minusSeconds(1)));
    }

    @Test
    void acceptsATokenThatExpiresInOneSecond() {
        assertThat(decoder.decode(tokenExpiringAt(NOW.plusSeconds(1))).getSubject()).isEqualTo("42");
    }

    @Test
    void rejectsATokenWithoutExpiration() {
        String token = TestTokens.signed(claims -> claims.subject("42").claim("auth", "ROLE_USER")
                .issuedAt(NOW.minusSeconds(60)));

        assertRejected(token);
    }

    @Test
    void rejectsATokenWithoutSubject() {
        String token = TestTokens.signed(claims -> claims.claim("auth", "ROLE_USER")
                .issuedAt(NOW.minusSeconds(60)).expiresAt(NOW.plusSeconds(3600)));

        assertRejected(token);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1", "4 2", "٤٢", "99999999999999999999"})
    void rejectsASubjectThatIsNotAUserId(String subject) {
        assertRejected(validToken(subject));
    }

    @Test
    void aTokenStillValidatesWithTheSameKeyPairAfterARestart() {
        JwtDecoder afterRestart = configuration.jwtDecoder(
                keysAt(TestRsaKeys.privateKeyLocation()), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(afterRestart.decode(validToken("42")).getSubject()).isEqualTo("42");
    }

    @Test
    void aTokenIsRejectedOnceTheKeyPairIsReplaced() {
        Path newKeyFile = TestRsaKeys.writePrivateKey(TestRsaKeys.generate(2048));
        JwtDecoder withNewPair = configuration.jwtDecoder(
                keysAt("file:" + newKeyFile.toAbsolutePath()), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> withNewPair.decode(validToken("42"))).isInstanceOf(JwtException.class);
    }

    // Spring agrega además FACTOR_BEARER (con qué se autenticó), que no es un rol
    @Test
    void everyRoleOfTheTokenBecomesAnAuthority() {
        Authentication authentication = authenticationWith("ROLE_ADMIN ROLE_USER");

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .filteredOn(authority -> authority.startsWith("ROLE_"))
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
    }

    @ParameterizedTest
    @EnumSource(Authority.class)
    void anyKnownRoleIsGranted(Authority authority) {
        assertThat(isGranted(authenticationWith(authority.name()))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ROLE_OTRO", "USER"})
    void aTokenWithoutAKnownRoleIsDenied(String authorities) {
        assertThat(isGranted(authenticationWith(authorities))).isFalse();
    }

    @Test
    void aTokenWithoutTheRolesClaimIsDenied() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("42").build();

        assertThat(isGranted(configuration.jwtAuthenticationConverter().convert(jwt))).isFalse();
    }

    private String validToken(String subject) {
        return TestTokens.signed(claims -> claims.subject(subject).claim("auth", "ROLE_USER")
                .issuedAt(NOW.minusSeconds(60)).expiresAt(NOW.plusSeconds(3600)));
    }

    private String tokenExpiringAt(Instant expiresAt) {
        return TestTokens.signed(claims -> claims.subject("42").claim("auth", "ROLE_USER")
                .issuedAt(expiresAt.minusSeconds(3600)).expiresAt(expiresAt));
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private Authentication authenticationWith(String authorities) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("42").claim("auth", authorities).build();
        return configuration.jwtAuthenticationConverter().convert(jwt);
    }

    private boolean isGranted(Authentication authentication) {
        AuthorizationManager<RequestAuthorizationContext> manager = configuration.userRolesAuthorizationManager();
        return manager.authorize(() -> authentication, new RequestAuthorizationContext(new MockHttpServletRequest()))
                .isGranted();
    }

    private static JwtSigningKeys keysAt(String location) {
        return new JwtSigningKeys(new JwtProperties(location, Duration.ofHours(24)), new DefaultResourceLoader());
    }
}
