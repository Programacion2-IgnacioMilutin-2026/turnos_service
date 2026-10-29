package turnos.user.infrastructure.persistence.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import turnos.TestcontainersConfiguration;
import turnos.config.JpaAuditingConfig;
import turnos.user.domain.model.Authority;
import turnos.user.domain.model.User;
import turnos.user.domain.ports.out.UserRepository;
import turnos.user.infrastructure.persistence.mapper.UserMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@DataJpaTest
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class, JpaUserRepositoryAdapter.class, UserMapper.class})
class JpaUserRepositoryAdapterTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void createAssignsIdAndAuditDates() {
        User created = userRepository.create(newUser("maria.gomez", "maria.gomez@example.com"));

        assertThat(created.getId()).isNotNull();
        assertThat(created.getCreatedDate()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(created.getLastModifiedDate()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void findByLoginReadsBackEveryFieldFromTheDatabase() {
        User original = newUser("maria.gomez", "maria.gomez@example.com");
        User created = userRepository.create(original);
        entityManager.flush();
        entityManager.clear();

        User found = userRepository.findByLogin("maria.gomez").orElseThrow();

        assertThat(found.getId()).isEqualTo(created.getId());
        assertThat(found.getCreatedDate()).isNotNull();
        assertThat(found.getLastModifiedDate()).isNotNull();
        assertThat(found).usingRecursiveComparison()
                .ignoringFields("id", "createdDate", "lastModifiedDate")
                .isEqualTo(original);
    }

    @Test
    void findByIdReadsBackEveryFieldFromTheDatabase() {
        User original = newUser("maria.gomez", "maria.gomez@example.com");
        User created = userRepository.create(original);
        entityManager.flush();
        entityManager.clear();

        User found = userRepository.findById(created.getId()).orElseThrow();

        assertThat(found.getId()).isEqualTo(created.getId());
        assertThat(found).usingRecursiveComparison()
                .ignoringFields("id", "createdDate", "lastModifiedDate")
                .isEqualTo(original);
    }

    @Test
    void findByIdIsEmptyWhenTheUserDoesNotExist() {
        User created = userRepository.create(newUser("maria.gomez", "maria.gomez@example.com"));

        assertThat(userRepository.findById(created.getId() + 1000)).isEmpty();
    }

    @Test
    void findByLoginIsEmptyWhenTheLoginDoesNotExist() {
        assertThat(userRepository.findByLogin("nobody")).isEmpty();
    }

    @Test
    void existsByLoginAndByEmailAnswerBothCases() {
        userRepository.create(newUser("maria.gomez", "maria.gomez@example.com"));

        assertThat(userRepository.existsByLogin("maria.gomez")).isTrue();
        assertThat(userRepository.existsByLogin("juan.perez")).isFalse();
        assertThat(userRepository.existsByEmail("maria.gomez@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("juan.perez@example.com")).isFalse();
    }

    @Test
    void theDatabaseRejectsARepeatedLogin() {
        userRepository.create(newUser("maria.gomez", "maria.gomez@example.com"));

        assertThatThrownBy(() -> userRepository.create(newUser("maria.gomez", "otra@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseRejectsARepeatedEmail() {
        userRepository.create(newUser("maria.gomez", "maria.gomez@example.com"));

        assertThatThrownBy(() -> userRepository.create(newUser("otro.login", "maria.gomez@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existsByIdTellsWhetherTheUserIsInTheDatabase() {
        User created = userRepository.create(newUser("maria.gomez", "maria.gomez@example.com"));
        entityManager.flush();

        assertThat(userRepository.existsById(created.getId())).isTrue();
        assertThat(userRepository.existsById(created.getId() + 1000)).isFalse();
    }

    private User newUser(String login, String email) {
        return User.builder()
                .login(login)
                .passwordHash("$2a$10$abcdefghijklmnopqrstuv")
                .firstName("María")
                .lastName("Gómez")
                .email(email)
                .imageUrl("https://example.com/avatar.png")
                .langKey("es")
                .activated(true)
                .authorities(Set.of(Authority.ROLE_USER))
                .build();
    }
}
