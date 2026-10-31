package turnos.catedra.infrastructure.kafka;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.infrastructure.kafka.dto.AppointmentActionMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Lee un mensaje del topic de acciones y decide si es un pedido de información adicional que se puede procesar.
// Solo valida el formato de la cátedra; si el proceso existe y en qué estado está lo decide quien lo procesa
@Component
@RequiredArgsConstructor
public class AppointmentActionReader {

    private static final String PHONE_REQUEST = "AdditionalInformationRequested";
    // Los resultados del proceso de reserva viajan por el mismo topic, pero todavía no se procesan
    private static final Set<String> RESULTS = Set.of("AppointmentConfirmed", "AdditionalInformationRejected",
            "AppointmentProcessExpired", "AppointmentProcessInvalid", "AppointmentCancelled");
    private static final int SCHEMA_VERSION = 1;
    private static final String PHONE_NUMBER = "PHONE_NUMBER";

    private final JsonMapper jsonMapper;

    public AppointmentAction read(String value) {
        if (value == null || value.isBlank()) {
            return new AppointmentAction.Discarded(null, null, "empty message");
        }
        AppointmentActionMessage message;
        try {
            message = jsonMapper.readValue(value, AppointmentActionMessage.class);
        } catch (JacksonException e) {
            return new AppointmentAction.Discarded(null, null, "unreadable message: " + e.getClass().getSimpleName());
        }
        if (message == null) {
            return new AppointmentAction.Discarded(null, null, "empty message");
        }
        String eventType = message.getEventType();
        if (blank(eventType)) {
            return discard(message, "missing eventType");
        }
        if (RESULTS.contains(eventType)) {
            return discard(message, eventType + " is not processed yet");
        }
        if (!PHONE_REQUEST.equals(eventType)) {
            return discard(message, "unknown eventType " + eventType);
        }
        List<String> missing = missingFields(message);
        if (!missing.isEmpty()) {
            return discard(message, "missing " + String.join(", ", missing));
        }
        if (message.getSchemaVersion() != SCHEMA_VERSION) {
            return discard(message, "unsupported schemaVersion " + message.getSchemaVersion());
        }
        if (!PHONE_NUMBER.equals(message.getRequiredField())) {
            return discard(message, "unsupported requiredField " + message.getRequiredField());
        }
        return new AppointmentAction.PhoneRequest(AdditionalInformationRequest.builder()
                .eventId(message.getEventId())
                .reservationProcessId(message.getReservationProcessId())
                .holdId(message.getHoldId())
                .expiresAt(message.getExpiresAt())
                .build());
    }

    // Los que hacen falta para procesar el pedido; message y occurredAt no se usan, así que su falta no lo descarta
    private static List<String> missingFields(AppointmentActionMessage message) {
        List<String> missing = new ArrayList<>();
        if (blank(message.getEventId())) {
            missing.add("eventId");
        }
        if (blank(message.getReservationProcessId())) {
            missing.add("reservationProcessId");
        }
        if (blank(message.getHoldId())) {
            missing.add("holdId");
        }
        if (blank(message.getRequiredField())) {
            missing.add("requiredField");
        }
        if (message.getExpiresAt() == null) {
            missing.add("expiresAt");
        }
        if (message.getSchemaVersion() == null) {
            missing.add("schemaVersion");
        }
        return missing;
    }

    private static AppointmentAction.Discarded discard(AppointmentActionMessage message, String reason) {
        return new AppointmentAction.Discarded(message.getEventId(), message.getReservationProcessId(), reason);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
