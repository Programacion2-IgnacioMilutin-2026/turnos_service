package turnos.integration.infrastructure.client.mapper;

import org.springframework.stereotype.Component;
import turnos.integration.domain.model.ProvisioningStatus;
import turnos.integration.domain.model.TechnicalIntegration;
import turnos.integration.infrastructure.client.dto.IntegrationResponse;

import java.util.Arrays;

@Component
public class TechnicalIntegrationMapper {

    public TechnicalIntegration toDomain(IntegrationResponse response) {
        if (response == null) {
            return null;
        }
        return new TechnicalIntegration(
                response.getGroupId(),
                response.getKafkaBootstrapServers(),
                response.getKafkaConsumerGroupId(),
                response.getKafkaAppointmentActionsTopic(),
                response.getKafkaAppointmentPhoneTopic(),
                provisioningStatusOf(response.getProvisioningStatus()));
    }

    // Un estado que la cátedra agregue en el futuro no rompe la lectura: queda como UNKNOWN, que no habilita la integración
    private ProvisioningStatus provisioningStatusOf(String value) {
        return Arrays.stream(ProvisioningStatus.values())
                .filter(status -> status != ProvisioningStatus.UNKNOWN && status.name().equals(value))
                .findFirst()
                .orElse(ProvisioningStatus.UNKNOWN);
    }
}
