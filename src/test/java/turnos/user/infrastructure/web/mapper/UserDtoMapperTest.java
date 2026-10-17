package turnos.user.infrastructure.web.mapper;

import org.junit.jupiter.api.Test;
import turnos.user.domain.model.Authority;
import turnos.user.domain.model.User;
import turnos.user.infrastructure.web.dto.UserRequest;
import turnos.user.infrastructure.web.dto.UserResponse;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserDtoMapperTest {

    private final UserDtoMapper mapper = new UserDtoMapper();

    @Test
    void requestToResponseKeepsEveryField() {
        UserRequest request = UserRequest.builder()
                .login("maria.gomez")
                .password("secreta123")
                .firstName("María")
                .lastName("Gómez")
                .email("maria.gomez@example.com")
                .langKey("es")
                .imageUrl("https://example.com/avatar.png")
                .build();
        assertThat(request).hasNoNullFieldsOrProperties();

        UserResponse response = mapper.toResponse(mapper.toDomain(request));

        assertThat(response).usingRecursiveComparison().ignoringFields("id", "activated").isEqualTo(request);
    }

    @Test
    void toDomainLeavesTheBackendFieldsEmpty() {
        UserRequest request = UserRequest.builder()
                .login("maria.gomez")
                .password("secreta123")
                .build();

        User domain = mapper.toDomain(request);

        assertThat(domain.getId()).isNull();
        assertThat(domain.getPasswordHash()).isNull();
        assertThat(domain.isActivated()).isFalse();
        assertThat(domain.getAuthorities()).isNull();
        assertThat(domain.getCreatedDate()).isNull();
        assertThat(domain.getLastModifiedDate()).isNull();
    }

    @Test
    void domainToResponseKeepsEveryExposedField() {
        User domain = User.builder()
                .id(1042L)
                .login("maria.gomez")
                .passwordHash("hash")
                .firstName("María")
                .lastName("Gómez")
                .email("maria.gomez@example.com")
                .imageUrl("https://example.com/avatar.png")
                .langKey("es")
                .activated(true)
                .authorities(Set.of(Authority.ROLE_USER))
                .createdDate(Instant.parse("2026-10-17T12:00:00Z"))
                .lastModifiedDate(Instant.parse("2026-10-17T12:30:00Z"))
                .build();
        assertThat(domain).hasNoNullFieldsOrProperties();

        assertThat(mapper.toResponse(domain)).usingRecursiveComparison().isEqualTo(domain);
    }

    @Test
    void nullMapsToNull() {
        assertThat(mapper.toDomain(null)).isNull();
        assertThat(mapper.toResponse(null)).isNull();
    }
}
