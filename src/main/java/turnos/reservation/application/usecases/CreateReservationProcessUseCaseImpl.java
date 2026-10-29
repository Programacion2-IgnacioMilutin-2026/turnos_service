package turnos.reservation.application.usecases;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.reservation.application.support.UserOperationLock;
import turnos.reservation.application.exception.ReservationHoldNotSavedException;
import turnos.reservation.application.exception.ReservationInvalidSlotException;
import turnos.reservation.application.exception.ReservationOpenProcessConflictException;
import turnos.reservation.application.exception.ReservationOpenProcessExistsException;
import turnos.reservation.application.exception.ReservationProfessionalDisabledException;
import turnos.reservation.application.exception.ReservationSlotInPastException;
import turnos.reservation.application.exception.ReservationUserNotFoundException;
import turnos.reservation.domain.model.ReservationHold;
import turnos.reservation.domain.model.ReservationPolicy;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationProfessional;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.in.CreateReservationProcessUseCase;
import turnos.reservation.domain.ports.out.HoldProvider;
import turnos.reservation.domain.ports.out.ProfessionalProvider;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;
import turnos.reservation.domain.ports.out.UserDirectory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateReservationProcessUseCaseImpl implements CreateReservationProcessUseCase {

    private final ReservationProcessRepository reservationProcessRepository;
    private final UserDirectory userDirectory;
    private final ProfessionalProvider professionalProvider;
    private final HoldProvider holdProvider;
    private final ReservationPolicy reservationPolicy;
    private final Clock clock;
    // El mismo candado que usa la confirmación: un bloqueo nunca corre a la vez que otra operación del mismo usuario
    private final UserOperationLock userOperationLock;

    // Primero lo que no necesita a nadie de afuera: el turno no pasó y el usuario existe. Lo demás, de a un pedido
    // por usuario, porque ver que no tiene un proceso abierto y guardar el nuevo tienen que ocurrir sin otro en el medio
    @Override
    public ReservationProcess createReservationProcess(Long userId, Long professionalId, LocalDate date,
                                                       LocalTime startTime, String userToken) {
        rejectPastSlot(date, startTime);
        if (!userDirectory.exists(userId)) {
            throw new ReservationUserNotFoundException(userId);
        }
        return userOperationLock.exclusivelyFor(userId, () -> holdAndSave(userId, professionalId, date, startTime, userToken));
    }

    private ReservationProcess holdAndSave(Long userId, Long professionalId, LocalDate date, LocalTime startTime,
                                           String userToken) {
        rejectIfAnOpenProcessRemains(userId);
        ReservationProfessional professional = professionalProvider.getProfessional(professionalId, userToken);
        if (!professional.isEnabled()) {
            throw new ReservationProfessionalDisabledException(professionalId);
        }
        if (!professional.offersSlot(date, startTime)) {
            throw new ReservationInvalidSlotException(professionalId);
        }
        ReservationHold hold = holdProvider.requestHold(professionalId, date, startTime, userId.toString());
        return save(ReservationProcess.builder()
                .reservationProcessId(hold.getReservationProcessId())
                .holdId(hold.getHoldId())
                .userId(userId)
                .professionalId(professionalId)
                .professionalFirstName(professional.getFirstName())
                .professionalLastName(professional.getLastName())
                .professionalCategoryName(professional.getCategoryName())
                .date(date)
                .startTime(startTime)
                .status(ReservationStatus.HELD)
                .expiresAt(hold.getExpiresAt())
                .build());
    }

    // Un turno que empieza justo ahora ya comenzó. La fecha y la hora son las de la zona de atención
    private void rejectPastSlot(LocalDate date, LocalTime startTime) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), reservationPolicy.zone());
        if (date.isBefore(now.toLocalDate())) {
            throw new ReservationSlotInPastException("date");
        }
        if (date.equals(now.toLocalDate()) && !startTime.isAfter(now.toLocalTime())) {
            throw new ReservationSlotInPastException("startTime");
        }
    }

    // Los procesos que vencieron se cierran primero; si después queda uno abierto, no se puede iniciar otro
    private void rejectIfAnOpenProcessRemains(Long userId) {
        Instant now = clock.instant();
        List<ReservationProcess> open = reservationProcessRepository.findOpenByUserId(userId);
        for (ReservationProcess process : open) {
            if (process.expireIfOverdue(now, reservationPolicy.safetyMargin())) {
                reservationProcessRepository.update(process.getReservationProcessId(), process);
            }
        }
        open.stream().filter(ReservationProcess::isOpen).findFirst().ifPresent(process -> {
            throw new ReservationOpenProcessExistsException(process);
        });
    }

    // El hold ya existe en la cátedra: si el proceso no se puede guardar, queda sin rastro local y vence solo.
    // Por eso se registra con los datos necesarios para encontrarlo
    private ReservationProcess save(ReservationProcess process) {
        try {
            return reservationProcessRepository.create(process);
        } catch (ReservationOpenProcessConflictException e) {
            log.error("Hold {} of reservation process {} was left without a local process (another open process "
                    + "was saved first); it expires at {}", process.getHoldId(), process.getReservationProcessId(),
                    process.getExpiresAt());
            ReservationProcess existing = reservationProcessRepository.findOpenByUserId(process.getUserId()).stream()
                    .findFirst()
                    .orElseThrow(() -> holdNotSaved(process, e));
            throw new ReservationOpenProcessExistsException(existing);
        } catch (RuntimeException e) {
            log.error("Hold {} of reservation process {} was granted but could not be saved; it expires at {}",
                    process.getHoldId(), process.getReservationProcessId(), process.getExpiresAt(), e);
            throw holdNotSaved(process, e);
        }
    }

    // Cuánto falta para que el turno se libere solo, redondeado hacia arriba: antes de eso no conviene reintentar
    private ReservationHoldNotSavedException holdNotSaved(ReservationProcess process, RuntimeException cause) {
        long millis = Duration.between(clock.instant(), process.getExpiresAt()).toMillis();
        long seconds = Math.max(0, (millis + 999) / 1000);
        return new ReservationHoldNotSavedException(process.getReservationProcessId(), process.getExpiresAt(), seconds, cause);
    }
}
