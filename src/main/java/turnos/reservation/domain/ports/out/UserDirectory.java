package turnos.reservation.domain.ports.out;

public interface UserDirectory {
    boolean exists(Long userId);
}
