package e2e;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

// Los E2E corren solo si el .env local tiene las credenciales de la cátedra. Lee el mismo archivo que la aplicación y
// solo comprueba que las tres variables tengan valor, sin mostrarlas
final class E2ECredentials {

    private static final List<String> CREDENTIALS = List.of("TURNOS_CATEDRA_BASE_URL", "TURNOS_CATEDRA_USERNAME", "TURNOS_CATEDRA_PASSWORD");

    private E2ECredentials() {
    }

    static boolean available() {
        Path path = Path.of(System.getProperty("turnos.env-file", ".env"));
        if (!Files.isReadable(path)) {
            return false;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException e) {
            return false;
        }
        return CREDENTIALS.stream().allMatch(name -> !properties.getProperty(name, "").isBlank());
    }
}
