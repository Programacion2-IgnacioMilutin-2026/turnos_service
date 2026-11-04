package turnos.reservation.infrastructure.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.catedra.domain.model.AppointmentCancellationResult;
import turnos.reservation.application.exception.ReservationAppointmentNotFoundException;
import turnos.reservation.application.exception.ReservationAppointmentOwnerMismatchException;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationIntegrationException;
import turnos.reservation.application.exception.ReservationNotCancellableException;
import turnos.reservation.application.exception.ReservationServiceDegradedException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class CatedraAppointmentAdapterTest {

    private static final Instant CANCELLED_AT = Instant.parse("2026-11-04T15:10:00Z");

    @Test
    void theProcessAndTheReasonReachCatedraAndTheDateComesBack() {
        String[] received = new String[2];
        CatedraAppointmentAdapter adapter = new CatedraAppointmentAdapter((id, reason) -> {
            received[0] = id;
            received[1] = reason;
            return new AppointmentCancellationResult.Cancelled(CANCELLED_AT);
        });

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
        return new CatedraAppointmentAdapter((id, reason) -> result);
    }
}
