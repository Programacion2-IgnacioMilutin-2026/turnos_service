package turnos.reservation.infrastructure.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.catedra.domain.model.Hold;
import turnos.catedra.domain.model.HoldConfirmation;
import turnos.catedra.domain.model.HoldConfirmationResult;
import turnos.catedra.domain.model.HoldResult;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationHoldExpiredException;
import turnos.reservation.application.exception.ReservationHoldNotFoundException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationInvalidSlotException;
import turnos.reservation.application.exception.ReservationPatientMismatchException;
import turnos.reservation.application.exception.ReservationProfessionalDisabledException;
import turnos.reservation.application.exception.ReservationProfessionalNotFoundException;
import turnos.reservation.application.exception.ReservationServiceDegradedException;
import turnos.reservation.application.exception.ReservationSlotNotAvailableException;
import turnos.reservation.domain.model.ReservationHold;
import turnos.reservation.domain.model.ReservationHoldConfirmation;
import turnos.reservation.domain.model.ReservationPatient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class CatedraHoldAdapterTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 2);
    private static final LocalTime NOON = LocalTime.of(12, 0);
    private static final ReservationPatient PATIENT = new ReservationPatient("María", "Gómez");

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

    @Test
    void theHoldAndThePatientAreForwardedAndTheExpirationIsCopied() {
        Instant expiresAt = Instant.parse("2026-10-28T05:04:54.812004682Z");

        ReservationHoldConfirmation result = confirmationAdapter(new HoldConfirmationResult.Confirmed(
                new HoldConfirmation("process-1", expiresAt))).confirmHold("hold-1", 201L, "42", PATIENT);

        assertThat(calls).containsExactly("hold-1 42 María Gómez");
        assertThat(result.isAlreadyConfirmed()).isFalse();
        assertThat(result.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void anAlreadyConfirmedHoldIsNotAnErrorButHasNoExpiration() {
        ReservationHoldConfirmation result = confirmationAdapter(new HoldConfirmationResult.AlreadyConfirmed())
                .confirmHold("hold-1", 201L, "42", PATIENT);

        assertThat(result.isAlreadyConfirmed()).isTrue();
        assertThat(result.getExpiresAt()).isNull();
    }

    @Test
    void eachConfirmationAnswerThatIsNotAConfirmationBecomesItsException() {
        Map<HoldConfirmationResult, Class<? extends RuntimeException>> expected = Map.ofEntries(
                Map.entry(new HoldConfirmationResult.HoldNotFound(), ReservationHoldNotFoundException.class),
                Map.entry(new HoldConfirmationResult.HoldExpired(), ReservationHoldExpiredException.class),
                Map.entry(new HoldConfirmationResult.OwnerMismatch(), ReservationIntegrationException.class),
                Map.entry(new HoldConfirmationResult.PatientMismatch(), ReservationPatientMismatchException.class),
                Map.entry(new HoldConfirmationResult.ProfessionalNotFound(), ReservationProfessionalNotFoundException.class),
                Map.entry(new HoldConfirmationResult.InvalidSlot(), ReservationInvalidSlotException.class),
                Map.entry(new HoldConfirmationResult.SlotNotAvailable(), ReservationSlotNotAvailableException.class),
                Map.entry(new HoldConfirmationResult.ConfigurationError("config"), ReservationServiceDegradedException.class),
                Map.entry(new HoldConfirmationResult.Unavailable("down"), ReservationCentralUnavailableException.class),
                Map.entry(new HoldConfirmationResult.Failed("HTTP 502"), ReservationIntegrationException.class));

        expected.forEach((answer, exception) -> assertThatThrownBy(
                () -> confirmationAdapter(answer).confirmHold("hold-1", 201L, "42", PATIENT)).isInstanceOf(exception));
    }

    @Test
    void aHoldOfAnotherTechnicalAccountIsLoggedAsASeriousError(CapturedOutput output) {
        assertThatThrownBy(() -> confirmationAdapter(new HoldConfirmationResult.OwnerMismatch())
                .confirmHold("hold-1", 201L, "42", PATIENT)).isInstanceOf(ReservationIntegrationException.class);

        assertThat(output.getAll()).contains("ERROR").contains("hold-1 belongs to another technical account");
    }

    private CatedraHoldAdapter adapter(HoldResult result) {
        return new CatedraHoldAdapter((professionalId, date, startTime, externalPatientId) -> {
            calls.add(professionalId + " " + date + " " + startTime + " " + externalPatientId);
            return result;
        }, (holdId, externalPatientId, firstName, lastName) -> {
            throw new UnsupportedOperationException("Blocking a slot never confirms it");
        });
    }

    private CatedraHoldAdapter confirmationAdapter(HoldConfirmationResult result) {
        return new CatedraHoldAdapter((professionalId, date, startTime, externalPatientId) -> {
            throw new UnsupportedOperationException("Confirming a hold never creates one");
        }, (holdId, externalPatientId, firstName, lastName) -> {
            calls.add(holdId + " " + externalPatientId + " " + firstName + " " + lastName);
            return result;
        });
    }
}
