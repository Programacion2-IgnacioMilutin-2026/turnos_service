package e2e;

import java.util.List;
import java.util.Properties;

// Los E2E corren solo si el .env local tiene las credenciales de la cátedra. Solo comprueba que las tres variables
// tengan valor, sin mostrarlas
final class E2ECredentials {

    private static final List<String> CREDENTIALS = List.of("TURNOS_CATEDRA_BASE_URL", "TURNOS_CATEDRA_USERNAME", "TURNOS_CATEDRA_PASSWORD");

    private E2ECredentials() {
    }

    static boolean available() {
        Properties properties = E2EEnvFile.read();
        return CREDENTIALS.stream().allMatch(name -> !properties.getProperty(name, "").isBlank());
    }
}
