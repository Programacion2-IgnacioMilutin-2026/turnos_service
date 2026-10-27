package turnos.reservation.infrastructure.gateway;

import org.junit.jupiter.api.Test;
import turnos.catalog.domain.model.Professional;
import turnos.catalog.domain.model.ProfessionalResult;
import turnos.catalog.domain.model.WeeklySchedule;
import turnos.reservation.application.exception.ReservationCatalogUnavailableException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationProfessionalNotFoundException;
import turnos.reservation.domain.model.ReservationProfessional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogProfessionalAdapterTest {

    private final List<String> calls = new ArrayList<>();

    @Test
    void theProfessionalAndTheUserTokenAreForwardedAndEveryUsedFieldIsCopied() {
        WeeklySchedule schedule = new WeeklySchedule(DayOfWeek.MONDAY, LocalTime.of(12, 0), LocalTime.of(15, 0), 30, true);
        Professional professional = Professional.builder().id(201L).firstName("Ana").lastName("Gomez")
                .categoryName("Clinica medica").enabled(true).weeklySchedules(List.of(schedule)).build();

        ReservationProfessional result = adapter(new ProfessionalResult.Found(professional)).getProfessional(201L, "user-jwt");

        assertThat(calls).containsExactly("201 user-jwt");
        assertThat(result).usingRecursiveComparison().ignoringFields("schedules").isEqualTo(professional);
        assertThat(result.getSchedules()).singleElement().usingRecursiveComparison().isEqualTo(schedule);
    }

    @Test
    void eachCatalogProblemBecomesItsException() {
        assertThatThrownBy(() -> adapter(new ProfessionalResult.NotFound()).getProfessional(201L, "user-jwt"))
                .isInstanceOf(ReservationProfessionalNotFoundException.class);
        assertThatThrownBy(() -> adapter(new ProfessionalResult.Unavailable("down")).getProfessional(201L, "user-jwt"))
                .isInstanceOf(ReservationCatalogUnavailableException.class);
        assertThatThrownBy(() -> adapter(new ProfessionalResult.Rejected(401)).getProfessional(201L, "user-jwt"))
                .isInstanceOf(ReservationIntegrationException.class);
        assertThatThrownBy(() -> adapter(new ProfessionalResult.Failed("HTTP 500")).getProfessional(201L, "user-jwt"))
                .isInstanceOf(ReservationIntegrationException.class);
    }

    private CatalogProfessionalAdapter adapter(ProfessionalResult result) {
        return new CatalogProfessionalAdapter((professionalId, userToken) -> {
            calls.add(professionalId + " " + userToken);
            return result;
        });
    }
}
