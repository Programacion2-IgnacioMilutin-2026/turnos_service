package turnos.user.infrastructure.persistence.mapper;

import org.springframework.stereotype.Component;
import turnos.user.domain.model.Authority;
import turnos.user.domain.model.User;
import turnos.user.infrastructure.persistence.entity.UserEntity;

import java.util.HashSet;
import java.util.Set;

@Component
public class UserMapper {

    public UserEntity toEntity(User user) {
        if (user == null) {
            return null;
        }
        return UserEntity.builder()
                .id(user.getId())
                .login(user.getLogin())
                .passwordHash(user.getPasswordHash())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .imageUrl(user.getImageUrl())
                .langKey(user.getLangKey())
                .activated(user.isActivated())
                .authorities(copyOf(user.getAuthorities()))
                .createdDate(user.getCreatedDate())
                .lastModifiedDate(user.getLastModifiedDate())
                .build();
    }

    public User toDomainModel(UserEntity entity) {
        if (entity == null) {
            return null;
        }
        return User.builder()
                .id(entity.getId())
                .login(entity.getLogin())
                .passwordHash(entity.getPasswordHash())
                .firstName(entity.getFirstName())
                .lastName(entity.getLastName())
                .email(entity.getEmail())
                .imageUrl(entity.getImageUrl())
                .langKey(entity.getLangKey())
                .activated(entity.isActivated())
                .authorities(copyOf(entity.getAuthorities()))
                .createdDate(entity.getCreatedDate())
                .lastModifiedDate(entity.getLastModifiedDate())
                .build();
    }

    // Copia propia: ni el Set de Hibernate llega al dominio, ni un Set inmutable del dominio llega a Hibernate
    private Set<Authority> copyOf(Set<Authority> authorities) {
        return authorities == null ? null : new HashSet<>(authorities);
    }
}
