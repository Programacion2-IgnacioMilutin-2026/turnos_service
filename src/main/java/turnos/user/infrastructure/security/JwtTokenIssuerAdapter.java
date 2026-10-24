package turnos.user.infrastructure.security;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;
import turnos.user.domain.model.Authority;
import turnos.user.domain.model.User;
import turnos.user.domain.ports.out.TokenIssuer;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;

// Paquete infrastructure/security: excepción a la skill autorizada por el usuario
@Component
public class JwtTokenIssuerAdapter implements TokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final Duration tokenValidity;

    public JwtTokenIssuerAdapter(JwtSigningKeys jwtSigningKeys, JwtProperties jwtProperties) {
        this.jwtEncoder = NimbusJwtEncoder.withKeyPair(jwtSigningKeys.publicKey(), jwtSigningKeys.privateKey())
                .algorithm(SignatureAlgorithm.RS256)
                .build();
        this.tokenValidity = jwtProperties.tokenValidity();
    }

    @Override
    public String issue(User user) {
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .claim("auth", authoritiesOf(user))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(tokenValidity))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String authoritiesOf(User user) {
        return user.getAuthorities().stream()
                .map(Authority::name)
                .sorted()
                .collect(Collectors.joining(" "));
    }
}
