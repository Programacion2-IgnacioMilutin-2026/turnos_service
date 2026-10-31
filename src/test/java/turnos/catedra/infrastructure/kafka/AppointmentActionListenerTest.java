package turnos.catedra.infrastructure.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;
import turnos.catedra.application.exception.CatedraEventNotSavedException;
import turnos.catedra.application.service.AdditionalInformationRequestService;
import turnos.catedra.domain.model.AdditionalInformationRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class AppointmentActionListenerTest {

    private final List<AdditionalInformationRequest> received = new CopyOnWriteArrayList<>();

    @Test
    void aPhoneRequestReachesTheApplicationOfCatedra() {
        listener(request -> received.add(request)).onMessage(message(read("additional-information-requested.json")));

        assertThat(received).singleElement().extracting(AdditionalInformationRequest::getEventId).isEqualTo("request-event-8a31");
    }

    @Test
    void aDiscardedMessageIsLoggedWithWhatItHadAndReachesNobody(CapturedOutput output) {
        listener(request -> received.add(request)).onMessage(message(read("appointment-confirmed.json")));

        assertThat(received).isEmpty();
        assertThat(output.getAll()).contains("WARN").contains("Catedra message at offset 7 discarded "
                + "(eventId=confirmation-event-7d10, reservationProcessId=process-a92b47d0): AppointmentConfirmed is not processed yet");
    }

    @Test
    void aFailureOfTheApplicationArrivesAsIs() {
        CatedraEventNotSavedException failure = new CatedraEventNotSavedException("request-event-8a31", "database down");

        assertThatThrownBy(() -> listener(request -> {
            throw failure;
        }).onMessage(message(read("additional-information-requested.json")))).isSameAs(failure);
    }

    private static AppointmentActionListener listener(Consumer<AdditionalInformationRequest> application) {
        return new AppointmentActionListener(new AppointmentActionReader(JsonMapper.builder().build()),
                new AdditionalInformationRequestService(application::accept));
    }

    private static ConsumerRecord<String, String> message(String value) {
        return new ConsumerRecord<>("alumnos.turnos.acciones.proyecto-de-prueba", 0, 7, "process-a92b47d0", value);
    }

    private static String read(String name) {
        try (InputStream input = AppointmentActionListenerTest.class.getResourceAsStream("/catedra/kafka/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
