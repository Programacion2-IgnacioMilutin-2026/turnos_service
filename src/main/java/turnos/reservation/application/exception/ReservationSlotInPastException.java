package turnos.reservation.application.exception;

import lombok.Getter;

// El turno pedido ya pasó: field dice qué campo lo muestra (la fecha, o la hora si es hoy)
@Getter
public class ReservationSlotInPastException extends ReservationException {
    private final String field;

    public ReservationSlotInPastException(String field) {
        super("Slot is not after the current time: " + field);
        this.field = field;
    }
}
