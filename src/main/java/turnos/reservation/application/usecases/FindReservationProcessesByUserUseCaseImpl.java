package turnos.reservation.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.reservation.domain.model.ReservationListCriteria;
import turnos.reservation.domain.model.ReservationPolicy;
import turnos.reservation.domain.model.ReservationProcessPage;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.in.FindReservationProcessesByUserUseCase;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;

@Component
@RequiredArgsConstructor
public class FindReservationProcessesByUserUseCaseImpl implements FindReservationProcessesByUserUseCase {

    private final ReservationProcessRepository reservationProcessRepository;
    private final ReservationPolicy reservationPolicy;
    private final Clock clock;

    // Solo lee, sin el candado del usuario: un vencido no se lista, y lo cierra o lo marca quien lo use o el barrido.
    // Si este listado escribiera, podría pisar un resultado de la cátedra
    @Override
    public ReservationProcessPage findReservationProcessesByUser(Long userId, ReservationStatus status, int page, int size) {
        Instant now = clock.instant();
        ZonedDateTime inTheAttentionZone = now.atZone(reservationPolicy.zone());
        return reservationProcessRepository.findListable(new ReservationListCriteria(userId, status,
                now.minus(reservationPolicy.safetyMargin()), inTheAttentionZone.toLocalDate(),
                inTheAttentionZone.toLocalTime(), page, size));
    }
}
