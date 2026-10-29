package turnos.availability.domain.ports.out;

import turnos.availability.domain.model.ProfessionalAgenda;

// Si el profesional no existe o catálogo no responde, lanza la excepción de availability que corresponde
public interface ProfessionalAgendaProvider {
    ProfessionalAgenda getAgenda(Long professionalId, String userToken);
}
