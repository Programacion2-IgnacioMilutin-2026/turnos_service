package turnos.user.application.usecases;

import turnos.user.domain.model.User;
import turnos.user.domain.ports.out.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Busca con igualdad exacta, como la base: así los tests comprueban que las minúsculas las pone el caso de uso
class InMemoryUserRepository implements UserRepository {

    final List<User> users = new ArrayList<>();
    final List<Long> idsReceivedOnCreate = new ArrayList<>();

    @Override
    public User create(User user) {
        idsReceivedOnCreate.add(user.getId());
        user.setId((long) users.size() + 1);
        users.add(user);
        return user;
    }

    @Override
    public Optional<User> findByLogin(String login) {
        return users.stream().filter(user -> user.getLogin().equals(login)).findFirst();
    }

    @Override
    public boolean existsByLogin(String login) {
        return findByLogin(login).isPresent();
    }

    @Override
    public boolean existsByEmail(String email) {
        return users.stream().anyMatch(user -> user.getEmail().equals(email));
    }
}
