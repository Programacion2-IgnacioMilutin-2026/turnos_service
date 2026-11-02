package turnos.catedra.application.exception;

// Un evento de la cátedra que no se pudo guardar: quien lo recibió tiene que volver a intentarlo más tarde
public class CatedraEventNotSavedException extends CatedraException {
    public CatedraEventNotSavedException(String eventId, String reason) {
        super("Catedra event " + eventId + " could not be saved: " + reason);
    }
}
