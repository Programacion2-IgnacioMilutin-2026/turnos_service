package turnos.user.infrastructure.web.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {
    private Long id;
    private String login;
    private String firstName;
    private String lastName;
    private String email;
    private String imageUrl;
    private String langKey;
    private boolean activated;
}
