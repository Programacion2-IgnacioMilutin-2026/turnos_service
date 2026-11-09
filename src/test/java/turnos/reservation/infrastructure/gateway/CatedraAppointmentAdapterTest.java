package turnos.reservation.infrastructure.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.catedra.domain.model.Appointment;
import turnos.catedra.domain.model.AppointmentCancellationResult;
import turnos.catedra.domain.model.AppointmentLookupResult;
import turnos.catedra.domain.model.AppointmentStatus;
import turnos.catedra.domain.ports.in.CancelAppointmentUseCase;
import turnos.catedra.domain.ports.in.FindAppointmentUseCase;
import turnos.reservation.application.exception.ReservationAppointmentNotFoundException;
import turnos.reservation.application.exception.ReservationAppointmentOwnerMismatchException;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationNotCancellableException;
import turnos.reservation.application.exception.ReservationServiceDegradedException;
import turnos.reservation.domain.model.ReservationRecord;
import turnos.reservation.domain.model.ReservationRecordStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class CatedraAppointmentAdapterTest {

    private static final Instant CANCELLED_AT = Instant.parse("2026-11-04T15:10:00Z");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-11-03T10:00:00Z");
    private static final FindAppointmentUseCase NO_SEARCH = (patient, process) -> {
        throw new UnsupportedOperationException("Cancelling does not search");
    };
    private static final CancelAppointmentUseCase NO_CANCELLATION = (process, reason) -> {
        throw new UnsupportedOperationException("Searching does not cancel");
    };

    @Test
    void theProcessAndTheReasonReachCatedraAndTheDateComesBack() {
        String[] received = new String[2];
        CatedraAppointmentAdapter adapter = new CatedraAppointmentAdapter((id, reason) -> {
            received[0] = id;
            received[1] = reason;
            return new AppointmentCancellationResult.Cancelled(CANCELLED_AT);
        }, NO_SEARCH);

        assertThat(adapter.cancel("process-1", "Cambio")).isEqualTo(CANCELLED_AT);
        assertThat(received).containsExactly("process-1", "Cambio");
    }

    @Test
    void aReservationCatedraDoesNotKnowIsLoggedAsAnError(CapturedOutput output) {
        assertThatThrownBy(() -> adapterAnswering(new AppointmentCancellationResult.NotFound()).cancel("process-1", null))
                .isInstanceOf(ReservationAppointmentNotFoundException.class);
        assertThat(output).contains("ERROR").contains("Catedra does not know reservation process process-1");
    }

    @Test
    void aReservationOfAnotherAccountIsLoggedAsAnError(CapturedOutput output) {
        assertThatThrownBy(() -> adapterAnswering(new AppointmentCancellationResult.OwnerMismatch()).cancel("process-1", null))
                .isInstanceOf(ReservationAppointmentOwnerMismatchException.class);
        assertThat(output).contains("ERROR").contains("belongs to another technical account");
    }

    @Test
    void aReservationThatCannotBeCancelledIsLogged(CapturedOutput output) {
        assertThatThrownBy(() -> adapterAnswering(new AppointmentCancellationResult.NotCancellable()).cancel("process-1", null))
                .isInstanceOf(ReservationNotCancellableException.class);
        assertThat(output).contains("WARN").contains("does not allow cancelling reservation process process-1");
    }

    @Test
    void aConfigurationErrorMeansTheServiceIsDegraded() {
        assertThatThrownBy(() -> adapterAnswering(new AppointmentCancellationResult.ConfigurationError("missing")).cancel("process-1", null))
                .isInstanceOf(ReservationServiceDegradedException.class);
    }

    @Test
    void aCatedraThatDoesNotAnswerIsUnavailable() {
        assertThatThrownBy(() -> adapterAnswering(new AppointmentCancellationResult.Unavailable("down")).cancel("process-1", null))
                .isInstanceOf(ReservationCentralUnavailableException.class);
    }

    @Test
    void anUnexpectedAnswerIsAnIntegrationErrorAndItIsLogged(CapturedOutput output) {
        assertThatThrownBy(() -> adapterAnswering(new AppointmentCancellationResult.Failed("unreadable")).cancel("process-1", null))
                .isInstanceOf(ReservationIntegrationException.class);
        assertThat(output).contains("ERROR").contains("unreadable");
    }

    private static CatedraAppointmentAdapter adapterAnswering(AppointmentCancellationResult result) {
        return new CatedraAppointmentAdapter((id, reason) -> result, NO_SEARCH);
    }

    // Búsqueda de la reserva de un proceso

    @Test
    void thePatientAndTheProcessReachCatedraAndTheRecordComesBackWithEveryField() {
        Appointment appointment = new Appointment("process-1", "1042", AppointmentStatus.CANCELLED, 8251L, CONFIRMED_AT,
                CANCELLED_AT, "Paciente no asiste", 15L, LocalDate.of(2026, 11, 9), LocalTime.of(10, 30));
        String[] received = new String[2];
        CatedraAppointmentAdapter adapter = new CatedraAppointmentAdapter(NO_CANCELLATION, (patient, process) -> {
            received[0] = patient;
            received[1] = process;
            return new AppointmentLookupResult.Found(appointment);
        });

        assertThat(adapter.find("1042", "process-1")).contains(new ReservationRecord("process-1",
                ReservationRecordStatus.CANCELLED, 8251L, CONFIRMED_AT, CANCELLED_AT, "Paciente no asiste", 15L,
                LocalDate.of(2026, 11, 9), LocalTime.of(10, 30)));
        assertThat(received).containsExactly("1042", "process-1");
    }

    @ParameterizedTest
    @CsvSource({"PHONE_PENDING, PHONE_PENDING", "CONFIRMED, CONFIRMED", "CANCELLED, CANCELLED", "FAILED, FAILED"})
    void eachStatusOfCatedraKeepsItsMeaning(AppointmentStatus catedra, ReservationRecordStatus reservation) {
        assertThat(finderAnswering(new AppointmentLookupResult.Found(appointment(catedra))).find("1042", "process-1"))
                .map(ReservationRecord::status).contains(reservation);
    }

    @Test
    void withoutARecordThereIsNothing() {
        assertThat(finderAnswering(new AppointmentLookupResult.NotFound()).find("1042", "process-1")).isEmpty();
    }

    @Test
    void anUnknownStatusCannotBeUsed() {
        assertThatThrownBy(() -> finderAnswering(new AppointmentLookupResult.Found(appointment(AppointmentStatus.UNKNOWN)))
                .find("1042", "process-1")).isInstanceOf(ReservationIntegrationException.class);
    }

    @Test
    void eachProblemOfTheSearchBecomesItsException() {
        assertThatThrownBy(() -> finderAnswering(new AppointmentLookupResult.ConfigurationError("missing")).find("1042", "process-1"))
                .isInstanceOf(ReservationServiceDegradedException.class);
        assertThatThrownBy(() -> finderAnswering(new AppointmentLookupResult.Unavailable("down")).find("1042", "process-1"))
                .isInstanceOf(ReservationCentralUnavailableException.class);
        assertThatThrownBy(() -> finderAnswering(new AppointmentLookupResult.Failed("incomplete")).find("1042", "process-1"))
                .isInstanceOf(ReservationIntegrationException.class);
    }

    private static CatedraAppointmentAdapter finderAnswering(AppointmentLookupResult result) {
        return new CatedraAppointmentAdapter(NO_CANCELLATION, (patient, process) -> result);
    }

    private static Appointment appointment(AppointmentStatus status) {
        return new Appointment("process-1", "1042", status, 8251L, CONFIRMED_AT, CANCELLED_AT, null, 15L,
                LocalDate.of(2026, 11, 9), LocalTime.of(10, 30));
    }
}
