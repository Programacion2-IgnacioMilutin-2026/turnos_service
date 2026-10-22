package turnos.user.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JwtPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void tokenValidityIsTwentyFourHoursWhenNotConfigured() {
        contextRunner.run(context ->
                assertThat(context.getBean(JwtProperties.class).tokenValidity()).isEqualTo(Duration.ofHours(24)));
    }

    @Test
    void tokenValidityCanBeConfigured() {
        contextRunner.withPropertyValues("turnos.jwt.token-validity=1h").run(context ->
                assertThat(context.getBean(JwtProperties.class).tokenValidity()).isEqualTo(Duration.ofHours(1)));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class PropertiesConfiguration {
    }
}
