package turnos.reservation.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import turnos.reservation.domain.model.ReservationPolicy;

// Convierte las propiedades en la política del dominio, para que el caso de uso no conozca la configuración
@Configuration(proxyBeanMethods = false)
public class ReservationConfiguration {

    @Bean
    ReservationPolicy reservationPolicy(ReservationProperties reservationProperties) {
        return new ReservationPolicy(reservationProperties.zoneId(), reservationProperties.safetyMargin());
    }
}
