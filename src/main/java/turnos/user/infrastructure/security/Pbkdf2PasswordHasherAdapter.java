package turnos.user.infrastructure.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Component;
import turnos.user.domain.ports.out.PasswordHasher;

// Paquete infrastructure/security: excepción a la skill autorizada por el usuario
@Component
public class Pbkdf2PasswordHasherAdapter implements PasswordHasher {

    private final PasswordEncoder passwordEncoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    @Override
    public String hash(String password) {
        return passwordEncoder.encode(password);
    }

    @Override
    public boolean matches(String password, String passwordHash) {
        return passwordEncoder.matches(password, passwordHash);
    }
}
