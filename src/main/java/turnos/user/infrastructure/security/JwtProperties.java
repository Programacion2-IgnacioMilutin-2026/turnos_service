package turnos.user.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("turnos.jwt")
public record JwtProperties(String privateKeyLocation, @DefaultValue("24h") Duration tokenValidity) {
}
