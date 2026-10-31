package e2e;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

// El mismo .env local que lee la aplicación, para que la suite E2E sepa qué tiene configurado. Nunca muestra valores
final class E2EEnvFile {

    private E2EEnvFile() {
    }

    // Vacío si el archivo no existe o no se puede leer
    static Properties read() {
        Properties properties = new Properties();
        Path path = Path.of(System.getProperty("turnos.env-file", ".env"));
        if (!Files.isReadable(path)) {
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException e) {
            return new Properties();
        }
        return properties;
    }
}
