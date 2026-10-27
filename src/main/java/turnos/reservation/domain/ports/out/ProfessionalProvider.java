package turnos.reservation.domain.ports.out;

import turnos.reservation.domain.model.ReservationProfessional;

// Si el profesional no existe o catálogo no responde, lanza la excepción de reservation que corresponde
public interface ProfessionalProvider {
    ReservationProfessional getProfessional(Long professionalId, String userToken);
}
