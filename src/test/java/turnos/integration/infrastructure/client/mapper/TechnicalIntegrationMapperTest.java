package turnos.integration.infrastructure.client.mapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import turnos.integration.domain.model.ProvisioningStatus;
import turnos.integration.domain.model.TechnicalIntegration;
import turnos.integration.infrastructure.client.dto.IntegrationResponse;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalIntegrationMapperTest {

    private final TechnicalIntegrationMapper mapper = new TechnicalIntegrationMapper();

    @Test
    void keepsEveryFieldTurnosUses() {
        IntegrationResponse response = IntegrationResponse.builder()
                .groupId("proyecto-juan-perez")
                .kafkaBootstrapServers("kafka.catedra.internal:9092")
                .kafkaConsumerGroupId("alumnos-proyecto-juan-perez")
                .kafkaAppointmentActionsTopic("alumnos.turnos.acciones.proyecto-juan-perez")
                .kafkaAppointmentPhoneTopic("catedra.turnos.telefono.proyecto-juan-perez")
                .provisioningStatus("PROVISIONED")
                .build();
        assertThat(response).hasNoNullFieldsOrProperties();

        assertThat(mapper.toDomain(response)).isEqualTo(new TechnicalIntegration(
                "proyecto-juan-perez", "kafka.catedra.internal:9092", "alumnos-proyecto-juan-perez",
                "alumnos.turnos.acciones.proyecto-juan-perez", "catedra.turnos.telefono.proyecto-juan-perez",
                ProvisioningStatus.PROVISIONED));
    }

    @ParameterizedTest
    @CsvSource(value = {"PENDING,PENDING", "FAILED,FAILED", "REVOKED,REVOKED", "ARCHIVED,UNKNOWN", "provisioned,UNKNOWN", "UNKNOWN,UNKNOWN", "NULL,UNKNOWN"}, nullValues = "NULL")
    void readsEveryProvisioningStatusAndTreatsUnexpectedOnesAsUnknown(String value, ProvisioningStatus expected) {
        IntegrationResponse response = IntegrationResponse.builder().provisioningStatus(value).build();

        assertThat(mapper.toDomain(response).provisioningStatus()).isEqualTo(expected);
    }

    @Test
    void nullMapsToNull() {
        assertThat(mapper.toDomain(null)).isNull();
    }
}
