package turnos.user.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import turnos.TestRsaKeys;
import turnos.user.domain.model.Authority;
import turnos.user.domain.model.User;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenIssuerAdapterTest {

    private final JwtTokenIssuerAdapter issuer = new JwtTokenIssuerAdapter(
            new JwtProperties(TestRsaKeys.privateKeyLocation(), Duration.ofHours(24)), new DefaultResourceLoader());

    @Test
    void issuesAnRs256TokenWithExactlyTheExpectedClaims() {
        Jwt jwt = decodeWith(TestRsaKeys.publicKey(), issuer.issue(user(42L, Authority.ROLE_USER)));

        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256");
        assertThat(jwt.getClaims()).containsOnlyKeys("sub", "auth", "iat", "exp");
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("auth")).isEqualTo("ROLE_USER");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void listsEveryRoleSeparatedBySpaces() {
        Jwt jwt = decodeWith(TestRsaKeys.publicKey(), issuer.issue(user(7L, Authority.ROLE_USER, Authority.ROLE_ADMIN)));

        assertThat(jwt.getClaimAsString("auth")).isEqualTo("ROLE_ADMIN ROLE_USER");
    }

    @Test
    void theTokenDoesNotVerifyWithAnotherKey() {
        String token = issuer.issue(user(42L, Authority.ROLE_USER));
        RSAPublicKey otherKey = (RSAPublicKey) TestRsaKeys.generate(2048).getPublic();

        assertThatThrownBy(() -> decodeWith(otherKey, token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void theApplicationDoesNotStartWithoutTheKeyProperty() {
        expectStartupFailure(contextRunner(), "Missing configuration property turnos.jwt.private-key-location");
    }

    @Test
    void theApplicationDoesNotStartWhenTheKeyFileDoesNotExist(@TempDir Path dir) {
        expectStartupFailure(contextRunner(dir.resolve("missing.pem")), "points to a file that does not exist");
    }

    @Test
    void theApplicationDoesNotStartWhenTheFileIsNotAKey(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("not-a-key.pem"), "-----BEGIN PRIVATE KEY-----\nnot a key\n-----END PRIVATE KEY-----\n");

        expectStartupFailure(contextRunner(file), "is not an RSA private key in PKCS#8 PEM format");
    }

    @Test
    void theApplicationDoesNotStartWithAKeySmallerThan2048Bits() {
        Path file = TestRsaKeys.writePrivateKey(TestRsaKeys.generate(1024));

        expectStartupFailure(contextRunner(file), "must be an RSA key of at least 2048 bits");
    }

    private ApplicationContextRunner contextRunner(Path keyFile) {
        return contextRunner().withPropertyValues("turnos.jwt.private-key-location=file:" + keyFile.toAbsolutePath());
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withBean(JwtTokenIssuerAdapter.class);
    }

    private void expectStartupFailure(ApplicationContextRunner runner, String expectedMessage) {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining(expectedMessage)
                    .message().doesNotContain("BEGIN", "MII");
        });
    }

    private Jwt decodeWith(RSAPublicKey publicKey, String token) {
        return NimbusJwtDecoder.withPublicKey(publicKey).build().decode(token);
    }

    private User user(Long id, Authority... authorities) {
        return User.builder().id(id).login("maria.gomez").activated(true).authorities(Set.of(authorities)).build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class PropertiesConfiguration {
    }
}
