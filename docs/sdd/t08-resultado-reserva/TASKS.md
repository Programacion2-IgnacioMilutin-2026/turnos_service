# TASKS: T08 — Procesar resultado de la reserva

**SPEC:** `docs/sdd/t08-resultado-reserva/SPEC.md` (aprobada el 2026-11-02)
**PLAN:** `docs/sdd/t08-resultado-reserva/PLAN.md` (aprobado el 2026-11-02)

Las tareas siguen el "Orden de implementación" del PLAN. Rutas relativas a `src/main/java/turnos/` y `src/test/java/turnos/`, salvo indicación. Los nombres de los tests describen el comportamiento, sin citar documentos. Los tests se cuentan con los reportes XML de Surefire o el resumen de Maven. Después de que un test pasa, una prueba en negativo: romper el código a propósito, ver fallar el test, restaurar y comprobar el hash. Ninguna tarea se conecta a la cátedra real ni a su Kafka.

Tandas propuestas, para que la suite quede en verde al cerrar cada una: TASK-01; TASK-02 y TASK-03; TASK-04 y TASK-05; TASK-06 y TASK-07; TASK-08; TASK-09 y TASK-10; TASK-11.

- [x] **TASK-01 · Renombrar las piezas de T07 sin cambiar el comportamiento**
  - Alcance: los renombres de P2: `AdditionalInformationRequestHandler` → `CatedraEventHandler`, `CatedraPhoneRequestAdapter` → `CatedraEventAdapter`, `AdditionalInformationRequestResult` → `CatedraEventResult`, `AdditionalInformationRequestService` → `CatedraEventService`, `PhoneRequestOutcome` → `ReservationEventOutcome`, con sus tests y comentarios. No agrega métodos ni cambia lógica. No toca `CLAUDE.md` (TASK-11).
  - Depende de: ninguna
  - Resuelve: base de P2
  - Validación: `./mvnw clean test` con el mismo conteo que al empezar (681) y sin fallos; `grep` de los cinco nombres viejos en `src/` sin coincidencias.
  - Resultado: 2026-11-02. Cinco clases y un test renombrados (`CatedraEventAdapterTest`), con los campos de quien los usa (`catedraEventHandler`, `catedraEventService`). Solo cambian nombres y los comentarios de esas clases, que pasan de "pedido" a "evento". `./mvnw clean test` termina bien: 681 tests en los reportes XML, 0 fallos, 0 errores, 0 salteados. `grep` de los nombres viejos en `src/`: sin coincidencias. Sin prueba en negativo: no hay comportamiento nuevo que probar. `CLAUDE.md` sigue con los nombres viejos hasta TASK-11. Sin commitear.

- [x] **TASK-02 · Estados y datos nuevos del proceso**
  - Alcance: `ReservationStatus` suma `CONFIRMED`, `CANCELLED` e `INVALID`, sin cambiar `isOpen`; `ReservationProcess` suma `reservationId`, `confirmedAt`, `cancelledAt`, `cancellationReason` y `rejectionReason`; migración `V5`; `ReservationProcessEntity` y `ReservationProcessMapper`; `CopyingReservationProcessRepository` copia los campos nuevos. Sin transiciones todavía. No toca `ReservationProcessDtoMapper` (T09).
  - Depende de: TASK-01
  - Resuelve: base de RF-06, RF-07 y RF-10; base de CA-21 (los estados nuevos no cuentan como abiertos)
  - Validación: `ReservationProcessMapperTest` (ida y vuelta con los campos nuevos); `JpaReservationProcessRepositoryAdapterTest` contra Postgres: guarda y lee los campos y los estados nuevos, un proceso anterior queda con los campos vacíos, un usuario con un proceso `CONFIRMED`, `CANCELLED` o `INVALID` puede tener otro abierto, y `findOpenByUserId` no los devuelve; `./mvnw test`.
  - Resultado: 2026-11-02. `V5` suma las cinco columnas, y el estado, el modelo, la entidad, el mapper y `CopyingReservationProcessRepository` los campos. `JpaReservationProcessRepositoryAdapterTest` contra Postgres real guarda y lee todo, con `reason` de 500 caracteres, y un proceso anterior queda con los campos vacíos; con un proceso `CONFIRMED`, `CANCELLED` o `INVALID`, el usuario puede tener otro abierto. Negativos: el mapper que pierde `rejectionReason` hace fallar `roundTripKeepsEveryField`; una columna de 400 hace fallar `theResultOfAProcessIsSavedAndReadBack`; `CONFIRMED` como abierto hace fallar `aClosedProcessIsNotOpenAndLetsItsUserHaveAnother`. Hash restaurado en cada ronda. La primera corrida completa falló en `ReservationProcessDtoMapperTest`: su control de que el ejemplo tenga todos los campos pidió sumar los cinco nuevos. Se sumaron al test, sin tocar el mapper web, y el test ya exige que esos campos sigan en `null` en la respuesta. `./mvnw clean test`: 736 tests, 0 fallos, 0 errores, 0 salteados. Sin commitear.

- [x] **TASK-03 · Transiciones en el modelo**
  - Alcance: `ReservationProcess`: una transición por evento, con la validación del estado de origen de la tabla del PLAN; recorte de `reason` a 500 caracteres; todo cierre borra el motivo y apaga la marca, también `expire` y `expireIfOverdue`; `AppointmentProcessInvalid` enciende la marca. No toca casos de uso.
  - Depende de: TASK-02
  - Resuelve: RF-05 a RF-10, RF-15, RF-16; CA-17, CA-18; parte de CA-01 a CA-07 y CA-19
  - Validación: `ReservationProcessTest` parametrizado: cada evento desde cada estado (aplica o lanza); cada salida de `WAITING_FOR_PHONE` deja el motivo vacío; la marca se apaga en los cierres, se enciende con `INVALID` y no cambia con un rechazo ni al pasar a `WAITING_FOR_PHONE`; `reason` de 500 y de 501 caracteres; `CONFIRMED` a `CANCELLED` conserva `reservationId` y `confirmedAt`; tests de T04, T05 y T07 en verde sin cambios.
  - Resultado: 2026-11-02. `ReservationProcess` suma `confirmReservation`, `canBeRejected`, `rejectPhone`, `closeAsExpired`, `invalidate`, `canBeCancelled` y `cancel`. Todos los cierres, también `expire` y `expireIfOverdue`, pasan por una sola pieza que borra el motivo y apaga la marca; `invalidate` la vuelve a encender. `ReservationProcessTest` parametrizado en verde; los tests de T04, T05 y T07 pasan sin cambios. Agregado al implementar: el recorte no parte un carácter de dos unidades (por ejemplo, un emoji), porque la base podría rechazarlo y frenar el consumo. Negativos, cada uno hizo fallar su test y se restauró con hash igual: el cierre no apaga la marca; no borra el motivo; `invalidate` no marca; el rechazo no recorta; el recorte parte el carácter; no se cancela un `CONFIRMED`; el rechazo se aplica en otro estado; confirmar pisa con `null`. `./mvnw clean test`: 736 tests, 0 fallos, 0 errores, 0 salteados. Sin commitear.

- [x] **TASK-04 · Caso de uso de T08 en `reservation`**
  - Alcance: `ReservationResult` (tipo sellado, P1); `ProcessReservationResultUseCase` y su implementación con el recorrido del PLAN (candado del dueño, duplicado, nueva lectura, `holdId`, se aplica o solo registra el `eventId`, transacción), los warning de D3 y D4, y el error de D6; método en `ReservationService`. Sumado el 2026-11-02 con aviso al usuario (RF-17): el modelo quita el carácter nulo de `reason`.
  - Depende de: TASK-03
  - Resuelve: RF-03 a RF-14 en el caso de uso; CA-01 a CA-07, CA-09 a CA-12, CA-15; parte de CA-16 y CA-19
  - Validación: `ProcessReservationResultUseCaseImplTest`: cada evento desde cada estado abierto; datos del turno distintos, iguales y ausentes; rechazo sobre `HELD` y `WAITING_FOR_PHONE`; cada evento sobre cada estado final; error de log solo con `AppointmentConfirmed` sobre `EXPIRED` e `INVALID`; duplicado, también con un cambio entre las dos entregas; huérfano e inconsistencia sin registrar el `eventId`; concurrencia con hilos reales y `CopyingReservationProcessRepository` (operación del mismo usuario en curso y evento de otro usuario); salida capturada sin tokens.
  - Resultado: 2026-11-02. `ReservationResult`, `ProcessReservationResultUseCase` y su implementación; método en `ReservationService`; el modelo quita el carácter nulo de `reason`. `ProcessReservationResultUseCaseImplTest` en verde: cada evento desde cada estado abierto, cada evento sobre cada estado final, rechazo sin teléfono enviado, duplicado, huérfano, otro hold, warning de D3 y D4, error de D6 y concurrencia con hilos reales. Negativos, cada uno hizo fallar su test y se restauró con hash igual: sin candado; sin registrar el evento ignorado; rechazo en cualquier abierto; confirmación que reabre un final; sin chequeo de hold; sin chequeo de duplicado; sin el error de D6; sin los warning de D3 y D4; sin quitar el carácter nulo. `./mvnw clean test`: 794 tests, 0 fallos, 0 errores, 0 salteados. **Defecto encontrado, sin corregir, a la espera del usuario:** con Postgres realmente caído, el primer `findById` falla con una excepción que no es `ReservationNotSavedException`, así que el consumidor la trata como error inesperado: reintenta pocas veces y descarta el mensaje. Reproducido con un test temporal, ya borrado, sobre el pedido de teléfono de T07 y Postgres pausado: `discarded after 2 attempts because of an unexpected error: PSQLException`, offset confirmado, proceso en `HELD` sin `requestEventId`. Contradice RF-12 y RF-09 y CA-07 de T07; el caso de uso de T08 lo hereda, porque hace las mismas lecturas. Sin commitear.

- [x] **TASK-05 · Atomicidad contra la base real**
  - Alcance: test del caso de uso contra Postgres de Testcontainers, sin la transacción por defecto de `@DataJpaTest`, como `SpringTransactionRunnerAdapterTest`. Solo tests, salvo que aparezca un defecto.
  - Depende de: TASK-04
  - Resuelve: CA-13
  - Validación: con una falla al registrar el evento, el proceso no cambia en la base; con una falla al guardar el proceso, el evento no queda registrado.
  - Resultado: 2026-11-02. `ProcessReservationResultAtomicityTest` contra Postgres real, sin la transacción por defecto de `@DataJpaTest`: el cambio y el evento se guardan juntos; si la clave primaria rechaza el evento, el proceso sigue en `PHONE_SUBMITTED` y sin `reservationId`; si guardar el proceso falla después de escribir, el evento no queda registrado. Negativo: con el proceso y el evento en transacciones separadas, fallan los dos últimos tests; restaurado con hash igual. La falla simulada es de escritura: la de lectura es el defecto anotado en TASK-04. Sin commitear.

- [ ] **TASK-06 · Modelo de `catedra`, puerto y gateway**
  - Alcance: `AppointmentResult` (tipo sellado, P1); `CatedraEventHandler` suma el método de los resultados; `CatedraEventAdapter` lo implementa: convierte a `ReservationResult`, llama a `ReservationService` y traduce el resultado (`ReservationNotSavedException` → `NotSaved`). Ajusta el test de T07 que usa el puerto si dependía de que tuviera un solo método.
  - Depende de: TASK-04
  - Resuelve: RF-01, RF-12 (traducción)
  - Validación: `CatedraEventAdapterTest`: cada variante llega con todos sus campos; aplicado → `Processed`; los demás resultados → `Discarded`; base caída → `NotSaved`; los casos de T07 siguen pasando.
  - Resultado: pendiente

- [ ] **TASK-07 · Recibir un resultado en `catedra`**
  - Alcance: `ReceiveAppointmentResultUseCase` y su implementación; método en `CatedraEventService`.
  - Depende de: TASK-06
  - Resuelve: RF-01, RF-12
  - Validación: `ReceiveAppointmentResultUseCaseImplTest`: `Processed` y `Discarded` terminan sin error; `NotSaved` lanza `CatedraEventNotSavedException`; `./mvnw test`.
  - Resultado: pendiente

- [ ] **TASK-08 · Lector, mensaje y oyente**
  - Alcance: `AppointmentActionMessage` con los campos nuevos y los valores convertibles como texto (P3); `AppointmentActionReader`: tabla de D8, conversión según el tipo, límite de 255 caracteres también en el pedido de teléfono; `AppointmentAction` y `AppointmentActionListener` suman el resultado. Comprueba si Jackson 3 acepta un número en un campo de texto; si no, usa `JsonNode` en esos dos campos y lo anota acá. Deja de descartar los cinco eventos.
  - Depende de: TASK-07
  - Resuelve: RF-01, RF-02, RF-17; CA-08; parte de CA-20; D3 de T07 reemplazada (D9)
  - Validación: `AppointmentActionReaderTest`: los seis mensajes de `src/test/resources/catedra/kafka/`; cada campo obligatorio ausente, por tipo; `schemaVersion` distinta; `eventType` desconocido; no JSON; valor mal escrito en un campo que el evento usa (descarta y conserva el `eventId` en el motivo) y en uno que no usa (se procesa); opcionales ausentes; campos desconocidos; identificadores de 255 y de 256 caracteres y con el carácter nulo; los casos de T07 siguen pasando, salvo los de "not processed yet", que cambian. `AppointmentActionListenerTest`: el resultado llega al service.
  - Resultado: pendiente

- [ ] **TASK-09 · Integración con Kafka de Testcontainers**
  - Alcance: `ReservationResultKafkaIntegrationTest`, con la aplicación completa, Postgres y Kafka de Testcontainers y la cátedra REST simulada. El proceso se deja en `PHONE_SUBMITTED` escribiendo directo en la base, porque T06 no existe. Si comparte contexto con `PhoneRequestKafkaIntegrationTest`, se reutiliza lo que ya tiene.
  - Depende de: TASK-08
  - Resuelve: CA-14, CA-16, CA-21; parte de CA-01, CA-03, CA-05 a CA-10, CA-19 y CA-20
  - Validación: un evento de cada tipo cambia la base y avanza el offset; inválido seguido de válido; duplicado; huérfano; base que falla y se recupera con otro evento detrás, y reinicio con el mensaje sin confirmar (como en T07); `reason` de 501 caracteres contra la columna real, con otro evento detrás; identificador de 256 caracteres y `reason` con el carácter nulo, con otro mensaje detrás; recorrido completo: registro, login, bloqueo, confirmación, pedido de teléfono, `PHONE_SUBMITTED`, `AppointmentConfirmed`, proceso `CONFIRMED` con `reservationId` en la base y bloqueo de otro turno aceptado; log sin tokens.
  - Resultado: pendiente

- [ ] **TASK-10 · Regresión de T04, T05 y T07 con los estados nuevos**
  - Alcance: tests de T05 y T07 sobre `CONFIRMED`, `CANCELLED` e `INVALID`; respuesta web de un proceso cerrado. Solo tests, salvo que aparezca un defecto.
  - Depende de: TASK-08
  - Resuelve: CA-22
  - Validación: confirmar un proceso en esos estados responde `409` `INVALID_PROCESS_STATE` con su `currentStatus`; un pedido de teléfono sobre esos estados queda como estado ya avanzado; `ReservationProcessDtoMapperTest`: `expiresAt` en `null` y los campos que T08 guarda siguen sin exponerse; respuestas de T04 y T05 sin cambios.
  - Resultado: pendiente

- [ ] **TASK-11 · Cierre**
  - Alcance: `CLAUDE.md` (slices, transiciones, nombres nuevos, Kafka y tests); decisión 014 (3.2 reemplazada, 3.14, sección 5 e historial); `README` de `src/test/resources/catedra/` si cambia lo que dice de los mensajes. No toca T06 ni T13: sus condiciones quedan en D2 y D5 de la SPEC.
  - Depende de: TASK-09, TASK-10
  - Resuelve: cierre de todos los CA
  - Validación: `./mvnw clean verify` con el conteo y la cobertura de JaCoCo reales; chequeos de la skill (§12), con las excepciones comentadas; repaso de CA-01 a CA-22 contra lo registrado en estas tareas; `git status` sin archivos con credenciales.
  - Resultado: pendiente
