package turnos.user.infrastructure.web.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import turnos.user.application.exception.UserEmailAlreadyExistsException;
import turnos.user.application.exception.UserException;
import turnos.user.application.exception.UserInvalidCredentialsException;
import turnos.user.application.exception.UserLoginAlreadyExistsException;
import turnos.user.application.service.UserService;
import turnos.user.domain.model.User;
import turnos.user.infrastructure.web.dto.AuthenticateRequest;
import turnos.user.infrastructure.web.dto.TokenResponse;
import turnos.user.infrastructure.web.dto.UserRequest;
import turnos.user.infrastructure.web.dto.UserResponse;
import turnos.user.infrastructure.web.mapper.UserDtoMapper;

// Rutas bajo /api (no /users): excepción a la skill autorizada por el usuario
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserDtoMapper userDtoMapper;

    @PostMapping("/register")
    public ResponseEntity<UserResponse> registerUser(@Valid @RequestBody UserRequest userRequest) {
        User user = userDtoMapper.toDomain(userRequest);
        try {
            User registeredUser = userService.registerUser(user, userRequest.getPassword());
            return new ResponseEntity<>(userDtoMapper.toResponse(registeredUser), HttpStatus.CREATED);
        } catch (UserLoginAlreadyExistsException e) {
            throw error(HttpStatus.BAD_REQUEST, "USERNAME_ALREADY_EXISTS", e);
        } catch (UserEmailAlreadyExistsException e) {
            throw error(HttpStatus.BAD_REQUEST, "EMAIL_ALREADY_EXISTS", e);
        }
    }

    @PostMapping("/authenticate")
    public ResponseEntity<TokenResponse> authenticateUser(@Valid @RequestBody AuthenticateRequest authenticateRequest) {
        try {
            String token = userService.authenticateUser(authenticateRequest.getUsername(), authenticateRequest.getPassword());
            return ResponseEntity.ok(userDtoMapper.toTokenResponse(token));
        } catch (UserInvalidCredentialsException e) {
            throw error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", e);
        }
    }

    private ResponseStatusException error(HttpStatus status, String code, UserException e) {
        ResponseStatusException exception = new ResponseStatusException(status, e.getMessage());
        exception.getBody().setProperty("code", code);
        return exception;
    }
}
