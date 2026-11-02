package turnos.catedra.application.usecases;

import org.junit.jupiter.api.Test;
import turnos.MutableClock;
import turnos.catedra.domain.model.AdditionalInformationSubmission;
import turnos.catedra.domain.model.AdditionalInformationSubmissionResult;
import turnos.catedra.domain.model.ProvisioningStatus;
import turnos.catedra.domain.model.TechnicalIntegration;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.domain.ports.out.AdditionalInformationPublisher;
import turnos.catedra.infrastructure.persistence.adapter.InMemoryTechnicalSessionRepositoryAdapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubmitAdditionalInformationUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-11-02T13:00:00.123456Z");
    private static final TechnicalIntegration INTEGRATION = ScriptedLoginClient.integration(ProvisioningStatus.PROVISIONED);

    private final InMemoryTechnicalSessionRepositoryAdapter repository = new InMemoryTechnicalSessionRepositoryAdapter();
    private final List<AdditionalInformationSubmission> submissions = new ArrayList<>();
    private final List<TechnicalIntegration> integrations = new ArrayList<>();
    private AdditionalInformationSubmissionResult answer = new AdditionalInformationSubmissionResult.Published();
    private final AdditionalInformationPublisher publisher = (submission, integration) -> {
        submissions.add(submission);
        integrations.add(integration);
        return answer;
    };
    private final SubmitAdditionalInformationUseCaseImpl useCase =
            new SubmitAdditionalInformationUseCaseImpl(repository, publisher, new MutableClock(NOW));

    @Test
    void theSubmissionHasTheDataOfTheProcessANewEventAndTheInstantInSeconds() {
        repository.save(TechnicalSession.enabled("jwt-a", INTEGRATION, NOW));

        AdditionalInformationSubmissionResult result = submit();

        assertThat(result).isEqualTo(answer);
        assertThat(submissions).singleElement().satisfies(submission -> {
            assertThat(submission.getEventId()).startsWith("phone-submitted-").hasSizeLessThanOrEqualTo(255);
            assertThat(submission.getReservationProcessId()).isEqualTo("process-1");
            assertThat(submission.getHoldId()).isEqualTo("hold-1");
            assertThat(submission.getRequestEventId()).isEqualTo("request-event-1");
            assertThat(submission.getPhoneNumber()).isEqualTo("+5492615551234");
            assertThat(submission.getOccurredAt()).isEqualTo(Instant.parse("2026-11-02T13:00:00Z"));
        });
    }

    @Test
    void eachSubmissionIsANewEventForTheSamePhoneRequest() {
        repository.save(TechnicalSession.enabled("jwt-a", INTEGRATION, NOW));

        submit();
        submit();

        assertThat(submissions.get(0).getEventId()).isNotEqualTo(submissions.get(1).getEventId());
        assertThat(submissions.get(0).getRequestEventId()).isEqualTo(submissions.get(1).getRequestEventId());
    }

    @Test
    void theIntegrationIsTheOneOfTheCurrentSession() {
        TechnicalIntegration later = new TechnicalIntegration("otro-grupo", "kafka:9092", "alumnos-otro-grupo",
                "alumnos.turnos.acciones.otro-grupo", "catedra.turnos.telefono.otro-grupo", ProvisioningStatus.PROVISIONED);
        repository.save(TechnicalSession.enabled("jwt-a", INTEGRATION, NOW));
        submit();

        repository.save(TechnicalSession.enabled("jwt-b", later, NOW));
        submit();

        assertThat(integrations).containsExactly(INTEGRATION, later);
    }

    @Test
    void whatThePublisherAnswersIsReturned() {
        repository.save(TechnicalSession.enabled("jwt-a", INTEGRATION, NOW));
        answer = new AdditionalInformationSubmissionResult.NotPublished("broker down");

        assertThat(submit()).isEqualTo(new AdditionalInformationSubmissionResult.NotPublished("broker down"));
    }

    @Test
    void withoutASessionNothingIsPublished() {
        repository.save(TechnicalSession.degraded(NOW));

        assertThat(submit()).isInstanceOf(AdditionalInformationSubmissionResult.NotPublished.class);
        assertThat(submissions).isEmpty();
    }

    private AdditionalInformationSubmissionResult submit() {
        return useCase.submitAdditionalInformation("process-1", "hold-1", "request-event-1", "+5492615551234");
    }
}
