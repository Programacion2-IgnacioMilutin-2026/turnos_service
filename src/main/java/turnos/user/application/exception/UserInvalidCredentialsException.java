package turnos.user.application.exception;

public class UserInvalidCredentialsException extends UserException {
    public UserInvalidCredentialsException() {
        super("Invalid credentials");
    }
}
