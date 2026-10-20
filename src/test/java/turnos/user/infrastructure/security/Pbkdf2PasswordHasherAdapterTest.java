package turnos.user.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class Pbkdf2PasswordHasherAdapterTest {

    private final Pbkdf2PasswordHasherAdapter hasher = new Pbkdf2PasswordHasherAdapter();

    // 4 y 100 caracteres son los largos mínimo y máximo; 50 "ñ" ocupan 100 bytes, más de lo que admite BCrypt
    static Stream<String> passwordsAllowed() {
        return Stream.of("abcd", "a".repeat(100), "ñ".repeat(50));
    }

    @ParameterizedTest
    @MethodSource("passwordsAllowed")
    void hashesEveryAllowedPassword(String password) {
        String hash = hasher.hash(password);

        assertThat(hash).isNotEqualTo(password).hasSizeLessThanOrEqualTo(100);
        assertThat(Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8().matches(password, hash)).isTrue();
    }

    @Test
    void theSamePasswordGivesADifferentHashEachTime() {
        assertThat(hasher.hash("secreta123")).isNotEqualTo(hasher.hash("secreta123"));
    }

    @Test
    void aPasswordMatchesItsOwnHash() {
        assertThat(hasher.matches("secreta123", hasher.hash("secreta123"))).isTrue();
    }

    @Test
    void aDifferentPasswordDoesNotMatch() {
        String hash = hasher.hash("secreta123");

        assertThat(hasher.matches("Secreta123", hash)).isFalse();
        assertThat(hasher.matches("secreta12", hash)).isFalse();
    }
}
