package turnos.user.infrastructure.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRequest {

    // La misma regla que el login de JHipster: letras, números y _ . @ -, o un login con forma de email.
    private static final String LOGIN_REGEX =
            "^(?:[a-zA-Z0-9!$&*+=?^_`{|}~.-]+@[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*+|[_.@A-Za-z0-9-]+)$";

    @NotBlank(message = "Login is mandatory")
    @Size(min = 3, max = 50, message = "Login must be between 3 and 50 characters")
    @Pattern(regexp = LOGIN_REGEX, message = "Login contains characters that are not allowed")
    private String login;

    @NotNull(message = "Password is mandatory")
    @Size(min = 4, max = 100, message = "Password must be between 4 and 100 characters")
    private String password;

    @NotBlank(message = "First name is mandatory")
    @Size(min = 2, max = 50, message = "First name must be between 2 and 50 characters")
    private String firstName;

    @NotBlank(message = "Last name is mandatory")
    @Size(min = 2, max = 50, message = "Last name must be between 2 and 50 characters")
    private String lastName;

    @NotBlank(message = "Email is mandatory")
    @Email(message = "Email must be a valid email address")
    @Size(min = 5, max = 254, message = "Email must be between 5 and 254 characters")
    private String email;

    @NotBlank(message = "Language key is mandatory")
    @Size(min = 2, max = 10, message = "Language key must be between 2 and 10 characters")
    private String langKey;

    private String imageUrl;
}
