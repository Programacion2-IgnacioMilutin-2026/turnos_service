package turnos.catedra.infrastructure.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("turnos.catedra")
public record CatedraProperties(
        String baseUrl,
        String username,
        String password,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue Login login,
        @DefaultValue Operations operations) {

    public record Login(
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("1s") Duration initialDelay,
            @DefaultValue("2") double multiplier,
            @DefaultValue("30s") Duration maxDelay,
            @DefaultValue("60s") Duration minInterval) {
    }

    // Las consultas que se hacen durante un request de un usuario: esperas más cortas que las del login
    public record Operations(
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("3s") Duration readTimeout,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("500ms") Duration initialDelay,
            @DefaultValue("2") double multiplier) {
    }

    // La contraseña de la cuenta técnica no debe aparecer en logs
    @Override
    public String toString() {
        return "CatedraProperties[baseUrl=" + baseUrl + ", username=" + username + ", password=***"
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + ", login=" + login + ", operations=" + operations + "]";
    }
}
