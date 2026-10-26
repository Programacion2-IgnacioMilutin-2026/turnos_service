package turnos.availability.infrastructure.gateway;

import org.junit.jupiter.api.Test;
import turnos.availability.application.exception.AvailabilityCatalogUnavailableException;
import turnos.availability.application.exception.AvailabilityIntegrationException;
import turnos.availability.application.exception.AvailabilityProfessionalNotFoundException;
import turnos.availability.domain.model.ProfessionalAgenda;
import turnos.catalog.domain.model.Professional;
import turnos.catalog.domain.model.ProfessionalResult;
import turnos.catalog.domain.model.WeeklySchedule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogAgendaAdapterTest {

    private final List<String> calls = new ArrayList<>();

    @Test
    void theProfessionalAndTheUserTokenAreForwardedAndTheAgendaKeepsEverySchedule() {
        WeeklySchedule schedule = new WeeklySchedule(DayOfWeek.MONDAY, LocalTime.of(12, 0), LocalTime.of(15, 0), 30, false);
        CatalogAgendaAdapter adapter = adapter(new ProfessionalResult.Found(new Professional(201L, true, List.of(schedule))));

        ProfessionalAgenda agenda = adapter.getAgenda(201L, "user-jwt");

        assertThat(calls).containsExactly("201 user-jwt");
        assertThat(agenda.isEnabled()).isTrue();
        assertThat(agenda.getSchedules()).singleElement().usingRecursiveComparison().isEqualTo(schedule);
    }

    @Test
    void aDisabledProfessionalKeepsItsFlag() {
        assertThat(adapter(new ProfessionalResult.Found(new Professional(201L, false, List.of()))).getAgenda(201L, "user-jwt").isEnabled())
                .isFalse();
    }

    @Test
    void eachErrorBecomesItsAvailabilityException() {
        assertThatThrownBy(() -> adapter(new ProfessionalResult.NotFound()).getAgenda(201L, "user-jwt"))
                .isInstanceOf(AvailabilityProfessionalNotFoundException.class);
        assertThatThrownBy(() -> adapter(new ProfessionalResult.Unavailable("down")).getAgenda(201L, "user-jwt"))
                .isInstanceOf(AvailabilityCatalogUnavailableException.class);
        assertThatThrownBy(() -> adapter(new ProfessionalResult.Rejected(401)).getAgenda(201L, "user-jwt"))
                .isInstanceOf(AvailabilityIntegrationException.class);
        assertThatThrownBy(() -> adapter(new ProfessionalResult.Failed("HTTP 500")).getAgenda(201L, "user-jwt"))
                .isInstanceOf(AvailabilityIntegrationException.class);
    }

    private CatalogAgendaAdapter adapter(ProfessionalResult result) {
        return new CatalogAgendaAdapter((professionalId, userToken) -> {
            calls.add(professionalId + " " + userToken);
            return result;
        });
    }
}
