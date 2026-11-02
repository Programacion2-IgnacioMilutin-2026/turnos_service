package turnos.catedra.infrastructure.kafka;

import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.AppointmentResult;

// Lo que resultó de leer un mensaje del topic de acciones. Tipos inmutables: excepción a la skill autorizada
public sealed interface AppointmentAction {

    // Un pedido de información adicional válido, listo para entregar
    record PhoneRequest(AdditionalInformationRequest request) implements AppointmentAction {
    }

    // Un resultado del proceso de reserva válido, listo para entregar
    record Result(AppointmentResult result) implements AppointmentAction {
    }

    // Un mensaje que no se procesa: inválido o de un tipo desconocido.
    // eventId y reservationProcessId son los que traía, si se pudieron leer, para que quede registrado cuál fue
    record Discarded(String eventId, String reservationProcessId, String reason) implements AppointmentAction {
    }
}
