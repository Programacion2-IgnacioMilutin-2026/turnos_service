package turnos.catedra.application.usecases;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import turnos.MutableClock;
import turnos.catedra.application.support.CatedraOperationRunner;
import turnos.catedra.domain.model.Appointment;
import turnos.catedra.domain.model.AppointmentCancellationResult;
import turnos.catedra.domain.model.AppointmentLookupResult;
import turnos.catedra.domain.model.AppointmentStatus;
import turnos.catedra.domain.model.CatedraOperationPolicy;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.model.ProvisioningStatus;
import turnos.catedra.domain.model.TechnicalLoginPolicy;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.domain.ports.out.CatedraAppointmentClient;
import turnos.catedra.infrastructure.persistence.adapter.InMemoryTechnicalSessionRepositoryAdapter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class FindAppointmentUseCaseImplTest {

    private static final Instant START = Instant.parse("2026-11-05T12:00:00Z");
    private static final String PATIENT = "1042";
    private static final String PROCESS = "process-1";
    private static final Appointment PENDING = appointment(PROCESS, PATIENT, AppointmentStatus.PHONE_PENDING, null, null);

    private final InMemoryTechnicalSessionRepositoryAdapter repository = new InMemoryTechnicalSessionRepositoryAdapter();
    private final ScriptedLoginClient loginClient = new ScriptedLoginClient();
    private final PagedAppointmentClient client = new PagedAppointmentClient();
    private final FindAppointmentUseCaseImpl useCase = new FindAppointmentUseCaseImpl(client, new CatedraOperationRunner(
            repository,
            new RenewTechnicalSessionUseCaseImpl(loginClient, repository,
                    new TechnicalLoginPolicy(1, Duration.ofMillis(1), 2, Duration.ofMillis(1), Duration.ofSeconds(60)),
                    new MutableClock(START), integration -> {}),
            new CatedraOperationPolicy(3, Duration.ofMillis(1), 2)));

    @Test
    void theFirstPageIsRequestedForThePatientWithTheCurrentToken() {
        repository.save(enabled("jwt-a"));
        client.pages(page -> answered(List.of(PENDING)));

        assertThat(useCase.findAppointment(PATIENT, PROCESS)).isEqualTo(new AppointmentLookupResult.Found(PENDING));
        assertThat(client.calls()).containsExactly("1042 page 0 size 20 jwt-a");
    }

    @Test
    void theReservationIsFoundOnALaterPage() {
        repository.save(enabled("jwt-a"));
        client.pages(page -> page == 0 ? answered(others(20)) : answered(List.of(other("process-x"), PENDING)));

        assertThat(useCase.findAppointment(PATIENT, PROCESS)).isEqualTo(new AppointmentLookupResult.Found(PENDING));
        assertThat(client.calls()).containsExactly("1042 page 0 size 20 jwt-a", "1042 page 1 size 20 jwt-a");
    }

    @Test
    void theSearchStopsAtTheFirstIncompletePage() {
        repository.save(enabled("jwt-a"));
        client.pages(page -> answered(others(3)));

        assertThat(useCase.findAppointment(PATIENT, PROCESS)).isEqualTo(new AppointmentLookupResult.NotFound());
        assertThat(client.calls()).hasSize(1);
    }

    @Test
    void aFullPageIsFollowedByTheNextOneEvenIfItIsEmpty() {
        repository.save(enabled("jwt-a"));
        client.pages(page -> page == 0 ? answered(others(20)) : answered(List.of()));

        assertThat(useCase.findAppointment(PATIENT, PROCESS)).isEqualTo(new AppointmentLookupResult.NotFound());
        assertThat(client.calls()).hasSize(2);
    }

    @Test
    void theSameProcessForAnotherPatientIsNotTheReservation() {
        repository.save(enabled("jwt-a"));
        client.pages(page -> answered(List.of(appointment(PROCESS, "2000", AppointmentStatus.CONFIRMED, START, null))));

        assertThat(useCase.findAppointment(PATIENT, PROCESS)).isEqualTo(new AppointmentLookupResult.NotFound());
    }

    @Test
    void theReservationOfThePatientIsChosenOverOneOfAnotherPatient() {
        repository.save(enabled("jwt-a"));
        Appointment ofAnotherPatient = appointment(PROCESS, "2000", AppointmentStatus.CONFIRMED, START, null);
        client.pages(page -> answered(List.of(ofAnotherPatient, PENDING)));

        assertThat(useCase.findAppointment(PATIENT, PROCESS)).isEqualTo(new AppointmentLookupResult.Found(PENDING));
    }

    @Test
    void validReservationsInEachStatusAreFound() {
        repository.save(enabled("jwt-a"));
        for (Appointment valid : List.of(PENDING,
                appointment(PROCESS, PATIENT, AppointmentStatus.CONFIRMED, START, null),
                appointment(PROCESS, PATIENT, AppointmentStatus.CANCELLED, null, START),
                appointment(PROCESS, PATIENT, AppointmentStatus.FAILED, null, null))) {
            client.pages(page -> answered(List.of(valid)));

            assertThat(useCase.findAppointment(PATIENT, PROCESS)).isEqualTo(new AppointmentLookupResult.Found(valid));
        }
    }

    @Test
    void anIncompleteOrUnknownReservationOfTheProcessCannotBeUsed() {
        repository.save(enabled("jwt-a"));
        for (Appointment incomplete : List.of(
                appointment(PROCESS, PATIENT, AppointmentStatus.UNKNOWN, START, START),
                appointment(PROCESS, PATIENT, AppointmentStatus.CONFIRMED, null, null),
                appointment(PROCESS, PATIENT, AppointmentStatus.CANCELLED, START, null))) {
            client.pages(page -> answered(List.of(incomplete)));

            assertThat(useCase.findAppointment(PATIENT, PROCESS)).isInstanceOf(AppointmentLookupResult.Failed.class);
        }
    }

    @Test
    void anIncompleteReservationOfAnotherProcessDoesNotMatter() {
        repository.save(enabled("jwt-a"));
        client.pages(page -> answered(List.of(appointment("process-x", PATIENT, AppointmentStatus.UNKNOWN, null, null),
                appointment("process-y", PATIENT, AppointmentStatus.CONFIRMED, null, null), PENDING)));

        assertThat(useCase.findAppointment(PATIENT, PROCESS)).isEqualTo(new AppointmentLookupResult.Found(PENDING));
    }

    @Test
    void aListThatNeverEndsIsNotWalkedForever() {
        repository.save(enabled("jwt-a"));
        client.pages(page -> answered(others(20)));

        assertThat(useCase.findAppointment(PATIENT, PROCESS)).isInstanceOf(AppointmentLookupResult.Failed.class);
        assertThat(client.calls()).hasSize(FindAppointmentUseCaseImpl.MAX_PAGES);
    }

    @Test
    void aCatedraThatDoesNotAnswerIsRetriedUpToTheMaximumAndThenUnavailable(CapturedOutput output) {
        repository.save(enabled("jwt-a"));
        client.pages(page -> new CatedraResponse.Unavailable<>("down"));

        assertThat(useCase.findAppointment(PATIENT, PROCESS)).isEqualTo(new AppointmentLookupResult.Unavailable("down"));
        assertThat(client.calls()).hasSize(3);
        assertThat(output.getAll()).contains("listing of appointments for process-1");
    }

    @Test
    void aCatedraThatStopsAnsweringOnALaterPageIsUnavailable() {
        repository.save(enabled("jwt-a"));
        client.pages(page -> page == 0 ? answered(others(20)) : new CatedraResponse.Unavailable<>("down"));

        assertThat(useCase.findAppointment(PATIENT, PROCESS)).isInstanceOf(AppointmentLookupResult.Unavailable.class);
    }

    @Test
    void aRejectedTokenRenewsTheSessionAndRepeatsOnce() {
        repository.save(enabled("jwt-a"));
        loginClient.then(ScriptedLoginClient.success("jwt-b", ProvisioningStatus.PROVISIONED));
        client.pages(page -> client.calls().size() == 1 ? new CatedraResponse.TokenRejected<>() : answered(List.of(PENDING)));

        assertThat(useCase.findAppointment(PATIENT, PROCESS)).isEqualTo(new AppointmentLookupResult.Found(PENDING));
        assertThat(client.calls()).containsExactly("1042 page 0 size 20 jwt-a", "1042 page 0 size 20 jwt-b");
    }

    @Test
    void integrationProblemsKeepTheirMeaningWithoutRepeating() {
        repository.save(enabled("jwt-a"));

        client.pages(page -> new CatedraResponse.ConfigurationError<>("missing"));
        assertThat(useCase.findAppointment(PATIENT, PROCESS)).isEqualTo(new AppointmentLookupResult.ConfigurationError("missing"));

        client.pages(page -> new CatedraResponse.Failed<>("unexpected"));
        assertThat(useCase.findAppointment(PATIENT, PROCESS)).isEqualTo(new AppointmentLookupResult.Failed("unexpected"));

        client.pages(page -> new CatedraResponse.Uncertain<>("cut"));
        assertThat(useCase.findAppointment(PATIENT, PROCESS)).isEqualTo(new AppointmentLookupResult.Unavailable("cut"));
        assertThat(client.calls()).hasSize(1);
    }

    @Test
    void aDegradedSessionWithoutAPossibleLoginIsUnavailableWithoutCalling() {
        repository.save(TechnicalSession.degraded(START));

        assertThat(useCase.findAppointment(PATIENT, PROCESS)).isInstanceOf(AppointmentLookupResult.Unavailable.class);
        assertThat(client.calls()).isEmpty();
    }

    private static CatedraResponse<List<Appointment>> answered(List<Appointment> appointments) {
        return new CatedraResponse.Answered<>(appointments);
    }

    private static List<Appointment> others(int count) {
        return IntStream.range(0, count).mapToObj(i -> other("process-other-" + i)).toList();
    }

    private static Appointment other(String reservationProcessId) {
        return appointment(reservationProcessId, PATIENT, AppointmentStatus.FAILED, null, null);
    }

    private static Appointment appointment(String reservationProcessId, String patient, AppointmentStatus status,
                                           Instant confirmedAt, Instant cancelledAt) {
        return new Appointment(reservationProcessId, patient, status, 8251L, confirmedAt, cancelledAt, null, 15L,
                LocalDate.of(2026, 11, 9), LocalTime.of(10, 30));
    }

    // El último login fue hace dos minutos: ya pasó el intervalo mínimo para pedir otro
    private static TechnicalSession enabled(String token) {
        return TechnicalSession.enabled(token, ScriptedLoginClient.integration(ProvisioningStatus.PROVISIONED),
                START.minusSeconds(120));
    }

    // Responde cada página con lo que indique el test y guarda los datos de cada llamada
    private static final class PagedAppointmentClient implements CatedraAppointmentClient {

        private final List<String> calls = new ArrayList<>();
        private IntFunction<CatedraResponse<List<Appointment>>> pages = page -> answered(List.of());

        void pages(IntFunction<CatedraResponse<List<Appointment>>> answers) {
            pages = answers;
            calls.clear();
        }

        List<String> calls() {
            return calls;
        }

        @Override
        public CatedraResponse<List<Appointment>> listAppointments(String externalPatientId, int page, int size,
                                                                   String technicalToken) {
            calls.add(externalPatientId + " page " + page + " size " + size + " " + technicalToken);
            return pages.apply(page);
        }

        @Override
        public CatedraResponse<AppointmentCancellationResult> cancelAppointment(String reservationProcessId, String reason,
                                                                                String technicalToken) {
            throw new UnsupportedOperationException("The search does not cancel");
        }
    }
}
