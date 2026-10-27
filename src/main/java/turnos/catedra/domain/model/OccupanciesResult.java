package turnos.catedra.domain.model;

import java.util.List;

// Los resultados posibles de consultar las ocupaciones a la cátedra. Tipos inmutables: excepción a la skill autorizada
public sealed interface OccupanciesResult {

    record Found(List<Occupancy> occupancies) implements OccupanciesResult {
    }

    // La integración del estudiante no existe o está en error de configuración: repetir no lo arregla
    record ConfigurationError(String reason) implements OccupanciesResult {
    }

    // La cátedra no respondió después de los reintentos, o no hay una sesión técnica disponible
    record Unavailable(String reason) implements OccupanciesResult {
    }

    // Una respuesta que no debería ocurrir: repetir no la arregla
    record Failed(String reason) implements OccupanciesResult {
    }
}
