package turnos.reservation.application.service;

import org.junit.jupiter.api.Test;
import turnos.reservation.application.exception.ReservationProcessNotFoundException;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationProcessPage;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.in.CancelReservationProcessUseCase;
import turnos.reservation.domain.ports.in.ConfirmReservationProcessUseCase;
import turnos.reservation.domain.ports.in.CreateReservationProcessUseCase;
import turnos.reservation.domain.ports.in.ExpireOverdueProcessesUseCase;
import turnos.reservation.domain.ports.in.FindReservationProcessesByUserUseCase;
import turnos.reservation.domain.ports.in.GetReservationProcessByIdUseCase;
import turnos.reservation.domain.ports.in.ProcessPhoneRequestUseCase;
import turnos.reservation.domain.ports.in.ProcessReservationResultUseCase;
import turnos.reservation.domain.ports.in.SubmitPhoneUseCase;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationServiceTest {

    private final GetReservationProcessByIdUseCase getReservationProcessById = mock(GetReservationProcessByIdUseCase.class);
    private final FindReservationProcessesByUserUseCase findReservationProcessesByUser = mock(FindReservationProcessesByUserUseCase.class);
    private final CancelReservationProcessUseCase cancelReservationProcess = mock(CancelReservationProcessUseCase.class);
    private final ExpireOverdueProcessesUseCase expireOverdueProcesses = mock(ExpireOverdueProcessesUseCase.class);
    private final ReservationService service = new ReservationService(mock(CreateReservationProcessUseCase.class),
            mock(ConfirmReservationProcessUseCase.class), mock(ProcessPhoneRequestUseCase.class),
            mock(ProcessReservationResultUseCase.class), mock(SubmitPhoneUseCase.class), getReservationProcessById,
            findReservationProcessesByUser, cancelReservationProcess, expireOverdueProcesses);

    @Test
    void aProcessFoundIsReturned() {
        ReservationProcess process = new ReservationProcess();
        when(getReservationProcessById.getReservationProcessById(42L, "process-1")).thenReturn(Optional.of(process));

        assertThat(service.getReservationProcessById(42L, "process-1")).isSameAs(process);
    }

    @Test
    void theReservationsOfAUserAreAskedAsTheyCame() {
        ReservationProcessPage page = new ReservationProcessPage(List.of(), 0);
        when(findReservationProcessesByUser.findReservationProcessesByUser(42L, ReservationStatus.CONFIRMED, 2, 25)).thenReturn(page);

        assertThat(service.findReservationProcessesByUser(42L, ReservationStatus.CONFIRMED, 2, 25)).isSameAs(page);
    }

    @Test
    void aCancellationIsAskedAsItCame() {
        ReservationProcess process = new ReservationProcess();
        when(cancelReservationProcess.cancelReservationProcess(42L, "process-1", "Cambio")).thenReturn(process);

        assertThat(service.cancelReservationProcess(42L, "process-1", "Cambio")).isSameAs(process);
    }

    @Test
    void aSweepIsDelegatedToItsUseCase() {
        service.expireOverdueProcesses();

        verify(expireOverdueProcesses).expireOverdueProcesses();
    }

    @Test
    void aProcessNotFoundIsAnError() {
        when(getReservationProcessById.getReservationProcessById(42L, "process-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getReservationProcessById(42L, "process-1"))
                .isInstanceOf(ReservationProcessNotFoundException.class);
    }
}
