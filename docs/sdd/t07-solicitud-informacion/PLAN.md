# PLAN: T07 — Procesar solicitud de información adicional

**SPEC de referencia:** `docs/sdd/t07-solicitud-informacion/SPEC.md`
**Versión de la spec revisada:** aprobada el 2026-10-29 (D1 a D8, RF-01 a RF-14, CA-01 a CA-17)
**Estado:** Aprobado <!-- Borrador | En revisión | Aprobado -->

<!-- PARA LA PERSONA
Copia esta plantilla como PLAN.md junto a la SPEC.md aprobada.
Este documento define la solución técnica. Una vez revisado, el agente puede
derivar TASKS.md con tareas, dependencias y comprobaciones.
-->

<!-- PARA EL AGENTE
- Lee la SPEC.md aprobada, las instrucciones del proyecto, BACKEND_GUIDELINES.md,
  los documentos citados en Referencias de la SPEC y la skill
  hexagonal-vertical-slicing. Si falta un documento necesario o la spec no está
  aprobada, indícalo antes de avanzar.
- Inspecciona el repositorio. Referencia rutas verificadas y distingue las nuevas propuestas.
- Propón una solución proporcional al alcance y coherente con el proyecto.
  Reutiliza lo existente y justifica nuevas dependencias o cambios de arquitectura.
- Aplica la skill hexagonal-vertical-slicing tal como está: no la reinterpretes.
  Si la funcionalidad necesita algo que la skill no contempla, regístralo como
  decisión pendiente en lugar de improvisar una estructura.
- Distingue hechos, decisiones confirmadas y propuestas. Consulta las decisiones
  no resueltas; haz pocas preguntas por vez y actualiza el plan con las respuestas.
- Referencia los requisitos y criterios por su ID, y los contratos y casos de uso
  por archivo e ID, sin copiar su contenido.
- Si una decisión cambia el comportamiento o alcance, vuelve a la spec y solicita
  confirmación. No resuelvas una duda de producto mediante una suposición técnica.
- Conserva estos comentarios. No implementes durante la planificación.
- Solicita aprobación antes de marcar el plan como Aprobado. La autorización
  para implementar debe ser explícita; no se deduce del estado de los documentos.
-->

## Contexto técnico verificado

<!-- Qué existe hoy y cómo participa en la funcionalidad: código, slices,
migraciones y configuración ya implementados. Si todavía no hay código para
esto, indícalo. -->

No hay código de Kafka. Rutas relativas a `src/main/java/turnos/`, salvo indicación.

| Componente o archivo existente | Ruta verificada | Responsabilidad y uso previsto |
| --- | --- | --- |
| Datos de la integración | `catedra/domain/model/TechnicalIntegration.java` | `record` con `groupId`, `kafkaBootstrapServers`, `kafkaConsumerGroupId`, `kafkaAppointmentActionsTopic`, `kafkaAppointmentPhoneTopic` y `provisioningStatus`. Ya se leen del login; nadie los usa (RF-01) |
| Sesión técnica | `catedra/domain/model/TechnicalSession.java`, `IntegrationState.java`; `catedra/domain/ports/out/TechnicalSessionRepository.java`; `catedra/infrastructure/persistence/adapter/InMemoryTechnicalSessionRepositoryAdapter.java` | Estados `CONNECTING`, `ENABLED`, `DEGRADED`, `CONFIGURATION_ERROR`; cada login guarda una sesión nueva entera. Hoy nadie se entera cuando la sesión pasa a `ENABLED`: D7 lo necesita (P3) |
| Logins | `catedra/application/usecases/StartTechnicalSessionUseCaseImpl.java`, `RenewTechnicalSessionUseCaseImpl.java`; `catedra/infrastructure/startup/TechnicalSessionStartup.java` | Login del arranque en un hilo virtual, con reintentos limitados; renovación a pedido con intervalo mínimo (decisión 005) |
| Credenciales de la cuenta técnica | `catedra/infrastructure/client/CatedraProperties.java` (`turnos.catedra.username`, `password`) | Las mismas que usaría Kafka si exige credenciales (D1). Su `toString` oculta la contraseña |
| Patrón de cruce entre slices | `reservation/infrastructure/gateway/CatedraHoldAdapter.java` | `reservation` usa a `catedra` por sus puertos de entrada y traduce resultados sellados (decisión 009) |
| Proceso de reserva | `reservation/domain/model/ReservationProcess.java`, `ReservationStatus.java` | Transiciones en el modelo (`confirm`, `expire`, `expireIfOverdue`, `markForReconciliation`). Falta `requestEventId` y la transición del pedido (RF-08) |
| Persistencia del proceso | `reservation/domain/ports/out/ReservationProcessRepository.java`; `reservation/infrastructure/persistence/` ; `src/main/resources/db/migration/V2__…`, `V3__…` | `update` reescribe la fila entera con `saveAndFlush`, cada escritura en su propia transacción, sin control de versión (motivo de D2). `findByIdAndUserId` exige el dueño: T07 necesita buscar solo por id |
| Candado por usuario | `reservation/application/support/UserOperationLock.java` | `exclusivelyFor(userId, trabajo)`; lo usan T04 y T05, y lo usará T07 (D2, decisión 013) |
| Confirmación (T05) | `reservation/application/usecases/ConfirmReservationProcessUseCaseImpl.java` | Toma el candado y llama a la cátedra dentro de él: el pedido de T07 espera a que termine (CA-09) |
| Dependencias | `pom.xml` (Spring Boot 4.1.1) | Sin Kafka. El BOM de Boot 4.1.1 gestiona `spring-boot-starter-kafka`, `spring-boot-starter-kafka-test`, spring-kafka 4.1.1 y kafka-clients 4.2.1, y Testcontainers 2.0.5 incluye `testcontainers-kafka` (verificado en `~/.m2`) |
| Tests | `src/test/java/turnos/TestcontainersConfiguration.java`, `turnos/catedra/FakeCatedraServer.java`, `e2e/ReservationProcessE2ETest.java`, `e2e/E2ECredentials.java` | Postgres de Testcontainers con `@ServiceConnection`; cátedra REST simulada; E2E de T05 que bloquea y confirma un turno real (lo amplía CA-14) |

**Convenciones y slice de referencia:** los slices `catedra` (operaciones y sesión) y `reservation` (T04 y T05), con las excepciones a la skill de `CLAUDE.md`. Decisiones de Kafka: `docs/decisiones/014-kafka-con-la-catedra.md`.

## Solución propuesta

<!-- Explica el enfoque y sus motivos. Describe las responsabilidades y el
recorrido de la solicitud, el evento o la tarea desde que entra hasta la
respuesta, la persistencia o el mensaje publicado. Referencia el diagrama de
secuencia del proyecto si ya lo describe; usa un diagrama nuevo solo si aporta
claridad. -->

El recorrido sigue el caso de uso T07 y el diagrama de secuencia §3 (bloque T07), con las decisiones de la SPEC y de este PLAN:

```
Arranque o login posterior (X01)
  StartTechnicalSessionUseCaseImpl / RenewTechnicalSessionUseCaseImpl
    └─ sesión habilitada → TechnicalIntegrationListener (puerto de salida de catedra, P3)
         = KafkaConsumerManager (catedra/infrastructure/kafka): si está habilitado y no corre, arma y arranca el consumo
           con los datos de integration (P2, P7); si ya corre y los datos cambiaron, warning (D7)

Kafka, topic de acciones (consumer group de integration, o el de tests en el E2E)
  AppointmentActionListener (catedra/infrastructure/kafka)
    ├─ lee el JSON y valida el formato con AppointmentActionReader (RF-02, RF-03, D3, D4)
    │    inválido, eventType desconocido o de resultado → log y termina (el offset se confirma)
    └─ pedido válido → AdditionalInformationRequestService (catedra/application/service)            (P10)
         └─ ReceiveAdditionalInformationRequestUseCase (puerto de entrada) = …UseCaseImpl
              └─ AdditionalInformationRequestHandler (puerto de salida de catedra)                    (P1)
                   = CatedraPhoneRequestAdapter (reservation/infrastructure/gateway)
                     └─ ReservationService → ProcessPhoneRequestUseCase = …UseCaseImpl (reservation)
                          candado del usuario → ¿evento ya procesado? → proceso → holdId → estado
                          → transacción: proceso actualizado + evento registrado (P4)
  Termina bien → se confirma el offset. Falla de la base → reintento sin fin sobre el mismo mensaje (D5).
  Otro error → reintentos limitados, error grave en el log y se confirma el offset (P6).
```

Cómo vuelve el resultado: el caso de uso de `reservation` devuelve qué pasó (aplicado o descartado, con el motivo, que ya registró en el log) o lanza `ReservationNotSavedException` si falló la base. El gateway lo traduce a un resultado del dominio de `catedra` (`Processed`, `Discarded` o `NotSaved`); con `NotSaved`, el caso de uso de `catedra` lanza su excepción `CatedraEventNotSavedException`, que el manejo de errores del consumidor reconoce como "falla de la base" (D5). Cualquier otra excepción es "inesperada" (P6). Así `catedra` nunca conoce las excepciones de `reservation`, y viceversa.

Por qué el gateway entra por `ReservationService`: por la misma regla de P10, es un adaptador de entrada para `reservation` (lo de afuera, la cátedra, entra por él), y la skill hace entrar esos adaptadores por el service (§5.3).

## Slices y componentes afectados

<!-- Aplica la skill hexagonal-vertical-slicing: un paquete por slice, con sus
capas domain, application e infrastructure. Identifica los slices afectados, sus
responsabilidades y la dirección de sus dependencias. Una funcionalidad puede
afectar más de un slice. Justifica cualquier slice nuevo o dependencia nueva
entre slices. -->

| Slice | Existe / nuevo | Responsabilidad y cambios | Dependencias con otros slices |
| --- | --- | --- | --- |
| `turnos.catedra` | Existente | Conexión y consumo de Kafka (solo en `infrastructure/kafka`), recepción del pedido por su aplicación, aviso de integración habilitada | Ninguna nueva: no conoce a `reservation` (le inyectan el adaptador de su puerto de salida) |
| `turnos.reservation` | Existente | Caso de uso de T07, `requestEventId`, eventos procesados, puerto de transacción, adaptador del puerto de `catedra` | Usa `catedra` (ya existía), ahora también implementando su puerto de salida (P1) |

No hay slices nuevos. La dirección de la dependencia no cambia: `reservation` → `catedra`.

<!-- Distingue lo que se reutiliza, modifica o crea. Las rutas nuevas son
propuestas. Señala impacto sobre modelos, contratos o componentes compartidos. -->

| Slice y capa | Componente o ruta | Acción | Cambio y responsabilidad | Requisito relacionado |
| --- | --- | --- | --- | --- |
| `catedra` dominio | `domain/model/AdditionalInformationRequest.java` | Crear | El pedido de la cátedra ya validado: `eventId`, `reservationProcessId`, `holdId`, `expiresAt`. Sin nada de Kafka | RF-03, RF-08 |
| `catedra` dominio | `domain/model/AdditionalInformationRequestResult.java` | Crear | Resultado sellado de quien procesa el pedido: `Processed`, `Discarded`, `NotSaved` | RF-04 a RF-09 |
| `catedra` dominio | `domain/ports/in/ReceiveAdditionalInformationRequestUseCase.java` | Crear | Puerto de entrada: recibir un pedido (P10) | RF-01 |
| `catedra` dominio | `domain/ports/out/AdditionalInformationRequestHandler.java` | Crear | "Quien procesa los pedidos" (P1); lo implementa `reservation` | RF-04 a RF-09 |
| `catedra` dominio | `domain/ports/out/TechnicalIntegrationListener.java` | Crear | Aviso de integración habilitada, con sus datos (P3) | RF-01, RF-13 |
| `catedra` aplicación | `application/usecases/ReceiveAdditionalInformationRequestUseCaseImpl.java` | Crear | Entrega el pedido al puerto de salida; con `NotSaved` lanza `CatedraEventNotSavedException` | RF-09 |
| `catedra` aplicación | `application/service/AdditionalInformationRequestService.java` | Crear | Service que usa el consumidor (P10) | RF-01 |
| `catedra` aplicación | `application/exception/CatedraException.java`, `CatedraEventNotSavedException.java` | Crear | Base de excepciones del slice (hasta ahora no tenía) y la falla de la base que se reintenta sin fin (D5) | RF-09 |
| `catedra` aplicación | `application/usecases/StartTechnicalSessionUseCaseImpl.java`, `RenewTechnicalSessionUseCaseImpl.java` | Modificar | Después de guardar una sesión habilitada, avisan a `TechnicalIntegrationListener` (P3). Sin otros cambios | RF-01, CA-16 |
| `catedra` infraestructura | `infrastructure/kafka/CatedraKafkaProperties.java` | Crear | Propiedades de P7; su `toString` no muestra la contraseña | RF-01, RF-09 |
| `catedra` infraestructura | `infrastructure/kafka/KafkaConsumerManager.java` | Crear | Implementa `TechnicalIntegrationListener`: si está habilitado, arma el contenedor de consumo (servidores, seguridad, consumer group, `earliest`, confirmación por mensaje, manejo de errores de D5 y P6) y lo arranca aparte del hilo del login; conserva los primeros datos y avisa si cambian; lo detiene al apagar la aplicación | RF-01, RF-09, RF-13, RF-14, D7 |
| `catedra` infraestructura | `infrastructure/kafka/AppointmentActionListener.java` | Crear | Recibe cada mensaje del topic de acciones, usa el lector y llama al service; registra los descartes | RF-02, RF-03, RF-12 |
| `catedra` infraestructura | `infrastructure/kafka/AppointmentActionReader.java` y `infrastructure/kafka/dto/AppointmentActionMessage.java` | Crear | Formato JSON del topic de acciones y su validación y conversión: `eventType`, seis campos obligatorios, `schemaVersion` 1, `requiredField` `PHONE_NUMBER`, instantes; los eventos de resultado se descartan (D3) | RF-02, RF-03 |
| `reservation` dominio | `domain/model/ReservationProcess.java` | Modificar | `requestEventId` y la transición `acceptPhoneRequest(requestEventId, expiresAt)`: solo desde `HELD` o `WAITING_FOR_PHONE`; `HELD` pasa a `WAITING_FOR_PHONE`; no toca la marca | RF-07, RF-08 |
| `reservation` dominio | `domain/model/ReservationPhoneRequest.java` | Crear | Copia propia del pedido (decisión 009) | RF-08 |
| `reservation` dominio | `domain/model/PhoneRequestOutcome.java` | Crear | Qué pasó con el pedido: `APPLIED`, `ALREADY_PROCESSED`, `ORPHAN`, `HOLD_MISMATCH`, `STATE_ADVANCED` | RF-04 a RF-08 |
| `reservation` dominio | `domain/model/ProcessedEvent.java` | Crear | `eventId` y `processedAt` (dominio §3). Su id es el `eventId`, un texto: excepción del mismo tipo que la decisión 012 | RF-04, RF-08 |
| `reservation` dominio | `domain/ports/in/ProcessPhoneRequestUseCase.java` | Crear | Puerto de entrada de T07 | RF-04 a RF-11 |
| `reservation` dominio | `domain/ports/out/ProcessedEventRepository.java` | Crear | `existsById` y `create` | RF-04, RF-08 |
| `reservation` dominio | `domain/ports/out/TransactionRunner.java` | Crear | Puerto técnico: ejecutar un bloque en una transacción (P4) | RF-08, CA-08 |
| `reservation` dominio | `domain/ports/out/ReservationProcessRepository.java` | Modificar | Suma `findById` (sin dueño) | RF-05 |
| `reservation` aplicación | `application/usecases/ProcessPhoneRequestUseCaseImpl.java` | Crear | Recorrido de "Transacciones, concurrencia y errores" | RF-04 a RF-12 |
| `reservation` aplicación | `application/service/ReservationService.java` | Modificar | Suma el método de T07 (lo usa el gateway) | RF-08 |
| `reservation` infraestructura | `infrastructure/gateway/CatedraPhoneRequestAdapter.java` | Crear | Implementa `AdditionalInformationRequestHandler` de `catedra`: convierte el modelo, llama a `ReservationService` y traduce el resultado (`ReservationNotSavedException` → `NotSaved`) | RF-04 a RF-09 |
| `reservation` infraestructura | `infrastructure/persistence/entity/ReservationProcessEntity.java`, `mapper/ReservationProcessMapper.java` | Modificar | Columna `request_event_id` | RF-08 |
| `reservation` infraestructura | `infrastructure/persistence/adapter/JpaReservationProcessRepositoryAdapter.java` | Modificar | `findById` | RF-05 |
| `reservation` infraestructura | `infrastructure/persistence/entity/ProcessedEventEntity.java`, `repository/JpaProcessedEventRepository.java`, `mapper/ProcessedEventMapper.java`, `adapter/JpaProcessedEventRepositoryAdapter.java` | Crear | Persistencia de los eventos procesados; la entidad implementa `Persistable` (id asignado, decisión 012) | RF-04, RF-08 |
| `reservation` infraestructura | `infrastructure/persistence/adapter/SpringTransactionRunnerAdapter.java` | Crear | Implementa `TransactionRunner` con la herramienta de transacciones de Spring; traduce cualquier falla de la base (también al confirmar la transacción) a `ReservationNotSavedException` | RF-08, RF-09 |
| `reservation` infraestructura | `src/main/resources/db/migration/V4__add_request_event_id_and_processed_events.sql` | Crear | Columna `request_event_id` y tabla `processed_events` | RF-08, CA-08 |
| Configuración | `pom.xml`, `src/main/resources/application.yaml`, `.env.example` | Modificar | Dependencias de P2 y P8; propiedades de P7 | RF-01 |
| Documentación | `CLAUDE.md`, `docs/decisiones/014-kafka-con-la-catedra.md`, `docs/decisiones/010-tests-simulados-y-e2e.md` | Modificar | Excepciones nuevas a la skill, cómo usar Kafka y los tests; decisiones de este PLAN (P9) | — |

Los nombres son propuestas y pueden ajustarse al implementar, sin cambiar las responsabilidades.

## Datos y contratos

<!-- Completa solo lo aplicable. Si un punto no aplica, indica el motivo. -->

- **Operaciones, eventos y DTO de entrada y salida:** se consume `AdditionalInformationRequested` (contrato §4.3.2, anexo §15.4) del topic de acciones, con los campos obligatorios de D4; los demás campos se ignoran. No se publica nada ni cambia ninguna respuesta de la API (RF-10).
- **Identificadores, relaciones y restricciones:** el proceso se busca por `reservationProcessId` (clave primaria, decisión 012) y se compara el `holdId`. `processed_events.event_id` es clave primaria; la tabla no se relaciona con los procesos.
- **Origen de los datos devueltos o publicados y transformaciones:** `requestEventId` es el `eventId` del pedido; `expiresAt` es el del pedido; `processedAt` es el reloj del servicio (`Clock`, en UTC).
- **Persistencia, consultas y migraciones:** `V4` suma `request_event_id VARCHAR(255)` (nulo en los procesos que ya existen) y crea `processed_events (event_id VARCHAR(255) PRIMARY KEY, processed_at TIMESTAMPTZ NOT NULL)`. Consultas por clave primaria.
- **Convivencia entre datos locales y remotos:** la cátedra es la fuente de verdad (dominio regla 15); el pedido es la prueba de que confirmó el hold, por eso avanza un `HELD`.
- **Compatibilidad y migraciones de datos existentes:** los procesos existentes quedan sin `requestEventId`; `V2` y `V3` no cambian.

## Transacciones, concurrencia y errores

<!-- Cómo se implementan los comportamientos aprobados en la spec.
Referencia RF/CA y aplica las consideraciones relevantes de BACKEND_GUIDELINES.md. -->

- **Estados y transiciones:** `HELD → WAITING_FOR_PHONE` y `WAITING_FOR_PHONE → WAITING_FOR_PHONE` con el pedido (`acceptPhoneRequest`). Cualquier otro estado no cambia (RF-07, dominio regla 6). Un `requestEventId` anterior se reemplaza, como dice T07 paso 6.
- **Transacciones y consistencia:** recorrido de `ProcessPhoneRequestUseCaseImpl`: (1) `findById`; si no existe, `ORPHAN` (sin candado: no hay dueño); (2) toma el candado del dueño; (3) dentro, ¿`eventId` ya procesado? → `ALREADY_PROCESSED`; (4) vuelve a leer el proceso (pudo cambiar mientras esperaba el candado); `holdId` distinto → `HOLD_MISMATCH`; estado distinto de `HELD` o `WAITING_FOR_PHONE` → `STATE_ADVANCED`; (5) `acceptPhoneRequest` y, con `TransactionRunner`, guarda el proceso y registra el evento: los dos o ninguno (CA-08); (6) suelta el candado y devuelve `APPLIED`. El offset se confirma recién cuando todo esto terminó bien. Los descartes (1, 3, 4) se registran en el log con `eventId`, `reservationProcessId` y motivo, y no registran el evento como procesado (T07 solo lo registra en el paso 7).
- **Idempotencia y mensajes duplicados:** chequeo de `existsById` dentro del candado, y la clave primaria como respaldo: si dos entregas llegaran a registrar el mismo evento, la segunda falla y se deshace (se trata como falla de la base y en la nueva entrega aparece como ya procesado).
- **Concurrencia y tareas programadas:** el mismo `UserOperationLock` de T04 y T05 (D2, decisión 013); usuarios distintos no se esperan. El consumo tiene un solo hilo (un consumidor); los mensajes de un mismo proceso llegan en orden por su clave. La espera del candado (lo que dure una confirmación de T05) demora a los mensajes siguientes, lo que es aceptable.
- **Timeouts, reintentos y errores de dependencias:** falla de la base: reintentos con espera creciente de P7 y, agotados, pausa y de nuevo sobre el mismo mensaje, sin confirmar (D5). Otro error: los mismos reintentos y, agotados, error grave y se confirma (P6). Kafka caído o inaccesible: el cliente reintenta la conexión solo, sin afectar la API REST (RF-13). Integración no habilitada: no se consume hasta un login exitoso (D7).
- **Seguridad y autorización:** sin endpoint. Credenciales de Kafka de la configuración externa (D1), nunca en logs; se revisa también la salida del propio cliente de Kafka, que oculta las contraseñas (CA-11).
- **Otras consideraciones de backend aplicables y su solución:** observabilidad: cada descarte, reintento y error con `eventId` y motivo (RF-12); arranque con la configuración desactivada o sin integración, sin errores (RF-13).

## Dependencias y configuración

<!-- Librerías, servicios externos, propiedades de configuración y secretos
afectados. Verifica compatibilidad con el proyecto y justifica las incorporaciones.
No agregues dependencias por defecto. Los valores sensibles y los parámetros
ajustables se externalizan. -->

- **`spring-boot-starter-kafka`** (P2), versión del BOM de Boot 4.1.1 (spring-kafka 4.1.1, kafka-clients 4.2.1). Se comprueba al implementar que no intente conectarse a un Kafka por defecto al arrancar.
- **`testcontainers-kafka`** (scope test, P8.1), versión del BOM de Testcontainers 2.0.5 que ya usa el proyecto. La imagen de Kafka se fija con una versión exacta al implementar.
- **Propiedades:** las de P7, más `turnos.catedra.kafka.enabled` (`TURNOS_CATEDRA_KAFKA_ENABLED`, verdadero por defecto; P8). `turnos.catedra.kafka.consumer-group` no tiene variable de entorno en la aplicación (P8.3). Se documentan en `.env.example`.
- **Secretos:** ninguno nuevo; las credenciales de Kafka son las de la cuenta técnica (D1).

## Estrategia de validación

<!-- Una fila por criterio de la spec. Selecciona el método capaz de demostrarlo:
test unitario, de integración (base de datos, mensajería), de API o prueba manual.
No todos requieren todos los métodos. Identifica tests existentes y separa los
nuevos propuestos. Incluye regresiones relevantes. Una prueba con mocks no
demuestra persistencia, transacciones ni idempotencia. -->

Tests propuestos (todos nuevos salvo que se diga): `ReservationProcessTest` (ampliado), `ProcessPhoneRequestUseCaseImplTest`, `AppointmentActionReaderTest`, `ReceiveAdditionalInformationRequestUseCaseImplTest`, `CatedraPhoneRequestAdapterTest`, `JpaProcessedEventRepositoryAdapterTest`, `SpringTransactionRunnerAdapterTest`, `JpaReservationProcessRepositoryAdapterTest` (ampliado), mappers con su prueba de ida y vuelta, `KafkaConsumerManagerTest`, los de login de X01 (ampliados con el aviso), `PhoneRequestKafkaIntegrationTest` (aplicación completa, Postgres y Kafka de Testcontainers, cátedra REST simulada que devuelve en el login los servidores del contenedor) y `e2e/ReservationProcessE2ETest` (ampliado).

| Criterio | Método y test existente o propuesto | Entorno y datos necesarios | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01, CA-02 | Modelo; caso de uso; integración con Kafka publicando el pedido grabado | Postgres y Kafka de Testcontainers | Proceso con `requestEventId` y `expiresAt`; evento registrado; offset confirmado (posición del consumer group) |
| CA-03 | `AppointmentActionReaderTest` con un mensaje por defecto; integración con uno inválido seguido de uno válido | Mensajes armados desde el grabado | Descartes con motivo; el válido se procesa, lo que prueba que el offset avanzó |
| CA-04 | Lector e integración con mensajes sin `eventType`, inventado y de resultado | Kafka de Testcontainers | Descartados; ningún evento registrado |
| CA-05 | Caso de uso; integración publicando dos veces el mismo pedido | Kafka y Postgres | Un solo efecto |
| CA-06 | Caso de uso; integración con proceso inexistente, `holdId` distinto, `PHONE_SUBMITTED` y `EXPIRED` | Procesos en la base | Sin cambios; log; offset avanzado |
| CA-07 | Integración con un `TransactionRunner` de test que falla N veces y después funciona, y otro pedido detrás; reinicio del consumo con el mensaje sin confirmar | Kafka de Testcontainers; esperas cortas en la configuración del test | Intentos en el log; orden respetado; un solo efecto |
| CA-08 | `SpringTransactionRunnerAdapterTest` y caso de uso contra Postgres real, con una falla al registrar el evento y otra al guardar el proceso | Testcontainers | Ninguna de las dos escrituras queda |
| CA-09 | Caso de uso con hilos reales: T05 demorado por la cátedra simulada y el pedido durante la espera (repositorio que devuelve copias, como en T05) | Dobles con demora | `WAITING_FOR_PHONE` con `requestEventId`; otro usuario no espera |
| CA-10 | Tests web existentes de T04 y T05 (`ReservationProcessControllerTest`) | `@WebMvcTest` | Sin `requestEventId` en las respuestas |
| CA-11 | Salida capturada (`OutputCaptureExtension`) en la integración y en `KafkaConsumerManagerTest` con credenciales de prueba | Salida capturada | `eventId` y motivo; sin el JWT técnico ni la contraseña |
| CA-12 | Integración: aplicación con Kafka detenido (contenedor pausado) que atiende REST, y que procesa al volver | Testcontainers | REST igual; pedido procesado después |
| CA-13 | `PhoneRequestKafkaIntegrationTest`: registro, login, bloqueo, confirmación y pedido | App completa, Kafka y Postgres de Testcontainers, cátedra simulada | `requestEventId` en la base |
| CA-14 | `e2e/ReservationProcessE2ETest` ampliado, corrido por el usuario (P8.3) | Cátedra y Kafka reales, `TURNOS_E2E_KAFKA_CONSUMER_GROUP` o plan B | Proceso con el `requestEventId` real; consumer group del servicio intacto |
| CA-15 | Integración con un consumer group nuevo y mensajes publicados antes de arrancar | Kafka de Testcontainers | Huérfano registrado; el otro aplicado |
| CA-16 | Integración: cátedra simulada que falla el login del arranque y acepta uno posterior; sesión en error de configuración | Kafka de Testcontainers | Sin consumo y después consumo; nunca con error de configuración |
| CA-17 | `KafkaConsumerManagerTest`: segundo aviso con otros datos | Salida capturada | Warning; el consumo sigue igual |

**Comprobaciones de regresión:** la suite completa (T01 a T05, X01, validación del JWT) con el consumo desactivado en los tests que no son de Kafka; los tests de login de X01 sin cambios de comportamiento; los chequeos de la skill (con las excepciones nuevas comentadas).

**Comandos verificados para compilar y ejecutar tests:** los de `CLAUDE.md`: `./mvnw test -Dtest=…`, `./mvnw clean test`, `./mvnw clean verify` y `./mvnw test -Pe2e`.

**Pruebas con infraestructura real o simulada:** Postgres y Kafka de Testcontainers en la suite normal (P8.1); la cátedra REST simulada con `FakeCatedraServer`. El Kafka y la API reales solo en la primera tarea (prueba de conexión y grabaciones, P8.2) y en el E2E (P8.3), corridos por el usuario después de confirmar qué hace cada uno.

**Limitaciones del entorno:** el modo automático bloquea las llamadas que escriben en la cátedra: el usuario corre el programa de la primera tarea y el E2E. No se puede provocar una falla de autenticación de Kafka en la cátedra real ni probar más de una instancia del servicio.

<!-- Esta sección planifica la validación. Durante la implementación, registra
en TASKS.md o en el informe de validación acordado los resultados y evidencias
reales. Distingue pruebas ejecutadas, fallidas, no ejecutadas y bloqueadas.
Compilar o tener tests en verde no sustituye revisar los criterios de la spec. -->

## Orden de implementación

<!-- Etapas y dependencias principales. El desglose ejecutable se escribe en TASKS.md.
Incluye puntos de comprobación para avanzar con cambios pequeños. -->

1. **Dependencia, prueba de conexión y grabaciones (D1, D8, P8.2):** agregar `spring-boot-starter-kafka` (y comprobar que la app arranca igual); programa de un solo archivo en el scratchpad, corrido por el usuario: login, lista de topics sin y con credenciales, consulta de un consumer group con otro nombre, lectura del topic de acciones sin consumer group. Comprobación: resultados leídos sin mostrar credenciales, mensajes limpios versionados en `src/test/resources/catedra/` con su `README`, y valores de seguridad por defecto de P7 ajustados. Si algo contradice lo decidido, se vuelve a este PLAN.
2. **`catedra` dominio y aplicación:** modelo, resultado, puertos, caso de uso, service, excepciones y el aviso en los logins. Comprobación: tests unitarios y los de X01 en verde.
3. **`reservation` dominio y persistencia:** modelo y transición, `ProcessedEvent`, `V4`, repositorios, `TransactionRunner`. Comprobación: tests del modelo y de los adaptadores contra Postgres (CA-08).
4. **`reservation` caso de uso, service y gateway.** Comprobación: tests del caso de uso, incluida la concurrencia (CA-05, CA-06, CA-09).
5. **`catedra` infraestructura de Kafka y configuración:** propiedades, lector, oyente, `KafkaConsumerManager`, `enabled` desactivado en los tests que no son de Kafka. Comprobación: tests del lector y del manager; suite completa en verde.
6. **Integración con Kafka de Testcontainers.** Comprobación: `PhoneRequestKafkaIntegrationTest` (CA-01 a CA-07, CA-12, CA-13, CA-15, CA-16).
7. **E2E (P8.3):** configuración común de la suite E2E (consumer group de tests o consumo apagado) y ampliación de `ReservationProcessE2ETest`, corrido por el usuario. Comprobación: reportes de Surefire; posición del consumer group del servicio sin cambios.
8. **Cierre:** `CLAUDE.md`, decisiones 010 y 014, `.env.example`, `./mvnw clean verify`, chequeos de la skill y cobertura de los CA.

## Riesgos y decisiones pendientes

<!-- Riesgos concretos de esta solución y cómo se resolverán, sin listas genéricas.
Escribe Ninguna en las decisiones pendientes cuando estén resueltas. -->

- **Riesgos y medidas acordadas:**
  - La autenticación y los permisos del Kafka real no están documentados: la primera tarea los verifica antes de escribir el código que se conecta, y P7 deja la seguridad configurable.
  - Si la cátedra no permite otro consumer group, el E2E usa el plan B y el consumo real del servicio se prueba recién en T06 (D8).
  - Que un E2E use el consumer group del servicio: lo impide la configuración común de la suite E2E (P8.3), y `enabled` apaga el consumo en los tests que no son de Kafka.
  - `catalog_service` puede usar el mismo consumer group sugerido sobre su propio topic: dos servicios en un mismo grupo provocan reacomodos entre ellos, aunque cada uno lee su topic. Se anota en la decisión 014 para revisarlo al hacer catálogo.
  - La configuración automática de Spring Boot para Kafka podría intentar conectarse al arrancar: se comprueba en la primera y la quinta etapa.
  - El contenedor de Kafka alarga la suite: uno solo compartido y esperas cortas en la configuración de los tests.
  - Un pedido que espera el candado demora a los mensajes siguientes mientras dure una confirmación de T05: aceptado (unos segundos).
- **Decisiones pendientes:** Ninguna. P9 (actualizar la decisión 014 con lo de este PLAN) se cumple junto con este borrador.

  Decisiones tomadas (2026-10-29 y 2026-10-30):

  - **P1 · Dónde vive Kafka y cómo llega un pedido a `reservation` (dependencia entre slices, skill §9, opción a):** la conexión y el consumo de Kafka viven en `catedra`, la única puerta hacia la cátedra (decisión 009), en un paquete nuevo de su infraestructura, `catedra/infrastructure/kafka` (el usuario prefirió nombrarlo por la tecnología; `messaging` seguía la regla de nombrar por función, como `persistence` o `web`). Ahí se lee cada mensaje y se valida su formato (`eventType`, los seis campos obligatorios, `schemaVersion` y `requiredField`: RF-02, RF-03), y el pedido válido se entrega como un modelo de `catedra` a un puerto de salida de `catedra` ("quien procesa los pedidos de información adicional"). Lo implementa un adaptador en `reservation/infrastructure/gateway`, que entra a `reservation` por `ReservationService` (ajuste del 2026-10-30, por la misma regla de P10: para `reservation` también es un adaptador de entrada) y llega al caso de uso de T07; ese caso de uso valida el negocio (proceso, `holdId`, estado: RF-04 a RF-08). `catedra` nunca nombra a `reservation`: Spring le inyecta el adaptador. **Regla pedida por el usuario: nada de Kafka fuera de `catedra/infrastructure/kafka`.** El dominio y la aplicación de `catedra` (y todo `reservation`) no conocen tipos, nombres ni conceptos de Kafka: hablan de "un pedido de información adicional de la cátedra" y de "la integración se habilitó". Fuera de ese paquete solo se suman un modelo del pedido, dos puertos de salida (este y el de P3) y el aviso en los logins (P3). La dependencia sigue siendo de `reservation` hacia `catedra`, y el gateway solo usa puertos y modelos del otro slice. **Excepciones a la skill autorizadas por el usuario**, con su comentario en el código y en `CLAUDE.md`: (1) un adaptador de `gateway` que implementa un puerto de salida de otro slice y llama a un puerto de entrada propio (hasta ahora era al revés); (2) el paquete nuevo de infraestructura de `catedra`. Motivo: las credenciales de la cuenta técnica y la renovación de la sesión no salen de `catedra`; T06 (publicar y credenciales rechazadas) y T08 (sus eventos) se apoyan en lo mismo. Alternativas descartadas: (b) Kafka en `reservation`, como un controller de mensajes, con los datos de conexión pedidos a `catedra`: las credenciales saldrían de `catedra` y se hablaría con la cátedra por fuera de su puerta; (c) un slice nuevo solo para Kafka: otro slice que habla con la cátedra y necesita igual la sesión de `catedra`; (d) que `catedra` llame directo al puerto de entrada de `reservation`: dejaría a los dos slices dependiendo uno del otro; (e) eventos de Spring: no elimina la dependencia (el tipo del evento sigue siendo de `catedra`) y esconde quién procesa el pedido y cuándo se confirma el offset.
  - **P2 · Librería y forma de consumir (opción a):** spring-kafka, con `spring-boot-starter-kafka` (gestionado por el BOM de Boot 4.1.1: spring-kafka 4.1.1, kafka-clients 4.2.1). Como los datos de conexión llegan con el login técnico y no al arrancar, el contenedor de consumo se arma por programa con los datos de `integration`; no se usa `@KafkaListener` ni `spring.kafka.bootstrap-servers`. Se usa lo que la librería ya resuelve: confirmar el offset de cada mensaje después de procesarlo bien, volver a leer el mismo mensaje con espera creciente ante un error (D5), y detener y arrancar el consumo. Al implementar se comprueba que la configuración automática de Spring Boot no intente conectarse a un Kafka por defecto al arrancar. Alternativa descartada: (b) kafka-clients puro con un hilo propio: control total, pero confirmar en el momento justo y volver a leer el mismo mensaje quedarían escritos y probados a mano.
  - **P3 · Cómo se entera el consumidor de que la integración quedó habilitada (D7, opción a):** los dos casos de uso de login de `catedra` (`StartTechnicalSessionUseCaseImpl` y `RenewTechnicalSessionUseCaseImpl`), después de guardar una sesión habilitada, avisan a un puerto de salida de `catedra` que la integración se habilitó, con sus datos. Lo implementa la infraestructura de `kafka`: si el consumo no corre, lo arranca sin trabar el login (aparte del hilo del login); si ya corre, compara los datos y, si cambiaron, registra el warning de D7 sin cambiar la conexión. El comportamiento de X01 no cambia y sus tests siguen valiendo; se suman los del aviso. Alternativas descartadas: (b) que el repositorio en memoria de la sesión avise al guardar: un efecto escondido en un repositorio; (c) un chequeo periódico: arranca con demora y suma una tarea que corre siempre.
  - **P4 · Persistencia: `requestEventId`, eventos procesados y la transacción que los une (RF-04, RF-08, CA-05, CA-08, opción b):**
    - **Fijo:** el proceso suma `request_event_id` (migración `V4`); los eventos procesados van en una tabla propia, independiente de los procesos y sin clave foránea (`event_id` como clave primaria, `processed_at`), porque la idempotencia es por evento: un proceso recibe varios eventos en su vida (T07 y T08) y una columna con "el último" no detectaría el duplicado de uno anterior. La clave primaria rechaza un segundo registro del mismo evento. El repositorio de procesos suma una búsqueda solo por id, porque T07 no tiene usuario.
    - **Por qué juntos:** si se guardara primero el proceso y el servicio se cayera antes de registrar el evento, la nueva entrega lo aplicaría dos veces; si se registrara primero el evento, la nueva entrega lo saltearía y el cambio se perdería. Por eso van en una transacción: los dos o ninguno.
    - **Decisión:** `ProcessedEvent` tiene su propio repositorio (`ProcessedEventRepository`, un `{Entity}Repository` de la skill, con su persistencia JPA) y la atomicidad la da un **puerto técnico** de `reservation` ("ejecutá este bloque en una transacción"), implementado en infraestructura con la herramienta de transacciones de Spring. El puerto no conoce procesos ni eventos: el caso de uso arma el bloque y llama a cada repositorio. Orden: candado del usuario → leer el proceso y ver si el evento ya se procesó → transacción (guardar el proceso y registrar el evento) → soltar el candado → confirmar el offset. La transacción va dentro del candado: si se confirmara después de soltarlo, otra operación del usuario podría leer el proceso viejo y pisarlo. El puerto técnico es una excepción a la skill del mismo tipo que `PasswordHasher` (decisión 001), autorizada por el usuario. Motivo: el dominio modela dos entidades y la skill pide un repositorio por entidad; la aplicación sigue sin conocer transacciones de Spring. Alternativas descartadas: (a) un método del repositorio de procesos que también registra el evento: más simple, pero mezcla las dos entidades; (c) `@Transactional` en el caso de uso: la aplicación usaría transacciones de Spring por primera vez y hay que cuidar que la transacción quede dentro del candado.
    - **Ubicación (confirmada por el usuario):** todo en `reservation` (modelo, puerto y persistencia de `ProcessedEvent`; puerto de transacción en `domain/ports/out` y su adaptador en `infrastructure/persistence/adapter`). Los eventos que se registran son todos de procesos de reserva, nadie más los usa, y la transacción abarca las dos tablas; un slice propio (`event`) o `catedra` obligarían a compartir la transacción entre slices.
  - **P5 · Control de versión además del candado (D2, opción a):** no se agrega. El servicio corre en una sola instancia, la regla de tomar el candado en toda operación sobre los procesos de un usuario está en `CLAUDE.md` y en la decisión 013, y esa decisión ya prevé qué cambiar si hay más instancias. Se compensa con un test de concurrencia en cada caso de uso que escribe procesos (en T07, CA-09). Alternativa descartada: (b) agregarlo ahora como red de seguridad: suma un campo técnico (`version`) al modelo del dominio, el mapper, una migración y el manejo del conflicto en T04, T05 y T07.
  - **P6 · Error inesperado al procesar un mensaje, que no es de la base (D5, opción a):** las fallas de la base llegan como excepciones propias de `reservation` (como hoy en el adaptador JPA) y siguen D5: espera sobre el mismo mensaje sin límite. Cualquier otro error se reintenta unas pocas veces con la misma espera creciente, por si es pasajero; agotados, se registra un error grave con el `eventId`, el `reservationProcessId` y el motivo, y se confirma el offset: el mensaje se descarta y el consumo sigue. Ese pedido se pierde (el proceso vence solo o lo resuelve T13), pero un error de código no detiene el consumo de todos los usuarios. Alternativas descartadas: (b) tratarlo como la base: un error de código frenaría todas las reservas hasta un nuevo despliegue; (c) descartarlo sin reintentos: perdería pedidos por fallos pasajeros.
  - **P7 · Propiedades de configuración (D1, D5, D6, D8):** bajo `turnos.catedra.kafka` en `application.yaml`, con variables de entorno opcionales y valores por defecto, leídas por un `record` de propiedades en `catedra/infrastructure/kafka`:

    | Propiedad | Variable | Por defecto | Origen |
    | --- | --- | --- | --- |
    | Protocolo de seguridad | `TURNOS_CATEDRA_KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` | D1 |
    | Mecanismo de autenticación (solo con `SASL_…`) | `TURNOS_CATEDRA_KAFKA_SASL_MECHANISM` | `PLAIN` | D1 |
    | Usuario y contraseña | Las de la cuenta técnica (`TURNOS_CATEDRA_USERNAME`, `TURNOS_CATEDRA_PASSWORD`); sin variables nuevas | — | D1 |
    | Consumo habilitado | `TURNOS_CATEDRA_KAFKA_ENABLED` | Verdadero | P8 (sumada el 2026-10-30) |
    | Consumer group | Ninguna en la aplicación (ver P8.3) | Vacía: el de `integration` | D8 (solo la suite E2E la completa) |
    | Reintentos ante una falla: espera inicial, multiplicador y máximo de intentos | `TURNOS_CATEDRA_KAFKA_RETRY_INITIAL_DELAY`, `TURNOS_CATEDRA_KAFKA_RETRY_MULTIPLIER`, `TURNOS_CATEDRA_KAFKA_RETRY_MAX_ATTEMPTS` | 500 ms, 2, 3 | D5, P6 |
    | Pausa antes de volver a intentar el mismo mensaje si la base sigue fallando | `TURNOS_CATEDRA_KAFKA_RETRY_PAUSE` | 30 s | D5 |

    No son configurables, porque son decisiones y no ajustes: leer desde el mensaje más antiguo (D6) y confirmar el offset solo después de procesar cada mensaje. Los valores de seguridad por defecto se corrigen con lo que muestre la prueba de conexión (D1) y se documentan en `.env.example`. La contraseña nunca aparece en logs (como en `CatedraProperties`).
  - **P10 · El consumidor entra por la aplicación de `catedra` (opción b):** el consumidor es un adaptador de entrada, el equivalente a un controller, y la skill hace entrar esos adaptadores por el service, sin importar puertos (§5.3). Recorrido: consumidor (`infrastructure/kafka`: lee el JSON, valida el formato y lo convierte al modelo) → service de `catedra` → caso de uso "recibir un pedido de información adicional" (puerto de entrada y su implementación) → puerto de salida "quien procesa los pedidos" (P1) → gateway de `reservation`. La aplicación es la única entrada al slice y el adaptador queda como traductor. No contradice "cada slice tiene lo que alguien usa": el service lo usa el consumidor, como un controller. Nombres en la tabla de componentes. Alternativa descartada: (a) que el consumidor llame directo al puerto de salida, como excepción a la skill: uno o dos archivos menos, pero lo que hace `catedra` al recibir un pedido quedaría escondido en la infraestructura.
  - **P8 · Cómo se prueba (D8):**
    - **Riesgo que resuelve:** después de T07, todo E2E (también los de T03 y T05) hace el login técnico real, y por P3 el consumo arrancaría solo con el consumer group del servicio, lo que D8 prohíbe. En la suite normal, la cátedra simulada devuelve servidores de Kafka inventados y cada test de integración intentaría conectarse a ellos. Por eso se suma `turnos.catedra.kafka.enabled` (`TURNOS_CATEDRA_KAFKA_ENABLED`, verdadero por defecto): la aplicación real no cambia; la suite normal lo apaga salvo en los tests de Kafka, y la suite E2E solo lo enciende con el consumer group de tests.
    - **P8.1 · Tests simulados (opción a):** Kafka real en Testcontainers (`testcontainers-kafka`), igual que Postgres, con un solo contenedor compartido. La cátedra simulada devuelve en el login los servidores del contenedor, así que se prueba el recorrido real (login, aviso, consumo, proceso actualizado) con los mensajes grabados, y lo que un doble no demuestra: confirmación del offset, nueva entrega, orden y reinicio. Además, tests unitarios del modelo, del lector de mensajes y del caso de uso (concurrencia con el repositorio que devuelve copias, como en T05). Alternativa descartada: (b) el Kafka embebido de `spring-kafka-test`: otro tipo de servidor y otro criterio que el de Postgres.
    - **P8.2 · Herramienta para la prueba de conexión y las grabaciones (opción a):** un programa Java de un solo archivo en el scratchpad, con el mismo cliente de Kafka que el servicio, corrido por el usuario con `java`: lee las credenciales del `.env` sin mostrarlas, hace el login técnico, pide la lista de topics sin y con credenciales, consulta si se permite otro consumer group sin leer mensajes y lee el topic de acciones sin consumer group (partición y posición a mano, sin confirmar nada). Se borra después de leer el resultado. Alternativa descartada: (b) descargar las herramientas de consola de Apache Kafka: archivos externos y sin certeza de que su lector no se una a un consumer group.
    - **P8.3 · E2E (de acuerdo con el usuario):** el E2E de T05 se amplía: después de confirmar, espera (hasta unos 30 s) que el proceso tenga su `requestEventId` en la base del test. La propiedad del consumer group (`turnos.catedra.kafka.consumer-group`) no tiene variable de entorno en la aplicación, para que la app local nunca use el de tests aunque lea el mismo `.env`; solo la configuración común de la suite E2E la completa a partir de **`TURNOS_E2E_KAFKA_CONSUMER_GROUP`** (nombre pedido por el usuario para que se vea que es del E2E), que el usuario define en su `.env` después de la prueba de conexión. Si esa variable está vacía, la suite E2E apaga el consumo y aplica el plan B: el test lee el pedido real sin consumer group, desde la posición que anota antes de confirmar, y entrega el mensaje crudo a la misma pieza que lo convierte y valida (lector, service de `catedra` y procesamiento de `reservation`). Ningún E2E puede usar el consumer group del servicio.

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el plan cubre los requisitos, respeta las exclusiones, reutiliza
componentes verificados, respeta la skill hexagonal-vertical-slicing y permite
demostrar todos los criterios de aceptación.
Resuelve dudas y marcadores pendientes. Si la spec cambió, revisa su impacto.
Tras aprobar el plan, deriva TASKS.md con IDs, dependencias, referencias a RF/CA
y comprobaciones. No marques una tarea terminada sin realizar su validación;
si está bloqueada, registra el motivo.
-->
