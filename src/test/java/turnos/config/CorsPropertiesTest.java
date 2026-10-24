package turnos.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class CorsPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void noOriginIsAllowedWhenNotConfigured() {
        contextRunner.run(context ->
                assertThat(context.getBean(CorsProperties.class).allowedOrigins()).isEmpty());
    }

    // Así queda cuando la variable del .env existe pero está vacía
    @Test
    void anEmptyValueAllowsNoOrigin() {
        contextRunner.withPropertyValues("turnos.cors.allowed-origins=").run(context ->
                assertThat(context.getBean(CorsProperties.class).allowedOrigins()).isEmpty());
    }

    @Test
    void severalOriginsAreSeparatedByCommas() {
        contextRunner.withPropertyValues("turnos.cors.allowed-origins=http://localhost:3000, https://turnos.example.com")
                .run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                        .containsExactly("http://localhost:3000", "https://turnos.example.com"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CorsProperties.class)
    static class PropertiesConfiguration {
    }
}
