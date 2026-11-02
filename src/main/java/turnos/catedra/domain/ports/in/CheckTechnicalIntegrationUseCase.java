package turnos.catedra.domain.ports.in;

// Si hay una integración con la cátedra para operar ahora. Sin sesión intenta obtener una. No entrega la sesión, porque
// lleva el JWT técnico, que no sale de este slice
public interface CheckTechnicalIntegrationUseCase {
    boolean checkTechnicalIntegration();
}
