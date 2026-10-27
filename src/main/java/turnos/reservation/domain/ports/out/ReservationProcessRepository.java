package turnos.reservation.domain.ports.out;

import turnos.reservation.domain.model.ReservationProcess;

import java.util.List;
import java.util.Optional;

public interface ReservationProcessRepository {

    ReservationProcess create(ReservationProcess reservationProcess);
    Optional<ReservationProcess> update(String reservationProcessId, ReservationProcess reservationProcess);
    List<ReservationProcess> findOpenByUserId(Long userId);

}
