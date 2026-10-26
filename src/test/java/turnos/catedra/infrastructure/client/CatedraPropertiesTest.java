package turnos.catedra.infrastructure.client;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class CatedraPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void withoutConfigurationTheOperationsUseShortTimesAndThreeAttempts() {
        contextRunner.run(context -> {
            CatedraProperties.Operations operations = context.getBean(CatedraProperties.class).operations();
            assertThat(operations.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(operations.readTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(operations.maxAttempts()).isEqualTo(3);
            assertThat(operations.initialDelay()).isEqualTo(Duration.ofMillis(500));
            assertThat(operations.multiplier()).isEqualTo(2);
        });
    }

    @Test
    void theOperationsCanBeConfiguredWithoutChangingTheLogin() {
        contextRunner.withPropertyValues("turnos.catedra.operations.connect-timeout=1s",
                "turnos.catedra.operations.read-timeout=4s", "turnos.catedra.operations.max-attempts=4",
                "turnos.catedra.operations.initial-delay=100ms", "turnos.catedra.operations.multiplier=1.5").run(context -> {
            CatedraProperties properties = context.getBean(CatedraProperties.class);
            assertThat(properties.operations().connectTimeout()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.operations().readTimeout()).isEqualTo(Duration.ofSeconds(4));
            assertThat(properties.operations().maxAttempts()).isEqualTo(4);
            assertThat(properties.operations().initialDelay()).isEqualTo(Duration.ofMillis(100));
            assertThat(properties.operations().multiplier()).isEqualTo(1.5);
            assertThat(properties.login().maxAttempts()).isEqualTo(5);
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void thePasswordNeverAppearsInItsText() {
        contextRunner.withPropertyValues("turnos.catedra.password=secreto-de-prueba").run(context ->
                assertThat(context.getBean(CatedraProperties.class).toString()).doesNotContain("secreto-de-prueba").contains("operations="));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CatedraProperties.class)
    static class PropertiesConfiguration {
    }
}
