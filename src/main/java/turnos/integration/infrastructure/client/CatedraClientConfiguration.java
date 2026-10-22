package turnos.integration.infrastructure.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import turnos.integration.domain.model.TechnicalLoginPolicy;

// Convierte las propiedades de reintento en la política del dominio, para que los casos de uso no conozcan la configuración
@Configuration(proxyBeanMethods = false)
public class CatedraClientConfiguration {

    @Bean
    TechnicalLoginPolicy technicalLoginPolicy(CatedraProperties catedraProperties) {
        CatedraProperties.Login login = catedraProperties.login();
        return new TechnicalLoginPolicy(login.maxAttempts(), login.initialDelay(), login.multiplier(),
                login.maxDelay(), login.minInterval());
    }
}
