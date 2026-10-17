package turnos.user.domain.ports.out;

public interface PasswordHasher {

    String hash(String password);

}
