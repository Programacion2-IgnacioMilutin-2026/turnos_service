package turnos.catedra.infrastructure.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;
import turnos.catedra.application.service.CatedraEventService;

// Recibe cada mensaje del topic de acciones, como un controller recibe un request: lo lee y lo entrega a la aplicación
// de catedra. Si termina sin error, el mensaje se da por leído; si lanza, se vuelve a entregar según el manejo de errores
@Slf4j
@Component
@RequiredArgsConstructor
public class AppointmentActionListener {

    private final AppointmentActionReader appointmentActionReader;
    private final CatedraEventService catedraEventService;

    public void onMessage(ConsumerRecord<String, String> message) {
        switch (appointmentActionReader.read(message.value())) {
            case AppointmentAction.PhoneRequest phoneRequest ->
                    catedraEventService.receiveAdditionalInformationRequest(phoneRequest.request());
            case AppointmentAction.Discarded discarded -> log.warn(
                    "Catedra message at offset {} discarded (eventId={}, reservationProcessId={}): {}",
                    message.offset(), discarded.eventId(), discarded.reservationProcessId(), discarded.reason());
        }
    }
}
