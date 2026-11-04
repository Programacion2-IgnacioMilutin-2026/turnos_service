package turnos.reservation.infrastructure.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservationProcessPageResponse {
    private List<ReservationProcessResponse> items;
    private int page;
    private int size;
    private long totalItems;
}
