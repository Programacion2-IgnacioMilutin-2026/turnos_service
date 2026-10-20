package turnos.user.infrastructure.web.mapper;

import org.springframework.stereotype.Component;
import turnos.user.domain.model.User;
import turnos.user.infrastructure.web.dto.TokenResponse;
import turnos.user.infrastructure.web.dto.UserRequest;
import turnos.user.infrastructure.web.dto.UserResponse;

@Component
public class UserDtoMapper {

    public User toDomain(UserRequest request) {
        if (request == null) {
            return null;
        }
        return User.builder()
                .login(request.getLogin())
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .imageUrl(request.getImageUrl())
                .langKey(request.getLangKey())
                .build();
    }

    public UserResponse toResponse(User domain) {
        if (domain == null) {
            return null;
        }
        return UserResponse.builder()
                .id(domain.getId())
                .login(domain.getLogin())
                .firstName(domain.getFirstName())
                .lastName(domain.getLastName())
                .email(domain.getEmail())
                .imageUrl(domain.getImageUrl())
                .langKey(domain.getLangKey())
                .activated(domain.isActivated())
                .build();
    }

    public TokenResponse toTokenResponse(String token) {
        if (token == null) {
            return null;
        }
        return TokenResponse.builder()
                .idToken(token)
                .build();
    }
}
