package turnos.user.infrastructure.persistence.mapper;

import org.junit.jupiter.api.Test;
import turnos.user.domain.model.Authority;
import turnos.user.domain.model.User;
import turnos.user.infrastructure.persistence.entity.UserEntity;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {

    private final UserMapper mapper = new UserMapper();

    @Test
    void roundTripKeepsEveryField() {
        User original = fullUser();
        assertThat(original).hasNoNullFieldsOrProperties();

        User result = mapper.toDomainModel(mapper.toEntity(original));

        assertThat(result).usingRecursiveComparison().isEqualTo(original);
    }

    @Test
    void authoritiesAreCopiedInBothDirections() {
        User original = fullUser();

        UserEntity entity = mapper.toEntity(original);
        User result = mapper.toDomainModel(entity);

        assertThat(entity.getAuthorities()).isNotSameAs(original.getAuthorities());
        assertThat(result.getAuthorities()).isNotSameAs(entity.getAuthorities());
    }

    @Test
    void nullMapsToNull() {
        assertThat(mapper.toEntity(null)).isNull();
        assertThat(mapper.toDomainModel(null)).isNull();
    }

    private User fullUser() {
        return User.builder()
                .id(1042L)
                .login("maria.gomez")
                .passwordHash("$2a$10$abcdefghijklmnopqrstuv")
                .firstName("María")
                .lastName("Gómez")
                .email("maria.gomez@example.com")
                .imageUrl("https://example.com/avatar.png")
                .langKey("es")
                .activated(true)
                .authorities(Set.of(Authority.ROLE_USER))
                .createdDate(Instant.parse("2026-10-16T12:00:00Z"))
                .lastModifiedDate(Instant.parse("2026-10-16T12:30:00Z"))
                .build();
    }
}
