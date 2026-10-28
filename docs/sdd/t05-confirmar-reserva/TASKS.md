# TASKS: T05 — Confirmar reserva (confirmación inicial)

**SPEC:** `docs/sdd/t05-confirmar-reserva/SPEC.md` (aprobada el 2026-10-28)
**PLAN:** `docs/sdd/t05-confirmar-reserva/PLAN.md` (aprobado el 2026-10-28)

Las tareas siguen el "Orden de implementación" del PLAN. Rutas relativas a `src/main/java/turnos/` y `src/test/java/turnos/`, salvo indicación. Los nombres de los tests describen el comportamiento, sin citar documentos. Los tests se cuentan con los reportes XML de Surefire. Después de que un test nuevo pasa, una prueba en negativo: romper el código a propósito, ver que falla, restaurar y comprobar el hash.

Tandas propuestas, para que la suite quede en verde al cerrar cada una: TASK-01; TASK-02 y TASK-03; TASK-04 y TASK-05; TASK-06 y TASK-07; TASK-08 y TASK-09; TASK-10; TASK-11 y TASK-12. TASK-01, TASK-10 y TASK-11 llaman a la cátedra real: antes se lista y se confirma cada request, y las corre el usuario en su terminal (el modo automático bloquea las escrituras a la cátedra).

- [ ] **TASK-01 · Grabar las respuestas reales de la confirmación**
  - Alcance: limpiar y versionar en `src/test/resources/catedra/` las dos respuestas de la verificación de D2 (`confirm-202-accepted.json`, `confirm-409-already-confirmed.json`), hoy en el scratchpad. Preparar un script en el scratchpad que, con las credenciales del `.env` sin mostrarlas, haga: login técnico; ocupaciones del profesional 201 el próximo lunes; confirmar un `holdId` inventado (`404` `HOLD_NOT_FOUND`); confirmar con un token inválido (`401`); un hold nuevo en un turno libre; confirmarlo con otro `externalPatientId` (`400` `EXTERNAL_PATIENT_MISMATCH`); esperar unos 10 minutos y medio, y confirmarlo bien (`404` `HOLD_EXPIRED`). Listar los requests y esperar la confirmación del usuario; lo corre el usuario. Versionar `confirm-404-hold-not-found.json`, `confirm-401-invalid-token.json` (vacío), `confirm-400-external-patient-mismatch.json` y `confirm-404-hold-expired.json`, y anotar en el `README.md` cada archivo y el `409` `RESERVATION_NOT_CANCELLABLE` de cancelar un `WAITING_FOR_PHONE` (no versionado). Al terminar, borrar el script y las respuestas crudas del scratchpad. Sin código de producción.
  - Depende de: ninguna
  - Resuelve: base de CA-04, CA-07, CA-08 y CA-09 (respuestas reales)
  - Validación: comparar cada archivo con lo grabado (estado, `Content-Type`, campos); `grep` sin coincidencias con la URL, el host, las credenciales ni textos con forma de JWT (comprobado sin imprimir los valores)
  - Resultado: pendiente

- [ ] **TASK-02 · `user`: nombre y apellido del usuario**
  - Alcance: `user/domain/ports/in/GetUserNameUseCase`, `user/domain/model/UserName` (solo `firstName` y `lastName`), `user/application/usecases/GetUserNameUseCaseImpl`; `UserRepository` y `JpaUserRepositoryAdapter` suman `findById`. Tests del caso de uso y del adaptador JPA. No toca `reservation`.
  - Depende de: ninguna
  - Resuelve: base de CA-04 (RF-05)
  - Validación: `./mvnw test -Dtest='GetUserNameUseCaseImplTest,JpaUserRepositoryAdapterTest'`: un usuario existente devuelve exactamente su nombre y apellido; uno inexistente, vacío; `findById` lee el usuario de Postgres real. Regresión de los tests de `user`
  - Resultado: pendiente

- [ ] **TASK-03 · `catedra`: confirmar un hold**
  - Alcance: `catedra/domain/model/HoldConfirmationResult` (sellado: `Confirmed` con `reservationProcessId` y `expiresAt`, `AlreadyConfirmed`, `HoldNotFound`, `HoldExpired`, `OwnerMismatch`, `PatientMismatch`, `ProfessionalNotFound`, `InvalidSlot`, `SlotNotAvailable`, `ConfigurationError`, `Unavailable`, `Failed`); `domain/ports/in/ConfirmHoldUseCase` y `application/usecases/ConfirmHoldUseCaseImpl` con `CatedraOperationRunner`; `CatedraHoldClient` y `CatedraHoldClientAdapter` suman `confirmHold` (un intento; respuestas definitivas como `Answered`; conexión rechazada, timeouts, corte, `503` y `500` como `Unavailable`; `401` como `TokenRejected`; `403` `STUDENT_INTEGRATION_NOT_FOUND` como `ConfigurationError`); DTO `ConfirmHoldRequest` y `HoldConfirmationResponse`; `HoldMapper` convierte el `202`. El hold de T04 no cambia.
  - Depende de: TASK-01
  - Resuelve: CA-05, CA-06; base de CA-04, CA-07, CA-08, CA-09 y CA-10
  - Validación: `./mvnw test -Dtest='CatedraHoldClientAdapterTest,ConfirmHoldUseCaseImplTest,HoldMapperTest'`: request exacto (ruta con el `holdId`, cuerpo, `Bearer`); cada grabación al resultado que corresponde; los errores armados a mano (`403` dueño, profesional, turno, `503`, `500`, timeouts, corte) a su resultado; `401` y después `202` → un login y una repetición; `401` dos veces → no repite más; fallas y después `202` o "ya confirmado" → resultado definitivo; el token técnico no aparece en la salida. Regresión de los tests del hold
  - Resultado: pendiente

- [ ] **TASK-04 · `reservation`: modelo, migración y persistencia**
  - Alcance: `ReservationProcess` suma `needsReconciliation`, `confirm(expiresAt)` (solo desde `HELD`) y `markForReconciliation()`; modelos nuevos `ReservationPatient` y `ReservationHoldConfirmation`; `src/main/resources/db/migration/V3__add_reservation_process_reconciliation_flag.sql` (`needs_reconciliation` `BOOLEAN NOT NULL DEFAULT FALSE`); entidad y mapper de persistencia con la columna; `ReservationProcessRepository`, `JpaReservationProcessRepository` y su adaptador suman `findByIdAndUserId` y `markForReconciliation(id)` (un `UPDATE` de una columna).
  - Depende de: ninguna
  - Resuelve: CA-15; base de CA-01, CA-02, CA-09, CA-10 y CA-11
  - Validación: `./mvnw test -Dtest='ReservationProcessTest,JpaReservationProcessRepositoryAdapterTest,ReservationProcessMapperTest'`: `confirm` solo desde `HELD`; la marca se enciende y se lee de la base; `findByIdAndUserId` no devuelve el proceso de otro usuario; `markForReconciliation` no cambia el estado ni los demás campos; un proceso guardado antes de `V3` queda con la marca en falso. Regresión de los tests de persistencia de T04
  - Resultado: pendiente

- [ ] **TASK-05 · Candado compartido `UserOperationLock`**
  - Alcance: mover el candado por usuario de `CreateReservationProcessUseCaseImpl` a `reservation/application/support/UserOperationLock` (paquete nuevo, con su comentario de excepción), sin cambiar su código; T04 lo recibe inyectado. Test propio del candado.
  - Depende de: ninguna
  - Resuelve: base de CA-14 (RF-21)
  - Validación: `./mvnw test -Dtest='UserOperationLockTest,CreateReservationProcessUseCaseImplTest'`: dos trabajos del mismo usuario no se superponen; de usuarios distintos sí; el candado se descarta al soltarse; todos los tests de T04, incluidos los de concurrencia, siguen en verde sin cambios de comportamiento
  - Resultado: pendiente

- [ ] **TASK-06 · Pasarelas de `reservation`**
  - Alcance: `UserExistenceAdapter` pasa a `UserDirectoryAdapter` y suma el nombre del paciente con `GetUserNameUseCase`; `UserDirectory` suma ese método; `HoldProvider` suma `confirmHold` y `CatedraHoldAdapter` lo implementa con `ConfirmHoldUseCase`: `Confirmed` y `AlreadyConfirmed` a `ReservationHoldConfirmation`; cada rechazo a la excepción de `reservation` que corresponde; `OwnerMismatch` con un error grave en el log. Las excepciones nuevas de P6 se crean aquí.
  - Depende de: TASK-02, TASK-03, TASK-04
  - Resuelve: base de CA-04, CA-07, CA-08 y CA-09
  - Validación: `./mvnw test -Dtest='UserDirectoryAdapterTest,CatedraHoldAdapterTest'`: nombre del paciente; cada resultado de la cátedra a su excepción o resultado; el error grave de `HOLD_OWNER_MISMATCH` en el log. Regresión de los tests de las pasarelas de T04
  - Resultado: pendiente

- [ ] **TASK-07 · Caso de uso de confirmar y service**
  - Alcance: `domain/ports/in/ConfirmReservationProcessUseCase`, `application/usecases/ConfirmReservationProcessUseCaseImpl` con el recorrido de P5 dentro de `UserOperationLock`; `ReservationService` suma confirmar.
  - Depende de: TASK-04, TASK-05, TASK-06
  - Resuelve: CA-01, CA-02, CA-03, CA-08, CA-09, CA-10, CA-11, CA-12, CA-14; parte de CA-04, CA-07 y CA-13
  - Validación: `./mvnw test -Dtest='ConfirmReservationProcessUseCaseImplTest'` con dobles y `MutableClock`: inexistente o ajeno → `404` sin cátedra; cada estado no `HELD` → estado inválido sin cátedra; 29 s y 31 s después del vencimiento con el margen; `202` → `WAITING_FOR_PHONE` con el vencimiento recibido; "ya confirmado" → mismo vencimiento; `HOLD_EXPIRED` → `EXPIRED`; cátedra no disponible → `HELD` marcado (y respuesta igual si la marca falla); guardado que falla → `LOCAL_STATE_OUT_OF_SYNC`, error crítico y marca (y solo el log si la marca también falla); cierre de vencido que falla → error interno; dos confirmaciones simultáneas con la cátedra demorada → una sola confirmación; un bloqueo durante una confirmación que vence no reabre el proceso; la salida sin tokens
  - Resultado: pendiente

- [ ] **TASK-08 · Endpoint de confirmar y errores HTTP**
  - Alcance: `ReservationProcessController` suma `POST /api/reservation-processes/{reservationProcessId}/confirm` y la traducción de P6 (`currentStatus` en `INVALID_PROCESS_STATE`); la respuesta reutiliza `ReservationProcessResponse` y `ReservationProcessDtoMapper`.
  - Depende de: TASK-07
  - Resuelve: parte de CA-01, CA-04, CA-07 y CA-15
  - Validación: `./mvnw test -Dtest='ReservationProcessControllerTest'`: `200` con exactamente los campos del proceso, sin `holdId` ni la marca; cada excepción con su estado y `code`, sin el motivo interno; `currentStatus`; sin token `401` sin llamar al service; el `sub` como dueño. Regresión de los casos de T04
  - Resultado: pendiente

- [ ] **TASK-09 · Integración con la aplicación completa**
  - Alcance: ampliar `ReservationProcessIntegrationTest`: registro, login, bloqueo y confirmación con la cátedra simulada (grabaciones de hold y de confirmación); segunda confirmación; token de un `sub` sin usuario; cátedra no disponible con el proceso marcado en la base; salida sin tokens.
  - Depende de: TASK-08
  - Resuelve: CA-16; parte de CA-01, CA-04, CA-10 y CA-13
  - Validación: `./mvnw test -Dtest='ReservationProcessIntegrationTest'` y `./mvnw clean test` completa en verde; prueba en negativo
  - Resultado: pendiente

- [ ] **TASK-10 · E2E: bloquear y confirmar con la cátedra real**
  - Alcance: ampliar `src/test/java/e2e/ReservationProcessE2ETest`: después del bloqueo, confirmar y comprobar `200`, `WAITING_FOR_PHONE` y el mismo `expiresAt` del bloqueo; el intento de cancelar del final sigue avisando sin fallar. Listar los requests a la cátedra real y esperar la confirmación; lo corre el usuario en su terminal.
  - Depende de: TASK-09
  - Resuelve: CA-17
  - Validación: `./mvnw test -Pe2e` sin credenciales (salteado) y con credenciales (corrido por el usuario): reportes de Surefire con el resultado y sin la URL, el host, las credenciales ni textos con forma de JWT
  - Resultado: pendiente

- [ ] **TASK-11 · Prueba manual con la aplicación levantada**
  - Alcance: `docker compose up -d --wait`, un catálogo simulado en el scratchpad y `./mvnw spring-boot:run` con `TURNOS_CATALOG_BASE_URL` por variable de entorno. Con `curl`: registro, login, turnos libres, bloqueo, confirmación y segunda confirmación; un proceso inexistente; la base con el proceso. Las llamadas a la cátedra real se confirman antes; las que escriben las corre el usuario si el modo automático las bloquea. Dejar todo como estaba (aplicación y catálogo apagados, proceso y usuario de prueba borrados en ese orden, compose en su estado previo) y borrar los scripts.
  - Depende de: TASK-09
  - Resuelve: evidencia real de CA-01, CA-02, CA-04 y CA-13
  - Validación: cada request con la respuesta observada: confirmación → `200` `WAITING_FOR_PHONE` con el mismo vencimiento del bloqueo; segunda → `409` `INVALID_PROCESS_STATE` con `currentStatus`; inexistente → `404` `RESERVATION_PROCESS_NOT_FOUND`; la base con el proceso en `WAITING_FOR_PHONE` sin marcar; el log sin tokens ni credenciales
  - Resultado: pendiente

- [ ] **TASK-12 · `CLAUDE.md`, regresión y cobertura de los CA**
  - Alcance: `CLAUDE.md`: T05 en la tabla de slices; `reservation/application/support` y `UserOperationLock` en las excepciones (decisión 013); `GetUserNameUseCase`; la confirmación en `catedra` y su clasificación (todo reintentable); que la cátedra no cancela `WAITING_FOR_PHONE`; los tests nuevos y las decisiones 001 a 013. Corrida completa, chequeos de la skill y revisión de que cada CA tiene su evidencia en estas tareas. Sin cambios de código, salvo correcciones que surjan, que se anotan aquí.
  - Depende de: TASK-01 a TASK-11
  - Resuelve: regresión de T01, T02, X01, la validación del JWT, T03 y T04; comprobación de todos los CA
  - Validación: `./mvnw clean verify` en verde con el reporte de JaCoCo; los cinco chequeos de la skill, con el quinto mostrando solo los cruces de `infrastructure/gateway`; cada CA de la SPEC con su evidencia registrada
  - Resultado: pendiente
