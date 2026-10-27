package turnos.availability.infrastructure.gateway;

import org.junit.jupiter.api.Test;
import turnos.availability.application.exception.AvailabilityCentralUnavailableException;
import turnos.availability.application.exception.AvailabilityIntegrationException;
import turnos.availability.application.exception.AvailabilityServiceDegradedException;
import turnos.catedra.domain.model.OccupanciesResult;
import turnos.catedra.domain.model.Occupancy;
import turnos.catedra.domain.model.OccupancyStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatedraOccupancyAdapterTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 26);

    private final List<String> calls = new ArrayList<>();

    @Test
    void confirmedAndHeldOccupanciesBecomeTheirStartTimes() {
        CatedraOccupancyAdapter adapter = adapter(new OccupanciesResult.Found(List.of(
                new Occupancy(LocalTime.of(12, 0), OccupancyStatus.CONFIRMED),
                new Occupancy(LocalTime.of(12, 30), OccupancyStatus.HELD))));

        assertThat(adapter.getOccupiedStartTimes(201L, DATE)).containsExactly(LocalTime.of(12, 0), LocalTime.of(12, 30));
        assertThat(calls).containsExactly("201 " + DATE);
    }

    @Test
    void eachErrorBecomesItsAvailabilityException() {
        assertThatThrownBy(() -> adapter(new OccupanciesResult.ConfigurationError("config")).getOccupiedStartTimes(201L, DATE))
                .isInstanceOf(AvailabilityServiceDegradedException.class);
        assertThatThrownBy(() -> adapter(new OccupanciesResult.Unavailable("down")).getOccupiedStartTimes(201L, DATE))
                .isInstanceOf(AvailabilityCentralUnavailableException.class);
        assertThatThrownBy(() -> adapter(new OccupanciesResult.Failed("HTTP 400")).getOccupiedStartTimes(201L, DATE))
                .isInstanceOf(AvailabilityIntegrationException.class);
    }

    private CatedraOccupancyAdapter adapter(OccupanciesResult result) {
        return new CatedraOccupancyAdapter((professionalId, date) -> {
            calls.add(professionalId + " " + date);
            return result;
        });
    }
}
