package turnos.catedra.infrastructure.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import turnos.catedra.domain.model.CatedraOperationPolicy;
import turnos.catedra.domain.model.TechnicalLoginPolicy;

// Convierte las propiedades de reintento en las políticas del dominio, para que los casos de uso no conozcan la configuración
@Configuration(proxyBeanMethods = false)
public class CatedraClientConfiguration {

    @Bean
    TechnicalLoginPolicy technicalLoginPolicy(CatedraProperties catedraProperties) {
        CatedraProperties.Login login = catedraProperties.login();
        return new TechnicalLoginPolicy(login.maxAttempts(), login.initialDelay(), login.multiplier(),
                login.maxDelay(), login.minInterval());
    }

    @Bean
    CatedraOperationPolicy catedraOperationPolicy(CatedraProperties catedraProperties) {
        CatedraProperties.Operations operations = catedraProperties.operations();
        return new CatedraOperationPolicy(operations.maxAttempts(), operations.initialDelay(), operations.multiplier());
    }
}
