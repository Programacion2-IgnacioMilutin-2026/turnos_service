-- Lo que informa la cátedra como resultado del proceso. Los procesos que ya existen quedan sin estos datos
ALTER TABLE reservation_processes
    ADD COLUMN reservation_id      BIGINT,
    ADD COLUMN confirmed_at        TIMESTAMPTZ,
    ADD COLUMN cancelled_at        TIMESTAMPTZ,
    ADD COLUMN cancellation_reason VARCHAR(500),
    ADD COLUMN rejection_reason    VARCHAR(500);
