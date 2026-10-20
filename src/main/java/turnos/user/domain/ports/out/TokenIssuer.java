package turnos.user.domain.ports.out;

import turnos.user.domain.model.User;

public interface TokenIssuer {

    String issue(User user);

}
