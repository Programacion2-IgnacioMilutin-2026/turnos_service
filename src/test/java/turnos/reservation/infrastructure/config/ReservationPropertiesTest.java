package turnos.reservation.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import turnos.reservation.domain.model.ReservationPolicy;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void withoutConfigurationTheZoneIsMendozaAndTheMarginIsThirtySeconds() {
        contextRunner.run(context -> {
            ReservationPolicy policy = context.getBean(ReservationPolicy.class);
            assertThat(policy.zone()).isEqualTo(ZoneId.of("America/Argentina/Mendoza"));
            assertThat(policy.safetyMargin()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void bothValuesCanBeConfigured() {
        contextRunner.withPropertyValues("turnos.reservation.zone-id=America/Argentina/Buenos_Aires",
                "turnos.reservation.safety-margin=45s").run(context -> {
            ReservationPolicy policy = context.getBean(ReservationPolicy.class);
            assertThat(policy.zone()).isEqualTo(ZoneId.of("America/Argentina/Buenos_Aires"));
            assertThat(policy.safetyMargin()).isEqualTo(Duration.ofSeconds(45));
        });
    }

    // Así queda si la variable del .env existe pero está vacía
    @Test
    void anEmptyMarginKeepsTheDefault() {
        contextRunner.withPropertyValues("turnos.reservation.safety-margin=").run(context ->
                assertThat(context.getBean(ReservationPolicy.class).safetyMargin()).isEqualTo(Duration.ofSeconds(30)));
    }

    @Test
    void anUnknownZoneStopsTheStartup() {
        contextRunner.withPropertyValues("turnos.reservation.zone-id=Mendoza").run(context ->
                assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReservationProperties.class)
    @Import(ReservationConfiguration.class)
    static class PropertiesConfiguration {
    }
}
