# TASKS: T06 — Informar teléfono

**SPEC:** `docs/sdd/t06-informar-telefono/SPEC.md` (aprobada el 2026-11-02)
**PLAN:** `docs/sdd/t06-informar-telefono/PLAN.md` (aprobado el 2026-11-02)

Tandas propuestas, cada una deja la suite en verde: 01+02; 03+04; 05+06+07; 08; 09+10; 11; 12.

- [x] **TASK-01 · Validar y normalizar el teléfono**
  - Alcance: `reservation/domain/model/ReservationPhoneNumber` y su test. Quita solo espacios, guiones, paréntesis y puntos, y exige el formato con coincidencia completa. Su `toString` no muestra el número. No toca el proceso ni la web.
  - Depende de: ninguna
  - Resuelve: CA-06 (tabla de formatos), RF-05, RF-13
  - Validación: `./mvnw test -Dtest=ReservationPhoneNumberTest`, con vacío, solo espacios, 6, 7, 15 y 16 dígitos, letras, `+` fuera del principio, tabulador, salto de línea final y cada carácter permitido. Prueba en negativo: buscar con coincidencia parcial en lugar de completa y ver fallar el caso del salto de línea.
  - Resultado: 2026-11-02. `ReservationPhoneNumberTest`: 24 tests en verde (7 válidos normalizados, 16 inválidos incluido `null`, y `toString` sin el número). Pruebas en negativo: con búsqueda parcial en lugar de coincidencia completa falla solo el caso del salto de línea final; quitando cualquier espacio en blanco fallan dos casos; en los dos se restauró el archivo y se comprobó el hash. Se implementó como `record`, una excepción a la skill que el usuario aprobó después de hecha.

- [x] **TASK-02 · Teléfono y transiciones en el proceso**
  - Alcance: `ReservationProcess` (`phoneNumber`, `canSubmitPhone`, `submitPhone`, `revertPhoneSubmission`), `ReservationProcessEntity`, `ReservationProcessMapper` y `V6__add_reservation_process_phone_number.sql`, con sus tests. No toca casos de uso.
  - Depende de: TASK-01
  - Resuelve: RF-02, RF-06, RF-09 (transiciones); base de CA-01 y CA-08
  - Validación: `./mvnw test -Dtest='ReservationProcessTest,ReservationProcessMapperTest'` más el test del adaptador contra Postgres: la columna guarda 16 caracteres y sobrevive a una ida y vuelta; `submitPhone` borra el motivo; `revertPhoneSubmission` enciende la marca y no restaura nada; cada transición rechaza los demás estados.
  - Resultado: 2026-11-02. `ReservationProcessTest` (103), `ReservationProcessMapperTest` (3) y `JpaReservationProcessRepositoryAdapterTest` (22, contra Postgres de Testcontainers, con `V6` aplicada) en verde. Un teléfono de 16 caracteres sobrevive a guardar y leer; un proceso sin teléfono lo lee vacío. Pruebas en negativo, con restauración y hash comprobado: `submitPhone` sin borrar el motivo falla 2 tests; la vuelta atrás sin marcar, 1; el mapper sin el teléfono, el round-trip; la columna de 15 caracteres, `value too long`. Suite completa con `./mvnw clean test`: la primera corrida falló en `ReservationProcessDtoMapperTest`, porque su fixture exige todos los campos y no tenía `phoneNumber`; se sumó, y el test ahora también comprueba que la respuesta no tiene el teléfono. La segunda corrida terminó con exit 0: 981 tests contando `<testcase>`, 0 fallos, 0 errores, 0 salteados.

- [x] **TASK-03 · Configuración de la publicación y seguridad compartida**
  - Alcance: `CatedraKafkaProperties` (sub-record `Publish` y método con la configuración de seguridad, movido desde `KafkaConsumerManager`), `application.yaml` y `.env.example`. El consumo no cambia de comportamiento.
  - Depende de: ninguna
  - Resuelve: RF-15 (configurable); regresión de CA-19
  - Validación: `./mvnw test -Dtest='KafkaConsumerManagerTest,CatedraKafkaPropertiesTest'`: valores por defecto, variable vacía, `SASL` armado igual y la contraseña fuera del log.
  - Resultado: 2026-11-02. `CatedraKafkaPropertiesTest` (9) y `KafkaConsumerManagerTest` (8, contra Kafka en contenedor, sin cambios salvo un parámetro nuevo del constructor) en verde: valores por defecto, variable vacía, `SASL` con `PLAIN` y `SCRAM`, escape de la contraseña, peor caso de 9 s dentro del tope de 10 s, y la contraseña fuera del log.

- [x] **TASK-04 · Publicador de Kafka**
  - Alcance: `AdditionalInformationSubmission`, `AdditionalInformationSubmissionResult`, `AdditionalInformationPublisher`, `KafkaAdditionalInformationPublisher`, `dto/AdditionalInformationSubmittedMessage` y `src/test/resources/catedra/kafka/additional-information-submitted.json`, armado desde el anexo §15.5. No toca `reservation`.
  - Depende de: TASK-03
  - Resuelve: RF-07, RF-09, RF-10, RF-15; CA-09, CA-10; CA-16 (unitario)
  - Validación: `./mvnw test -Dtest=KafkaAdditionalInformationPublisherTest`. Con `MockProducer`: mismo registro en cada intento; `max-attempts`; esperas crecientes; `AuthenticationException` con un solo intento; un intento que no entra en el tope no empieza; ningún log con el teléfono. Con Kafka en contenedor: el mensaje leído coincide con el JSON de ejemplo y su clave es `reservationProcessId`.
  - Resultado: 2026-11-02. `KafkaAdditionalInformationPublisherTest` (17) en verde. Con un productor simulado: mismo mensaje en cada intento, máximo de intentos, esperas crecientes, credenciales y topic sin autorización con un solo intento, intento fuera del tope que no empieza, interrupción y teléfono fuera del log. Con Kafka en contenedor: el mensaje leído coincide con el JSON de ejemplo y lleva la clave del proceso. Con `localhost:1`, `KafkaProducer` real: `NotPublished` dentro del tope de 3 s. Pruebas en negativo, con restauración y hash comprobado: reintentar todo falla 2 tests; sin el control del tope, 1; registrar el mensaje de un error no previsto, 1; otra clave, 3; sin limitar `max.block.ms`, el test del Kafka inalcanzable falla después de bloquear (71 s en total). Suite completa con `./mvnw clean test`: exit 0, 1004 tests contando `<testcase>`, 0 fallos, 0 errores, 0 salteados. **Sin verificar:** el rechazo real de credenciales (CA-10 se prueba con un doble) y publicar en el Kafka real (D8).

- [x] **TASK-05 · Casos de uso de `catedra`**
  - Alcance: `CheckTechnicalIntegrationUseCase` y `SubmitAdditionalInformationUseCase`, con sus `Impl` y tests. El `eventId` y `occurredAt` se generan una vez por envío; el topic y el `groupId` salen de la sesión vigente.
  - Depende de: TASK-04
  - Resuelve: RF-07, RF-16; CA-07 (`catedra`)
  - Validación: `./mvnw test -Dtest='CheckTechnicalIntegrationUseCaseImplTest,SubmitAdditionalInformationUseCaseImplTest'`: sesión habilitada, degradada con renovación que funciona y que falla, error de configuración y sesión conectando, con el motivo en el log; dos envíos dan dos `eventId`.
  - Resultado: 2026-11-02. `CheckTechnicalIntegrationUseCaseImplTest` (5) y `SubmitAdditionalInformationUseCaseImplTest` (5) en verde: sesión habilitada sin login, degradada con renovación que funciona y que falla, conectando y error de configuración, con el motivo en el log; dos envíos dan dos `eventId` con el mismo `requestEventId`; topic y `groupId` de la sesión vigente. Prueba en negativo, con hash comprobado: pedir login con error de configuración falla 1 test.

- [x] **TASK-06 · Caso de uso T06**
  - Alcance: `PhoneSubmitter`, `CatedraPhoneAdapter`, `SubmitPhoneUseCase` e `Impl`, `ReservationInvalidPhoneNumberException` y `ReservationPhoneRequestNotReadyException`, con sus tests. Sin web.
  - Depende de: TASK-02, TASK-05
  - Resuelve: RF-01 a RF-11, RF-14, RF-16; CA-03, CA-04, CA-05, CA-07; CA-06 (no toca el repositorio); CA-08, CA-11 y CA-12 (con dobles)
  - Validación: `./mvnw test -Dtest='SubmitPhoneUseCaseImplTest,CatedraPhoneAdapterTest'`. Pruebas en negativo: publicar antes de guardar, y no volver atrás tras fallar, y ver fallar los tests.
  - Resultado: 2026-11-02. `SubmitPhoneUseCaseImplTest` y `CatedraPhoneAdapterTest` (3) en verde, con dobles. Comprobado: guardar antes de publicar; formato antes de la base y del candado; cada `409`; sin integración no cambia nada; al fallar la publicación vuelve a `WAITING_FOR_PHONE`, marcado y sin restaurar; falla al guardar sin publicar; falla al volver atrás con error grave. Pruebas en negativo, con hash comprobado: publicar antes de guardar falla 5 tests; no volver atrás, 5; sin comprobar la integración, 1; validar dentro del candado, 1. Dos ajustes respecto del PLAN: la vuelta atrás también cubre un error no previsto al publicar, y el mensaje de `ReservationLocalStateOutOfSyncException` pasó a ser genérico, porque decía que la cátedra había confirmado. **Con dobles:** persistencia y Kafka reales se comprueban en TASK-09 y TASK-10.

- [x] **TASK-07 · Concurrencia con el candado**
  - Alcance: tests de `SubmitPhoneUseCaseImpl` con `CopyingReservationProcessRepository` y un publicador demorado, junto con `ProcessReservationResultUseCaseImpl` y el mismo `UserOperationLock`. Si un test muestra un defecto, se corrige en el caso de uso.
  - Depende de: TASK-06
  - Resuelve: RF-12; CA-14 y CA-15 (con dobles)
  - Validación: `./mvnw test -Dtest=SubmitPhoneUseCaseImplTest`: dos envíos del mismo usuario dan un éxito y un `INVALID_PROCESS_STATE`, con un solo mensaje; otro usuario no espera; un resultado durante la espera se aplica después y nada se pisa. Prueba en negativo: publicar fuera del candado y ver fallar CA-15.
  - Resultado: 2026-11-02. En `SubmitPhoneUseCaseImplTest` (27 en total, incluidos los de TASK-06), con `CopyingReservationProcessRepository`: dos envíos del mismo usuario dan un éxito y un `INVALID_PROCESS_STATE`, con una sola publicación; otro usuario no espera; un rechazo durante la publicación se aplica después, sin pisar nada; una confirmación durante una publicación que falla queda `CONFIRMED`. Prueba en negativo, con hash comprobado: sin el candado fallan 3 tests. Suite completa con `./mvnw clean test`: exit 0, 1044 tests contando `<testcase>`, 0 fallos, 0 errores, 0 salteados. **Con dobles:** el caso real de CA-14 se comprueba en TASK-09.

- [x] **TASK-08 · Endpoint y errores**
  - Alcance: `PhoneSubmissionRequest`, `ReservationService.submitPhone` y `ReservationProcessController`, con sus tests. `toResponse` no cambia.
  - Depende de: TASK-06
  - Resuelve: RF-01, RF-05, RF-08; respuestas de CA-02 a CA-08, CA-11 y CA-12
  - Validación: `./mvnw test -Dtest=ReservationProcessControllerTest`: un test por código del contrato §3.6; `401`; `VALIDATION_ERROR` con `fieldErrors`; un `phoneNumber` que no es texto, registrando lo que hace Jackson; la respuesta no contiene el teléfono ni la marca.
  - Resultado: 2026-11-02. `ReservationProcessControllerTest` (59, antes 36) en verde, con el service simulado: un test por código del contrato §3.6, `currentStatus` en cada estado, `401` sin token y con token vencido, dueño tomado del token aunque el body traiga otro. Falta `phoneNumber` o es `null`: `VALIDATION_ERROR` con `fieldErrors`, sin llamar al service. Vacío, solo espacios o mal formado: llega al caso de uso, que responde `INVALID_PHONE_NUMBER`. **Lo que hace Jackson con un valor que no es texto (observado):** un número JSON se convierte en texto y sigue la validación de formato; un array o un objeto da `VALIDATION_ERROR`; un body que es solo un texto, vacío o mal escrito también. En esos casos, ni la respuesta ni el log repiten el número. La respuesta nunca incluye el teléfono, el `holdId`, el `requestEventId` ni la marca. Pruebas en negativo, con hash comprobado: sin traducir `PHONE_REQUEST_NOT_READY` falla 1 test; sin `@NotNull` fallan 2; sin `@Valid`, 2; sumar el teléfono a la respuesta, 5. Suite completa con `./mvnw clean test`: exit 0, 1067 tests contando `<testcase>`, 0 fallos, 0 errores, 0 salteados. **Sin comprobar:** el recorrido con la aplicación levantada (TASK-09 y TASK-10).

- [x] **TASK-09 · Recorrido completo**
  - Alcance: `FakeCatedraServer` recibe el topic de teléfono, `KafkaTestTopic` lee lo publicado y `reservation/PhoneSubmissionKafkaIntegrationTest` es nuevo. Se reemplaza el recorrido de `ReservationResultKafkaIntegrationTest` que escribe `PHONE_SUBMITTED`; los demás tests de T08 conservan su estado de partida.
  - Depende de: TASK-08
  - Resuelve: CA-01, CA-02, CA-13, CA-18; CA-14 (real)
  - Validación: `./mvnw test -Dtest='PhoneSubmissionKafkaIntegrationTest,ReservationResultKafkaIntegrationTest'`, con la aplicación completa, Postgres y Kafka de Testcontainers y la cátedra simulada: base y topic en cada paso; rechazo, nuevo envío con otro `eventId` y el mismo `requestEventId`, y confirmación.
  - Resultado: 2026-11-02. `PhoneSubmissionKafkaIntegrationTest` (11 en total, con los de TASK-10), con la aplicación completa, Postgres y Kafka de Testcontainers y la cátedra simulada, en verde. Comprobado leyendo la base y el topic de teléfono: el mensaje es igual al ejemplo del anexo salvo los valores propios, con clave `reservationProcessId`; la respuesta no tiene el teléfono, el `holdId` ni el `requestEventId`; rechazo real por Kafka, nuevo envío con otro `eventId` y el mismo `requestEventId`, y confirmación real hasta `CONFIRMED`; `404` iguales para ajeno e inexistente; `401`; `INVALID_PHONE_NUMBER` con un proceso inexistente; `PHONE_REQUEST_NOT_READY`; `HOLD_EXPIRED` con `EXPIRED` en la base; dos envíos simultáneos dan `200` y `409` y un solo mensaje. `ReservationResultKafkaIntegrationTest` (14): su recorrido ahora informa el teléfono por el endpoint, en lugar de escribir `PHONE_SUBMITTED` en la base. **CA-13 no provoca una caída:** parte del estado que dejaría, sin nada publicado. Los mensajes se filtran por su instante, porque un intento vencido puede llegar cuando Kafka vuelve.

- [x] **TASK-10 · Fallas con infraestructura real**
  - Alcance: `PhoneSubmissionKafkaIntegrationTest` con Kafka pausado y con un repositorio controlable que falla al guardar y al volver atrás.
  - Depende de: TASK-09
  - Resuelve: CA-08, CA-11, CA-12, CA-16, CA-17
  - Validación: `./mvnw test -Dtest=PhoneSubmissionKafkaIntegrationTest`: intentos en el log; `503`; fila en la base; nuevo envío cuando Kafka vuelve; duración medida dentro del tope; `500` con cada código; salida capturada sin el número ni tokens. Prueba en negativo: registrar el teléfono a propósito y ver fallar CA-17.
  - Resultado: 2026-11-02. En `PhoneSubmissionKafkaIntegrationTest`, con los tiempos de la configuración real. Con Kafka congelado (`pause`): `503` `CENTRAL_UNAVAILABLE` dentro de los 10 s (el test tarda unos 11 s en total), los tres intentos en el log, y en la base `WAITING_FOR_PHONE`, marcado, sin motivo y con el teléfono del intento fallido. Con Kafka de vuelta, un nuevo envío responde `200`, con otro `eventId` y el mismo `requestEventId`. Con un repositorio que falla sobre la base real: al guardar, `INTERNAL_ERROR`, sin publicar y sin cambios; con Kafka congelado y falla al volver atrás, `LOCAL_STATE_OUT_OF_SYNC`, error grave en el log y `PHONE_SUBMITTED` sin marca. La salida capturada no tiene los teléfonos ni los tokens. Pruebas en negativo, con hash comprobado: registrar el teléfono en el log falla 5 tests; no guardar la vuelta atrás, 2. La clase se repitió 2 veces más sin fallos, para descartar inestabilidad. Suite completa con `./mvnw clean test`: exit 0, 1078 tests contando `<testcase>`, 0 fallos, 0 errores, 0 salteados; el log de la corrida no tiene los teléfonos de prueba. **CA-15** queda probado con dobles (TASK-07), como preveía el PLAN.

- [ ] **TASK-11 · E2E**
  - Alcance: `e2e/ReservationProcessE2ETest` suma informar el teléfono después del pedido. Se compila y se confirma que sigue excluido de la suite normal. **Claude no lo corre:** lo corre el usuario, con la lista de requests confirmada antes.
  - Depende de: TASK-09
  - Resuelve: CA-20
  - Validación: `./mvnw test-compile`, y `./mvnw test -Dtest=ReservationProcessE2ETest` sin el perfil no lo ejecuta. **Bloqueada para la prueba real:** la cátedra no publica el pedido (decisión 014 §4), así que el recorrido no llega a publicar. Queda sin marcar hasta que se pueda correr, o como no verificada.
  - Resultado: pendiente

- [ ] **TASK-12 · Cierre**
  - Alcance: `CLAUDE.md` (slice `reservation` y `catedra`, transiciones, "Cómo usar Kafka", tests, condiciones ya cumplidas de T06), decisión 014 (lo que se compruebe), `src/test/resources/catedra/README.md` y tabla de cobertura en este archivo. Verificación final de la skill §12.
  - Depende de: TASK-01 a TASK-10
  - Resuelve: CA-19; cobertura de RF y CA
  - Validación: `./mvnw clean verify`, contando `<testcase>` en los XML, con JaCoCo; comandos de la skill §12, revisando cada coincidencia frente a las excepciones autorizadas; `documentacion` y sus copias en `turnos_service` idénticas.
  - Resultado: pendiente
