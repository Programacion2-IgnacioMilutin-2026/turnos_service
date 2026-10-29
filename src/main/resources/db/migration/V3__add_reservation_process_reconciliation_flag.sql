-- Marca los procesos cuyo estado local puede no coincidir con el de la cátedra. Los que ya existen quedan sin marcar
ALTER TABLE reservation_processes
    ADD COLUMN needs_reconciliation BOOLEAN NOT NULL DEFAULT FALSE;
