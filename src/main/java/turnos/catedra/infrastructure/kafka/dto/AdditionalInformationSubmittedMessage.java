package turnos.catedra.infrastructure.kafka.dto;

import lombok.*;

// El mensaje que recibe la cátedra en el topic de teléfono
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdditionalInformationSubmittedMessage {
    private String eventId;
    private String eventType;
    private String groupId;
    private String reservationProcessId;
    private String holdId;
    private String requestEventId;
    private Information information;
    private String occurredAt;
    private int schemaVersion;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Information {
        private String phoneNumber;
    }
}
