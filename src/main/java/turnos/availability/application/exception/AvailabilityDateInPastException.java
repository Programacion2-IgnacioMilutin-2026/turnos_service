package turnos.availability.application.exception;

import java.time.LocalDate;

public class AvailabilityDateInPastException extends AvailabilityException {
    public AvailabilityDateInPastException(LocalDate date) {
        super("Date is before today: " + date);
    }
}
