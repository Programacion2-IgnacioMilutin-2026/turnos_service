package turnos.catedra.application.usecases;

import turnos.catedra.domain.model.ProvisioningStatus;
import turnos.catedra.domain.model.TechnicalIntegration;
import turnos.catedra.domain.model.TechnicalLoginResult;
import turnos.catedra.domain.ports.out.TechnicalLoginClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

// Cliente de login falso para los tests de casos de uso: devuelve una secuencia de resultados y cuenta las llamadas.
// Cuando la secuencia se agota, repite el último resultado
public class ScriptedLoginClient implements TechnicalLoginClient {

    private final List<Supplier<TechnicalLoginResult>> script = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    public ScriptedLoginClient then(TechnicalLoginResult result) {
        script.add(() -> result);
        return this;
    }

    public ScriptedLoginClient then(Supplier<TechnicalLoginResult> result) {
        script.add(result);
        return this;
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public TechnicalLoginResult login() {
        int call = calls.incrementAndGet();
        return script.get(Math.min(call, script.size()) - 1).get();
    }

    public static TechnicalLoginResult.Success success(String token, ProvisioningStatus status) {
        return new TechnicalLoginResult.Success(token, integration(status));
    }

    public static TechnicalIntegration integration(ProvisioningStatus status) {
        return new TechnicalIntegration("proyecto-juan-perez", "kafka:9092", "alumnos-proyecto-juan-perez",
                "alumnos.turnos.acciones.proyecto-juan-perez", "catedra.turnos.telefono.proyecto-juan-perez", status);
    }
}
