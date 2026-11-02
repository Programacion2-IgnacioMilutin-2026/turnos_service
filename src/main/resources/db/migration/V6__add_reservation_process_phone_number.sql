-- El último teléfono que el usuario intentó informar, normalizado: un + y hasta 15 dígitos. Los procesos que ya existen
-- quedan sin teléfono
ALTER TABLE reservation_processes
    ADD COLUMN phone_number VARCHAR(16);
