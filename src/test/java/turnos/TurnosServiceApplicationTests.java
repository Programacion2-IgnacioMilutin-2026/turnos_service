package turnos;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import turnos.config.ProbeController;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TurnosServiceApplicationTests {

	@Test
	void contextLoads() {
	}

	// Sin este límite, una base que deja de responder sin cerrar la conexión deja el pedido esperando para siempre
	@Test
	void theDatabaseConnectionHasALimitToWaitForAnAnswer(@Autowired HikariDataSource dataSource) {
		assertThat(dataSource.getDataSourceProperties().getProperty("socketTimeout")).isEqualTo("30");
	}

	// El endpoint de prueba de la seguridad solo existe en los tests que lo importan
	@Test
	void theTestOnlyProbeEndpointIsNotPartOfTheApplication(@Autowired ApplicationContext context) {
		assertThat(context.getBeansOfType(ProbeController.class)).isEmpty();
	}

	// Con un .env local, esta variable estaría definida: los tests nunca deben usar credenciales reales
	@Test
	void testsDoNotReadTheLocalEnvFile(@Autowired Environment environment) {
		assertThat(environment.getProperty("TURNOS_JWT_PRIVATE_KEY_LOCATION")).isNull();
		assertThat(environment.getProperty("TURNOS_CATEDRA_PASSWORD")).isNull();
	}

}
