package turnos.catalog.domain.model;

// Los resultados posibles de pedir un profesional a catálogo. Tipos inmutables: excepción a la skill autorizada
public sealed interface ProfessionalResult {

    record Found(Professional professional) implements ProfessionalResult {
    }

    record NotFound() implements ProfessionalResult {
    }

    // Catálogo rechazó el token del usuario que turnos le reenvió
    record Rejected(int status) implements ProfessionalResult {
    }

    // Catálogo no respondió después de los reintentos, o no hay una URL válida configurada
    record Unavailable(String reason) implements ProfessionalResult {
    }

    // Una respuesta que no debería ocurrir: repetir no la arregla
    record Failed(String reason) implements ProfessionalResult {
    }
}
