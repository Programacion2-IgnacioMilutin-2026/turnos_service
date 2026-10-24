package turnos.catedra.domain.model;

// record inmutable en lugar de clase Lombok: excepción a la skill autorizada por el usuario.
// La sesión se lee en paralelo mientras un login la reemplaza; así nunca se ve a medio actualizar
public record TechnicalIntegration(
        String groupId,
        String kafkaBootstrapServers,
        String kafkaConsumerGroupId,
        String kafkaAppointmentActionsTopic,
        String kafkaAppointmentPhoneTopic,
        ProvisioningStatus provisioningStatus) {

    public boolean isProvisioned() {
        return provisioningStatus == ProvisioningStatus.PROVISIONED;
    }
}
