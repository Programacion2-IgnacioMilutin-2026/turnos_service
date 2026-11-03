package turnos.reservation.application.service;

import org.junit.jupiter.api.Test;
import turnos.reservation.application.exception.ReservationProcessNotFoundException;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.ports.in.ConfirmReservationProcessUseCase;
import turnos.reservation.domain.ports.in.CreateReservationProcessUseCase;
import turnos.reservation.domain.ports.in.GetReservationProcessByIdUseCase;
import turnos.reservation.domain.ports.in.ProcessPhoneRequestUseCase;
import turnos.reservation.domain.ports.in.ProcessReservationResultUseCase;
import turnos.reservation.domain.ports.in.SubmitPhoneUseCase;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservationServiceTest {

    private final GetReservationProcessByIdUseCase getReservationProcessById = mock(GetReservationProcessByIdUseCase.class);
    private final ReservationService service = new ReservationService(mock(CreateReservationProcessUseCase.class),
            mock(ConfirmReservationProcessUseCase.class), mock(ProcessPhoneRequestUseCase.class),
            mock(ProcessReservationResultUseCase.class), mock(SubmitPhoneUseCase.class), getReservationProcessById);

    @Test
    void aProcessFoundIsReturned() {
        ReservationProcess process = new ReservationProcess();
        when(getReservationProcessById.getReservationProcessById(42L, "process-1")).thenReturn(Optional.of(process));

        assertThat(service.getReservationProcessById(42L, "process-1")).isSameAs(process);
    }

    @Test
    void aProcessNotFoundIsAnError() {
        when(getReservationProcessById.getReservationProcessById(42L, "process-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getReservationProcessById(42L, "process-1"))
                .isInstanceOf(ReservationProcessNotFoundException.class);
    }
}
