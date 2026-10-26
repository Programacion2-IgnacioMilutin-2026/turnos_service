package turnos.availability.infrastructure.web.dto;

import lombok.*;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreeSlotsResponse {
    private List<FreeSlotResponse> slots;
    private String emptyReason;
    private boolean stale;
    private Instant dataAsOf;
}
