package turnos.reservation.infrastructure.gateway;

import org.junit.jupiter.api.Test;
import turnos.catedra.domain.model.AdditionalInformationSubmissionResult;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.domain.model.ReservationProcess;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatedraPhoneAdapterTest {

    private static final ReservationProcess PROCESS = ReservationProcess.builder().reservationProcessId("process-1")
            .holdId("hold-1").requestEventId("request-event-1").phoneNumber("+5492615551234").build();

    private final List<String> calls = new ArrayList<>();

    @Test
    void itCanSubmitWhenCatedraHasAnIntegration() {
        assertThat(adapter(true, new AdditionalInformationSubmissionResult.Published()).canSubmit()).isTrue();
        assertThat(adapter(false, new AdditionalInformationSubmissionResult.Published()).canSubmit()).isFalse();
    }

    @Test
    void theDataOfTheProcessAreForwarded() {
        adapter(true, new AdditionalInformationSubmissionResult.Published()).submit(PROCESS);

        assertThat(calls).containsExactly("process-1 hold-1 request-event-1 +5492615551234");
    }

    @Test
    void whatWasNotPublishedBecomesTheExceptionWithItsReason() {
        CatedraPhoneAdapter adapter = adapter(true, new AdditionalInformationSubmissionResult.NotPublished("broker down"));

        assertThatThrownBy(() -> adapter.submit(PROCESS)).isInstanceOf(ReservationCentralUnavailableException.class)
                .hasMessageContaining("broker down");
    }

    private CatedraPhoneAdapter adapter(boolean available, AdditionalInformationSubmissionResult result) {
        return new CatedraPhoneAdapter(() -> available, (reservationProcessId, holdId, requestEventId, phoneNumber) -> {
            calls.add(String.join(" ", reservationProcessId, holdId, requestEventId, phoneNumber));
            return result;
        });
    }
}
