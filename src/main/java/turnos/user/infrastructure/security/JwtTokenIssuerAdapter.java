package turnos.user.infrastructure.security;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;
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

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;

@Component
public class JwtTokenIssuerAdapter implements TokenIssuer {

    private static final String KEY_PROPERTY = "turnos.jwt.private-key-location";
    private static final int MIN_KEY_SIZE = 2048;

    private final JwtEncoder jwtEncoder;
    private final Duration tokenValidity;

    // Carga la clave al crearse: si falta o no sirve, la aplicación no arranca. Los mensajes nunca incluyen la clave
    public JwtTokenIssuerAdapter(JwtProperties jwtProperties, ResourceLoader resourceLoader) {
        RSAPrivateKey privateKey = loadPrivateKey(jwtProperties.privateKeyLocation(), resourceLoader);
        this.jwtEncoder = NimbusJwtEncoder.withKeyPair(publicKeyOf(privateKey), privateKey)
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

    private static RSAPrivateKey loadPrivateKey(String location, ResourceLoader resourceLoader) {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("Missing configuration property " + KEY_PROPERTY);
        }
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException(KEY_PROPERTY + " points to a file that does not exist: " + location);
        }
        RSAPrivateKey privateKey;
        try (InputStream inputStream = resource.getInputStream()) {
            privateKey = RsaKeyConverters.pkcs8().convert(inputStream);
        } catch (Exception e) {
            throw new IllegalStateException(KEY_PROPERTY + " is not an RSA private key in PKCS#8 PEM format: " + location);
        }
        if (privateKey == null || privateKey.getModulus().bitLength() < MIN_KEY_SIZE) {
            throw new IllegalStateException(KEY_PROPERTY + " must be an RSA key of at least " + MIN_KEY_SIZE + " bits: " + location);
        }
        return privateKey;
    }

    // Una clave privada PKCS#8 trae el módulo y el exponente público, así que no hace falta configurar la pública
    private static RSAPublicKey publicKeyOf(RSAPrivateKey privateKey) {
        if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
            throw new IllegalStateException(KEY_PROPERTY + " does not contain the public exponent of the key");
        }
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot derive the public key from " + KEY_PROPERTY);
        }
    }
}
