package turnos.availability.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void withoutConfigurationTheZoneIsMendozaAndTheCacheLastsAnHour() {
        contextRunner.run(context -> {
            AvailabilityProperties properties = context.getBean(AvailabilityProperties.class);
            assertThat(properties.zoneId()).isEqualTo(ZoneId.of("America/Argentina/Mendoza"));
            assertThat(properties.occupancyCacheMaxAge()).isEqualTo(Duration.ofMinutes(60));
        });
    }

    @Test
    void bothValuesCanBeConfigured() {
        contextRunner.withPropertyValues("turnos.availability.zone-id=America/Argentina/Buenos_Aires",
                "turnos.availability.occupancy-cache-max-age=15m").run(context -> {
            AvailabilityProperties properties = context.getBean(AvailabilityProperties.class);
            assertThat(properties.zoneId()).isEqualTo(ZoneId.of("America/Argentina/Buenos_Aires"));
            assertThat(properties.occupancyCacheMaxAge()).isEqualTo(Duration.ofMinutes(15));
        });
    }

    @Test
    void anUnknownZoneStopsTheStartup() {
        contextRunner.withPropertyValues("turnos.availability.zone-id=Mendoza").run(context ->
                assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AvailabilityProperties.class)
    static class PropertiesConfiguration {
    }
}
