package turnos.catedra.application.usecases;

import org.junit.jupiter.api.Test;
import turnos.catedra.application.exception.CatedraEventNotSavedException;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.AdditionalInformationRequestResult;

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
        useCase(new AdditionalInformationRequestResult.Processed()).receiveAdditionalInformationRequest(REQUEST);

        assertThat(handled).containsExactly(REQUEST);
    }

    @Test
    void aProcessedOrDiscardedRequestEndsWithoutError() {
        useCase(new AdditionalInformationRequestResult.Processed()).receiveAdditionalInformationRequest(REQUEST);
        useCase(new AdditionalInformationRequestResult.Discarded("ORPHAN")).receiveAdditionalInformationRequest(REQUEST);

        assertThat(handled).hasSize(2);
    }

    @Test
    void aRequestThatCouldNotBeSavedHasToBeTriedAgain() {
        assertThatThrownBy(() -> useCase(new AdditionalInformationRequestResult.NotSaved("database down"))
                .receiveAdditionalInformationRequest(REQUEST))
                .isInstanceOf(CatedraEventNotSavedException.class)
                .hasMessage("Catedra event request-event-1 could not be saved: database down");
    }

    private ReceiveAdditionalInformationRequestUseCaseImpl useCase(AdditionalInformationRequestResult result) {
        return new ReceiveAdditionalInformationRequestUseCaseImpl(request -> {
            handled.add(request);
            return result;
        });
    }
}
