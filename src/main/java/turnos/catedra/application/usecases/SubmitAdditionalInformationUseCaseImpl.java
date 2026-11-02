package turnos.catedra.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.AdditionalInformationSubmission;
import turnos.catedra.domain.model.AdditionalInformationSubmissionResult;
import turnos.catedra.domain.model.TechnicalSession;
import turnos.catedra.domain.ports.in.SubmitAdditionalInformationUseCase;
import turnos.catedra.domain.ports.out.AdditionalInformationPublisher;
import turnos.catedra.domain.ports.out.TechnicalSessionRepository;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SubmitAdditionalInformationUseCaseImpl implements SubmitAdditionalInformationUseCase {

    private static final String EVENT_ID_PREFIX = "phone-submitted-";

    private final TechnicalSessionRepository technicalSessionRepository;
    private final AdditionalInformationPublisher additionalInformationPublisher;
    private final Clock clock;

    // Cada envío es un evento nuevo. Los datos de la integración son los de la sesión vigente, nunca una copia guardada
    @Override
    public AdditionalInformationSubmissionResult submitAdditionalInformation(String reservationProcessId, String holdId,
                                                                             String requestEventId, String phoneNumber) {
        TechnicalSession session = technicalSessionRepository.get();
        if (!session.isEnabled()) {
            return new AdditionalInformationSubmissionResult.NotPublished("No technical session available");
        }
        AdditionalInformationSubmission submission = AdditionalInformationSubmission.builder()
                .eventId(EVENT_ID_PREFIX + UUID.randomUUID())
                .reservationProcessId(reservationProcessId)
                .holdId(holdId)
                .requestEventId(requestEventId)
                .phoneNumber(phoneNumber)
                .occurredAt(clock.instant().truncatedTo(ChronoUnit.SECONDS))
                .build();
        return additionalInformationPublisher.publish(submission, session.integration());
    }
}
