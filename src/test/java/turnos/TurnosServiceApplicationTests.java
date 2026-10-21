package turnos;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TurnosServiceApplicationTests {

	@Test
	void contextLoads() {
	}

	// Con un .env local, esta variable estaría definida: los tests nunca deben usar credenciales reales
	@Test
	void testsDoNotReadTheLocalEnvFile(@Autowired Environment environment) {
		assertThat(environment.getProperty("TURNOS_JWT_PRIVATE_KEY_LOCATION")).isNull();
		assertThat(environment.getProperty("TURNOS_CATEDRA_PASSWORD")).isNull();
	}

}
