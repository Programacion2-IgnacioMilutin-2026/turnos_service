package turnos.catedra.infrastructure.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import turnos.catedra.domain.model.AdditionalInformationRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AppointmentActionReaderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REQUEST = message("additional-information-requested.json");

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
    void aMalformedInstantOrSomethingThatIsNotJsonIsDiscarded() {
        ObjectNode request = node(REQUEST);
        request.put("expiresAt", "tomorrow");

        assertThat(reader.read(request.toString())).isInstanceOfSatisfying(AppointmentAction.Discarded.class,
                discarded -> assertThat(discarded.reason()).startsWith("unreadable message"));
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

    @ParameterizedTest
    @ValueSource(strings = {"appointment-confirmed.json", "additional-information-rejected.json",
            "appointment-process-expired.json", "appointment-process-invalid.json", "appointment-cancelled.json"})
    void theResultsOfTheReservationAreNotProcessedYet(String file) {
        ObjectNode result = node(message(file));

        assertThat(reader.read(result.toString())).isEqualTo(new AppointmentAction.Discarded(
                result.get("eventId").asString(), "process-a92b47d0",
                result.get("eventType").asString() + " is not processed yet"));
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
