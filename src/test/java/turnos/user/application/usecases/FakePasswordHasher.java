package turnos.user.application.usecases;

import turnos.user.domain.ports.out.PasswordHasher;

// Hash predecible para los tests de casos de uso: "secreta123" → "hashed:secreta123"
class FakePasswordHasher implements PasswordHasher {

    @Override
    public String hash(String password) {
        return "hashed:" + password;
    }

    @Override
    public boolean matches(String password, String passwordHash) {
        return hash(password).equals(passwordHash);
    }
}
