package turnos.availability.domain.model;

import lombok.*;

import java.time.Instant;
import java.util.List;

// La respuesta a una consulta de turnos libres: los turnos, por qué no hay ninguno y de cuándo son los datos
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreeSlotsResult {
    private List<FreeSlot> slots;
    private EmptyReason emptyReason;
    private boolean stale;
    private Instant dataAsOf;

    // El profesional no atiende ese día: no se consultaron ocupaciones, los datos son del momento de la consulta
    public static FreeSlotsResult notWorking(Instant now) {
        return new FreeSlotsResult(List.of(), EmptyReason.PROFESSIONAL_NOT_WORKING, false, now);
    }

    // Sin turnos (todos ocupados o ya comenzados), el motivo es que no quedan libres
    public static FreeSlotsResult of(List<FreeSlot> slots, boolean stale, Instant dataAsOf) {
        return new FreeSlotsResult(List.copyOf(slots), slots.isEmpty() ? EmptyReason.NO_FREE_SLOTS : null, stale, dataAsOf);
    }
}
