package turnos.reservation.infrastructure.web.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import turnos.reservation.application.exception.ReservationCatalogUnavailableException;
import turnos.reservation.application.exception.ReservationCentralUnavailableException;
import turnos.reservation.application.exception.ReservationException;
import turnos.reservation.application.exception.ReservationHoldExpiredException;
import turnos.reservation.application.exception.ReservationHoldNotFoundException;
import turnos.reservation.application.exception.ReservationHoldNotSavedException;
import turnos.reservation.application.exception.ReservationInvalidProcessStateException;
import turnos.reservation.application.exception.ReservationInvalidSlotException;
import turnos.reservation.application.exception.ReservationLocalStateOutOfSyncException;
import turnos.reservation.application.exception.ReservationOpenProcessExistsException;
import turnos.reservation.application.exception.ReservationPatientMismatchException;
import turnos.reservation.application.exception.ReservationProcessNotFoundException;
import turnos.reservation.application.exception.ReservationProfessionalDisabledException;
import turnos.reservation.application.exception.ReservationProfessionalNotFoundException;
import turnos.reservation.application.exception.ReservationServiceDegradedException;
import turnos.reservation.application.exception.ReservationSlotInPastException;
import turnos.reservation.application.exception.ReservationSlotNotAvailableException;
import turnos.reservation.application.exception.ReservationUserNotFoundException;
import turnos.reservation.application.service.ReservationService;
import turnos.reservation.domain.model.ReservationProcess;
import turnos.reservation.infrastructure.web.dto.ReservationProcessRequest;
import turnos.reservation.infrastructure.web.dto.ReservationProcessResponse;
import turnos.reservation.infrastructure.web.mapper.ReservationProcessDtoMapper;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

// Rutas bajo /api (no /reservations): excepción a la skill autorizada por el usuario.
// Los errores se lanzan como ErrorResponseException, que a diferencia de ResponseStatusException puede llevar
// encabezados; el manejador global les da el mismo formato que a todos. Los textos son fijos: nunca exponen el motivo interno
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReservationProcessController {

    private final ReservationService reservationService;
    private final ReservationProcessDtoMapper reservationProcessDtoMapper;

    // El dueño del proceso sale del token, nunca del cuerpo; el token se reenvía a catálogo tal como llegó
    @PostMapping("/reservation-processes")
    public ResponseEntity<ReservationProcessResponse> createReservationProcess(
            @Valid @RequestBody ReservationProcessRequest request, @AuthenticationPrincipal Jwt jwt) {
        try {
            ReservationProcess created = reservationService.createReservationProcess(Long.valueOf(jwt.getSubject()),
                    reservationProcessDtoMapper.toProfessionalId(request), request.getDate(), request.getStartTime(),
                    jwt.getTokenValue());
            return new ResponseEntity<>(reservationProcessDtoMapper.toResponse(created), HttpStatus.CREATED);
        } catch (ReservationSlotInPastException e) {
            ErrorResponseException exception = error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "One or more fields are invalid");
            exception.getBody().setProperty("fieldErrors", List.of(Map.of("field", e.getField(),
                    "message", "date".equals(e.getField()) ? "must not be before today" : "must be after the current time")));
            throw exception;
        } catch (ReservationUserNotFoundException e) {
            // Igual que un token inválido: la app vuelve a iniciar sesión sin distinguir los casos
            ErrorResponseException exception = error(HttpStatus.UNAUTHORIZED, null, "A valid token is required");
            exception.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            throw exception;
        } catch (ReservationOpenProcessExistsException e) {
            ReservationProcess open = e.getOpenProcess();
            ErrorResponseException exception = error(HttpStatus.CONFLICT, "OPEN_PROCESS_EXISTS",
                    "The user already has an open reservation process");
            exception.getBody().setProperty("reservationProcessId", open.getReservationProcessId());
            exception.getBody().setProperty("currentStatus", open.getStatus().name());
            exception.getBody().setProperty("expiresAt", open.getExpiresAt().truncatedTo(ChronoUnit.SECONDS));
            throw exception;
        } catch (ReservationProfessionalNotFoundException e) {
            throw error(HttpStatus.NOT_FOUND, "PROFESSIONAL_NOT_FOUND", "Professional not found");
        } catch (ReservationProfessionalDisabledException e) {
            throw error(HttpStatus.CONFLICT, "PROFESSIONAL_DISABLED", "Professional is disabled");
        } catch (ReservationInvalidSlotException e) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_SLOT", "Slot is not part of the professional's schedules");
        } catch (ReservationSlotNotAvailableException e) {
            throw error(HttpStatus.CONFLICT, "SLOT_NOT_AVAILABLE", "Slot is no longer available");
        } catch (ReservationCatalogUnavailableException e) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE", "Catalog is unavailable");
        } catch (ReservationServiceDegradedException e) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_DEGRADED", "Service is degraded");
        } catch (ReservationCentralUnavailableException e) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "CENTRAL_UNAVAILABLE", "Central system is unavailable");
        } catch (ReservationHoldNotSavedException e) {
            ErrorResponseException exception = error(HttpStatus.INTERNAL_SERVER_ERROR, "HOLD_NOT_SAVED",
                    "The slot is held until the indicated instant");
            exception.getBody().setProperty("expiresAt", e.getExpiresAt().truncatedTo(ChronoUnit.SECONDS));
            exception.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(e.getSecondsUntilExpiration()));
            throw exception;
        } catch (ReservationException e) {
            throw error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error");
        }
    }

    // Sin body: el paciente es el dueño del token y su nombre sale del perfil. El proceso se busca entre los suyos,
    // así que uno ajeno responde igual que uno inexistente
    @PostMapping("/reservation-processes/{reservationProcessId}/confirm")
    public ResponseEntity<ReservationProcessResponse> confirmReservationProcess(@PathVariable String reservationProcessId,
                                                                                @AuthenticationPrincipal Jwt jwt) {
        try {
            ReservationProcess confirmed = reservationService.confirmReservationProcess(Long.valueOf(jwt.getSubject()),
                    reservationProcessId);
            return ResponseEntity.ok(reservationProcessDtoMapper.toResponse(confirmed));
        } catch (ReservationProcessNotFoundException e) {
            throw error(HttpStatus.NOT_FOUND, "RESERVATION_PROCESS_NOT_FOUND", "Reservation process not found");
        } catch (ReservationInvalidProcessStateException e) {
            ErrorResponseException exception = error(HttpStatus.CONFLICT, "INVALID_PROCESS_STATE",
                    "The reservation process is not in the required state");
            exception.getBody().setProperty("currentStatus", e.getCurrentStatus().name());
            throw exception;
        } catch (ReservationHoldExpiredException e) {
            throw error(HttpStatus.CONFLICT, "HOLD_EXPIRED", "The hold has expired");
        } catch (ReservationHoldNotFoundException e) {
            throw error(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "Hold not found");
        } catch (ReservationPatientMismatchException e) {
            throw error(HttpStatus.CONFLICT, "PATIENT_MISMATCH", "The patient does not match the hold");
        } catch (ReservationProfessionalNotFoundException e) {
            throw error(HttpStatus.NOT_FOUND, "PROFESSIONAL_NOT_FOUND", "Professional not found");
        } catch (ReservationInvalidSlotException e) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_SLOT", "Slot is not part of the professional's schedules");
        } catch (ReservationSlotNotAvailableException e) {
            throw error(HttpStatus.CONFLICT, "SLOT_NOT_AVAILABLE", "Slot is no longer available");
        } catch (ReservationServiceDegradedException e) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_DEGRADED", "Service is degraded");
        } catch (ReservationCentralUnavailableException e) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "CENTRAL_UNAVAILABLE", "Central system is unavailable");
        } catch (ReservationLocalStateOutOfSyncException e) {
            throw error(HttpStatus.INTERNAL_SERVER_ERROR, "LOCAL_STATE_OUT_OF_SYNC",
                    "The reservation process was confirmed but its state could not be saved");
        } catch (ReservationException e) {
            throw error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error");
        }
    }

    private static ErrorResponseException error(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        if (code != null) {
            body.setProperty("code", code);
        }
        return new ErrorResponseException(status, body, null);
    }
}
