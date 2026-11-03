package turnos.reservation.application.usecases;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import turnos.MutableClock;
import turnos.reservation.domain.model.ReservationListCriteria;
import turnos.reservation.domain.model.ReservationPolicy;
import turnos.reservation.domain.model.ReservationProcessPage;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class FindReservationProcessesByUserUseCaseImplTest {

    // En UTC ya es el día siguiente: la fecha que vale es la de la zona de atención
    private static final Instant NOW = Instant.parse("2026-11-04T01:30:00Z");

    private final ReservationProcessRepository repository = mock(ReservationProcessRepository.class);
    private final FindReservationProcessesByUserUseCaseImpl useCase = new FindReservationProcessesByUserUseCaseImpl(repository,
            new ReservationPolicy(ZoneId.of("America/Argentina/Mendoza"), Duration.ofSeconds(30)), new MutableClock(NOW));

    @Test
    void itAsksForThePageWithTheDateAndTimeOfTheAttentionZoneAndTheLimitOfTheOverdue() {
        ReservationProcessPage expected = new ReservationProcessPage(List.of(), 7);
        ArgumentCaptor<ReservationListCriteria> criteria = ArgumentCaptor.forClass(ReservationListCriteria.class);
        when(repository.findListable(criteria.capture())).thenReturn(expected);

        ReservationProcessPage result = useCase.findReservationProcessesByUser(42L, ReservationStatus.CONFIRMED, 2, 25);

        assertThat(result).isSameAs(expected);
        assertThat(criteria.getValue()).isEqualTo(new ReservationListCriteria(42L, ReservationStatus.CONFIRMED,
                NOW.minusSeconds(30), LocalDate.of(2026, 11, 3), LocalTime.of(22, 30), 2, 25));
    }

    @Test
    void withoutAFilterTheStatusStaysEmpty() {
        ArgumentCaptor<ReservationListCriteria> criteria = ArgumentCaptor.forClass(ReservationListCriteria.class);
        when(repository.findListable(criteria.capture())).thenReturn(new ReservationProcessPage(List.of(), 0));

        useCase.findReservationProcessesByUser(42L, null, 0, 10);

        assertThat(criteria.getValue().status()).isNull();
    }

    @Test
    void itOnlyReadsTheList() {
        when(repository.findListable(any())).thenReturn(new ReservationProcessPage(List.of(), 0));

        useCase.findReservationProcessesByUser(42L, null, 0, 10);

        verify(repository).findListable(any());
        verifyNoMoreInteractions(repository);
    }
}
