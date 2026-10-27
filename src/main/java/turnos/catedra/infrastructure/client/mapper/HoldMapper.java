package turnos.catedra.infrastructure.client.mapper;

import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.Hold;
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
}
