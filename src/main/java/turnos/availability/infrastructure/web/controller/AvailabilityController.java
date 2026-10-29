package turnos.availability.infrastructure.web.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import turnos.availability.application.exception.AvailabilityCatalogUnavailableException;
import turnos.availability.application.exception.AvailabilityCentralUnavailableException;
import turnos.availability.application.exception.AvailabilityDateInPastException;
import turnos.availability.application.exception.AvailabilityIntegrationException;
import turnos.availability.application.exception.AvailabilityProfessionalDisabledException;
import turnos.availability.application.exception.AvailabilityProfessionalNotFoundException;
import turnos.availability.application.exception.AvailabilityServiceDegradedException;
import turnos.availability.application.service.AvailabilityService;
import turnos.availability.domain.model.FreeSlotsResult;
import turnos.availability.infrastructure.web.dto.FreeSlotsRequest;
import turnos.availability.infrastructure.web.dto.FreeSlotsResponse;
import turnos.availability.infrastructure.web.mapper.AvailabilityDtoMapper;

import java.util.List;
import java.util.Map;

// Rutas bajo /api (no /availabilities): excepción a la skill autorizada por el usuario.
// Los textos de error son fijos: nunca exponen por qué falló catálogo o la cátedra
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AvailabilityController {

    private final AvailabilityService availabilityService;
    private final AvailabilityDtoMapper availabilityDtoMapper;

    // El token del usuario se reenvía a catálogo tal como llegó
    @GetMapping("/free-slots")
    public ResponseEntity<FreeSlotsResponse> getFreeSlots(@Valid @ModelAttribute FreeSlotsRequest request,
                                                          @AuthenticationPrincipal Jwt jwt) {
        try {
            FreeSlotsResult result = availabilityService.getFreeSlots(request.getProfessionalId(), request.getDate(), jwt.getTokenValue());
            return ResponseEntity.ok(availabilityDtoMapper.toResponse(request.getProfessionalId(), request.getDate(), result));
        } catch (AvailabilityDateInPastException e) {
            ResponseStatusException exception = error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "One or more fields are invalid");
            exception.getBody().setProperty("fieldErrors", List.of(Map.of("field", "date", "message", "must not be before today")));
            throw exception;
        } catch (AvailabilityProfessionalNotFoundException e) {
            throw error(HttpStatus.NOT_FOUND, "PROFESSIONAL_NOT_FOUND", "Professional not found");
        } catch (AvailabilityProfessionalDisabledException e) {
            throw error(HttpStatus.CONFLICT, "PROFESSIONAL_DISABLED", "Professional is disabled");
        } catch (AvailabilityCatalogUnavailableException e) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "CATALOG_UNAVAILABLE", "Catalog is unavailable");
        } catch (AvailabilityServiceDegradedException e) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_DEGRADED", "Service is degraded");
        } catch (AvailabilityCentralUnavailableException e) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "CENTRAL_UNAVAILABLE", "Central system is unavailable");
        } catch (AvailabilityIntegrationException e) {
            throw error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error");
        }
    }

    private static ResponseStatusException error(HttpStatus status, String code, String detail) {
        ResponseStatusException exception = new ResponseStatusException(status, detail);
        exception.getBody().setProperty("code", code);
        return exception;
    }
}
