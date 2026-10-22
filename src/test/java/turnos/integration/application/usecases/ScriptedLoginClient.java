package turnos.integration.application.usecases;

import turnos.integration.domain.model.ProvisioningStatus;
import turnos.integration.domain.model.TechnicalIntegration;
import turnos.integration.domain.model.TechnicalLoginResult;
import turnos.integration.domain.ports.out.TechnicalLoginClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

// Cliente de login falso para los tests de casos de uso: devuelve una secuencia de resultados y cuenta las llamadas.
// Cuando la secuencia se agota, repite el último resultado
class ScriptedLoginClient implements TechnicalLoginClient {

    private final List<Supplier<TechnicalLoginResult>> script = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    ScriptedLoginClient then(TechnicalLoginResult result) {
        script.add(() -> result);
        return this;
    }

    ScriptedLoginClient then(Supplier<TechnicalLoginResult> result) {
        script.add(result);
        return this;
    }

    int calls() {
        return calls.get();
    }

    @Override
    public TechnicalLoginResult login() {
        int call = calls.incrementAndGet();
        return script.get(Math.min(call, script.size()) - 1).get();
    }

    static TechnicalLoginResult.Success success(String token, ProvisioningStatus status) {
        return new TechnicalLoginResult.Success(token, integration(status));
    }

    static TechnicalIntegration integration(ProvisioningStatus status) {
        return new TechnicalIntegration("proyecto-juan-perez", "kafka:9092", "alumnos-proyecto-juan-perez",
                "alumnos.turnos.acciones.proyecto-juan-perez", "catedra.turnos.telefono.proyecto-juan-perez", status);
    }
}
