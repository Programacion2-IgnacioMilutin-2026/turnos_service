package turnos;

import org.springframework.boot.SpringApplication;

public class TestTurnosServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(TurnosServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
