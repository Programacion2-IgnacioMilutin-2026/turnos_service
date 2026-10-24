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
        @DefaultValue Login login) {

    public record Login(
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("1s") Duration initialDelay,
            @DefaultValue("2") double multiplier,
            @DefaultValue("30s") Duration maxDelay,
            @DefaultValue("60s") Duration minInterval) {
    }

    // La contraseña de la cuenta técnica no debe aparecer en logs
    @Override
    public String toString() {
        return "CatedraProperties[baseUrl=" + baseUrl + ", username=" + username + ", password=***"
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + ", login=" + login + "]";
    }
}
