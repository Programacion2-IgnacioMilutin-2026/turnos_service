package turnos.availability.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import turnos.availability.domain.model.AvailabilityPolicy;

// Convierte las propiedades en la política del dominio, para que el caso de uso y el caché no conozcan la configuración
@Configuration(proxyBeanMethods = false)
public class AvailabilityConfiguration {

    @Bean
    AvailabilityPolicy availabilityPolicy(AvailabilityProperties availabilityProperties) {
        return new AvailabilityPolicy(availabilityProperties.zone(), availabilityProperties.cacheMaxAge());
    }
}
