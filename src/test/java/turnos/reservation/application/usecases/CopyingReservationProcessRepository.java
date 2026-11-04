package turnos.reservation.application.usecases;

import turnos.reservation.domain.model.ReservationListCriteria;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationProcessPage;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

// Como la base: guarda y devuelve copias, así cada operación trabaja sobre su propia lectura. Registra cada estado
// escrito por proceso, puede fallar al actualizar o al marcar, y deja simular lo que pasa justo después de una lectura
final class CopyingReservationProcessRepository implements ReservationProcessRepository {

    private final Map<String, ReservationProcess> processes = new LinkedHashMap<>();
    private final Map<String, List<ReservationStatus>> histories = new LinkedHashMap<>();
    private volatile Predicate<ReservationProcess> failUpdate = process -> false;
    private volatile RuntimeException updateError = new IllegalStateException("database down");
    private volatile boolean failMarks;
    private volatile Runnable afterOwnerRead = () -> { };

    synchronized void add(ReservationProcess process) {
        processes.put(process.getReservationProcessId(), copy(process));
    }

    synchronized ReservationProcess stored(String reservationProcessId) {
        return copy(processes.get(reservationProcessId));
    }

    synchronized List<ReservationStatus> history(String reservationProcessId) {
        return new ArrayList<>(histories.getOrDefault(reservationProcessId, List.of()));
    }

    void failUpdatesWhen(Predicate<ReservationProcess> condition) {
        failUpdate = condition;
    }

    void failUpdatesWith(RuntimeException error) {
        updateError = error;
        failUpdate = process -> true;
    }

    // Lo que otra operación hace después de la próxima lectura por dueño, antes de que el que leyó siga adelante
    void afterNextOwnerRead(Runnable action) {
        afterOwnerRead = action;
    }

    void failMarks() {
        failMarks = true;
    }

    @Override
    public synchronized ReservationProcess create(ReservationProcess process) {
        add(process);
        histories.computeIfAbsent(process.getReservationProcessId(), id -> new ArrayList<>()).add(process.getStatus());
        return copy(process);
    }

    @Override
    public synchronized Optional<ReservationProcess> update(String reservationProcessId, ReservationProcess process) {
        if (!processes.containsKey(reservationProcessId)) {
            return Optional.empty();
        }
        if (failUpdate.test(process)) {
            throw updateError;
        }
        processes.put(reservationProcessId, copy(process));
        histories.computeIfAbsent(reservationProcessId, id -> new ArrayList<>()).add(process.getStatus());
        return Optional.of(copy(process));
    }

    @Override
    public synchronized List<ReservationProcess> findOpenByUserId(Long userId) {
        return processes.values().stream().filter(p -> p.getUserId().equals(userId) && p.isOpen())
                .map(CopyingReservationProcessRepository::copy).toList();
    }

    @Override
    public synchronized Optional<ReservationProcess> findById(String reservationProcessId) {
        return Optional.ofNullable(processes.get(reservationProcessId)).map(CopyingReservationProcessRepository::copy);
    }

    @Override
    public synchronized Optional<ReservationProcess> findByIdAndUserId(String reservationProcessId, Long userId) {
        Optional<ReservationProcess> found = Optional.ofNullable(processes.get(reservationProcessId))
                .filter(p -> p.getUserId().equals(userId)).map(CopyingReservationProcessRepository::copy);
        Runnable action = afterOwnerRead;
        afterOwnerRead = () -> { };
        action.run();
        return found;
    }

    // Filtrar y ordenar lo hace la base: se prueba contra Postgres, no acá
    @Override
    public ReservationProcessPage findListable(ReservationListCriteria criteria) {
        throw new UnsupportedOperationException("Listing is tested against the database");
    }

    // Cuál trae y en qué orden se prueba contra Postgres; acá alcanza con entregar la misma regla, y copias como la base
    @Override
    public synchronized List<ReservationProcess> findOverdue(Instant expiredBefore) {
        return processes.values().stream()
                .filter(p -> p.isOpen() && p.getExpiresAt().isBefore(expiredBefore)
                        && !(p.getStatus() == ReservationStatus.PHONE_SUBMITTED && p.isNeedsReconciliation()))
                .sorted(Comparator.comparing(ReservationProcess::getExpiresAt).thenComparing(ReservationProcess::getReservationProcessId))
                .map(CopyingReservationProcessRepository::copy).toList();
    }

    @Override
    public synchronized void markForReconciliation(String reservationProcessId) {
        if (failMarks) {
            throw new IllegalStateException("database down");
        }
        processes.get(reservationProcessId).markForReconciliation();
    }

    private static ReservationProcess copy(ReservationProcess p) {
        return ReservationProcess.builder().reservationProcessId(p.getReservationProcessId()).holdId(p.getHoldId())
                .userId(p.getUserId()).professionalId(p.getProfessionalId())
                .professionalFirstName(p.getProfessionalFirstName()).professionalLastName(p.getProfessionalLastName())
                .professionalCategoryName(p.getProfessionalCategoryName()).date(p.getDate())
                .startTime(p.getStartTime()).status(p.getStatus()).expiresAt(p.getExpiresAt())
                .needsReconciliation(p.isNeedsReconciliation()).requestEventId(p.getRequestEventId()).phoneNumber(p.getPhoneNumber())
                .reservationId(p.getReservationId()).confirmedAt(p.getConfirmedAt()).cancelledAt(p.getCancelledAt())
                .cancellationReason(p.getCancellationReason()).rejectionReason(p.getRejectionReason()).build();
    }
}
