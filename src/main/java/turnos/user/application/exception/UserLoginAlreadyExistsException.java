package turnos.user.application.exception;

public class UserLoginAlreadyExistsException extends UserException {
    public UserLoginAlreadyExistsException(String login) {
        super("Login already registered: " + login);
    }
}
