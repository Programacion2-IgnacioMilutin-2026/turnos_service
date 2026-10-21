package turnos.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// Paquete config fuera de los slices: excepción a la skill autorizada por el usuario.
// Un único reloj en UTC; los tests lo reemplazan para controlar esperas e intervalos
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
