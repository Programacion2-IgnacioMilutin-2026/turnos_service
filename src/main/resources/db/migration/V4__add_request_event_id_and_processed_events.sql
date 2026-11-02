-- El pedido de teléfono de la cátedra que el proceso tiene que responder. Los procesos que ya existen quedan sin él
ALTER TABLE reservation_processes
    ADD COLUMN request_event_id VARCHAR(255);

-- Los eventos de la cátedra que ya se procesaron, para no repetir su efecto si llegan otra vez. La clave es el
-- eventId que asigna la cátedra; no se relaciona con los procesos
CREATE TABLE processed_events (
    event_id     VARCHAR(255) PRIMARY KEY,
    processed_at TIMESTAMPTZ  NOT NULL
);
