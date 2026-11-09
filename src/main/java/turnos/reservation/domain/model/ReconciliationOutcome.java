package turnos.reservation.domain.model;

// Lo que dejó la reconciliación de un proceso con lo que dice la cátedra
public enum ReconciliationOutcome {
    // El estado local cambió para coincidir con la cátedra
    CORRECTED,
    // Ya coincidía: a lo sumo se apagó la marca
    MATCHED,
    // El teléfono se envió y la cátedra todavía no lo resolvió: se vuelve a consultar más adelante
    STILL_PENDING,
    // Un proceso cerrado que la cátedra contradice y no se puede corregir sin reabrirlo: queda como está
    UNRESOLVED
}
