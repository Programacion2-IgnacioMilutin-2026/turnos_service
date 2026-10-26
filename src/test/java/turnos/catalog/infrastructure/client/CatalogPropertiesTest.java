package turnos.catalog.infrastructure.client;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void withoutConfigurationThereIsNoUrlAndTheDefaultTimesApply() {
        contextRunner.run(context -> {
            CatalogProperties properties = context.getBean(CatalogProperties.class);
            assertThat(properties.baseUrl()).isNull();
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.maxAttempts()).isEqualTo(3);
            assertThat(properties.initialDelay()).isEqualTo(Duration.ofMillis(500));
            assertThat(properties.multiplier()).isEqualTo(2);
        });
    }

    @Test
    void everyValueCanBeConfigured() {
        contextRunner.withPropertyValues("turnos.catalog.base-url=http://catalog:8081", "turnos.catalog.connect-timeout=1s",
                "turnos.catalog.read-timeout=4s", "turnos.catalog.max-attempts=5", "turnos.catalog.initial-delay=250ms",
                "turnos.catalog.multiplier=3").run(context -> {
            CatalogProperties properties = context.getBean(CatalogProperties.class);
            assertThat(properties.baseUrl()).isEqualTo("http://catalog:8081");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(4));
            assertThat(properties.maxAttempts()).isEqualTo(5);
            assertThat(properties.initialDelay()).isEqualTo(Duration.ofMillis(250));
            assertThat(properties.multiplier()).isEqualTo(3);
        });
    }

    // Así queda si la variable del .env existe pero está vacía
    @Test
    void anEmptyTimeKeepsTheDefault() {
        contextRunner.withPropertyValues("turnos.catalog.connect-timeout=").run(context ->
                assertThat(context.getBean(CatalogProperties.class).connectTimeout()).isEqualTo(Duration.ofSeconds(2)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CatalogProperties.class)
    static class PropertiesConfiguration {
    }
}
