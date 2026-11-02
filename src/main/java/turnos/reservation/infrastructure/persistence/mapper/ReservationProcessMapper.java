package turnos.reservation.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.infrastructure.persistence.entity.ReservationProcessEntity;

@Component
public class ReservationProcessMapper {

    public ReservationProcessEntity toEntity(ReservationProcess reservationProcess) {
        if (reservationProcess == null) {
            return null;
        }
        return ReservationProcessEntity.builder()
                .reservationProcessId(reservationProcess.getReservationProcessId())
                .holdId(reservationProcess.getHoldId())
                .userId(reservationProcess.getUserId())
                .professionalId(reservationProcess.getProfessionalId())
                .professionalFirstName(reservationProcess.getProfessionalFirstName())
                .professionalLastName(reservationProcess.getProfessionalLastName())
                .professionalCategoryName(reservationProcess.getProfessionalCategoryName())
                .date(reservationProcess.getDate())
                .startTime(reservationProcess.getStartTime())
                .status(reservationProcess.getStatus())
                .expiresAt(reservationProcess.getExpiresAt())
                .needsReconciliation(reservationProcess.isNeedsReconciliation())
                .requestEventId(reservationProcess.getRequestEventId())
                .build();
    }

    public ReservationProcess toDomainModel(ReservationProcessEntity entity) {
        if (entity == null) {
            return null;
        }
        return ReservationProcess.builder()
                .reservationProcessId(entity.getReservationProcessId())
                .holdId(entity.getHoldId())
                .userId(entity.getUserId())
                .professionalId(entity.getProfessionalId())
                .professionalFirstName(entity.getProfessionalFirstName())
                .professionalLastName(entity.getProfessionalLastName())
                .professionalCategoryName(entity.getProfessionalCategoryName())
                .date(entity.getDate())
                .startTime(entity.getStartTime())
                .status(entity.getStatus())
                .expiresAt(entity.getExpiresAt())
                .needsReconciliation(entity.isNeedsReconciliation())
                .requestEventId(entity.getRequestEventId())
                .build();
    }
}
