package turnos.reservation.application.exception;

import lombok.Getter;

import java.time.Instant;

// La cátedra concedió el hold pero no se pudo guardar el proceso: el turno queda retenido hasta expiresAt, dentro de
// secondsUntilExpiration segundos (redondeado hacia arriba, nunca negativo)
@Getter
public class ReservationHoldNotSavedException extends ReservationException {
    private final Instant expiresAt;
    private final long secondsUntilExpiration;

    public ReservationHoldNotSavedException(String reservationProcessId, Instant expiresAt, long secondsUntilExpiration,
                                            Throwable cause) {
        super("Hold was granted but the reservation process could not be saved: " + reservationProcessId, cause);
        this.expiresAt = expiresAt;
        this.secondsUntilExpiration = secondsUntilExpiration;
    }
}
