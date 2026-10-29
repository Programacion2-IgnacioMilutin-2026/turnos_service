package turnos.user.domain.ports.out;

import turnos.user.domain.model.User;

import java.util.Optional;

public interface UserRepository {

    User create(User user);
    Optional<User> findByLogin(String login);

    Optional<User> findById(Long id);
    boolean existsByLogin(String login);
    boolean existsByEmail(String email);
    boolean existsById(Long id);

}
