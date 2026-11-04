# TASKS: T11 — Cancelar reserva

**SPEC:** `docs/sdd/t11-cancelar-reserva/SPEC.md` (aprobada el 2026-11-04)
**PLAN:** `docs/sdd/t11-cancelar-reserva/PLAN.md` (aprobado el 2026-11-04)

Tandas propuestas, cada una deja la suite en verde: 01+02; 03; 04; 05; 06; 07+08.

- [x] **TASK-01 · El abandono en el modelo**
  - Alcance: `canBeAbandoned` y `abandon` en `ReservationProcess`, y sus tests. `cancel` no cambia.
  - Depende de: ninguna
  - Resuelve: CA-06 y CA-07 (la regla)
  - Validación: `./mvnw test -Dtest=ReservationProcessTest`, con cada estado, con y sin marca, y el cierre que borra el motivo de rechazo y la marca. Prueba en negativo: permitir abandonar un marcado.
  - Resultado: 2026-11-04. `ReservationProcessTest`: 124 tests en verde, 10 nuevos: abandono en `HELD` y `WAITING_FOR_PHONE` con la fecha y el motivo, borrando el motivo de rechazo, sin comparar el vencimiento; marcados y otros estados rechazados, sin cambios. Prueba en negativo: permitir abandonar un marcado hace fallar 2 casos de `aProcessMarkedForReconciliationIsNotAbandoned`. Archivo restaurado y hash comprobado.

- [x] **TASK-02 · La cancelación en `catedra`**
  - Alcance: `AppointmentCancellationResult`, `CancelAppointmentUseCase` y su `Impl`, `CatedraAppointmentClient`, `CatedraAppointmentClientAdapter` y sus DTO. Sin motivo, el pedido va sin body (P4). Las respuestas se arman desde el anexo §12 y §13, porque no hay grabaciones. No toca `reservation`.
  - Depende de: ninguna
  - Resuelve: CA-03 (el pedido sin motivo), CA-08 (el pedido), CA-09, CA-10, CA-11 y CA-12 (clasificación)
  - Validación: `./mvnw test -Dtest='CatedraAppointmentClientAdapterTest,CancelAppointmentUseCaseImplTest'`, con `FakeHttpServer`: `200`, `401`, `403` de cada código, `404`, `409`, `500`, `503`, corte, timeout y un `200` que no se puede leer; JWT y motivo en el pedido, y el token fuera del log.
  - Resultado: 2026-11-04. `CatedraAppointmentClientAdapterTest` (24) y `CancelAppointmentUseCaseImplTest` (8) en verde. Cliente: pedido con JWT y motivo, sin body si no hay motivo, `200` con y sin `reservationId`, `200` que no se puede usar, cada rechazo por su código, `401`, `500`, `503`, otros códigos, timeout, sin servidor, conexión cortada y token fuera del log. Caso de uso: reintentos hasta el máximo, renovación ante `401` una sola vez, y sesión degradada. Pruebas en negativo: no validar `cancelledAt`, mandar body sin motivo, no reconocer el `409` y no reintentar el `500` hacen fallar 1 test cada una. Archivo restaurado y hash comprobado. Las respuestas salen del anexo: **no se ejecutó contra la cátedra real**. Suite completa de la tanda: `./mvnw clean test`, exit 0, 1324 `<testcase>` (1282 + 42), 0 fallas, 0 errores, 0 salteados, 108 XML, 196 s.

- [ ] **TASK-03 · Caso de uso y gateway en `reservation`**
  - Alcance: `AppointmentCanceller`, `CatedraAppointmentAdapter`, las dos excepciones nuevas, `CancelReservationProcessUseCase` y su `Impl`, y `cancelReservationProcess` en `ReservationService`. Crea sus tests y actualiza los que arman el service a mano.
  - Depende de: TASK-01, TASK-02
  - Resuelve: CA-04, CA-05, CA-06, CA-07, CA-08, CA-09, CA-10, CA-11, CA-12 y CA-13 (lógica), CA-14, CA-15 (log)
  - Validación: `./mvnw test -Dtest='CancelReservationProcessUseCaseImplTest,CatedraAppointmentAdapterTest,ReservationServiceTest,CatedraEventAdapterTest'`. Concurrencia con `CopyingReservationProcessRepository`: candado tomado y dos pedidos a la vez. Pruebas en negativo: llamar a la cátedra en un abandono, no marcar en cada error, no tomar el candado y registrar la causa al fallar.
  - Resultado: pendiente

- [ ] **TASK-04 · El endpoint**
  - Alcance: `ReservationCancellationRequest`, el motivo en el mapper (vacío como ausente y sin carácter nulo) y `POST /api/reservation-processes/{id}/cancel` en el controller, con la traducción de cada error. Sus tests.
  - Depende de: TASK-03
  - Resuelve: CA-01, CA-02, CA-03 y CA-15 (web)
  - Validación: `./mvnw test -Dtest='ReservationProcessControllerTest,ReservationProcessDtoMapperTest'`: `401` y `403` sin llamar al service; 500 y 501 caracteres; sin body, `{}`, vacío y con espacios; JSON mal escrito; cada excepción con su estado y código; la causa de una falla al guardar fuera del log.
  - Resultado: pendiente

- [ ] **TASK-05 · Integración con la app completa**
  - Alcance: crea `ReservationCancellationIntegrationTest`, con Postgres real y cátedra y catálogo simulados, y el estado de partida escrito en la base. Cubre cada estado por HTTP; abandonar y después bloquear otro turno (T04) y listar (T10); cancelar un `CONFIRMED` con el pedido real a la cátedra simulada; los errores de la cátedra y la marca en la fila; falla al guardar con `ControllableProcessRepository`; y datos privados fuera del JSON y del log.
  - Depende de: TASK-04
  - Resuelve: CA-03, CA-04, CA-05, CA-06, CA-07, CA-08, CA-09, CA-11, CA-13 y CA-15
  - Validación: `./mvnw test -Dtest=ReservationCancellationIntegrationTest`
  - Resultado: pendiente

- [ ] **TASK-06 · E2E**
  - Alcance: `ReservationProcessE2ETest` suma un test que bloquea, abandona con T11 y bloquea otro turno, sin depender de Kafka. El recorrido largo cancela con T11 en lugar de la llamada directa, y la llamada directa queda solo como limpieza al terminar. **Claude no lo corre:** lo corre el usuario, con la lista de requests confirmada antes.
  - Depende de: TASK-05
  - Resuelve: CA-16 y CA-17 (código). CA-17 queda bloqueado hasta que la cátedra publique
  - Validación: `./mvnw test-compile`, y sin el perfil `e2e` no se ejecuta. CA-16 se verifica con la corrida del usuario, leyendo los reportes sin mostrar credenciales.
  - Resultado: pendiente

- [ ] **TASK-07 · Documentación del servicio**
  - Alcance: `CLAUDE.md` (T11 en el slice y en la lista de `docs/sdd`, la transición nueva, cómo se decidió reintentar la cancelación, el E2E, y la decisión 015 en la lista) y la decisión 015, comparada contra el código final. No toca contratos ni casos de uso, que ya se ajustaron en la SPEC.
  - Depende de: TASK-03, TASK-06
  - Resuelve: documentación de D1
  - Validación: releer los textos contra el código final.
  - Resultado: pendiente

- [ ] **TASK-08 · Suite completa y revisión de los criterios**
  - Alcance: correr toda la suite y revisar CA-01 a CA-18 contra lo observado. Comprobar que los tests existentes solo cambiaron en lo previsto.
  - Depende de: TASK-01 a TASK-07
  - Resuelve: CA-18
  - Validación: `./mvnw clean test`, con el conteo tomado de `target/surefire-reports/TEST-*.xml` por `<testcase>`.
  - Resultado: pendiente
