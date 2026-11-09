package turnos.reservation.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import turnos.reservation.application.exception.ReservationProcessNotFoundException;
import turnos.reservation.domain.model.ReservationEventOutcome;
import turnos.reservation.domain.model.ReservationPhoneRequest;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationProcessPage;
import turnos.reservation.domain.model.ReservationResult;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.in.CancelReservationProcessUseCase;
import turnos.reservation.domain.ports.in.ConfirmReservationProcessUseCase;
import turnos.reservation.domain.ports.in.CreateReservationProcessUseCase;
import turnos.reservation.domain.ports.in.ExpireOverdueProcessesUseCase;
import turnos.reservation.domain.ports.in.FindReservationProcessesByUserUseCase;
import turnos.reservation.domain.ports.in.GetReservationProcessByIdUseCase;
import turnos.reservation.domain.ports.in.ProcessPhoneRequestUseCase;
import turnos.reservation.domain.ports.in.ProcessReservationResultUseCase;
import turnos.reservation.domain.ports.in.ReconcilePendingProcessesUseCase;
import turnos.reservation.domain.ports.in.SubmitPhoneUseCase;

import java.time.LocalDate;
import java.time.LocalTime;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final CreateReservationProcessUseCase createReservationProcessUseCase;
    private final ConfirmReservationProcessUseCase confirmReservationProcessUseCase;
    private final ProcessPhoneRequestUseCase processPhoneRequestUseCase;
    private final ProcessReservationResultUseCase processReservationResultUseCase;
    private final SubmitPhoneUseCase submitPhoneUseCase;
    private final GetReservationProcessByIdUseCase getReservationProcessByIdUseCase;
    private final FindReservationProcessesByUserUseCase findReservationProcessesByUserUseCase;
    private final CancelReservationProcessUseCase cancelReservationProcessUseCase;
    private final ExpireOverdueProcessesUseCase expireOverdueProcessesUseCase;
    private final ReconcilePendingProcessesUseCase reconcilePendingProcessesUseCase;

    public ReservationProcess createReservationProcess(Long userId, Long professionalId, LocalDate date,
                                                       LocalTime startTime, String userToken) {
        return createReservationProcessUseCase.createReservationProcess(userId, professionalId, date, startTime, userToken);
    }

    public ReservationProcess confirmReservationProcess(Long userId, String reservationProcessId) {
        return confirmReservationProcessUseCase.confirmReservationProcess(userId, reservationProcessId);
    }

    public ReservationProcess submitPhone(Long userId, String reservationProcessId, String phoneNumber) {
        return submitPhoneUseCase.submitPhone(userId, reservationProcessId, phoneNumber);
    }

    public ReservationProcess getReservationProcessById(Long userId, String reservationProcessId) {
        return getReservationProcessByIdUseCase.getReservationProcessById(userId, reservationProcessId)
                .orElseThrow(() -> new ReservationProcessNotFoundException(reservationProcessId));
    }

    public ReservationProcessPage findReservationProcessesByUser(Long userId, ReservationStatus status, int page, int size) {
        return findReservationProcessesByUserUseCase.findReservationProcessesByUser(userId, status, page, size);
    }

    public ReservationProcess cancelReservationProcess(Long userId, String reservationProcessId, String reason) {
        return cancelReservationProcessUseCase.cancelReservationProcess(userId, reservationProcessId, reason);
    }

    public void expireOverdueProcesses() {
        expireOverdueProcessesUseCase.expireOverdueProcesses();
    }

    public void reconcilePendingProcesses() {
        reconcilePendingProcessesUseCase.reconcilePendingProcesses();
    }

    public ReservationEventOutcome processPhoneRequest(ReservationPhoneRequest request) {
        return processPhoneRequestUseCase.processPhoneRequest(request);
    }

    public ReservationEventOutcome processReservationResult(ReservationResult result) {
        return processReservationResultUseCase.processReservationResult(result);
    }
}
