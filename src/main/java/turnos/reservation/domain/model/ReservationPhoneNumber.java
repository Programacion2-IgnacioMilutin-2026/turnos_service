package turnos.reservation.domain.model;

import java.util.Optional;
import java.util.regex.Pattern;

// record inmutable en lugar de clase Lombok: excepción a la skill autorizada por el usuario.
// Un teléfono válido y normalizado: se quitan solo los separadores que acepta la cátedra, y cualquier otro carácter lo
// hace inválido. Es un dato personal: su toString no lo muestra, para que no termine en un log
public record ReservationPhoneNumber(String value) {

    private static final Pattern SEPARATORS = Pattern.compile("[ ().-]");
    private static final Pattern VALID = Pattern.compile("\\+?[0-9]{7,15}");

    public static Optional<ReservationPhoneNumber> parse(String phoneNumber) {
        if (phoneNumber == null) {
            return Optional.empty();
        }
        String normalized = SEPARATORS.matcher(phoneNumber).replaceAll("");
        return VALID.matcher(normalized).matches() ? Optional.of(new ReservationPhoneNumber(normalized)) : Optional.empty();
    }

    @Override
    public String toString() {
        return "ReservationPhoneNumber[***]";
    }
}
