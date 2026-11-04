package turnos.reservation.domain.ports.out;

import java.time.Instant;

// Si la cátedra no cancela, lanza la excepción de reservation que corresponde. Devuelve cuándo quedó cancelada
public interface AppointmentCanceller {
    Instant cancel(String reservationProcessId, String reason);
}
