package turnos.reservation.application.exception;

import lombok.Getter;
import turnos.reservation.domain.model.ReservationStatus;

// Lleva el estado actual, para que la app sepa en qué quedó el proceso
@Getter
public class ReservationInvalidProcessStateException extends ReservationException {
    private final ReservationStatus currentStatus;

    public ReservationInvalidProcessStateException(String reservationProcessId, ReservationStatus currentStatus) {
        super("Reservation process " + reservationProcessId + " is " + currentStatus);
        this.currentStatus = currentStatus;
    }
}
