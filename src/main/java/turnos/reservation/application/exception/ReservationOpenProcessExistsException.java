package turnos.reservation.application.exception;

import lombok.Getter;
import turnos.reservation.domain.model.ReservationProcess;

// Lleva el proceso abierto, para que la app pueda continuarlo o cancelarlo
@Getter
public class ReservationOpenProcessExistsException extends ReservationException {
    private final transient ReservationProcess openProcess;

    public ReservationOpenProcessExistsException(ReservationProcess openProcess) {
        super("User already has an open reservation process: " + openProcess.getReservationProcessId());
        this.openProcess = openProcess;
    }
}
