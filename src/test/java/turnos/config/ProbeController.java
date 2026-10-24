package turnos.config;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

// Endpoint protegido que existe solo en los tests, hasta que haya endpoints protegidos reales.
// @TestComponent: no lo registra el escaneo de la aplicación, solo los tests que lo importan.
// Obtiene la identidad igual que lo harán los controllers reales: del Jwt ya validado
@TestComponent
@RestController
public class ProbeController {

    private final AtomicInteger calls = new AtomicInteger();

    @GetMapping("/api/probe")
    public Map<String, Object> probe(@AuthenticationPrincipal Jwt jwt) {
        calls.incrementAndGet();
        Long userId = Long.valueOf(jwt.getSubject());
        return Map.of("userId", userId, "token", jwt.getTokenValue());
    }

    public int calls() {
        return calls.get();
    }

    // El contexto de Spring se reutiliza entre tests: cada test empieza la cuenta de cero
    public void reset() {
        calls.set(0);
    }
}
