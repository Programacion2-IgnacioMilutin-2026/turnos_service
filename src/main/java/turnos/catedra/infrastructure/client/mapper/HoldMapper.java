package turnos.catedra.infrastructure.client.mapper;

import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.Hold;
import turnos.catedra.domain.model.HoldConfirmation;
import turnos.catedra.infrastructure.client.dto.HoldConfirmationResponse;
import turnos.catedra.infrastructure.client.dto.HoldResponse;

@Component
public class HoldMapper {

    public Hold toDomain(HoldResponse response) {
        if (response == null) {
            return null;
        }
        return Hold.builder()
                .holdId(response.getHoldId())
                .reservationProcessId(response.getReservationProcessId())
                .expiresAt(response.getExpiresAt())
                .build();
    }

    public HoldConfirmation toDomain(HoldConfirmationResponse response) {
        if (response == null) {
            return null;
        }
        return HoldConfirmation.builder()
                .reservationProcessId(response.getReservationProcessId())
                .expiresAt(response.getExpiresAt())
                .build();
    }
}
