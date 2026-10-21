package turnos.integration.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

// Solo los datos que usa turnos: los campos redis* y kafkaCatalogTopic se descartan al leer la respuesta
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IntegrationResponse {
    private String groupId;
    private String kafkaBootstrapServers;
    private String kafkaConsumerGroupId;
    private String kafkaAppointmentActionsTopic;
    private String kafkaAppointmentPhoneTopic;
    private String provisioningStatus;
}
