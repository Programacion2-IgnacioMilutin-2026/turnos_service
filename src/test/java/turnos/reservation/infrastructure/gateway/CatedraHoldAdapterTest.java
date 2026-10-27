package turnos.reservation.infrastructure.gateway;

import org.junit.jupiter.api.Test;
import turnos.catedra.domain.model.Hold;
import turnos.catedra.domain.model.HoldResult;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationInvalidSlotException;
import turnos.reservation.application.exception.ReservationProfessionalDisabledException;
import turnos.reservation.application.exception.ReservationProfessionalNotFoundException;
import turnos.reservation.application.exception.ReservationServiceDegradedException;
import turnos.reservation.application.exception.ReservationSlotNotAvailableException;
import turnos.reservation.domain.model.ReservationHold;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatedraHoldAdapterTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final LocalTime NOON = LocalTime.of(12, 0);

    private final List<String> calls = new ArrayList<>();

    @Test
    void theSlotAndThePatientAreForwardedAndTheHoldIsCopied() {
        Hold hold = new Hold("hold-1", "process-1", Instant.parse("2026-10-27T04:44:38Z"));

        ReservationHold result = adapter(new HoldResult.Created(hold)).requestHold(201L, DATE, NOON, "42");

        assertThat(calls).containsExactly("201 2026-11-02 12:00 42");
        assertThat(result).usingRecursiveComparison().isEqualTo(hold);
    }

    @Test
    void eachAnswerThatIsNotAHoldBecomesItsException() {
        Map<HoldResult, Class<? extends RuntimeException>> expected = Map.of(
                new HoldResult.ProfessionalNotFound(), ReservationProfessionalNotFoundException.class,
                new HoldResult.ProfessionalDisabled(), ReservationProfessionalDisabledException.class,
                new HoldResult.InvalidSlot(), ReservationInvalidSlotException.class,
                new HoldResult.SlotNotAvailable(), ReservationSlotNotAvailableException.class,
                new HoldResult.ConfigurationError("config"), ReservationServiceDegradedException.class,
                new HoldResult.Unavailable("down"), ReservationCentralUnavailableException.class,
                new HoldResult.Failed("HTTP 502"), ReservationIntegrationException.class);

        expected.forEach((answer, exception) ->
                assertThatThrownBy(() -> adapter(answer).requestHold(201L, DATE, NOON, "42")).isInstanceOf(exception));
    }

    private CatedraHoldAdapter adapter(HoldResult result) {
        return new CatedraHoldAdapter((professionalId, date, startTime, externalPatientId) -> {
            calls.add(professionalId + " " + date + " " + startTime + " " + externalPatientId);
            return result;
        });
    }
}
