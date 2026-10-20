package turnos.user.domain.model;

import lombok.*;

import java.time.Instant;
import java.util.Set;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private Long id;
    private String login;
    private String passwordHash;
    private String firstName;
    private String lastName;
    private String email;
    private String imageUrl;
    private String langKey;
    private boolean activated;
    private Set<Authority> authorities;
    private Instant createdDate;
    private Instant lastModifiedDate;

    public String externalPatientId() {
        return id == null ? null : id.toString();
    }
}
