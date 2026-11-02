# TASKS: T06 — Informar teléfono

**SPEC:** `docs/sdd/t06-informar-telefono/SPEC.md` (aprobada el 2026-11-02)
**PLAN:** `docs/sdd/t06-informar-telefono/PLAN.md` (aprobado el 2026-11-02)

Tandas propuestas, cada una deja la suite en verde: 01+02; 03+04; 05+06+07; 08; 09+10; 11; 12.

- [ ] **TASK-01 · Validar y normalizar el teléfono**
  - Alcance: `reservation/domain/model/ReservationPhoneNumber` y su test. Quita solo espacios, guiones, paréntesis y puntos, y exige el formato con coincidencia completa. Su `toString` no muestra el número. No toca el proceso ni la web.
  - Depende de: ninguna
  - Resuelve: CA-06 (tabla de formatos), RF-05, RF-13
  - Validación: `./mvnw test -Dtest=ReservationPhoneNumberTest`, con vacío, solo espacios, 6, 7, 15 y 16 dígitos, letras, `+` fuera del principio, tabulador, salto de línea final y cada carácter permitido. Prueba en negativo: buscar con coincidencia parcial en lugar de completa y ver fallar el caso del salto de línea.
  - Resultado: pendiente

- [ ] **TASK-02 · Teléfono y transiciones en el proceso**
  - Alcance: `ReservationProcess` (`phoneNumber`, `canSubmitPhone`, `submitPhone`, `revertPhoneSubmission`), `ReservationProcessEntity`, `ReservationProcessMapper` y `V6__add_reservation_process_phone_number.sql`, con sus tests. No toca casos de uso.
  - Depende de: TASK-01
  - Resuelve: RF-02, RF-06, RF-09 (transiciones); base de CA-01 y CA-08
  - Validación: `./mvnw test -Dtest='ReservationProcessTest,ReservationProcessMapperTest'` más el test del adaptador contra Postgres: la columna guarda 16 caracteres y sobrevive a una ida y vuelta; `submitPhone` borra el motivo; `revertPhoneSubmission` enciende la marca y no restaura nada; cada transición rechaza los demás estados.
  - Resultado: pendiente

- [ ] **TASK-03 · Configuración de la publicación y seguridad compartida**
  - Alcance: `CatedraKafkaProperties` (sub-record `Publish` y método con la configuración de seguridad, movido desde `KafkaConsumerManager`), `application.yaml` y `.env.example`. El consumo no cambia de comportamiento.
  - Depende de: ninguna
  - Resuelve: RF-15 (configurable); regresión de CA-19
  - Validación: `./mvnw test -Dtest='KafkaConsumerManagerTest,CatedraKafkaPropertiesTest'`: valores por defecto, variable vacía, `SASL` armado igual y la contraseña fuera del log.
  - Resultado: pendiente

- [ ] **TASK-04 · Publicador de Kafka**
  - Alcance: `AdditionalInformationSubmission`, `AdditionalInformationSubmissionResult`, `AdditionalInformationPublisher`, `KafkaAdditionalInformationPublisher`, `dto/AdditionalInformationSubmittedMessage` y `src/test/resources/catedra/kafka/additional-information-submitted.json`, armado desde el anexo §15.5. No toca `reservation`.
  - Depende de: TASK-03
  - Resuelve: RF-07, RF-09, RF-10, RF-15; CA-09, CA-10; CA-16 (unitario)
  - Validación: `./mvnw test -Dtest=KafkaAdditionalInformationPublisherTest`. Con `MockProducer`: mismo registro en cada intento; `max-attempts`; esperas crecientes; `AuthenticationException` con un solo intento; un intento que no entra en el tope no empieza; ningún log con el teléfono. Con Kafka en contenedor: el mensaje leído coincide con el JSON de ejemplo y su clave es `reservationProcessId`.
  - Resultado: pendiente

- [ ] **TASK-05 · Casos de uso de `catedra`**
  - Alcance: `CheckTechnicalIntegrationUseCase` y `SubmitAdditionalInformationUseCase`, con sus `Impl` y tests. El `eventId` y `occurredAt` se generan una vez por envío; el topic y el `groupId` salen de la sesión vigente.
  - Depende de: TASK-04
  - Resuelve: RF-07, RF-16; CA-07 (`catedra`)
  - Validación: `./mvnw test -Dtest='CheckTechnicalIntegrationUseCaseImplTest,SubmitAdditionalInformationUseCaseImplTest'`: sesión habilitada, degradada con renovación que funciona y que falla, error de configuración y sesión conectando, con el motivo en el log; dos envíos dan dos `eventId`.
  - Resultado: pendiente

- [ ] **TASK-06 · Caso de uso T06**
  - Alcance: `PhoneSubmitter`, `CatedraPhoneAdapter`, `SubmitPhoneUseCase` e `Impl`, `ReservationInvalidPhoneNumberException` y `ReservationPhoneRequestNotReadyException`, con sus tests. Sin web.
  - Depende de: TASK-02, TASK-05
  - Resuelve: RF-01 a RF-11, RF-14, RF-16; CA-03, CA-04, CA-05, CA-07; CA-06 (no toca el repositorio); CA-08, CA-11 y CA-12 (con dobles)
  - Validación: `./mvnw test -Dtest='SubmitPhoneUseCaseImplTest,CatedraPhoneAdapterTest'`. Pruebas en negativo: publicar antes de guardar, y no volver atrás tras fallar, y ver fallar los tests.
  - Resultado: pendiente

- [ ] **TASK-07 · Concurrencia con el candado**
  - Alcance: tests de `SubmitPhoneUseCaseImpl` con `CopyingReservationProcessRepository` y un publicador demorado, junto con `ProcessReservationResultUseCaseImpl` y el mismo `UserOperationLock`. Si un test muestra un defecto, se corrige en el caso de uso.
  - Depende de: TASK-06
  - Resuelve: RF-12; CA-14 y CA-15 (con dobles)
  - Validación: `./mvnw test -Dtest=SubmitPhoneUseCaseImplTest`: dos envíos del mismo usuario dan un éxito y un `INVALID_PROCESS_STATE`, con un solo mensaje; otro usuario no espera; un resultado durante la espera se aplica después y nada se pisa. Prueba en negativo: publicar fuera del candado y ver fallar CA-15.
  - Resultado: pendiente

- [ ] **TASK-08 · Endpoint y errores**
  - Alcance: `PhoneSubmissionRequest`, `ReservationService.submitPhone` y `ReservationProcessController`, con sus tests. `toResponse` no cambia.
  - Depende de: TASK-06
  - Resuelve: RF-01, RF-05, RF-08; respuestas de CA-02 a CA-08, CA-11 y CA-12
  - Validación: `./mvnw test -Dtest=ReservationProcessControllerTest`: un test por código del contrato §3.6; `401`; `VALIDATION_ERROR` con `fieldErrors`; un `phoneNumber` que no es texto, registrando lo que hace Jackson; la respuesta no contiene el teléfono ni la marca.
  - Resultado: pendiente

- [ ] **TASK-09 · Recorrido completo**
  - Alcance: `FakeCatedraServer` recibe el topic de teléfono, `KafkaTestTopic` lee lo publicado y `reservation/PhoneSubmissionKafkaIntegrationTest` es nuevo. Se reemplaza el recorrido de `ReservationResultKafkaIntegrationTest` que escribe `PHONE_SUBMITTED`; los demás tests de T08 conservan su estado de partida.
  - Depende de: TASK-08
  - Resuelve: CA-01, CA-02, CA-13, CA-18; CA-14 (real)
  - Validación: `./mvnw test -Dtest='PhoneSubmissionKafkaIntegrationTest,ReservationResultKafkaIntegrationTest'`, con la aplicación completa, Postgres y Kafka de Testcontainers y la cátedra simulada: base y topic en cada paso; rechazo, nuevo envío con otro `eventId` y el mismo `requestEventId`, y confirmación.
  - Resultado: pendiente

- [ ] **TASK-10 · Fallas con infraestructura real**
  - Alcance: `PhoneSubmissionKafkaIntegrationTest` con Kafka pausado y con un repositorio controlable que falla al guardar y al volver atrás.
  - Depende de: TASK-09
  - Resuelve: CA-08, CA-11, CA-12, CA-16, CA-17
  - Validación: `./mvnw test -Dtest=PhoneSubmissionKafkaIntegrationTest`: intentos en el log; `503`; fila en la base; nuevo envío cuando Kafka vuelve; duración medida dentro del tope; `500` con cada código; salida capturada sin el número ni tokens. Prueba en negativo: registrar el teléfono a propósito y ver fallar CA-17.
  - Resultado: pendiente

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
