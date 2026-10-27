package turnos.reservation.application.exception;

import lombok.Getter;

import java.time.Instant;

// La cátedra concedió el hold pero no se pudo guardar el proceso: el turno queda retenido hasta expiresAt
@Getter
public class ReservationHoldNotSavedException extends ReservationException {
    private final Instant expiresAt;

    public ReservationHoldNotSavedException(String reservationProcessId, Instant expiresAt, Throwable cause) {
        super("Hold was granted but the reservation process could not be saved: " + reservationProcessId, cause);
        this.expiresAt = expiresAt;
    }
}
