package turnos.user.domain.ports.in;

import turnos.user.domain.model.UserName;

import java.util.Optional;

public interface GetUserNameUseCase {
    Optional<UserName> getUserName(Long id);
}
