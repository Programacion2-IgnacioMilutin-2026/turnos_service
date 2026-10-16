package turnos.user.infrastructure.persistence.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import turnos.user.domain.model.User;
import turnos.user.domain.ports.out.UserRepository;
import turnos.user.infrastructure.persistence.entity.UserEntity;
import turnos.user.infrastructure.persistence.mapper.UserMapper;
import turnos.user.infrastructure.persistence.repository.JpaUserRepository;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaUserRepositoryAdapter implements UserRepository {

    private final JpaUserRepository jpaUserRepository;
    private final UserMapper userMapper;

    @Override
    public User create(User user) {
        UserEntity userEntity = userMapper.toEntity(user);
        UserEntity savedEntity = jpaUserRepository.save(userEntity);
        return userMapper.toDomainModel(savedEntity);
    }

    @Override
    public Optional<User> findByLogin(String login) {
        return jpaUserRepository.findByLogin(login)
                .map(userMapper::toDomainModel);
    }

    @Override
    public boolean existsByLogin(String login) {
        return jpaUserRepository.existsByLogin(login);
    }

    @Override
    public boolean existsByEmail(String email) {
        return jpaUserRepository.existsByEmail(email);
    }
}
