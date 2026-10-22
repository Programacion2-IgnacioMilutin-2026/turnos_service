package turnos.user.application.exception;

public class UserEmailAlreadyExistsException extends UserException {
    public UserEmailAlreadyExistsException(String email) {
        super("Email already registered: " + email);
    }
}
