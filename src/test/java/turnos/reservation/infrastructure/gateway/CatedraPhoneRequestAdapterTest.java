package turnos.reservation.infrastructure.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.AdditionalInformationRequestResult;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.service.ReservationService;
import turnos.reservation.domain.model.PhoneRequestOutcome;
import turnos.reservation.domain.model.ReservationPhoneRequest;
import turnos.reservation.domain.ports.in.ProcessPhoneRequestUseCase;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatedraPhoneRequestAdapterTest {

    private static final AdditionalInformationRequest REQUEST = AdditionalInformationRequest.builder()
            .eventId("request-event-1").reservationProcessId("process-1").holdId("hold-1")
            .expiresAt(Instant.parse("2026-11-02T13:10:00Z")).build();

    private final List<ReservationPhoneRequest> received = new CopyOnWriteArrayList<>();

    @Test
    void theRequestReachesReservationWithEveryField() {
        assertThat(REQUEST).hasNoNullFieldsOrProperties();

        adapter(request -> PhoneRequestOutcome.APPLIED).handleAdditionalInformationRequest(REQUEST);

        assertThat(received).singleElement().usingRecursiveComparison().isEqualTo(REQUEST);
    }

    @Test
    void anAppliedRequestIsProcessed() {
        assertThat(adapter(request -> PhoneRequestOutcome.APPLIED).handleAdditionalInformationRequest(REQUEST))
                .isEqualTo(new AdditionalInformationRequestResult.Processed());
    }

    @ParameterizedTest
    @EnumSource(value = PhoneRequestOutcome.class, names = "APPLIED", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherOutcomeIsDiscardedWithItsReason(PhoneRequestOutcome outcome) {
        assertThat(adapter(request -> outcome).handleAdditionalInformationRequest(REQUEST))
                .isEqualTo(new AdditionalInformationRequestResult.Discarded(outcome.name()));
    }

    @Test
    void aDatabaseFailureIsNotSaved() {
        AdditionalInformationRequestResult result = adapter(request -> {
            throw new ReservationNotSavedException(new IllegalStateException("database down"));
        }).handleAdditionalInformationRequest(REQUEST);

        assertThat(result).isEqualTo(new AdditionalInformationRequestResult.NotSaved("Reservation data could not be saved"));
    }

    @Test
    void anyOtherErrorArrivesAsIs() {
        IllegalStateException failure = new IllegalStateException("unexpected");

        assertThatThrownBy(() -> adapter(request -> {
            throw failure;
        }).handleAdditionalInformationRequest(REQUEST)).isSameAs(failure);
    }

    private CatedraPhoneRequestAdapter adapter(ProcessPhoneRequestUseCase useCase) {
        ProcessPhoneRequestUseCase recording = request -> {
            received.add(request);
            return useCase.processPhoneRequest(request);
        };
        return new CatedraPhoneRequestAdapter(new ReservationService(null, null, recording));
    }
}
