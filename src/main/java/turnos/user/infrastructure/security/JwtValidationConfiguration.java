package turnos.user.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import turnos.user.domain.model.Authority;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;

// Paquete infrastructure/security: excepción a la skill autorizada por el usuario.
// Las piezas que validan el token viven junto a las que lo emiten: comparten la clave, los claims y los roles.
// La cadena de seguridad las recibe por sus interfaces de Spring, sin conocer este slice
@Configuration(proxyBeanMethods = false)
public class JwtValidationConfiguration {

    private static final String AUTHORITIES_CLAIM = "auth";

    // Solo RS256 con la clave propia. Sin tolerancia de reloj: firma y validación usan el mismo reloj.
    // Un token sin vencimiento valdría para siempre, así que también se rechaza
    @Bean
    JwtDecoder jwtDecoder(JwtSigningKeys jwtSigningKeys, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(jwtSigningKeys.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        JwtTimestampValidator expiry = new JwtTimestampValidator(Duration.ZERO);
        expiry.setClock(clock);
        expiry.setAllowEmptyExpiryClaim(false);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtTypeValidator.jwt(),
                expiry,
                new JwtClaimValidator<String>(JwtClaimNames.SUB, JwtValidationConfiguration::isUserId)));
        return decoder;
    }

    // Los roles viajan separados por un espacio y ya tienen el prefijo ROLE_
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(AUTHORITIES_CLAIM);
        authorities.setAuthoritiesClaimDelimiter(" ");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    // Cualquier rol de Authority alcanza; un rol nuevo en el enum queda aceptado sin cambiar la configuración
    @Bean
    AuthorizationManager<RequestAuthorizationContext> userRolesAuthorizationManager() {
        String[] roles = Arrays.stream(Authority.values()).map(Authority::name).toArray(String[]::new);
        return AuthorityAuthorizationManager.hasAnyAuthority(roles);
    }

    // El sub es el id del usuario: solo dígitos 0-9 (no otros sistemas de dígitos) y dentro del rango de un Long
    private static boolean isUserId(String subject) {
        if (subject == null || subject.isEmpty() || !subject.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        try {
            Long.parseLong(subject);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
