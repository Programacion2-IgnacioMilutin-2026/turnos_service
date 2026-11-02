package turnos.catedra.infrastructure.kafka;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.AppointmentResult;
import turnos.catedra.infrastructure.kafka.dto.AppointmentActionMessage;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

// Lee un mensaje del topic de acciones y decide si se puede procesar. Solo valida el formato de la cátedra; si el
// proceso existe y en qué estado está lo decide quien lo procesa. Exige y convierte solo lo que cada tipo de mensaje
// necesita: un descarte no tiene vuelta atrás, y un campo que no se usa no es motivo para perder el mensaje
@Component
@RequiredArgsConstructor
public class AppointmentActionReader {

    private static final String PHONE_REQUEST = "AdditionalInformationRequested";
    private static final String CONFIRMED = "AppointmentConfirmed";
    private static final String REJECTED = "AdditionalInformationRejected";
    private static final String EXPIRED = "AppointmentProcessExpired";
    private static final String INVALID = "AppointmentProcessInvalid";
    private static final String CANCELLED = "AppointmentCancelled";

    private static final Field REQUIRED_FIELD = new Field("requiredField", AppointmentActionMessage::getRequiredField);
    private static final Field EXPIRES_AT = new Field("expiresAt", AppointmentActionMessage::getExpiresAt);
    private static final Field OCCURRED_AT = new Field("occurredAt", AppointmentActionMessage::getOccurredAt);
    private static final Field RESERVATION_ID = new Field("reservationId", AppointmentActionMessage::getReservationId);
    private static final Field REASON = new Field("reason", AppointmentActionMessage::getReason);
    private static final Field EVENT_ID = new Field("eventId", AppointmentActionMessage::getEventId);
    private static final Field RESERVATION_PROCESS_ID = new Field("reservationProcessId", AppointmentActionMessage::getReservationProcessId);
    private static final Field HOLD_ID = new Field("holdId", AppointmentActionMessage::getHoldId);

    private static final List<Field> IDENTIFIERS = List.of(EVENT_ID, RESERVATION_PROCESS_ID, HOLD_ID);
    private static final Map<String, List<Field>> REQUIRED_BY_TYPE = Map.of(
            PHONE_REQUEST, List.of(REQUIRED_FIELD, EXPIRES_AT),
            CONFIRMED, List.of(RESERVATION_ID, OCCURRED_AT),
            REJECTED, List.of(REASON, EXPIRES_AT),
            EXPIRED, List.of(),
            INVALID, List.of(),
            CANCELLED, List.of(OCCURRED_AT));

    private static final int SCHEMA_VERSION = 1;
    private static final String PHONE_NUMBER = "PHONE_NUMBER";
    // Lo que entra en las columnas de estos identificadores. Un valor más largo, o con el carácter nulo que la base no
    // acepta, haría fallar el guardado, y eso se reintenta sin fin y frena a todos los mensajes siguientes
    private static final int MAX_IDENTIFIER_LENGTH = 255;

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
        if (!REQUIRED_BY_TYPE.containsKey(eventType)) {
            return discard(message, "unknown eventType " + eventType);
        }
        List<String> missing = missingFields(message, REQUIRED_BY_TYPE.get(eventType));
        if (!missing.isEmpty()) {
            return discard(message, "missing " + String.join(", ", missing));
        }
        for (Field identifier : IDENTIFIERS) {
            String problem = identifierProblem(identifier.value().apply(message));
            if (problem != null) {
                return discard(message, identifier.name() + " " + problem);
            }
        }
        if (message.getSchemaVersion() != SCHEMA_VERSION) {
            return discard(message, "unsupported schemaVersion " + message.getSchemaVersion());
        }
        try {
            return toAction(message);
        } catch (MalformedFieldException e) {
            return discard(message, "malformed " + e.getMessage());
        }
    }

    private static AppointmentAction toAction(AppointmentActionMessage message) {
        return switch (message.getEventType()) {
            case PHONE_REQUEST -> phoneRequest(message);
            case CONFIRMED -> new AppointmentAction.Result(new AppointmentResult.Confirmed(message.getEventId(),
                    message.getReservationProcessId(), message.getHoldId(),
                    number("reservationId", message.getReservationId()), instant("occurredAt", message.getOccurredAt()),
                    number("professionalId", message.getProfessionalId()), date(message.getDate()), time(message.getStartTime())));
            case REJECTED -> new AppointmentAction.Result(new AppointmentResult.Rejected(message.getEventId(),
                    message.getReservationProcessId(), message.getHoldId(), message.getReason(),
                    instant("expiresAt", message.getExpiresAt())));
            case EXPIRED -> new AppointmentAction.Result(new AppointmentResult.Expired(message.getEventId(),
                    message.getReservationProcessId(), message.getHoldId()));
            case INVALID -> new AppointmentAction.Result(new AppointmentResult.Invalid(message.getEventId(),
                    message.getReservationProcessId(), message.getHoldId(), message.getReason()));
            case CANCELLED -> new AppointmentAction.Result(new AppointmentResult.Cancelled(message.getEventId(),
                    message.getReservationProcessId(), message.getHoldId(), instant("occurredAt", message.getOccurredAt()),
                    message.getReason()));
            default -> throw new IllegalStateException("Unexpected eventType " + message.getEventType());
        };
    }

    private static AppointmentAction phoneRequest(AppointmentActionMessage message) {
        if (!PHONE_NUMBER.equals(message.getRequiredField())) {
            return discard(message, "unsupported requiredField " + message.getRequiredField());
        }
        return new AppointmentAction.PhoneRequest(AdditionalInformationRequest.builder()
                .eventId(message.getEventId())
                .reservationProcessId(message.getReservationProcessId())
                .holdId(message.getHoldId())
                .expiresAt(instant("expiresAt", message.getExpiresAt()))
                .build());
    }

    // Los comunes y los de ese tipo; lo que el tipo no usa no se exige
    private static List<String> missingFields(AppointmentActionMessage message, List<Field> required) {
        List<String> missing = new ArrayList<>();
        for (Field field : IDENTIFIERS) {
            addIfBlank(missing, field, message);
        }
        for (Field field : required) {
            addIfBlank(missing, field, message);
        }
        if (message.getSchemaVersion() == null) {
            missing.add("schemaVersion");
        }
        return missing;
    }

    private static void addIfBlank(List<String> missing, Field field, AppointmentActionMessage message) {
        if (blank(field.value().apply(message))) {
            missing.add(field.name());
        }
    }

    private static String identifierProblem(String identifier) {
        if (identifier.length() > MAX_IDENTIFIER_LENGTH) {
            return "is too long";
        }
        return identifier.indexOf('\0') >= 0 ? "has the null character" : null;
    }

    private static Instant instant(String field, String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeException e) {
            throw new MalformedFieldException(field);
        }
    }

    // Opcional en donde se usa: si falta, queda sin valor
    private static LocalDate date(String value) {
        try {
            return blank(value) ? null : LocalDate.parse(value);
        } catch (DateTimeException e) {
            throw new MalformedFieldException("date");
        }
    }

    private static LocalTime time(String value) {
        try {
            return blank(value) ? null : LocalTime.parse(value);
        } catch (DateTimeException e) {
            throw new MalformedFieldException("startTime");
        }
    }

    private static Long number(String field, String value) {
        try {
            return blank(value) ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new MalformedFieldException(field);
        }
    }

    private static AppointmentAction.Discarded discard(AppointmentActionMessage message, String reason) {
        return new AppointmentAction.Discarded(message.getEventId(), message.getReservationProcessId(), reason);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // Un campo que el mensaje exige, con el nombre con que se informa si falta
    private record Field(String name, Function<AppointmentActionMessage, String> value) {
    }

    // El mensaje de la excepción es el nombre del campo
    private static final class MalformedFieldException extends RuntimeException {
        private MalformedFieldException(String field) {
            super(field);
        }
    }
}
