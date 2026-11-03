package turnos.reservation.infrastructure.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.AppointmentResult;
import turnos.catedra.domain.model.CatedraEventResult;
import turnos.reservation.application.exception.ReservationNotSavedException;
import turnos.reservation.application.service.ReservationService;
import turnos.reservation.domain.model.ReservationEventOutcome;
import turnos.reservation.domain.model.ReservationPhoneRequest;
import turnos.reservation.domain.model.ReservationResult;
import turnos.reservation.domain.ports.in.ProcessPhoneRequestUseCase;
import turnos.reservation.domain.ports.in.ProcessReservationResultUseCase;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatedraEventAdapterTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-02T13:00:00Z");
    private static final AppointmentResult.Expired EXPIRED = new AppointmentResult.Expired("event-1", "process-1", "hold-1");
    private static final AdditionalInformationRequest REQUEST = AdditionalInformationRequest.builder()
            .eventId("request-event-1").reservationProcessId("process-1").holdId("hold-1")
            .expiresAt(Instant.parse("2026-11-02T13:10:00Z")).build();

    private final List<ReservationPhoneRequest> received = new CopyOnWriteArrayList<>();
    private final List<ReservationResult> receivedResults = new CopyOnWriteArrayList<>();

    @Test
    void theRequestReachesReservationWithEveryField() {
        assertThat(REQUEST).hasNoNullFieldsOrProperties();

        adapter(request -> ReservationEventOutcome.APPLIED).handleAdditionalInformationRequest(REQUEST);

        assertThat(received).singleElement().usingRecursiveComparison().isEqualTo(REQUEST);
    }

    @Test
    void anAppliedRequestIsProcessed() {
        assertThat(adapter(request -> ReservationEventOutcome.APPLIED).handleAdditionalInformationRequest(REQUEST))
                .isEqualTo(new CatedraEventResult.Processed());
    }

    @ParameterizedTest
    @EnumSource(value = ReservationEventOutcome.class, names = "APPLIED", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherOutcomeIsDiscardedWithItsReason(ReservationEventOutcome outcome) {
        assertThat(adapter(request -> outcome).handleAdditionalInformationRequest(REQUEST))
                .isEqualTo(new CatedraEventResult.Discarded(outcome.name()));
    }

    @Test
    void aDatabaseFailureIsNotSaved() {
        CatedraEventResult result = adapter(request -> {
            throw new ReservationNotSavedException(new IllegalStateException("database down"));
        }).handleAdditionalInformationRequest(REQUEST);

        assertThat(result).isEqualTo(new CatedraEventResult.NotSaved("Reservation data could not be saved"));
    }

    @Test
    void anyOtherErrorArrivesAsIs() {
        IllegalStateException failure = new IllegalStateException("unexpected");

        assertThatThrownBy(() -> adapter(request -> {
            throw failure;
        }).handleAdditionalInformationRequest(REQUEST)).isSameAs(failure);
    }

    @ParameterizedTest
    @MethodSource("results")
    void eachResultReachesReservationWithEveryField(AppointmentResult result, Class<? extends ReservationResult> variant) {
        assertThat(result).hasNoNullFieldsOrProperties();

        assertThat(resultAdapter(received -> ReservationEventOutcome.APPLIED).handleAppointmentResult(result))
                .isEqualTo(new CatedraEventResult.Processed());

        assertThat(receivedResults).singleElement().isInstanceOf(variant)
                .usingRecursiveComparison().isEqualTo(result);
    }

    @Test
    void aResultWithoutItsOptionalDataAlsoReachesReservation() {
        AppointmentResult.Confirmed withoutAppointment = new AppointmentResult.Confirmed("event-1", "process-1", "hold-1",
                8251L, OCCURRED_AT, null, null, null);

        resultAdapter(received -> ReservationEventOutcome.APPLIED).handleAppointmentResult(withoutAppointment);

        assertThat(receivedResults).singleElement().usingRecursiveComparison().isEqualTo(withoutAppointment);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationEventOutcome.class, names = "APPLIED", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherOutcomeOfAResultIsDiscardedWithItsReason(ReservationEventOutcome outcome) {
        assertThat(resultAdapter(received -> outcome).handleAppointmentResult(EXPIRED))
                .isEqualTo(new CatedraEventResult.Discarded(outcome.name()));
    }

    @Test
    void aDatabaseFailureWithAResultIsNotSaved() {
        CatedraEventResult delivery = resultAdapter(received -> {
            throw new ReservationNotSavedException(new IllegalStateException("database down"));
        }).handleAppointmentResult(EXPIRED);

        assertThat(delivery).isEqualTo(new CatedraEventResult.NotSaved("Reservation data could not be saved"));
    }

    @Test
    void anyOtherErrorWithAResultArrivesAsIs() {
        IllegalStateException failure = new IllegalStateException("unexpected");

        assertThatThrownBy(() -> resultAdapter(received -> {
            throw failure;
        }).handleAppointmentResult(EXPIRED)).isSameAs(failure);
    }

    private static Stream<Arguments> results() {
        return Stream.of(
                Arguments.of(new AppointmentResult.Confirmed("event-1", "process-1", "hold-1", 8251L, OCCURRED_AT, 15L,
                        LocalDate.of(2026, 10, 20), LocalTime.of(9, 30)), ReservationResult.Confirmed.class),
                Arguments.of(new AppointmentResult.Rejected("event-1", "process-1", "hold-1", "INVALID_PHONE_NUMBER",
                        OCCURRED_AT.plusSeconds(600)), ReservationResult.Rejected.class),
                Arguments.of(EXPIRED, ReservationResult.Expired.class),
                Arguments.of(new AppointmentResult.Invalid("event-1", "process-1", "hold-1", "REQUEST_EVENT_MISMATCH"),
                        ReservationResult.Invalid.class),
                Arguments.of(new AppointmentResult.Cancelled("event-1", "process-1", "hold-1", OCCURRED_AT,
                        "Cambio solicitado por el usuario"), ReservationResult.Cancelled.class));
    }

    private CatedraEventAdapter resultAdapter(ProcessReservationResultUseCase useCase) {
        ProcessReservationResultUseCase recording = result -> {
            receivedResults.add(result);
            return useCase.processReservationResult(result);
        };
        return new CatedraEventAdapter(new ReservationService(null, null, null, recording, null, null));
    }

    private CatedraEventAdapter adapter(ProcessPhoneRequestUseCase useCase) {
        ProcessPhoneRequestUseCase recording = request -> {
            received.add(request);
            return useCase.processPhoneRequest(request);
        };
        return new CatedraEventAdapter(new ReservationService(null, null, recording, null, null, null));
    }
}
