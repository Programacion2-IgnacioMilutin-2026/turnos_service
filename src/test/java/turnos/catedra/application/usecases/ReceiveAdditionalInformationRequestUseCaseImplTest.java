package turnos.catedra.application.usecases;

import org.junit.jupiter.api.Test;
import turnos.catedra.application.exception.CatedraEventNotSavedException;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.AppointmentResult;
import turnos.catedra.domain.model.CatedraEventResult;
import turnos.catedra.domain.ports.out.CatedraEventHandler;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiveAdditionalInformationRequestUseCaseImplTest {

    private static final AdditionalInformationRequest REQUEST = AdditionalInformationRequest.builder()
            .eventId("request-event-1").reservationProcessId("process-1").holdId("hold-1")
            .expiresAt(Instant.parse("2026-11-02T13:10:00Z")).build();

    private final List<AdditionalInformationRequest> handled = new CopyOnWriteArrayList<>();

    @Test
    void theRequestReachesWhoeverProcessesItAsIs() {
        useCase(new CatedraEventResult.Processed()).receiveAdditionalInformationRequest(REQUEST);

        assertThat(handled).containsExactly(REQUEST);
    }

    @Test
    void aProcessedOrDiscardedRequestEndsWithoutError() {
        useCase(new CatedraEventResult.Processed()).receiveAdditionalInformationRequest(REQUEST);
        useCase(new CatedraEventResult.Discarded("ORPHAN")).receiveAdditionalInformationRequest(REQUEST);

        assertThat(handled).hasSize(2);
    }

    @Test
    void aRequestThatCouldNotBeSavedHasToBeTriedAgain() {
        assertThatThrownBy(() -> useCase(new CatedraEventResult.NotSaved("database down"))
                .receiveAdditionalInformationRequest(REQUEST))
                .isInstanceOf(CatedraEventNotSavedException.class)
                .hasMessage("Catedra event request-event-1 could not be saved: database down");
    }

    private ReceiveAdditionalInformationRequestUseCaseImpl useCase(CatedraEventResult result) {
        return new ReceiveAdditionalInformationRequestUseCaseImpl(new CatedraEventHandler() {
            @Override
            public CatedraEventResult handleAdditionalInformationRequest(AdditionalInformationRequest request) {
                handled.add(request);
                return result;
            }

            @Override
            public CatedraEventResult handleAppointmentResult(AppointmentResult appointmentResult) {
                throw new AssertionError("A phone request does not go through the results");
            }
        });
    }
}
