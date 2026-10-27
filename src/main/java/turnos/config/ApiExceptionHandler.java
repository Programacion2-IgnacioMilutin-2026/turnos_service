package turnos.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

// @ControllerAdvice global en config: excepción a la skill autorizada por el usuario.
// Solo da formato a los errores; los estados y códigos de cada slice los decide su controller.
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldErrorItem> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorItem(error.getField(), messageOf(error)))
                .toList();
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more fields are invalid");
        body.setProperty("code", "VALIDATION_ERROR");
        body.setProperty("fieldErrors", fieldErrors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    // Si el JSON está bien escrito pero un campo no se puede convertir (texto en lugar de número, una fecha imposible),
    // se dice cuál; un JSON mal escrito no tiene un campo al que atribuirle el error
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Optional<String> field = unconvertibleField(ex);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                field.isPresent() ? "One or more fields are invalid" : "Malformed request body");
        body.setProperty("code", "VALIDATION_ERROR");
        field.ifPresent(name -> body.setProperty("fieldErrors", List.of(new FieldErrorItem(name, "invalid value"))));
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    // El texto de Spring revela cómo busca la ruta ("No static resource ..."): se reemplaza por uno neutro
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found");
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        body.setProperty("code", "INTERNAL_ERROR");
        return body;
    }

    private static Optional<String> unconvertibleField(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof MismatchedInputException || cause instanceof InputCoercionException) {
                String path = ((JacksonException) cause).getPath().stream()
                        .map(JacksonException.Reference::getPropertyName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("."));
                return path.isEmpty() ? Optional.empty() : Optional.of(path);
            }
        }
        return Optional.empty();
    }

    // Un valor que no se pudo convertir trae un texto de Spring con tipos de Java: se reemplaza por uno neutro
    private static String messageOf(FieldError error) {
        return error.isBindingFailure() ? "invalid value" : error.getDefaultMessage();
    }

    record FieldErrorItem(String field, String message) {
    }
}
