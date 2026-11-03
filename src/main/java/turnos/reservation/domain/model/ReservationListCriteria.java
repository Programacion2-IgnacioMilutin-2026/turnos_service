package turnos.reservation.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

// Qué reservas de un usuario se piden. status vacío significa todas las que se pueden listar. Un proceso que espera al
// usuario con un vencimiento anterior a expiredBefore no se lista. today y currentTime, en la zona de atención, separan
// los turnos que vienen de los que ya comenzaron
public record ReservationListCriteria(Long userId, ReservationStatus status, Instant expiredBefore, LocalDate today,
                                      LocalTime currentTime, int page, int size) {
}
