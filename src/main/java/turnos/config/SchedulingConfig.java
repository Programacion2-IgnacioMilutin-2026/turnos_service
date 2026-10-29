package turnos.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Paquete config fuera de los slices: excepción a la skill autorizada por el usuario.
// Habilita las tareas periódicas, como el barrido del caché de ocupaciones
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
