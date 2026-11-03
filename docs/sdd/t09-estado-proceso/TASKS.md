# TASKS: T09 — Consultar estado de un proceso

**SPEC:** `docs/sdd/t09-estado-proceso/SPEC.md` (aprobada el 2026-11-03)
**PLAN:** `docs/sdd/t09-estado-proceso/PLAN.md` (aprobado el 2026-11-03)

Tandas propuestas, cada una deja la suite en verde: 01+02; 03+04+05; 06; 07+08+09; 10+13; 11+12.

- [x] **TASK-01 · La regla del vencido en el modelo**
  - Alcance: `needsOverdueResolution` y `resolveOverdue` en `ReservationProcess`, y sus tests en `ReservationProcessTest` (P1): cada estado, dentro y fuera del margen, y con la marca encendida o apagada. No toca el caso de uso ni los métodos existentes.
  - Depende de: ninguna
  - Resuelve: CA-03, CA-04 y CA-05 (la regla; la respuesta y la base en TASK-03 y TASK-07)
  - Validación: `./mvnw test -Dtest=ReservationProcessTest`. Prueba en negativo: ignorar la marca en `needsOverdueResolution` y ver fallar el test.
  - Resultado: 2026-11-03. `ReservationProcessTest`: 114 tests en verde, 11 nuevos (cierre, marca, ya marcado, dentro del margen y cada estado cerrado). Prueba en negativo: al ignorar la marca en `needsOverdueResolution` falla `aProcessWithThePhoneSubmittedAlreadyMarkedHasNothingLeftToResolve`; archivo restaurado y hash comprobado.

- [x] **TASK-02 · La respuesta con todos sus campos**
  - Alcance: `ReservationProcessDtoMapper` informa `rejectionReason`, `reservationId`, `confirmedAt`, `cancelledAt` y `cancellationReason`, con los instantes en segundos. Actualiza `everyExposedFieldOfAnOpenProcessReachesTheResponse` y suma un caso por estado en `ReservationProcessDtoMapperTest`. No cambia el DTO.
  - Depende de: ninguna
  - Resuelve: CA-08 (mapper), CA-06 (el DTO no expone esos datos)
  - Validación: `./mvnw test -Dtest='ReservationProcessDtoMapperTest,ReservationProcessControllerTest,ReservationProcessIntegrationTest'`, sin cambiar lo que los dos últimos comprueban de las respuestas de T04 a T06. Prueba en negativo: sacar un campo del mapper y ver fallar el test.
  - Resultado: 2026-11-03. `ReservationProcessDtoMapperTest`: 14 tests en verde, 6 nuevos. `ReservationProcessControllerTest` (59) e `ReservationProcessIntegrationTest` (11) en verde, sin cambios en esos archivos. Se actualizó `everyExposedFieldOfAnOpenProcessReachesTheResponse`, que ya no ignora los cinco campos. Pruebas en negativo: sacar `confirmedAt` y no recortar a segundos hacen fallar 3 tests cada una; archivo restaurado y hash comprobado. Suite completa de la tanda: `./mvnw clean test`, exit 0, 1096 `<testcase>` (partida: 1079), 0 fallas, 0 errores, 0 salteados, 99 XML. La suma del atributo `tests` da 1094; no se investigó la diferencia de 2, y el conteo de partida se hizo por `<testcase>`.

- [x] **TASK-03 · Caso de uso y service: consultar y resolver el vencido**
  - Alcance: crea `GetReservationProcessByIdUseCase` y su `Impl`, con la lectura 1, el candado, la lectura 2 y el `update` (P3, P4); suma `getReservationProcessById` a `ReservationService`, con `orElseThrow`. Crea `GetReservationProcessByIdUseCaseImplTest`, con `CopyingReservationProcessRepository` y `MutableClock`, para inexistente o ajeno, cierre, marca y sin escritura en los estados cerrados. No toca el controller. Suma `ReservationServiceTest` (el `404` del service) y actualiza `CatedraEventAdapterTest`, que arma el service a mano; el alcance se amplió con esto al implementar.
  - Depende de: TASK-01
  - Resuelve: CA-02, CA-03, CA-04 y CA-05 (lógica; sobre la base en TASK-07)
  - Validación: `./mvnw test -Dtest=GetReservationProcessByIdUseCaseImplTest`
  - Resultado: 2026-11-03. `GetReservationProcessByIdUseCaseImplTest` en verde (28 tests en total con TASK-04 y TASK-05), `ReservationServiceTest` (2) y `CatedraEventAdapterTest` (20, sin cambiar lo que comprueba).

- [x] **TASK-04 · Convivencia con las demás operaciones del usuario**
  - Alcance: tests de concurrencia en `GetReservationProcessByIdUseCaseImplTest`, con el candado tomado por el test y la consulta en otro hilo. Solo agrega tests, salvo que muestren un defecto de TASK-03. `CopyingReservationProcessRepository` suma `afterNextOwnerRead`, que simula otra operación justo después de una lectura.
  - Depende de: TASK-03
  - Resuelve: CA-09, CA-10 (simulado; real en TASK-08), CA-11
  - Validación: `./mvnw test -Dtest=GetReservationProcessByIdUseCaseImplTest`. Pruebas en negativo: quitar la lectura 2 (falla CA-10) y tomar siempre el candado (fallan CA-09 y CA-11).
  - Resultado: 2026-11-03. Tests en verde: responde con el candado tomado si no venció o ya está marcado; espera si tiene que escribir; un `CONFIRMED`, un `CANCELLED` o un rechazo con nuevo plazo después de la lectura 1 no se pisan; dos consultas simultáneas escriben una sola vez. La carrera se simula en la base en memoria; sobre Postgres queda para TASK-08. Pruebas en negativo: sin la lectura 2 fallan 4 tests; siempre con candado fallan 2; sin candado al escribir falla 1. Archivo restaurado y hash comprobado.

- [x] **TASK-05 · Falla al guardar el cierre o la marca**
  - Alcance: en `GetReservationProcessByIdUseCaseImpl`, atrapar la falla del `update`, registrar solo el tipo y el id, y lanzar `ReservationNotSavedException`. Tests con `failUpdatesWhen`, una fila con teléfono y el log capturado, también cuando el mensaje de la excepción incluye el teléfono. `CopyingReservationProcessRepository` suma `failUpdatesWith`.
  - Depende de: TASK-03
  - Resuelve: CA-12 (lógica y log)
  - Validación: `./mvnw test -Dtest=GetReservationProcessByIdUseCaseImplTest`. Prueba en negativo: registrar la excepción completa y ver fallar el test.
  - Resultado: 2026-11-03. Tests en verde: al fallar el `update` lanza `ReservationNotSavedException`, el proceso queda igual y la consulta siguiente cierra o marca; el log tiene solo el tipo de error, también con una excepción cuyo mensaje incluye el teléfono; una falla de lectura llega sin traducir. Prueba en negativo: registrar el mensaje de la excepción hace fallar `thePhoneNeverReachesTheLogWhenSavingFails`; archivo restaurado y hash comprobado. Suite completa de la tanda: `./mvnw clean test`, exit 0, 1126 `<testcase>` (1096 + 30), 0 fallas, 0 errores, 0 salteados, 101 XML.

- [x] **TASK-06 · El endpoint**
  - Alcance: `GET /api/reservation-processes/{reservationProcessId}` en `ReservationProcessController`, con `404` `RESERVATION_PROCESS_NOT_FOUND` y `500` `INTERNAL_ERROR`. Tests en `ReservationProcessControllerTest`: `200`, `404`, `500`, y `401` sin token, con token inválido y vencido, sin llamar al service.
  - Depende de: TASK-03
  - Resuelve: CA-01, CA-02 y CA-12 (capa web)
  - Validación: `./mvnw test -Dtest=ReservationProcessControllerTest`
  - Resultado: 2026-11-03. `ReservationProcessControllerTest`: 69 tests en verde, 10 nuevos: `200` con los campos y sin datos privados, dueño del token con otro rol (`ROLE_ADMIN`), `404`, `500` al leer y al guardar, teléfono de la causa fuera del log, `401` sin token, inválido y vencido, y `403` con un rol no válido, sin llamar al service. Pruebas en negativo: sin el `catch` del `404` falla 1 test; con el dueño fijo falla 1. Sin el `catch` de `ReservationException` al principio no falló ningún test, porque el manejador global responde lo mismo: lo que evita ese `catch` es registrar la causa, así que se sumó `thePhoneInTheCauseOfAFailedSaveNeverReachesTheLog`, que con esa rotura falla. Archivo restaurado y hash comprobado. Suite completa: `./mvnw clean test`, exit 0, 1136 `<testcase>` (1126 + 10), 0 fallas, 0 errores, 0 salteados, 101 XML.

- [x] **TASK-07 · Integración sobre Postgres real**
  - Alcance: crea `ReservationProcessStatusIntegrationTest`, con la app completa, Postgres en Testcontainers, y cátedra y catálogo simulados. Escribe el estado de partida en la base. Cubre inexistente y ajeno vencido, cierre, marca, cada estado con sus campos, sin requests a los servidores simulados, y JSON y log sin los datos privados.
  - Depende de: TASK-02, TASK-05, TASK-06
  - Resuelve: CA-02, CA-03, CA-04, CA-05, CA-06, CA-08
  - Validación: `./mvnw test -Dtest=ReservationProcessStatusIntegrationTest`
  - Resultado: 2026-11-03. `ReservationProcessStatusIntegrationTest` en verde (28 tests en total con TASK-08 y TASK-09): inexistente y ajeno vencido con el mismo `404` y la fila ajena igual; cierre y marca en la respuesta y en la fila; sin cambios dentro del margen; dos consultas de un `PHONE_SUBMITTED` vencido escriben una sola vez; estados cerrados sin escritura ni requests a los servidores simulados; JSON sin datos privados y teléfono fuera del log en los siete estados; campos por estado, con instantes en segundos.

- [x] **TASK-08 · Carrera y falla de escritura sobre la base real**
  - Alcance: en `ReservationProcessStatusIntegrationTest`: un `CONFIRMED` escrito en la base mientras la consulta espera el candado; y un `update` que falla a pedido sobre la base real, seguido de una consulta que resuelve. Si hace falta el repositorio controlable de `PhoneSubmissionKafkaIntegrationTest`, se extrae a su propio archivo para compartirlo, sin copiarlo.
  - Depende de: TASK-07
  - Resuelve: CA-10 (real, P2), CA-12 (escritura real)
  - Validación: `./mvnw test -Dtest='ReservationProcessStatusIntegrationTest,PhoneSubmissionKafkaIntegrationTest'`. Prueba en negativo: quitar la lectura 2 y ver fallar el test.
  - Resultado: 2026-11-03. El repositorio controlable se extrajo a `ControllableProcessRepository`; `PhoneSubmissionKafkaIntegrationTest` solo cambió su `@Import` y sigue en verde (11 tests). Un `CONFIRMED` escrito en Postgres mientras la consulta espera el candado se responde y no se pisa. Con un `update` que falla con el teléfono en el mensaje: `500` `INTERNAL_ERROR`, fila igual, teléfono fuera del log, y la consulta siguiente cierra o marca. Pruebas en negativo: `open-in-view: true` hace fallar `aReservationConfirmedWhileTheQueryWaitsForTheLockIsNotOverwritten` (confirma P2), y quitar la lectura 2 hace fallar el mismo test. Archivos restaurados y hash comprobado.

- [x] **TASK-09 · Id con el carácter nulo**
  - Alcance: consulta con `%00` contra el servidor real, en `ReservationProcessStatusIntegrationTest` (P5). Si responde `500`, el caso de uso trata ese id como inexistente, sin ir a la base, y se avisa al usuario. Si T05 o T06 tienen el mismo problema, se avisa y no se arregla acá.
  - Depende de: TASK-07
  - Resuelve: CA-13
  - Validación: `./mvnw test -Dtest=ReservationProcessStatusIntegrationTest`, y registrar qué respondió de verdad.
  - Resultado: 2026-11-03. `GET` con `%00` contra el servidor real: `400`, página HTML de Tomcat. También `400` sin token, y `400` en `POST /confirm` y `POST /phone`, así que el servidor lo rechaza antes de la seguridad y de los controllers: no llega a la base y no hace falta cambiar código (P5). Esa respuesta no es `problem+json`. Un id de 300 caracteres responde `404`. Suite completa de la tanda: `./mvnw clean test`, exit 0, 1164 `<testcase>` (1136 + 28), 0 fallas, 0 errores, 0 salteados, 102 XML.

- [x] **TASK-10 · Consulta con la base caída de verdad**
  - Alcance: crea `DatabaseDownStatusQueryIntegrationTest`, con Postgres propio en pausa y `hikari.connection-timeout` corto, como `DatabaseDownKafkaIntegrationTest`. Comprueba `500` `INTERNAL_ERROR` y que, al reanudar, la consulta siguiente cierra el proceso.
  - Depende de: TASK-07
  - Resuelve: CA-12 (lectura real, y la consulta siguiente resuelve)
  - Validación: `./mvnw test -Dtest=DatabaseDownStatusQueryIntegrationTest`
  - Resultado: 2026-11-03. `DatabaseDownStatusQueryIntegrationTest`, con Postgres propio en pausa: `500` `INTERNAL_ERROR`, fila igual, teléfono fuera del log, y al reanudar la consulta siguiente cierra o marca. Prueba en negativo: si el caso de uso convierte la falla de lectura en vacío, fallan 3 tests. Archivo restaurado y hash comprobado. Una primera versión del test se colgó más de 5 minutos: leía la base justo antes de pausarla, y Hikari entrega sin comprobar una conexión recién usada. La consulta quedó bloqueada leyendo el socket, lo que dio lugar a D5 y TASK-13. Se corrigió el test y se le puso un tiempo máximo de 45 s.

- [x] **TASK-13 · Límite de espera de la base**
  - Alcance: agregada al implementar TASK-10, por D5. `socketTimeout` en `application.yaml` y `.env.example`, y tests en `TurnosServiceApplicationTests` y `DatabaseDownStatusQueryIntegrationTest`. No toca código de los casos de uso.
  - Depende de: TASK-10
  - Resuelve: CA-14
  - Validación: `./mvnw test -Dtest='DatabaseDownStatusQueryIntegrationTest,TurnosServiceApplicationTests'`, con pruebas en negativo: límite en 0 y sin la propiedad en el yaml.
  - Resultado: 2026-11-03. `DatabaseDownStatusQueryIntegrationTest` (5 tests) y `TurnosServiceApplicationTests` (4) en verde. Con el límite en 2 s: una lectura sobre una conexión congelada responde `500`; una escritura con la base congelada, con el candado tomado, responde `500` y la consulta siguiente cierra el proceso; `pg_sleep(10)` falla en menos de 8 s. Pruebas en negativo: con el límite en 0, los 5 tests dan error (dos se cuelgan hasta los 45 s, y el contenedor pausado arrastra a los demás); sin la propiedad en el yaml falla `theDatabaseConnectionHasALimitToWaitForAnAnswer`. Archivos restaurados y hash comprobado. Con la variable vacía la aplicación no arranca (`socketTimeout parameter value must be an integer`), comprobado con un test temporal ya borrado. El valor de 30 s no se midió contra operaciones reales. Suite completa de la tanda: `./mvnw clean test`, exit 0, 1170 `<testcase>` (1164 + 6), 0 fallas, 0 errores, 0 salteados, 103 XML.

- [ ] **TASK-11 · Documentación del servicio**
  - Alcance: `CLAUDE.md` (excepción de la lectura sin candado, T09 en el slice y en la lista de `docs/sdd`, las transiciones nuevas, `TURNOS_DB_SOCKET_TIMEOUT` y que no puede quedar vacía) y una actualización en `docs/decisiones/013-operaciones-de-un-usuario-de-a-una.md`. No toca contratos ni casos de uso.
  - Depende de: TASK-03
  - Resuelve: restricción de la SPEC (D2)
  - Validación: releer los dos textos contra el código final.
  - Resultado: pendiente

- [ ] **TASK-12 · Suite completa y revisión de los criterios**
  - Alcance: correr toda la suite y revisar CA-01 a CA-14 contra lo observado. Comprobar que los tests existentes solo cambiaron en lo previsto por TASK-02.
  - Depende de: TASK-01 a TASK-11 y TASK-13
  - Resuelve: CA-07
  - Validación: `./mvnw clean test`, con el conteo tomado de `target/surefire-reports/TEST-*.xml` (partida: 1079 tests, 0 fallas, 0 errores).
  - Resultado: pendiente
