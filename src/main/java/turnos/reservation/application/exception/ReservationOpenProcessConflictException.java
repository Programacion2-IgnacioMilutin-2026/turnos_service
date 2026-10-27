package turnos.reservation.application.exception;

// La base rechazó un segundo proceso abierto del mismo usuario: otro pedido lo guardó antes
public class ReservationOpenProcessConflictException extends ReservationException {
    public ReservationOpenProcessConflictException(Long userId) {
        super("User already has an open reservation process: " + userId);
    }
}
