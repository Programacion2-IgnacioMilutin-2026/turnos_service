-- Las reservas de un usuario se listan por este campo. El índice único de un proceso abierto solo cubre a los abiertos
CREATE INDEX ix_reservation_processes_user ON reservation_processes (user_id);
