package turnos.catedra.infrastructure.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import turnos.catedra.domain.model.TechnicalIntegration;
import turnos.catedra.domain.ports.out.TechnicalIntegrationListener;

// Paquete infrastructure/kafka: excepción a la skill autorizada por el usuario. Es el único lugar del servicio
// que conoce Kafka.
// Recibe el aviso de integración habilitada, que es cuando se conocen los datos de conexión a Kafka.
// Provisorio: por ahora solo registra el aviso; el consumo se arma acá cuando exista quien lo procese
@Slf4j
@Component
public class KafkaConsumerManager implements TechnicalIntegrationListener {

    @Override
    public void integrationEnabled(TechnicalIntegration integration) {
        log.debug("Integration enabled for groupId={}; Kafka consumption is not set up yet", integration.groupId());
    }
}
