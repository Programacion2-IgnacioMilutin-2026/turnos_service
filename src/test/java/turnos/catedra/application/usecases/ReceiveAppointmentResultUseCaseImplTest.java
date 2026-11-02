package turnos.catedra.application.usecases;

import org.junit.jupiter.api.Test;
import turnos.catedra.application.exception.CatedraEventNotSavedException;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.AppointmentResult;
import turnos.catedra.domain.model.CatedraEventResult;
import turnos.catedra.domain.ports.out.CatedraEventHandler;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceiveAppointmentResultUseCaseImplTest {

    private static final AppointmentResult RESULT = new AppointmentResult.Expired("event-1", "process-1", "hold-1");

    private final List<AppointmentResult> handled = new CopyOnWriteArrayList<>();

    @Test
    void theResultReachesWhoeverProcessesItAsIs() {
        useCase(new CatedraEventResult.Processed()).receiveAppointmentResult(RESULT);

        assertThat(handled).containsExactly(RESULT);
    }

    @Test
    void aProcessedOrDiscardedResultEndsWithoutError() {
        useCase(new CatedraEventResult.Processed()).receiveAppointmentResult(RESULT);
        useCase(new CatedraEventResult.Discarded("ORPHAN")).receiveAppointmentResult(RESULT);

        assertThat(handled).hasSize(2);
    }

    @Test
    void aResultThatCouldNotBeSavedHasToBeTriedAgain() {
        assertThatThrownBy(() -> useCase(new CatedraEventResult.NotSaved("database down")).receiveAppointmentResult(RESULT))
                .isInstanceOf(CatedraEventNotSavedException.class)
                .hasMessage("Catedra event event-1 could not be saved: database down");
    }

    private ReceiveAppointmentResultUseCaseImpl useCase(CatedraEventResult delivery) {
        return new ReceiveAppointmentResultUseCaseImpl(new CatedraEventHandler() {
            @Override
            public CatedraEventResult handleAdditionalInformationRequest(AdditionalInformationRequest request) {
                throw new AssertionError("A result does not go through the phone request");
            }

            @Override
            public CatedraEventResult handleAppointmentResult(AppointmentResult result) {
                handled.add(result);
                return delivery;
            }
        });
    }
}
