package turnos.catedra.domain.model;

// El estado de una reserva en la cátedra. UNKNOWN agrupa lo que no se reconoce: solo importa si es la reserva buscada
public enum AppointmentStatus {
    PHONE_PENDING,
    CONFIRMED,
    CANCELLED,
    FAILED,
    UNKNOWN
}
