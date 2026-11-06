package turnos.catedra.infrastructure.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import turnos.catedra.domain.model.AdditionalInformationRequest;
import turnos.catedra.domain.model.AppointmentResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AppointmentActionReaderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REQUEST = message("additional-information-requested.json");
    private static final String CONFIRMED = "appointment-confirmed.json";
    private static final String REJECTED = "additional-information-rejected.json";
    private static final String EXPIRED = "appointment-process-expired.json";
    private static final String INVALID = "appointment-process-invalid.json";
    private static final String CANCELLED = "appointment-cancelled.json";

    private final AppointmentActionReader reader = new AppointmentActionReader(JSON);

    @Test
    void thePhoneRequestBecomesTheRequestWithEveryFieldItNeeds() {
        AppointmentAction action = reader.read(REQUEST);

        assertThat(action).isInstanceOfSatisfying(AppointmentAction.PhoneRequest.class, phoneRequest -> {
            assertThat(phoneRequest.request()).hasNoNullFieldsOrProperties();
            assertThat(phoneRequest.request()).usingRecursiveComparison().isEqualTo(AdditionalInformationRequest.builder()
                    .eventId("request-event-8a31").reservationProcessId("process-a92b47d0").holdId("hold-6c0c2f41")
                    .expiresAt(Instant.parse("2026-10-12T14:44:50Z")).build());
        });
    }

    @Test
    void unknownFieldsAreIgnoredAndTheTextAndInstantItDoesNotUseAreNotRequired() {
        ObjectNode request = node(REQUEST);
        request.put("newOptionalField", "added by the catedra");
        request.remove("message");
        request.remove("occurredAt");

        assertThat(reader.read(request.toString())).isInstanceOf(AppointmentAction.PhoneRequest.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "reservationProcessId", "holdId", "requiredField", "expiresAt", "schemaVersion"})
    void aRequestWithoutARequiredFieldIsDiscarded(String field) {
        ObjectNode request = node(REQUEST);
        request.remove(field);

        assertThat(reader.read(request.toString())).isInstanceOfSatisfying(AppointmentAction.Discarded.class,
                discarded -> assertThat(discarded.reason()).isEqualTo("missing " + field));
    }

    @Test
    void aBlankFieldCountsAsMissing() {
        ObjectNode request = node(REQUEST);
        request.put("holdId", " ");

        assertThat(reader.read(request.toString())).isEqualTo(
                new AppointmentAction.Discarded("request-event-8a31", "process-a92b47d0", "missing holdId"));
    }

    @Test
    void anotherSchemaVersionIsDiscarded() {
        ObjectNode request = node(REQUEST);
        request.put("schemaVersion", 2);

        assertThat(reader.read(request.toString())).isEqualTo(
                new AppointmentAction.Discarded("request-event-8a31", "process-a92b47d0", "unsupported schemaVersion 2"));
    }

    @Test
    void anotherRequiredFieldIsDiscarded() {
        ObjectNode request = node(REQUEST);
        request.put("requiredField", "EMAIL");

        assertThat(reader.read(request.toString())).isEqualTo(
                new AppointmentAction.Discarded("request-event-8a31", "process-a92b47d0", "unsupported requiredField EMAIL"));
    }

    @Test
    void aMalformedInstantDiscardsTheRequestSayingWhichOneItWas() {
        ObjectNode request = node(REQUEST);
        request.put("expiresAt", "tomorrow");

        assertThat(reader.read(request.toString())).isEqualTo(
                new AppointmentAction.Discarded("request-event-8a31", "process-a92b47d0", "malformed expiresAt"));
    }

    @Test
    void aValueTheRequestDoesNotUseCanBeMalformed() {
        ObjectNode request = node(REQUEST);
        request.put("occurredAt", "2026-10-12T14:35:10");
        request.put("date", "not a date");
        request.put("reservationId", "not a number");

        assertThat(reader.read(request.toString())).isInstanceOf(AppointmentAction.PhoneRequest.class);
    }

    @Test
    void somethingThatIsNotJsonIsDiscarded() {
        assertThat(reader.read("not json")).isInstanceOfSatisfying(AppointmentAction.Discarded.class,
                discarded -> assertThat(discarded.reason()).startsWith("unreadable message"));
    }

    @Test
    void anEmptyMessageIsDiscarded() {
        for (String empty : new String[]{null, "", "  ", "null"}) {
            assertThat(reader.read(empty)).isEqualTo(new AppointmentAction.Discarded(null, null, "empty message"));
        }
    }

    @Test
    void aMessageWithoutEventTypeOrWithAnUnknownOneIsDiscarded() {
        ObjectNode withoutType = node(REQUEST);
        withoutType.remove("eventType");
        ObjectNode unknownType = node(REQUEST);
        unknownType.put("eventType", "SomethingNew");

        assertThat(reader.read(withoutType.toString())).isEqualTo(
                new AppointmentAction.Discarded("request-event-8a31", "process-a92b47d0", "missing eventType"));
        assertThat(reader.read(unknownType.toString())).isEqualTo(
                new AppointmentAction.Discarded("request-event-8a31", "process-a92b47d0", "unknown eventType SomethingNew"));
    }

    @Test
    void aConfirmationBecomesTheResultWithEveryFieldItUses() {
        assertThat(reader.read(message(CONFIRMED))).isEqualTo(new AppointmentAction.Result(new AppointmentResult.Confirmed(
                "confirmation-event-7d10", "process-a92b47d0", "hold-6c0c2f41", 8251L,
                Instant.parse("2026-10-12T14:36:01Z"), 15L, LocalDate.of(2026, 10, 20), LocalTime.of(9, 30))));
    }

    @Test
    void aConfirmationWithoutTheAppointmentDataIsStillAConfirmation() {
        ObjectNode confirmed = node(message(CONFIRMED));
        confirmed.remove(List.of("professionalId", "date", "startTime"));

        assertThat(reader.read(confirmed.toString())).isEqualTo(new AppointmentAction.Result(new AppointmentResult.Confirmed(
                "confirmation-event-7d10", "process-a92b47d0", "hold-6c0c2f41", 8251L,
                Instant.parse("2026-10-12T14:36:01Z"), null, null, null)));
    }

    @Test
    void aRejectionBecomesTheResultWithItsReasonAndExpiration() {
        assertThat(reader.read(message(REJECTED))).isEqualTo(new AppointmentAction.Result(new AppointmentResult.Rejected(
                "rejection-event-7d11", "process-a92b47d0", "hold-6c0c2f41", "INVALID_PHONE_NUMBER",
                Instant.parse("2026-10-12T14:44:50Z"))));
    }

    @Test
    void anExpirationBecomesTheResult() {
        assertThat(reader.read(message(EXPIRED))).isEqualTo(new AppointmentAction.Result(new AppointmentResult.Expired(
                "process-event-7d12", "process-a92b47d0", "hold-6c0c2f41")));
    }

    @Test
    void anInvalidProcessBecomesTheResultWithItsReason() {
        assertThat(reader.read(message(INVALID))).isEqualTo(new AppointmentAction.Result(new AppointmentResult.Invalid(
                "process-event-7d13", "process-a92b47d0", "hold-6c0c2f41", "REQUEST_EVENT_MISMATCH")));
    }

    @Test
    void aCancellationBecomesTheResultWithItsReasonAndInstant() {
        assertThat(reader.read(message(CANCELLED))).isEqualTo(new AppointmentAction.Result(new AppointmentResult.Cancelled(
                "cancellation-event-7d14", "process-a92b47d0", "hold-6c0c2f41", Instant.parse("2026-10-13T15:10:00Z"),
                "Cambio solicitado por el usuario")));
    }

    @Test
    void aCancellationDoesNotRequireWhatMayBeMissingBeforeTheConfirmation() {
        ObjectNode cancelled = node(message(CANCELLED));
        cancelled.remove(List.of("requestEventId", "reservationId", "professionalId", "date", "startTime"));
        cancelled.putNull("reason");

        assertThat(reader.read(cancelled.toString())).isEqualTo(new AppointmentAction.Result(new AppointmentResult.Cancelled(
                "cancellation-event-7d14", "process-a92b47d0", "hold-6c0c2f41", Instant.parse("2026-10-13T15:10:00Z"), null)));
    }

    @Test
    void anExpirationOrAnInvalidProcessOnlyRequireTheCommonFields() {
        for (String file : List.of(EXPIRED, INVALID)) {
            ObjectNode result = node(message(file));
            result.remove(List.of("reason", "occurredAt", "requestEventId", "groupId"));

            assertThat(reader.read(result.toString())).isInstanceOf(AppointmentAction.Result.class);
        }
    }

    @ParameterizedTest
    @CsvSource({
            CONFIRMED + ",eventId", CONFIRMED + ",reservationProcessId", CONFIRMED + ",holdId", CONFIRMED + ",schemaVersion",
            CONFIRMED + ",reservationId", CONFIRMED + ",occurredAt",
            REJECTED + ",eventId", REJECTED + ",reservationProcessId", REJECTED + ",holdId", REJECTED + ",schemaVersion",
            REJECTED + ",reason", REJECTED + ",expiresAt",
            EXPIRED + ",eventId", EXPIRED + ",reservationProcessId", EXPIRED + ",holdId", EXPIRED + ",schemaVersion",
            INVALID + ",eventId", INVALID + ",reservationProcessId", INVALID + ",holdId", INVALID + ",schemaVersion",
            CANCELLED + ",eventId", CANCELLED + ",reservationProcessId", CANCELLED + ",holdId", CANCELLED + ",schemaVersion",
            CANCELLED + ",occurredAt"})
    void aResultWithoutARequiredFieldIsDiscarded(String file, String field) {
        ObjectNode result = node(message(file));
        result.remove(field);

        assertThat(reader.read(result.toString())).isInstanceOfSatisfying(AppointmentAction.Discarded.class,
                discarded -> assertThat(discarded.reason()).isEqualTo("missing " + field));
    }

    @ParameterizedTest
    @ValueSource(strings = {CONFIRMED, REJECTED, EXPIRED, INVALID, CANCELLED})
    void aResultWithAnotherSchemaVersionIsDiscarded(String file) {
        ObjectNode result = node(message(file));
        result.put("schemaVersion", 2);

        assertThat(reader.read(result.toString())).isInstanceOfSatisfying(AppointmentAction.Discarded.class,
                discarded -> assertThat(discarded.reason()).isEqualTo("unsupported schemaVersion 2"));
    }

    @ParameterizedTest
    @CsvSource({CONFIRMED + ",reservationId", CONFIRMED + ",occurredAt", CONFIRMED + ",professionalId",
            CONFIRMED + ",date", CONFIRMED + ",startTime", REJECTED + ",expiresAt", CANCELLED + ",occurredAt"})
    void aMalformedValueTheResultUsesDiscardsItKeepingWhichOneItWas(String file, String field) {
        ObjectNode result = node(message(file));
        result.put(field, "not valid");

        assertThat(reader.read(result.toString())).isEqualTo(new AppointmentAction.Discarded(
                result.get("eventId").asString(), "process-a92b47d0", "malformed " + field));
    }

    @ParameterizedTest
    @CsvSource({CONFIRMED + ",expiresAt", REJECTED + ",occurredAt", REJECTED + ",date", EXPIRED + ",occurredAt",
            INVALID + ",occurredAt", CANCELLED + ",date", CANCELLED + ",reservationId", CANCELLED + ",startTime"})
    void aMalformedValueTheResultDoesNotUseDoesNotDiscardIt(String file, String field) {
        ObjectNode result = node(message(file));
        result.put(field, "not valid");

        assertThat(reader.read(result.toString())).isInstanceOf(AppointmentAction.Result.class);
    }

    @ParameterizedTest
    @MethodSource("messagesAndIdentifiers")
    void anIdentifierUpToTheLimitIsAcceptedAndALongerOneIsDiscarded(String file, String identifier) {
        ObjectNode atTheLimit = node(message(file));
        atTheLimit.put(identifier, "a".repeat(255));
        ObjectNode tooLong = node(message(file));
        tooLong.put(identifier, "a".repeat(256));

        assertThat(reader.read(atTheLimit.toString())).isNotInstanceOf(AppointmentAction.Discarded.class);
        assertThat(reader.read(tooLong.toString())).isInstanceOfSatisfying(AppointmentAction.Discarded.class,
                discarded -> assertThat(discarded.reason()).isEqualTo(identifier + " is too long"));
    }

    @ParameterizedTest
    @MethodSource("messagesAndIdentifiers")
    void anIdentifierWithTheNullCharacterIsDiscarded(String file, String identifier) {
        ObjectNode result = node(message(file));
        result.put(identifier, "with\0null");

        assertThat(reader.read(result.toString())).isInstanceOfSatisfying(AppointmentAction.Discarded.class,
                discarded -> assertThat(discarded.reason()).isEqualTo(identifier + " has the null character"));
    }

    // Mensajes reales publicados por la cátedra (2026-11-04 y 2026-11-06): instantes con nanosegundos y, en una
    // cancelación sin motivo, reason en null

    @Test
    void aRealPhoneRequestBecomesTheRequestWithItsExactExpiration() {
        AppointmentAction action = reader.read(message("additional-information-requested-real.json"));

        assertThat(action).isInstanceOfSatisfying(AppointmentAction.PhoneRequest.class, phoneRequest ->
                assertThat(phoneRequest.request()).usingRecursiveComparison().isEqualTo(AdditionalInformationRequest.builder()
                        .eventId("request-event-dc431eb4-19ad-49fb-bcd8-efb6493e89ee")
                        .reservationProcessId("process-dc876cba-7654-42e0-bb93-ceaaacba6a25")
                        .holdId("hold-b6240361-bb98-49b4-8a75-3acd8d832779")
                        .expiresAt(Instant.parse("2026-11-06T06:15:34.496777646Z")).build()));
    }

    @Test
    void aRealConfirmationBecomesTheResultWithItsAppointment() {
        assertThat(reader.read(message("appointment-confirmed-real.json"))).isEqualTo(new AppointmentAction.Result(
                new AppointmentResult.Confirmed("confirmation-event-52d9034c-a32a-448f-ba31-98fa6f083207",
                        "process-dc876cba-7654-42e0-bb93-ceaaacba6a25", "hold-b6240361-bb98-49b4-8a75-3acd8d832779", 1665L,
                        Instant.parse("2026-11-06T06:05:41.713858758Z"), 201L, LocalDate.of(2026, 11, 9), LocalTime.of(13, 30))));
    }

    @Test
    void aRealCancellationWithoutReasonBecomesTheResultWithoutReason() {
        assertThat(reader.read(message("appointment-cancelled-real.json"))).isEqualTo(new AppointmentAction.Result(
                new AppointmentResult.Cancelled("cancellation-event-575fa905-d1be-43e5-8d79-4130b0ffee7c",
                        "process-dc876cba-7654-42e0-bb93-ceaaacba6a25", "hold-b6240361-bb98-49b4-8a75-3acd8d832779",
                        Instant.parse("2026-11-06T06:05:44.233380937Z"), null)));
    }

    @Test
    void aRealExpirationBecomesTheResult() {
        assertThat(reader.read(message("appointment-process-expired-real.json"))).isEqualTo(new AppointmentAction.Result(
                new AppointmentResult.Expired("process-event-129d8e5a-01e2-48d9-9327-d03baa83ed69",
                        "process-bfcb30eb-6e39-402f-bd92-95e73bfec54d", "hold-6798700e-87fe-4f94-8b2e-0a57054ff10d")));
    }

    private static Stream<Arguments> messagesAndIdentifiers() {
        return Stream.of(CONFIRMED, REJECTED, EXPIRED, INVALID, CANCELLED, "additional-information-requested.json")
                .flatMap(file -> Stream.of("eventId", "reservationProcessId", "holdId")
                        .map(identifier -> Arguments.of(file, identifier)));
    }

    private static ObjectNode node(String json) {
        return (ObjectNode) JSON.readTree(json);
    }

    private static String message(String name) {
        try (InputStream input = AppointmentActionReaderTest.class.getResourceAsStream("/catedra/kafka/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
