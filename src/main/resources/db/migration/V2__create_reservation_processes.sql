-- La clave es el id que asigna la cátedra. Solo las columnas que usa el bloqueo de turnos; cada caso de uso
-- siguiente suma las suyas con su propia migración
CREATE TABLE reservation_processes (
    reservation_process_id     VARCHAR(255) PRIMARY KEY,
    hold_id                    VARCHAR(255) NOT NULL,
    user_id                    BIGINT       NOT NULL,
    professional_id            BIGINT       NOT NULL,
    professional_first_name    VARCHAR(255),
    professional_last_name     VARCHAR(255),
    professional_category_name VARCHAR(255),
    appointment_date           DATE         NOT NULL,
    start_time                 TIME         NOT NULL,
    status                     VARCHAR(30)  NOT NULL,
    expires_at                 TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_reservation_processes_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- Un usuario tiene como máximo un proceso abierto
CREATE UNIQUE INDEX ux_reservation_processes_one_open_per_user ON reservation_processes (user_id)
    WHERE status IN ('HELD', 'WAITING_FOR_PHONE', 'PHONE_SUBMITTED');
