package turnos.catedra.domain.model;

// Lo que responde la cátedra a un intento de cualquier operación; el contenido de una respuesta definitiva es propio
// de cada operación. Tipos inmutables: excepción a la skill autorizada
public sealed interface CatedraResponse<T> {

    // Una respuesta definitiva: el resultado de la operación, sea un éxito o un rechazo de negocio
    record Answered<T>(T content) implements CatedraResponse<T> {
    }

    // La cátedra rechazó el JWT técnico sin procesar el pedido
    record TokenRejected<T>() implements CatedraResponse<T> {
    }

    // La integración del estudiante no existe: repetir no lo arregla
    record ConfigurationError<T>(String reason) implements CatedraResponse<T> {
    }

    // Seguro que el pedido no se procesó: se puede repetir
    record Unavailable<T>(String reason) implements CatedraResponse<T> {
    }

    // El pedido pudo haberse procesado sin que llegara la respuesta: repetirlo podría duplicar su efecto
    record Uncertain<T>(String reason) implements CatedraResponse<T> {
    }

    // Una respuesta que no debería ocurrir: repetir no la arregla
    record Failed<T>(String reason) implements CatedraResponse<T> {
    }
}
