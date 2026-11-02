# PLAN: T06 — Informar teléfono

**SPEC de referencia:** `docs/sdd/t06-informar-telefono/SPEC.md`
**Versión de la spec revisada:** aprobada el 2026-11-02 (D0 a D8, RF-01 a RF-16, CA-01 a CA-20), sin commitear
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

Rutas relativas a `src/main/java/turnos/`, salvo indicación.

| Componente o archivo existente | Ruta verificada | Responsabilidad y uso previsto |
| --- | --- | --- |
| Proceso de reserva | `reservation/domain/model/ReservationProcess.java` | Tiene `requestEventId`, `rejectionReason`, `needsReconciliation` y `expireIfOverdue`; le faltan `phoneNumber` y dos transiciones |
| Forma del caso de uso | `reservation/application/usecases/ConfirmReservationProcessUseCaseImpl.java` | Patrón a seguir: candado, búsqueda por dueño, estado, vencimiento, llamada externa dentro del candado |
| Candado | `reservation/application/support/UserOperationLock.java` | Se reutiliza sin cambios (D4) |
| Excepciones | `reservation/application/exception/` | Se reutilizan `ReservationProcessNotFoundException`, `ReservationInvalidProcessStateException`, `ReservationHoldExpiredException`, `ReservationCentralUnavailableException` y `ReservationLocalStateOutOfSyncException` |
| Controller y mapper web | `reservation/infrastructure/web/controller/ReservationProcessController.java`, `web/mapper/ReservationProcessDtoMapper.java` | Suma un endpoint. `toResponse` no cambia: en `PHONE_SUBMITTED` los campos que no mapea van en `null` |
| Persistencia | `reservation/infrastructure/persistence/entity/ReservationProcessEntity.java`, `mapper/ReservationProcessMapper.java`, `adapter/JpaReservationProcessRepositoryAdapter.java` | Suma `phoneNumber`. `update` no traduce los errores de la base: el caso de uso los atrapa, como T05 |
| Gateway hacia `catedra` | `reservation/infrastructure/gateway/CatedraHoldAdapter.java` | Patrón a seguir: entra por puertos de entrada de `catedra` y traduce el resultado a excepciones de `reservation` |
| Sesión técnica | `catedra/domain/ports/out/TechnicalSessionRepository.java`, `domain/ports/in/RenewTechnicalSessionUseCase.java`, `domain/model/TechnicalIntegration.java` | Dan `groupId` y `kafkaAppointmentPhoneTopic`, ya leídos del login y sin uso; la renovación ya respeta el error de configuración y el intervalo mínimo |
| Runner de la cátedra | `catedra/application/support/CatedraOperationRunner.java` | **No se usa:** entrega un JWT, que Kafka no necesita, no entrega la integración, y sus reintentos y renovación ante `401` no aplican (D6) |
| Kafka | `catedra/infrastructure/kafka/KafkaConsumerManager.java`, `CatedraKafkaProperties.java` | La configuración de seguridad pasa a compartirse con el productor |
| Manejo global de errores | `config/ApiExceptionHandler.java` | Da formato a `VALIDATION_ERROR` y registra lo no previsto |
| Migraciones | `src/main/resources/db/migration/V1` a `V5` | Se suma `V6` |
| Tests | `src/test/java/turnos/KafkaTestContainer.java`, `KafkaTestTopic.java`, `catedra/FakeCatedraServer.java`, `reservation/ReservationResultKafkaIntegrationTest.java`, `e2e/ReservationProcessE2ETest.java` | Se amplían. `FakeCatedraServer` hoy fija el topic de teléfono |

**Convenciones y slice de referencia:** `reservation` para el caso de uso; `catedra` y su `infrastructure/kafka` para lo de Kafka (decisión 014 §3.7).

## Solución propuesta

<!-- Explica el enfoque y sus motivos. Describe las responsabilidades y el
recorrido de la solicitud, el evento o la tarea desde que entra hasta la
respuesta, la persistencia o el mensaje publicado. Referencia el diagrama de
secuencia del proyecto si ya lo describe; usa un diagrama nuevo solo si aporta
claridad. -->

Sigue la secuencia §4. El recorrido es el de una operación con la cátedra (como T05), pero por Kafka:

```
POST /phone
└─ ReservationProcessController → ReservationService
   └─ SubmitPhoneUseCaseImpl
      ├─ ReservationPhoneNumber.parse   valida y normaliza, sin base ni candado (D7)
      └─ UserOperationLock (todo lo que sigue, D4)
         ├─ ReservationProcessRepository   dueño, estado, vencimiento, requestEventId
         ├─ PhoneSubmitter.canSubmit       sesión técnica, antes de guardar (D5)
         ├─ ReservationProcess.submitPhone + update
         └─ PhoneSubmitter.submit
            = reservation/infrastructure/gateway/CatedraPhoneAdapter
              └─ SubmitAdditionalInformationUseCase (catedra)     arma eventId, occurredAt, groupId y topic
                 └─ AdditionalInformationPublisher (puerto)
                    = infrastructure/kafka/KafkaAdditionalInformationPublisher   intentos y envío
```

Si `submit` falla: `ReservationProcess.revertPhoneSubmission` + `update`, y `503`. Si ese `update` también falla: error grave y `500` `LOCAL_STATE_OUT_OF_SYNC`.

**Justificación de cada archivo nuevo:**

| Archivo | Quién lo usa y por qué existe |
| --- | --- |
| `ReservationPhoneNumber` | El caso de uso: la regla del teléfono es del dominio y se prueba sola. Su `toString` lo oculta (RF-13) |
| `SubmitPhoneUseCase` e `Impl` | El service: es el caso de uso T06 |
| `PhoneSubmitter` | Puerto para que `reservation` no conozca a `catedra` (P1) |
| `CatedraPhoneAdapter` | Implementa ese puerto (P1) |
| `CheckTechnicalIntegrationUseCase` e `Impl` | `canSubmit`: `TechnicalSession` lleva el JWT técnico, que no debe salir de `catedra` |
| `SubmitAdditionalInformationUseCase` e `Impl` | Arma el mensaje con la sesión vigente; así el JWT y la integración no salen de `catedra` |
| `AdditionalInformationSubmission` | El dato que viaja del caso de uso al puerto, sin Kafka |
| `AdditionalInformationSubmissionResult` | Tipo sellado de lo que devuelve el caso de uso, como `HoldConfirmationResult`: `Published` o `NotPublished(reason)` |
| `AdditionalInformationPublisher` | Puerto de salida: `application` no puede conocer Kafka |
| `KafkaAdditionalInformationPublisher` | Único lugar que conoce el productor; hace los intentos |
| `dto/AdditionalInformationSubmittedMessage` | El JSON exacto del anexo §15.5 |
| `PhoneSubmissionRequest` | El body del endpoint |
| `ReservationInvalidPhoneNumberException`, `ReservationPhoneRequestNotReadyException` | Los dos códigos nuevos del controller (RF-04, RF-05) |

## Slices y componentes afectados

<!-- Aplica la skill hexagonal-vertical-slicing: un paquete por slice, con sus
capas domain, application e infrastructure. Identifica los slices afectados, sus
responsabilidades y la dirección de sus dependencias. Una funcionalidad puede
afectar más de un slice. Justifica cualquier slice nuevo o dependencia nueva
entre slices. -->

| Slice | Existe / nuevo | Responsabilidad y cambios | Dependencias con otros slices |
| --- | --- | --- | --- |
| `turnos.reservation` | Existente | Caso de uso T06, endpoint, teléfono en el proceso | `reservation` → `catedra` por `infrastructure/gateway`, igual que T04 y T05 (decisión 009; no es una dependencia nueva) |
| `turnos.catedra` | Existente | Comprobar la integración y publicar | No conoce a `reservation` |

<!-- Distingue lo que se reutiliza, modifica o crea. Las rutas nuevas son
propuestas. Señala impacto sobre modelos, contratos o componentes compartidos. -->

| Slice y capa | Componente o ruta | Acción | Cambio y responsabilidad | Requisito relacionado |
| --- | --- | --- | --- | --- |
| `reservation` dominio | `domain/model/ReservationPhoneNumber.java` | Crear | `parse`: quita los cinco caracteres y exige `^\+?[0-9]{7,15}$` con `matches`; `toString` oculto | RF-05, RF-13 |
| `reservation` dominio | `domain/model/ReservationProcess.java` | Modificar | `phoneNumber`; `canSubmitPhone`, `submitPhone`, `revertPhoneSubmission`, que valida el estado y enciende la marca | RF-02, RF-06, RF-09 |
| `reservation` dominio | `domain/ports/in/SubmitPhoneUseCase.java` | Crear | Puerto de entrada | RF-01 a RF-16 |
| `reservation` dominio | `domain/ports/out/PhoneSubmitter.java` | Crear | `canSubmit()` y `submit(...)`; `submit` lanza `ReservationCentralUnavailableException` | RF-07, RF-09, RF-16 |
| `reservation` aplicación | `application/usecases/SubmitPhoneUseCaseImpl.java` | Crear | El flujo completo | RF-01 a RF-16 |
| `reservation` aplicación | `application/service/ReservationService.java` | Modificar | `submitPhone` | RF-01 |
| `reservation` aplicación | `application/exception/ReservationInvalidPhoneNumberException.java`, `ReservationPhoneRequestNotReadyException.java` | Crear | Dos códigos nuevos | RF-04, RF-05 |
| `reservation` infraestructura | `infrastructure/gateway/CatedraPhoneAdapter.java` | Crear | Traduce entre `catedra` y `reservation`; registra el motivo | RF-07, RF-09, RF-16 |
| `reservation` infraestructura | `infrastructure/persistence/entity/ReservationProcessEntity.java`, `mapper/ReservationProcessMapper.java` | Modificar | `phoneNumber` | RF-06 |
| `reservation` infraestructura | `src/main/resources/db/migration/V6__add_reservation_process_phone_number.sql` | Crear | `phone_number VARCHAR(16)`, vacía en los procesos que ya existen | RF-06 |
| `reservation` infraestructura | `infrastructure/web/controller/ReservationProcessController.java` | Modificar | `POST /api/reservation-processes/{id}/phone` y sus errores | RF-01 a RF-09, RF-14, RF-16 |
| `reservation` infraestructura | `infrastructure/web/dto/PhoneSubmissionRequest.java` | Crear | `phoneNumber` con `@NotNull` y mensaje en inglés | RF-05 |
| `catedra` dominio | `domain/ports/in/CheckTechnicalIntegrationUseCase.java`, `SubmitAdditionalInformationUseCase.java` | Crear | Puertos de entrada | RF-07, RF-16 |
| `catedra` dominio | `domain/ports/out/AdditionalInformationPublisher.java` | Crear | Puerto de salida | RF-07, RF-09 |
| `catedra` dominio | `domain/model/AdditionalInformationSubmission.java`, `AdditionalInformationSubmissionResult.java` | Crear | Lombok sin `toString`; tipo sellado | RF-07, RF-13 |
| `catedra` aplicación | `application/usecases/CheckTechnicalIntegrationUseCaseImpl.java` | Crear | Sesión habilitada o intento de renovarla; registra el motivo | RF-16 |
| `catedra` aplicación | `application/usecases/SubmitAdditionalInformationUseCaseImpl.java` | Crear | `eventId` (`phone-submitted-` + UUID), `occurredAt`, `groupId` y topic de la sesión vigente | RF-07, RF-09 |
| `catedra` infraestructura | `infrastructure/kafka/KafkaAdditionalInformationPublisher.java` | Crear | Productor e intentos (P2) | RF-07, RF-09, RF-10, RF-15 |
| `catedra` infraestructura | `infrastructure/kafka/dto/AdditionalInformationSubmittedMessage.java` | Crear | JSON del anexo §15.5 | RF-07 |
| `catedra` infraestructura | `infrastructure/kafka/CatedraKafkaProperties.java` | Modificar | Sub-record `Publish`; método que arma la configuración de seguridad, movido del manager | RF-15 |
| `catedra` infraestructura | `infrastructure/kafka/KafkaConsumerManager.java` | Modificar | Usa ese método, sin cambiar su comportamiento | Restricciones |
| Configuración | `src/main/resources/application.yaml`, `.env.example` | Modificar | `turnos.catedra.kafka.publish` | RF-15 |
| Documentación | `CLAUDE.md`, `docs/decisiones/014-kafka-con-la-catedra.md`, `src/test/resources/catedra/README.md` | Modificar | Al cerrar | — |

## Datos y contratos

<!-- Completa solo lo aplicable. Si un punto no aplica, indica el motivo. -->

- **Operaciones, eventos y DTO de entrada y salida:** contrato §3.6 y §4.3.3 y anexo §15.5. La respuesta es `ReservationProcessResponse`, sin cambios. Un `phoneNumber` que no es texto: lo que haga Jackson se comprueba con un test (TASK de la web); un número JSON puede convertirse en texto y pasar por la validación de formato, sin efecto distinto.
- **Identificadores, relaciones y restricciones:** `eventId` = `phone-submitted-` + UUID, generado una vez por envío. Clave del mensaje = `reservationProcessId`.
- **Origen de los datos devueltos o publicados y transformaciones:** `holdId`, `requestEventId` y `reservationProcessId`, del proceso; `groupId` y topic, de la sesión vigente; `occurredAt`, del `Clock`, fijo para todos los intentos de ese envío; `phoneNumber`, normalizado.
- **Persistencia, consultas y migraciones:** `V6`, una columna nullable de hasta 16 caracteres, que es el máximo tras normalizar. Sin consultas nuevas. Se conserva la escritura del proceso completo con `update`.
- **Convivencia entre datos locales y remotos:** el proceso local queda en `PHONE_SUBMITTED` antes de saber el resultado (D0). No se consulta a la cátedra.
- **Compatibilidad y migraciones de datos existentes:** los procesos existentes quedan con `phone_number` vacío.

## Transacciones, concurrencia y errores

<!-- Cómo se implementan los comportamientos aprobados en la spec.
Referencia RF/CA y aplica las consideraciones relevantes de BACKEND_GUIDELINES.md. -->

- **Estados y transiciones:** en el modelo. `submitPhone` exige `WAITING_FOR_PHONE`, guarda el teléfono y borra el motivo. `revertPhoneSubmission` exige `PHONE_SUBMITTED`, vuelve a `WAITING_FOR_PHONE` y enciende la marca, sin restaurar nada (D2). El `close` de T08 no cambia.
- **Transacciones y consistencia:** cada `update` es su propia transacción: no hay escritura después de publicar (D1) y `TransactionRunner` no hace falta. Una falla de la base al guardar se envuelve en `ReservationNotSavedException`, con un `log.error`, y el controller responde `INTERNAL_ERROR` (RF-06). Una falla en `revertPhoneSubmission` se registra y lanza `ReservationLocalStateOutOfSyncException` (RF-14).
- **Idempotencia y mensajes duplicados:** el segundo envío encuentra `PHONE_SUBMITTED` y recibe `409`. Los reintentos internos llevan el mismo `eventId` y contenido; el `ProducerRecord` se arma una vez (CA-09).
- **Concurrencia y tareas programadas:** todo lo posterior a `ReservationPhoneNumber.parse` va dentro de `UserOperationLock` (D4). Sin tareas programadas.
- **Timeouts, reintentos y errores de dependencias (P2):**
  - Propiedades nuevas en `turnos.catedra.kafka.publish`: `timeout` 10 s (el tope de RF-15), `attempt-timeout` 2,5 s, `max-attempts` 3, `initial-delay` 500 ms, `multiplier` 2. En el peor caso: 3 × 2,5 s + 0,5 s + 1 s = 9 s.
  - Se configura `max.block.ms`, `request.timeout.ms` y `delivery.timeout.ms` iguales a `attempt-timeout` y `linger.ms=0`, porque por defecto el cliente puede bloquear hasta 60 s. `acks=all`; la idempotencia queda en su valor por defecto.
  - Un intento no empieza si ya no entra en el tope.
  - Solo se reintentan las `RetriableException` (incluido el timeout); cualquier otra, incluidas las de autenticación y autorización, corta sin reintentar y se registra (RF-10).
  - Cada intento fallido queda en el log, con `eventId` y `reservationProcessId`, porque el anexo §18.4 pide reintentos observables.
  - Toda excepción del productor se traduce a `NotPublished`, y el motivo que llega al log no incluye el contenido del mensaje.
- **Seguridad y autorización:** JWT y dueño, como T05. El teléfono nunca va a logs: `ReservationPhoneNumber` oculta su `toString`, `ReservationProcess` y `AdditionalInformationSubmission` no generan `toString`, y `PhoneSubmissionRequest` tampoco lo tiene.
- **Otras consideraciones de backend aplicables y su solución:** el productor se crea en el primer envío, porque los servidores llegan con el login, se cierra con `@PreDestroy` y conserva los servidores de esa primera creación hasta reiniciar, como el consumo (014 §3.5, P3). El topic y el `groupId` salen de la sesión vigente en cada envío. `turnos.catedra.kafka.enabled` solo apaga el consumo, como dice su nombre en 014 §3.11: sin `requestEventId` no se llega a publicar.

## Dependencias y configuración

<!-- Librerías, servicios externos, propiedades de configuración y secretos
afectados. Verifica compatibilidad con el proyecto y justifica las incorporaciones.
No agregues dependencias por defecto. Los valores sensibles y los parámetros
ajustables se externalizan. -->

- Sin dependencias nuevas: `kafka-clients` 4.2.1 ya llega con `spring-boot-starter-kafka` (verificado), incluidos `KafkaProducer` y `MockProducer`.
- Variables opcionales nuevas: `TURNOS_CATEDRA_KAFKA_PUBLISH_TIMEOUT`, `…_ATTEMPT_TIMEOUT`, `…_MAX_ATTEMPTS`, `…_INITIAL_DELAY` y `…_MULTIPLIER`. Sin secretos nuevos.

## Estrategia de validación

<!-- Una fila por criterio de la spec. Selecciona el método capaz de demostrarlo:
test unitario, de integración (base de datos, mensajería), de API o prueba manual.
No todos requieren todos los métodos. Identifica tests existentes y separa los
nuevos propuestos. Incluye regresiones relevantes. Una prueba con mocks no
demuestra persistencia, transacciones ni idempotencia. -->

Test de integración nuevo: `reservation/PhoneSubmissionKafkaIntegrationTest` (aplicación completa, Postgres y Kafka de Testcontainers, cátedra simulada). `KafkaTestTopic` suma leer lo publicado, y `FakeCatedraServer` recibe el topic de teléfono.

| Criterio | Método y test existente o propuesto | Entorno y datos necesarios | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01 | `PhoneSubmissionKafkaIntegrationTest`; comparación con `catedra/kafka/additional-information-submitted.json`, armado desde el anexo | App completa | `200`, fila en la base y mensaje leído del topic |
| CA-02 | `ReservationProcessControllerTest` (`401`) e integración (`404` iguales, topic vacío) | MockMvc y app completa | Respuestas y `endOffset` |
| CA-03 | `SubmitPhoneUseCaseImplTest` con un estado por caso; `ReservationProcessControllerTest` | Dobles y web | `409` con `currentStatus`, sin llamar al publicador |
| CA-04 | `SubmitPhoneUseCaseImplTest` con `MutableClock` | Dobles | `EXPIRED`; dentro del margen, publica |
| CA-05 | `SubmitPhoneUseCaseImplTest`, controller | Dobles y web | `409` |
| CA-06 | `ReservationPhoneNumberTest`; `SubmitPhoneUseCaseImplTest` comprueba que no toca el repositorio; `ReservationProcessControllerTest` | Unitario y web | Tabla de formatos y `verifyNoInteractions` |
| CA-07 | `CheckTechnicalIntegrationUseCaseImplTest`, `SubmitPhoneUseCaseImplTest`, controller | Dobles y web | `503`, sin cambios, motivo en el log |
| CA-08 | Integración con `KafkaTestContainer.pause`/`unpause` | App completa | Intentos en el log, `503`, fila en la base y nuevo envío con otro `eventId` |
| CA-09 | `KafkaAdditionalInformationPublisherTest` con `MockProducer` | Unitario | Registros enviados iguales |
| CA-10 | `KafkaAdditionalInformationPublisherTest` (`AuthenticationException`), sin login | Unitario. **Con un doble:** el Kafka de tests no pide autenticación (D6) | Un solo intento |
| CA-11 | `SubmitPhoneUseCaseImplTest` con un repositorio que falla, controller. Además, integración con un repositorio controlable | Dobles, web y app completa | `500` `INTERNAL_ERROR`, nada publicado |
| CA-12 | Igual que CA-11, con Kafka pausado y el guardado de vuelta atrás fallando | Dobles, web y app completa | `500` `LOCAL_STATE_OUT_OF_SYNC`, error grave en el log |
| CA-13 | Integración: proceso escrito en la base en `PHONE_SUBMITTED` sin publicar; nuevo envío. No hay nada que correr al arrancar (D1) | App completa | `409`, sin marca, topic vacío |
| CA-14 | `SubmitPhoneUseCaseImplTest` con `CopyingReservationProcessRepository` y publicador demorado; integración con dos pedidos simultáneos | Dobles y app completa | Uno `200`, otro `409`, un mensaje |
| CA-15 | Caso de uso de T06 demorado junto con `ProcessReservationResultUseCaseImpl` y el mismo candado | Dobles | Orden correcto |
| CA-16 | Integración con Kafka pausado y tiempo medido; `KafkaAdditionalInformationPublisherTest` con el tope reducido | App completa y unitario | Duración medida ≤ tope |
| CA-17 | Salida capturada de los escenarios de integración, buscando el número | App completa | Sin teléfono ni tokens |
| CA-18 | `PhoneSubmissionKafkaIntegrationTest`; reemplaza el recorrido de `ReservationResultKafkaIntegrationTest` que escribe `PHONE_SUBMITTED`. Los demás tests siguen escribiendo el estado de partida en la base | App completa | Base y topic en cada paso |
| CA-19 | Suite completa | `./mvnw clean verify` | Conteo y JaCoCo |
| CA-20 | `ReservationProcessE2ETest` ampliado, corrido por el usuario | Cátedra real | **Bloqueado:** la cátedra no publica el pedido. Se registra como no verificado |

**Comprobaciones de regresión:** `KafkaConsumerManagerTest` sigue pasando con la seguridad movida (el `SASL` y que la contraseña no salga al log). Round-trip de `ReservationProcessMapperTest` con `phoneNumber`. Pruebas en negativo en lo que no se puede ver de otra forma: el orden de guardar y publicar, la vuelta atrás y la ausencia del teléfono en el log.

**Comandos verificados para compilar y ejecutar tests:** `./mvnw test -Dtest=NombreDeLaClase`, `./mvnw clean test` y `./mvnw clean verify` (`CLAUDE.md`). El conteo se hace contando `<testcase>` en los XML.

**Pruebas con infraestructura real o simulada:** Postgres y Kafka reales en Testcontainers; cátedra REST simulada con `FakeCatedraServer`. La suite normal nunca usa la cátedra real. El E2E lo corre el usuario, con la lista de requests confirmada antes.

**Limitaciones del entorno:** no se comprueba contra el Kafka real (D8): el topic de teléfono, que acepte lo publicado, que la cátedra lo consuma ni un rechazo real de credenciales. La caída real del servicio entre guardar y publicar no se provoca: CA-13 parte del estado que dejaría.

<!-- Esta sección planifica la validación. Durante la implementación, registra
en TASKS.md o en el informe de validación acordado los resultados y evidencias
reales. Distingue pruebas ejecutadas, fallidas, no ejecutadas y bloqueadas.
Compilar o tener tests en verde no sustituye revisar los criterios de la spec. -->

## Orden de implementación

<!-- Etapas y dependencias principales. El desglose ejecutable se escribe en TASKS.md.
Incluye puntos de comprobación para avanzar con cambios pequeños. -->

1. **Modelo y persistencia:** `ReservationPhoneNumber`, transiciones, `phoneNumber`, entidad, mapper y `V6`. Comprobación: tests del modelo, del mapper y la migración contra Postgres.
2. **Publicación en `catedra`:** propiedades, seguridad compartida, publicador con `MockProducer` y prueba contra Kafka en contenedor. Comprobación: `KafkaConsumerManagerTest` sin cambios de comportamiento.
3. **Casos de uso de `catedra`:** comprobar la integración y armar el envío.
4. **Caso de uso de T06:** puerto, gateway y `SubmitPhoneUseCaseImpl`. Comprobación: CA-03 a CA-07, CA-11, CA-12, CA-14 y CA-15 con dobles.
5. **Web:** DTO, endpoint y errores. Comprobación: `ReservationProcessControllerTest`.
6. **Integración:** `PhoneSubmissionKafkaIntegrationTest` y reemplazo del recorrido de T08.
7. **E2E:** ampliar, sin correrlo contra la cátedra hasta confirmar la lista de requests.
8. **Cierre:** `CLAUDE.md`, decisión 014, `./mvnw clean verify` y verificación de la skill §12.

## Riesgos y decisiones pendientes

<!-- Riesgos concretos de esta solución y cómo se resolverán, sin listas genéricas.
Escribe Ninguna en las decisiones pendientes cuando estén resueltas. -->

- **Riesgos y medidas acordadas:**
  - Un intento de 2,5 s puede quedar corto contra el Kafka real, sobre todo en el primer envío, que también pide los metadatos. Efecto: un `503` de más, y el reintento del usuario encuentra el productor ya conectado. Es configurable; sin evidencia real (D8).
  - Con un timeout, el mensaje puede haber salido y se reenvía con el mismo `eventId`. El anexo §15.1 lo prevé, pero que la cátedra descarte repetidos no está verificado.
  - Si la cátedra pidiera idempotencia con permisos que no da, publicar fallaría siempre; hoy no hay control de acceso (014 §3.1). Sin verificar.
- **Decisiones pendientes:** Ninguna.
- **Decisiones tomadas (2026-11-02):**
  - **P1 · Puerto y gateway de `reservation` (opción a, decidida por el usuario):** un puerto y un gateway nuevos, `PhoneSubmitter` y `CatedraPhoneAdapter`, porque informar el teléfono no es una operación de un hold. Alternativa descartada: (b) ampliar `HoldProvider` y `CatedraHoldAdapter`, que ahorra dos archivos, pero deja nombres que no describen lo que hacen o exige renombrarlos.
  - **P2 · Cliente del productor e intentos (opción a, decidida por el usuario):** `KafkaProducer` de `kafka-clients`, con intentos propios en el adaptador, que dejan cada fallo en el log y se prueban con `MockProducer`. Alternativas descartadas: (b) `KafkaTemplate`, porque publicar no tiene la dificultad que justificó spring-kafka en el consumo (014 §3.8), hay que desenvolver sus excepciones y `MockProducer` lleva más armado; (c) dejar los reintentos solo al cliente, que no permite verlos ni contarlos y obliga a cambiar RF-09 y CA-08.
  - **P3 · Servidores del productor (opción a, decidida por el usuario):** el productor conserva la dirección de su creación hasta reiniciar, como el consumo (014 §3.5). Motivo: si la dirección cambia, hay que reiniciar de todos modos para que vuelva el consumo. Limitación aceptada: hasta reiniciar, T06 responde `503`. Alternativa descartada: (b) crear otro productor cuando cambia, que publicaría en el Kafka nuevo mientras el consumo sigue en el viejo: el resultado no llegaría nunca y el proceso quedaría trabado en `PHONE_SUBMITTED`.

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el plan cubre los requisitos, respeta las exclusiones, reutiliza
componentes verificados, respeta la skill hexagonal-vertical-slicing y permite
demostrar todos los criterios de aceptación.
Resuelve dudas y marcadores pendientes. Si la spec cambió, revisa su impacto.
Tras aprobar el plan, deriva TASKS.md con IDs, dependencias, referencias a RF/CA
y comprobaciones. No marques una tarea terminada sin realizar su validación;
si está bloqueada, registra el motivo.
-->
