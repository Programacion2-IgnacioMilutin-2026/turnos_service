package turnos.reservation.domain.ports.out;

import turnos.reservation.domain.model.ReservationProcess;

public interface PhoneSubmitter {

    // Si hay con qué enviar el teléfono ahora; se pregunta antes de guardar nada
    boolean canSubmit();

    // Envía a la cátedra el teléfono guardado en el proceso. Si no lo logra, lanza ReservationCentralUnavailableException
    void submit(ReservationProcess reservationProcess);

}
