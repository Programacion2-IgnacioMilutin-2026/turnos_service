package turnos.catedra.application.usecases;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.catedra.application.support.CatedraOperationRunner;
import turnos.catedra.domain.model.Appointment;
import turnos.catedra.domain.model.AppointmentLookupResult;
import turnos.catedra.domain.model.AppointmentStatus;
import turnos.catedra.domain.model.CatedraResponse;
import turnos.catedra.domain.ports.in.FindAppointmentUseCase;
import turnos.catedra.domain.ports.out.CatedraAppointmentClient;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class FindAppointmentUseCaseImpl implements FindAppointmentUseCase {

    // El tamaño de página probado con la cátedra real
    static final int PAGE_SIZE = 20;
    // Un paciente con más reservas que esto es una respuesta anómala: no se recorre sin fin
    static final int MAX_PAGES = 50;

    private final CatedraAppointmentClient catedraAppointmentClient;
    private final CatedraOperationRunner catedraOperationRunner;

    // Recorre las reservas del paciente hasta encontrar la del proceso. Solo se valida esa: una reserva mal formada de
    // otro proceso no impide encontrarla, y los datos de las demás no salen de aquí
    @Override
    public AppointmentLookupResult findAppointment(String externalPatientId, String reservationProcessId) {
        for (int page = 0; page < MAX_PAGES; page++) {
            int requestedPage = page;
            CatedraResponse<List<Appointment>> response = catedraOperationRunner.runAfterInitialLogin(
                    "listing of appointments for " + reservationProcessId,
                    token -> catedraAppointmentClient.listAppointments(externalPatientId, requestedPage, PAGE_SIZE, token));
            if (!(response instanceof CatedraResponse.Answered<List<Appointment>> answered)) {
                return problem(response);
            }
            List<Appointment> appointments = answered.content();
            Optional<Appointment> found = appointments.stream()
                    .filter(appointment -> reservationProcessId.equals(appointment.reservationProcessId())
                            && externalPatientId.equals(appointment.externalPatientId()))
                    .findFirst();
            if (found.isPresent()) {
                return validated(found.get());
            }
            if (appointments.size() < PAGE_SIZE) {
                return new AppointmentLookupResult.NotFound();
            }
        }
        return new AppointmentLookupResult.Failed("Catedra listed more than " + MAX_PAGES + " pages of appointments");
    }

    private static AppointmentLookupResult validated(Appointment appointment) {
        if (appointment.status() == AppointmentStatus.UNKNOWN) {
            return new AppointmentLookupResult.Failed("Catedra answered an appointment with an unknown status");
        }
        if (appointment.status() == AppointmentStatus.CONFIRMED && appointment.confirmedAt() == null) {
            return new AppointmentLookupResult.Failed("Catedra answered a confirmed appointment without its confirmation date");
        }
        if (appointment.status() == AppointmentStatus.CANCELLED && appointment.cancelledAt() == null) {
            return new AppointmentLookupResult.Failed("Catedra answered a cancelled appointment without its cancellation date");
        }
        return new AppointmentLookupResult.Found(appointment);
    }

    private static AppointmentLookupResult problem(CatedraResponse<List<Appointment>> response) {
        return switch (response) {
            case CatedraResponse.ConfigurationError<List<Appointment>> error -> new AppointmentLookupResult.ConfigurationError(error.reason());
            case CatedraResponse.Unavailable<List<Appointment>> unavailable -> new AppointmentLookupResult.Unavailable(unavailable.reason());
            // El cliente del listado no informa respuestas inciertas; si llegara una, una consulta se puede repetir
            case CatedraResponse.Uncertain<List<Appointment>> uncertain -> new AppointmentLookupResult.Unavailable(uncertain.reason());
            // El componente común renueva la sesión ante un rechazo; si igual llegara uno, la cátedra no está disponible
            case CatedraResponse.TokenRejected<List<Appointment>> _ -> new AppointmentLookupResult.Unavailable("Technical token rejected");
            case CatedraResponse.Failed<List<Appointment>> failed -> new AppointmentLookupResult.Failed(failed.reason());
            case CatedraResponse.Answered<List<Appointment>> _ -> throw new IllegalStateException("An answer is not a problem");
        };
    }
}
