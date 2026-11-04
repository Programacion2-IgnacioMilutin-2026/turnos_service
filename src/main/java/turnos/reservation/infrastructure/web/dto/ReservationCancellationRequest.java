package turnos.reservation.infrastructure.web.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ReservationCancellationRequest {
    @Size(max = 500, message = "Reason must have at most 500 characters")
    private String reason;
}
