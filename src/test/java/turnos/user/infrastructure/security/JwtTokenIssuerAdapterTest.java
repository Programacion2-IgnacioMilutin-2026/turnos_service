package turnos.user.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import turnos.TestRsaKeys;
import turnos.user.domain.model.Authority;
import turnos.user.domain.model.User;

import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// La carga de la clave y sus errores de arranque se prueban en JwtSigningKeysTest
class JwtTokenIssuerAdapterTest {

    private final JwtProperties jwtProperties = new JwtProperties(TestRsaKeys.privateKeyLocation(), Duration.ofHours(24));
    private final JwtTokenIssuerAdapter issuer = new JwtTokenIssuerAdapter(
            new JwtSigningKeys(jwtProperties, new DefaultResourceLoader()), jwtProperties);

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

    private Jwt decodeWith(RSAPublicKey publicKey, String token) {
        return NimbusJwtDecoder.withPublicKey(publicKey).build().decode(token);
    }

    private User user(Long id, Authority... authorities) {
        return User.builder().id(id).login("maria.gomez").activated(true).authorities(Set.of(authorities)).build();
    }
}
