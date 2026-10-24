package turnos;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import turnos.config.ClockConfig;
import turnos.config.CorsProperties;
import turnos.config.SecurityConfig;
import turnos.config.SecurityProblemHandler;
import turnos.user.infrastructure.security.JwtProperties;
import turnos.user.infrastructure.security.JwtSigningKeys;
import turnos.user.infrastructure.security.JwtValidationConfiguration;

// La seguridad real para los tests de la capa web (@WebMvcTest), que no cargan la aplicación completa.
// Valida los tokens con el par de claves de test
@TestConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties({JwtProperties.class, CorsProperties.class})
@Import({SecurityConfig.class, SecurityProblemHandler.class, JwtValidationConfiguration.class, JwtSigningKeys.class,
		ClockConfig.class})
public class TestSecurityConfiguration {

	@Bean
	DynamicPropertyRegistrar testJwtKeyProperty() {
		return registry -> registry.add("turnos.jwt.private-key-location", TestRsaKeys::privateKeyLocation);
	}

}
