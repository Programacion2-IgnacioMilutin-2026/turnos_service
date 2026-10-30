# TASKS: T07 — Procesar solicitud de información adicional

**SPEC:** `docs/sdd/t07-solicitud-informacion/SPEC.md` (aprobada el 2026-10-29)
**PLAN:** `docs/sdd/t07-solicitud-informacion/PLAN.md` (aprobado el 2026-10-30)

Las tareas siguen el "Orden de implementación" del PLAN. Rutas relativas a `src/main/java/turnos/` y `src/test/java/turnos/`, salvo indicación. Los nombres de los tests describen el comportamiento, sin citar documentos. Los tests se cuentan con los reportes XML de Surefire. Después de que un test nuevo pasa, una prueba en negativo: romper el código a propósito, ver que falla, restaurar y comprobar el hash.

Tandas propuestas, para que la suite quede en verde al cerrar cada una: TASK-01; TASK-02; TASK-03 y TASK-04; TASK-05 y TASK-06; TASK-07 y TASK-08; TASK-09 y TASK-10; TASK-11; TASK-12; TASK-13. TASK-02 y TASK-12 hablan con la cátedra y su Kafka reales: antes se lista y se confirma cada paso, y las corre el usuario en su terminal (el modo automático bloquea esas llamadas). Ninguna usa el consumer group del servicio.

- [ ] **TASK-01 · Agregar la dependencia de Kafka sin cambiar el arranque**
  - Alcance: `spring-boot-starter-kafka` en el `pom.xml` (versión del BOM de Boot 4.1.1). No toca código ni configuración de la aplicación.
  - Depende de: ninguna
  - Resuelve: base de RF-01 (P2)
  - Validación: `./mvnw clean test` en verde con el mismo conteo que antes (588); la salida de un test que levanta la aplicación completa no muestra intentos de conexión a un Kafka por defecto (`localhost:9092`) ni beans de Kafka que se conecten al arrancar; `./mvnw dependency:tree` muestra spring-kafka 4.1.1 y kafka-clients 4.2.1
  - Resultado: pendiente

- [ ] **TASK-02 · Prueba de conexión y grabaciones del Kafka real**
  - Alcance: programa Java de un solo archivo en el scratchpad (P8.2), con el classpath del proyecto, que el usuario corre en su terminal después de confirmar cada paso: lee las credenciales del `.env` sin mostrarlas; login técnico; lista de topics sin credenciales y con las de la cuenta técnica (protocolos y mecanismos de D1); consulta, sin leer mensajes, si se permite un consumer group con otro nombre (para `TURNOS_E2E_KAFKA_CONSUMER_GROUP`); particiones y posiciones del topic de acciones; lectura de ese topic **sin consumer group** y sin confirmar nada; posición del consumer group del servicio (solo lectura), para compararla en TASK-12. Si no hay ningún `AdditionalInformationRequested`, se agrega al programa un bloqueo y una confirmación (ocupa un turno 10 minutos), previa confirmación. Los mensajes se limpian (`groupId` en los nombres, datos sensibles) y se versionan en `src/test/resources/catedra/kafka/`, anotados en `src/test/resources/catedra/README.md`. Después se borran el programa y sus salidas. No toca código de la aplicación.
  - Depende de: TASK-01
  - Resuelve: D1 (verificación), base de CA-01, CA-03, CA-13 y CA-14
  - Validación: resultados leídos y registrados acá (qué configuración conectó, si se permite otro consumer group, particiones, retención si se puede ver, formato de los mensajes frente al anexo §15); `grep` sin coincidencias con hosts, credenciales, `groupId` real ni textos con forma de JWT (comprobado sin imprimir valores). Si algo contradice el PLAN (por ejemplo, otra forma de autenticación), se detiene y se vuelve al PLAN. Los valores por defecto de seguridad de P7 se ajustan en TASK-09 con este resultado
  - Resultado: pendiente

- [ ] **TASK-03 · `catedra`: recibir un pedido de información adicional**
  - Alcance: `catedra/domain/model/AdditionalInformationRequest`, `AdditionalInformationRequestResult` (sellado: `Processed`, `Discarded`, `NotSaved`); `domain/ports/in/ReceiveAdditionalInformationRequestUseCase`; `domain/ports/out/AdditionalInformationRequestHandler`; `application/usecases/ReceiveAdditionalInformationRequestUseCaseImpl` (con `NotSaved` lanza `CatedraEventNotSavedException`); `application/service/AdditionalInformationRequestService`; `application/exception/CatedraException` y `CatedraEventNotSavedException`. Nada de Kafka. No toca `reservation`.
  - Depende de: ninguna
  - Resuelve: base de RF-08 y RF-09 (P1, P10)
  - Validación: `./mvnw test -Dtest='ReceiveAdditionalInformationRequestUseCaseImplTest'`: el pedido llega igual al puerto; `Processed` y `Discarded` terminan sin error; `NotSaved` lanza la excepción. Chequeo de la skill: el dominio y la aplicación de `catedra` no importan nada de Kafka ni de Spring Kafka
  - Resultado: pendiente

- [ ] **TASK-04 · `catedra`: aviso de integración habilitada**
  - Alcance: `catedra/domain/ports/out/TechnicalIntegrationListener`; `StartTechnicalSessionUseCaseImpl` y `RenewTechnicalSessionUseCaseImpl` avisan después de guardar una sesión habilitada, y solo entonces. Una implementación vacía provisoria no hace falta si el test usa un doble; si la aplicación necesita un bean para arrancar, se agrega una que no hace nada hasta TASK-10 (se avisa al usuario).
  - Depende de: ninguna
  - Resuelve: base de CA-16 y CA-17 (P3)
  - Validación: `./mvnw test -Dtest='StartTechnicalSessionUseCaseImplTest,RenewTechnicalSessionUseCaseImplTest'`: un login exitoso avisa una vez con la integración; degradado, error de configuración o `provisioningStatus` distinto no avisan; los tests existentes de X01 pasan sin cambios de comportamiento. Suite completa en verde
  - Resultado: pendiente

- [ ] **TASK-05 · `reservation`: modelo del pedido de teléfono y eventos procesados**
  - Alcance: `ReservationProcess` suma `requestEventId` y `acceptPhoneRequest(requestEventId, expiresAt)` (solo desde `HELD` o `WAITING_FOR_PHONE`; `HELD` pasa a `WAITING_FOR_PHONE`; no toca la marca de reconciliación); `domain/model/ReservationPhoneRequest`, `PhoneRequestOutcome` y `ProcessedEvent`.
  - Depende de: ninguna
  - Resuelve: base de CA-01, CA-02 y CA-06 (RF-07, RF-08)
  - Validación: `./mvnw test -Dtest='ReservationProcessTest'` (ampliado): desde `HELD` y desde `WAITING_FOR_PHONE` guarda los dos datos y queda en `WAITING_FOR_PHONE`; con la marca encendida, sigue encendida; desde `PHONE_SUBMITTED` y `EXPIRED` no se permite y el proceso no cambia
  - Resultado: pendiente

- [ ] **TASK-06 · `reservation`: persistencia del pedido, de los eventos procesados y la transacción**
  - Alcance: migración `V4__add_request_event_id_and_processed_events.sql`; `ReservationProcessEntity` y `ReservationProcessMapper` con `request_event_id`; `ReservationProcessRepository.findById` y su implementación; `domain/ports/out/ProcessedEventRepository` y su persistencia JPA (`ProcessedEventEntity` con `Persistable`, `JpaProcessedEventRepository`, `ProcessedEventMapper`, `JpaProcessedEventRepositoryAdapter`); `domain/ports/out/TransactionRunner` y `persistence/adapter/SpringTransactionRunnerAdapter` (traduce las fallas de la base, también al confirmar, a `ReservationNotSavedException`).
  - Depende de: TASK-05
  - Resuelve: CA-08, CA-10 (RF-08, RF-10)
  - Validación: `./mvnw test -Dtest='ReservationProcessMapperTest,ProcessedEventMapperTest,JpaReservationProcessRepositoryAdapterTest,JpaProcessedEventRepositoryAdapterTest,SpringTransactionRunnerAdapterTest,ReservationProcessDtoMapperTest'` contra Postgres real: ida y vuelta de los mappers con todos los campos; `findById` sin dueño; la migración deja sin `requestEventId` los procesos existentes; un segundo registro del mismo evento falla; dentro de una transacción, si falla registrar el evento no queda el cambio del proceso, y al revés; la respuesta del proceso sigue sin `requestEventId`
  - Resultado: pendiente

- [ ] **TASK-07 · `reservation`: caso de uso de T07**
  - Alcance: `domain/ports/in/ProcessPhoneRequestUseCase` y `application/usecases/ProcessPhoneRequestUseCaseImpl` con el recorrido del PLAN ("Transacciones, concurrencia y errores"): huérfano sin candado; dentro del candado del dueño, evento ya procesado, `holdId`, estado y la transacción; logs de cada descarte. `ReservationService` suma el método de T07.
  - Depende de: TASK-06
  - Resuelve: CA-01, CA-02, CA-05, CA-06, CA-09, CA-11 (RF-04 a RF-08, RF-11, RF-12)
  - Validación: `./mvnw test -Dtest='ProcessPhoneRequestUseCaseImplTest,ReservationServiceTest'`: cada resultado de `PhoneRequestOutcome` con el proceso y el registro esperados; duplicado sin efecto aunque el proceso haya cambiado; falla de la base → `ReservationNotSavedException` sin cambios; con hilos reales y el repositorio que devuelve copias, un pedido durante una confirmación demorada (T05) termina en `WAITING_FOR_PHONE` con su `requestEventId`, y otro usuario no espera; salida capturada con `eventId` y motivo, sin tokens
  - Resultado: pendiente

- [ ] **TASK-08 · `reservation`: adaptador del puerto de `catedra`**
  - Alcance: `reservation/infrastructure/gateway/CatedraPhoneRequestAdapter` implementa `AdditionalInformationRequestHandler` de `catedra`, convierte el modelo, llama a `ReservationService` y traduce: `APPLIED` → `Processed`; los descartes → `Discarded`; `ReservationNotSavedException` → `NotSaved`. Comentario de la excepción a la skill (P1).
  - Depende de: TASK-03, TASK-07
  - Resuelve: base de CA-07 (RF-09)
  - Validación: `./mvnw test -Dtest='CatedraPhoneRequestAdapterTest'`: conversión de todos los campos; cada traducción; otra excepción se propaga sin traducir. Chequeo de la skill: el único cruce nuevo entre slices está en `gateway` y tiene su comentario
  - Resultado: pendiente

- [ ] **TASK-09 · `catedra`: formato del topic de acciones y configuración**
  - Alcance: `catedra/infrastructure/kafka/dto/AppointmentActionMessage` y `AppointmentActionReader` (eventType, seis campos obligatorios, `schemaVersion` 1, `requiredField` `PHONE_NUMBER`, instantes; eventos de resultado y desconocidos se descartan); `CatedraKafkaProperties` (P7 y `enabled`, `toString` sin contraseña) con sus valores en `application.yaml` (valores de seguridad por defecto según TASK-02) y `.env.example`; los tests que no son de Kafka apagan el consumo.
  - Depende de: TASK-02, TASK-03
  - Resuelve: CA-03, CA-04 (RF-02, RF-03; D3, D4)
  - Validación: `./mvnw test -Dtest='AppointmentActionReaderTest,CatedraKafkaPropertiesTest'` con los mensajes grabados: el pedido real se convierte con todos sus campos; cada defecto se descarta con su motivo; uno con campos extra o sin `message` ni `occurredAt` se acepta; los cinco de resultado y uno desconocido se descartan; propiedades por defecto y vacías. Suite completa en verde
  - Resultado: pendiente

- [ ] **TASK-10 · `catedra`: consumo de Kafka**
  - Alcance: `testcontainers-kafka` en el `pom.xml` (scope test) y la configuración compartida del contenedor de Kafka para los tests (imagen con versión exacta); `catedra/infrastructure/kafka/AppointmentActionListener` (usa el lector y el service; registra los descartes) y `KafkaConsumerManager` (implementa `TechnicalIntegrationListener`; si está habilitado, arma el contenedor de consumo con servidores, seguridad, consumer group, `earliest`, confirmación por mensaje y manejo de errores: `CatedraEventNotSavedException` sin límite con pausa, otro error con reintentos limitados y error grave; arranca aparte del hilo del login; conserva los primeros datos y avisa si cambian; se detiene al apagar). Comentario de la excepción a la skill del paquete `kafka`.
  - Depende de: TASK-04, TASK-08, TASK-09
  - Resuelve: CA-11, CA-17 (RF-01, RF-09, RF-12, RF-13, RF-14; D5, D6, D7, P6)
  - Validación: `./mvnw test -Dtest='KafkaConsumerManagerTest'` con el Kafka de Testcontainers: con `enabled` en falso no consume; el primer aviso arranca el consumo con el consumer group de `integration` o el configurado; un segundo aviso con otros datos deja un warning y no cambia nada; la salida no tiene la contraseña ni el JWT técnico. Chequeo: nada de Kafka fuera de `catedra/infrastructure/kafka`. Suite completa en verde
  - Resultado: pendiente

- [ ] **TASK-11 · Integración con la aplicación completa y Kafka de Testcontainers**
  - Alcance: `PhoneRequestKafkaIntegrationTest` (o los que hagan falta): aplicación completa con Postgres y Kafka de Testcontainers y la cátedra REST simulada, que devuelve en el login los servidores del contenedor; se publican los mensajes grabados. Una variante de `TransactionRunner` que falla a pedido para CA-07.
  - Depende de: TASK-10
  - Resuelve: CA-01 a CA-07, CA-12, CA-13, CA-15, CA-16 (RF-01 a RF-09, RF-13, RF-14)
  - Validación: `./mvnw test -Dtest='PhoneRequestKafkaIntegrationTest'`: registro, login, bloqueo, confirmación y pedido dejan el `requestEventId` en la base; inválido seguido de válido, duplicado, huérfano, `holdId` distinto y estados avanzados según los CA; la base falla y después vuelve, con otro pedido detrás procesado en orden y un solo efecto tras reiniciar el consumo; Kafka pausado no afecta la API REST y los pedidos se procesan al volver; consumer group nuevo lee desde el más antiguo; login del arranque fallido y después uno exitoso; con error de configuración nunca consume. Suite completa en verde
  - Resultado: pendiente

- [ ] **TASK-12 · E2E contra la cátedra y su Kafka reales**
  - Alcance: configuración común de la suite E2E (`src/test/java/e2e/`): con `TURNOS_E2E_KAFKA_CONSUMER_GROUP` definida, consumo con ese consumer group; vacía, consumo apagado (plan B). Así también `FreeSlotsE2ETest` y el resto quedan protegidos. `ReservationProcessE2ETest` ampliado: después de confirmar, espera hasta unos 30 s el `requestEventId` en la base del test; en el plan B, lee el pedido sin consumer group desde la posición anotada antes de confirmar y lo entrega al lector y al service de `catedra`. Lo corre el usuario en su terminal, después de confirmar qué hace.
  - Depende de: TASK-02, TASK-11
  - Resuelve: CA-14
  - Validación: `./mvnw test -Pe2e` corrido por el usuario: el proceso confirmado queda con el `requestEventId` real; la posición del consumer group del servicio es la misma que antes (consulta de solo lectura, como en TASK-02); sin credenciales, los tests se saltean. Reportes de Surefire leídos sin mostrar credenciales
  - Resultado: pendiente

- [ ] **TASK-13 · Cierre**
  - Alcance: `CLAUDE.md` (slice `catedra` con Kafka, excepciones nuevas a la skill, cómo usar Kafka y los tests, decisiones hasta la 014); decisión 014 (lo verificado en TASK-02 y TASK-12, "Por verificar", historial) y 010; `.env.example` completo; revisión de la cobertura de los CA.
  - Depende de: TASK-01 a TASK-12
  - Resuelve: todos los CA (revisión)
  - Validación: `./mvnw clean verify` en verde con el conteo de los reportes XML; los cinco chequeos de la skill sin coincidencias fuera de las excepciones autorizadas y comentadas; `grep` de imports de Kafka solo en `catedra/infrastructure/kafka` (y tests); cada CA con su evidencia registrada en este archivo
  - Resultado: pendiente
