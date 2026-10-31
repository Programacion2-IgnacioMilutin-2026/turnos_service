package turnos.reservation.application.usecases;

import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.domain.model.ReservationStatus;
import turnos.reservation.domain.ports.out.ReservationProcessRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

// Como la base: guarda y devuelve copias, así cada operación trabaja sobre su propia lectura. Registra cada estado
// escrito por proceso, y puede fallar al actualizar o al marcar
final class CopyingReservationProcessRepository implements ReservationProcessRepository {

    private final Map<String, ReservationProcess> processes = new LinkedHashMap<>();
    private final Map<String, List<ReservationStatus>> histories = new LinkedHashMap<>();
    private volatile Predicate<ReservationProcess> failUpdate = process -> false;
    private volatile boolean failMarks;

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
            throw new IllegalStateException("database down");
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
        return Optional.ofNullable(processes.get(reservationProcessId))
                .filter(p -> p.getUserId().equals(userId)).map(CopyingReservationProcessRepository::copy);
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
                .needsReconciliation(p.isNeedsReconciliation()).requestEventId(p.getRequestEventId()).build();
    }
}
