# PLAN: T08 — Procesar resultado de la reserva

**SPEC de referencia:** `docs/sdd/t08-resultado-reserva/SPEC.md`
**Versión de la spec revisada:** aprobada el 2026-11-02, sin commitear todavía
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
| Consumo de Kafka | `catedra/infrastructure/kafka/KafkaConsumerManager.java`, `MessageRetryBackOff.java`, `CatedraKafkaProperties.java` | Arma el consumo y maneja errores; `CatedraEventNotSavedException` se reintenta sin fin. No cambian |
| Oyente y lector | `catedra/infrastructure/kafka/AppointmentActionListener.java`, `AppointmentActionReader.java`, `AppointmentAction.java`, `dto/AppointmentActionMessage.java` | El lector descarta los cinco eventos ("is not processed yet"). Se amplían (RF-01, RF-02, RF-17) |
| Entrada de `catedra` | `catedra/application/service/AdditionalInformationRequestService.java`, `application/usecases/ReceiveAdditionalInformationRequestUseCaseImpl.java`, `domain/ports/out/AdditionalInformationRequestHandler.java`, `domain/model/AdditionalInformationRequestResult.java` | Camino del pedido de teléfono: el patrón que sigue T08 (P2) |
| Gateway | `reservation/infrastructure/gateway/CatedraPhoneRequestAdapter.java` | Traduce modelo y resultado entre `catedra` y `reservation`; patrón a seguir (P2) |
| Caso de uso de T07 | `reservation/application/usecases/ProcessPhoneRequestUseCaseImpl.java`, `domain/model/PhoneRequestOutcome.java` | Recorrido a seguir: buscar, candado del dueño, ¿ya procesado?, volver a leer, `holdId`, estado, transacción |
| Proceso de reserva | `reservation/domain/model/ReservationProcess.java`, `ReservationStatus.java` | Falta `CONFIRMED`, `CANCELLED`, `INVALID` y cinco campos. `expire` solo admite `HELD` y `WAITING_FOR_PHONE`, y `expireIfOverdue` también: `PHONE_SUBMITTED` no se cierra por reloj (regla 8) |
| Persistencia | `reservation/infrastructure/persistence/entity/ReservationProcessEntity.java`, `mapper/ReservationProcessMapper.java`, `adapter/JpaReservationProcessRepositoryAdapter.java`; `src/main/resources/db/migration/V2` a `V4` | `update` reescribe la fila entera. `OPEN_STATUSES` se calcula del enum: los estados nuevos no se suman solos a los abiertos porque `isOpen` los excluye. Índice parcial de un abierto por usuario |
| Idempotencia y atomicidad | `reservation/domain/ports/out/ProcessedEventRepository.java`, `TransactionRunner.java`, `infrastructure/persistence/adapter/SpringTransactionRunnerAdapter.java` | Se reutilizan sin cambios. Una falla de la base llega como `ReservationNotSavedException` |
| Candado | `reservation/application/support/UserOperationLock.java` | Se reutiliza (RF-13) |
| Respuestas web | `reservation/infrastructure/web/mapper/ReservationProcessDtoMapper.java`, `dto/ReservationProcessResponse.java`, `web/controller/ReservationProcessController.java` | El DTO ya tiene los campos nuevos y el mapper no los llena (T09, fuera de alcance). `expiresAt` en `null` si no está abierto. `INVALID_PROCESS_STATE` usa `currentStatus` con cualquier estado, sin `switch` (CA-22) |
| Mensajes de prueba | `src/test/resources/catedra/kafka/` | Los cinco de resultado, armados desde el anexo |
| Tests de referencia | `src/test/java/turnos/reservation/PhoneRequestKafkaIntegrationTest.java`, `application/usecases/ProcessPhoneRequestUseCaseImplTest.java`, `CopyingReservationProcessRepository.java`, `InMemoryProcessedEventRepository.java`; `catedra/infrastructure/kafka/AppointmentActionReaderTest.java`, `AppointmentActionListenerTest.java` | Se amplían o se toman de modelo |

**Convenciones y slice de referencia:** el camino de T07 en `catedra` y `reservation`, con las excepciones a la skill de `CLAUDE.md`. Decisiones de Kafka: `docs/decisiones/014-kafka-con-la-catedra.md`. La dependencia entre slices ya está decidida (P1 y P10 de T07): no cambia.

## Solución propuesta

<!-- Explica el enfoque y sus motivos. Describe las responsabilidades y el
recorrido de la solicitud, el evento o la tarea desde que entra hasta la
respuesta, la persistencia o el mensaje publicado. Referencia el diagrama de
secuencia del proyecto si ya lo describe; usa un diagrama nuevo solo si aporta
claridad. -->

El recorrido sigue T08 y la secuencia §4 (bloque T08), por el mismo camino de T07 (D1, P2).

```
Kafka, topic de acciones
  AppointmentActionListener
    ├─ AppointmentActionReader: eventType, campos según D8, schemaVersion, largo de los identificadores (RF-02, RF-17)
    │    inválido o desconocido → log y termina (el offset se confirma)
    ├─ pedido de teléfono → camino de T07, sin cambios
    └─ resultado válido (AppointmentResult) → CatedraEventService
         └─ ReceiveAppointmentResultUseCase = …UseCaseImpl
              └─ CatedraEventHandler (puerto de salida de catedra)
                   = CatedraEventAdapter (reservation/infrastructure/gateway)
                     └─ ReservationService → ProcessReservationResultUseCase = …UseCaseImpl
                          ¿existe el proceso? → candado del dueño → ¿ya procesado? → volver a leer → holdId
                          → ¿el evento se aplica en este estado?
                               no → solo registra el eventId (RF-05, RF-07)
                               sí → transición en el modelo → transacción: proceso + eventId (RF-11)
```

Cómo vuelve el resultado: igual que T07. `reservation` devuelve qué pasó o lanza `ReservationNotSavedException`; el gateway lo traduce a un resultado de `catedra`; con "no se guardó", `catedra` lanza `CatedraEventNotSavedException`, que el consumidor reintenta sin fin (RF-12).

## Slices y componentes afectados

<!-- Aplica la skill hexagonal-vertical-slicing: un paquete por slice, con sus
capas domain, application e infrastructure. Identifica los slices afectados, sus
responsabilidades y la dirección de sus dependencias. Una funcionalidad puede
afectar más de un slice. Justifica cualquier slice nuevo o dependencia nueva
entre slices. -->

| Slice | Existe / nuevo | Responsabilidad y cambios | Dependencias con otros slices |
| --- | --- | --- | --- |
| `turnos.catedra` | Existente | Lee y valida los cinco eventos; los entrega por su aplicación y un puerto de salida | Ninguna nueva |
| `turnos.reservation` | Existente | Caso de uso de T08, estados, datos y transiciones nuevas, migración, adaptador del puerto de `catedra` | La misma: `reservation` → `catedra` |

No hay slices nuevos.

<!-- Distingue lo que se reutiliza, modifica o crea. Las rutas nuevas son
propuestas. Señala impacto sobre modelos, contratos o componentes compartidos. -->

| Slice y capa | Componente o ruta | Acción | Cambio y responsabilidad | Requisito relacionado |
| --- | --- | --- | --- | --- |
| `catedra` dominio | `domain/model/AppointmentResult.java` | Crear | Tipo sellado con las cinco variantes, ya validadas (P1) | RF-01, RF-02 |
| `catedra` dominio | `domain/model/AdditionalInformationRequestResult.java` → `CatedraEventResult.java` | Renombrar | Lo comparten los seis eventos (P2) | RF-12 |
| `catedra` dominio | `domain/ports/out/AdditionalInformationRequestHandler.java` → `CatedraEventHandler.java` | Renombrar y modificar | Suma el método de los resultados (P2) | RF-01, RF-12 |
| `catedra` dominio | `domain/ports/in/ReceiveAppointmentResultUseCase.java` | Crear | Puerto de entrada: recibir un resultado | RF-01 |
| `catedra` aplicación | `application/usecases/ReceiveAppointmentResultUseCaseImpl.java` | Crear | Lo entrega al puerto de salida; si no se guardó, lanza `CatedraEventNotSavedException` | RF-12 |
| `catedra` aplicación | `application/usecases/ReceiveAdditionalInformationRequestUseCaseImpl.java` | Modificar | Solo los nombres nuevos (P2) | — |
| `catedra` aplicación | `application/service/AdditionalInformationRequestService.java` → `CatedraEventService.java` | Renombrar y modificar | Suma el método de los resultados (P2) | RF-01 |
| `catedra` infraestructura | `infrastructure/kafka/dto/AppointmentActionMessage.java` | Modificar | Suma `reason`, `reservationId`, `professionalId`, `date`, `startTime` y `occurredAt`, y `expiresAt` pasa a texto (P3) | RF-02 |
| `catedra` infraestructura | `infrastructure/kafka/AppointmentActionReader.java` | Modificar | Valida cada tipo con la tabla de D8; convierte solo los campos que ese tipo usa (P3); rechaza identificadores de más de 255 caracteres, también en el pedido de teléfono; arma el resultado | RF-01, RF-02, RF-17 |
| `catedra` infraestructura | `infrastructure/kafka/AppointmentAction.java`, `AppointmentActionListener.java` | Modificar | Suma el caso del resultado y lo entrega al service | RF-01 |
| `reservation` dominio | `domain/model/ReservationStatus.java` | Modificar | Suma `CONFIRMED`, `CANCELLED`, `INVALID`; `isOpen` no cambia | RF-05 a RF-10 |
| `reservation` dominio | `domain/model/ReservationProcess.java` | Modificar | Campos `reservationId`, `confirmedAt`, `cancelledAt`, `cancellationReason`, `rejectionReason`; una transición por evento, con su validación; todos los cierres borran el motivo y apagan la marca, también `expire` y `expireIfOverdue`; recorte a 500 caracteres | RF-05 a RF-10, RF-15, RF-16, D4 |
| `reservation` dominio | `domain/model/ReservationResult.java` | Crear | Copia propia del tipo sellado (P1, decisión 009) | RF-03 a RF-11 |
| `reservation` dominio | `domain/model/PhoneRequestOutcome.java` → `ReservationEventOutcome.java` | Renombrar | Qué pasó con un evento; lo comparten T07 y T08 (P2) | RF-03 a RF-05 |
| `reservation` dominio | `domain/ports/in/ProcessReservationResultUseCase.java` | Crear | Puerto de entrada de T08 | RF-03 a RF-11 |
| `reservation` aplicación | `application/usecases/ProcessPhoneRequestUseCaseImpl.java`, `domain/ports/in/ProcessPhoneRequestUseCase.java` | Modificar | Solo el nombre nuevo del resultado (P2) | — |
| `reservation` aplicación | `application/usecases/ProcessReservationResultUseCaseImpl.java` | Crear | Recorrido de "Transacciones, concurrencia y errores"; los warning y el error de D3, D4 y D6 | RF-03 a RF-14 |
| `reservation` aplicación | `application/service/ReservationService.java` | Modificar | Suma el método de T08 | RF-01 |
| `reservation` infraestructura | `infrastructure/gateway/CatedraPhoneRequestAdapter.java` → `CatedraEventAdapter.java` | Renombrar y modificar | Implementa `CatedraEventHandler`: suma la traducción de los resultados (P2) | RF-01, RF-12 |
| `reservation` infraestructura | `infrastructure/persistence/entity/ReservationProcessEntity.java`, `mapper/ReservationProcessMapper.java` | Modificar | Los cinco campos nuevos | RF-06, RF-07, RF-10 |
| `reservation` infraestructura | `src/main/resources/db/migration/V5__add_reservation_result_fields.sql` | Crear | Columnas nuevas | RF-06, RF-07, RF-10 |
| Documentación | `CLAUDE.md`, `docs/decisiones/014-kafka-con-la-catedra.md` | Modificar | Slices, Kafka y tests; decisiones de este PLAN | — |

Los nombres son propuestas y pueden ajustarse al implementar, sin cambiar las responsabilidades.

## Datos y contratos

<!-- Completa solo lo aplicable. Si un punto no aplica, indica el motivo. -->

- **Operaciones, eventos y DTO de entrada y salida:** se consumen los cinco eventos de contrato §4.3.4 y anexo §15.6 a §15.10, con los campos obligatorios de D8; los demás se ignoran. No se publica nada ni cambia ninguna respuesta de la API.
- **Identificadores, relaciones y restricciones:** el proceso se busca por `reservationProcessId` y se compara el `holdId`, como T07. `requestEventId`, `groupId` y `status` del evento no se leen.
- **Origen de los datos devueltos o publicados y transformaciones:** `confirmedAt` y `cancelledAt` son el `occurredAt` del evento; `rejectionReason` y `cancellationReason`, su `reason`, recortado a 500 caracteres (D4); `processedAt`, el reloj del servicio.
- **Persistencia, consultas y migraciones:** `V5` suma `reservation_id BIGINT`, `confirmed_at TIMESTAMPTZ`, `cancelled_at TIMESTAMPTZ`, `cancellation_reason VARCHAR(500)` y `rejection_reason VARCHAR(500)`, todas opcionales. `status` es `VARCHAR(30)`: entran los valores nuevos, sin migración. El índice parcial no cambia: los estados nuevos no son abiertos. `expires_at` sigue siendo obligatorio: un proceso cerrado conserva el valor y el mapper web ya no lo muestra.
- **Convivencia entre datos locales y remotos:** la cátedra es la fuente de verdad (regla 15); un evento tardío no reabre un estado final (anexo §18.3).
- **Compatibilidad y migraciones de datos existentes:** los procesos existentes quedan con los campos nuevos vacíos; `V2` a `V4` no cambian.

## Transacciones, concurrencia y errores

<!-- Cómo se implementan los comportamientos aprobados en la spec.
Referencia RF/CA y aplica las consideraciones relevantes de BACKEND_GUIDELINES.md. -->

- **Estados y transiciones:** en el modelo, cada una valida el estado de origen y lanza `IllegalStateException` si no corresponde, como las existentes.

  | Evento | Se aplica desde | Queda | Además |
  | --- | --- | --- | --- |
  | `AppointmentConfirmed` | Abierto | `CONFIRMED` | `reservationId`, `confirmedAt`; actualiza los datos del turno que traiga (D3); apaga la marca y borra el motivo |
  | `AdditionalInformationRejected` | `PHONE_SUBMITTED` | `WAITING_FOR_PHONE` | `expiresAt`, `rejectionReason`; no toca `requestEventId` ni la marca |
  | `AppointmentProcessExpired` | Abierto | `EXPIRED` | Apaga la marca y borra el motivo. Es una transición nueva: `expire` no admite `PHONE_SUBMITTED` |
  | `AppointmentProcessInvalid` | Abierto | `INVALID` | Enciende la marca y borra el motivo |
  | `AppointmentCancelled` | Abierto o `CONFIRMED` | `CANCELLED` | `cancelledAt`, `cancellationReason`; apaga la marca y borra el motivo; conserva `reservationId` y `confirmedAt` |

- **Transacciones y consistencia:** recorrido del caso de uso: (1) `findById`; si no existe y el `eventId` ya se procesó, ya procesado; si no, huérfano (sin registrar); (2) candado del dueño; (3) ¿`eventId` ya procesado?; (4) vuelve a leer el proceso; `holdId` distinto → inconsistencia (sin registrar); (5) si el evento no se aplica en este estado, registra solo el `eventId` con `TransactionRunner` y termina (RF-05, RF-07; a diferencia de T07, sí se registra); (6) si se aplica, transición y, con `TransactionRunner`, guarda el proceso y registra el evento: los dos o ninguno (RF-11, CA-13). El offset se confirma cuando todo terminó.
- **Idempotencia y mensajes duplicados:** como T07: chequeo dentro del candado y clave primaria de `processed_events` como respaldo.
- **Concurrencia y tareas programadas:** el mismo `UserOperationLock` (RF-13); un solo hilo de consumo. La transacción va dentro del candado (P4 de T07). Sin control de versión (P5 de T07).
- **Timeouts, reintentos y errores de dependencias:** los de T07, sin cambios (decisión 014 §3.3 y §3.10). Un valor demasiado largo no llega a la base: lo cortan el lector (255) y el modelo (500), así que no puede frenar el consumo (RF-17).
- **Seguridad y autorización:** sin endpoint. Kafka sin credenciales, verificado en T07.
- **Otras consideraciones de backend aplicables y su solución:** observabilidad: warning con D3 y D4; error con D6 (`reservationProcessId` y `reservationId`); log de cada descarte con `eventId` y motivo (RF-14).

## Dependencias y configuración

<!-- Librerías, servicios externos, propiedades de configuración y secretos
afectados. Verifica compatibilidad con el proyecto y justifica las incorporaciones.
No agregues dependencias por defecto. Los valores sensibles y los parámetros
ajustables se externalizan. -->

Sin dependencias, propiedades ni secretos nuevos. Los límites de 255 y 500 caracteres no son configurables: dependen del tamaño de las columnas y del dominio.

## Estrategia de validación

<!-- Una fila por criterio de la spec. Selecciona el método capaz de demostrarlo:
test unitario, de integración (base de datos, mensajería), de API o prueba manual.
No todos requieren todos los métodos. Identifica tests existentes y separa los
nuevos propuestos. Incluye regresiones relevantes. Una prueba con mocks no
demuestra persistencia, transacciones ni idempotencia. -->

Tests propuestos: `ReservationProcessTest`, `AppointmentActionReaderTest`, `AppointmentActionListenerTest`, `ReservationProcessMapperTest`, `JpaReservationProcessRepositoryAdapterTest` y `ReservationProcessDtoMapperTest` (ampliados); `ProcessReservationResultUseCaseImplTest`, `ReceiveAppointmentResultUseCaseImplTest` y `ReservationResultKafkaIntegrationTest` (nuevos); `CatedraPhoneRequestAdapterTest` (renombrado a `CatedraEventAdapterTest` y ampliado), y los demás de T07 que usen esos nombres.

| Criterio | Método y test existente o propuesto | Entorno y datos necesarios | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01 a CA-07 | Modelo y caso de uso, un caso por estado de origen; integración con Kafka para cada tipo de evento | Postgres y Kafka de Testcontainers; mensajes de `catedra/kafka/` | Estado y datos en la base; `eventId` registrado; offset confirmado |
| CA-08 | Lector, un caso por defecto y por tipo; integración con uno inválido seguido de uno válido | Mensajes armados | Descartes con motivo; el válido se procesa |
| CA-09, CA-10 | Caso de uso; integración con duplicado, huérfano y `holdId` distinto | Kafka y Postgres | Un solo efecto; sin registrar los descartados |
| CA-11, CA-12 | Caso de uso parametrizado: cada evento sobre cada estado final; salida capturada para D6 | Dobles; `OutputCaptureExtension` | Sin cambios; `eventId` registrado; error solo con `EXPIRED` e `INVALID` |
| CA-13 | Caso de uso contra Postgres real, con falla al registrar y al guardar | Testcontainers | Ninguna de las dos escrituras queda |
| CA-14 | Integración con un `TransactionRunner` de test que falla y se recupera, otro evento detrás y reinicio del consumo, como `PhoneRequestKafkaIntegrationTest` | Kafka de Testcontainers; esperas cortas | Intentos en el log; orden; un solo efecto |
| CA-15 | Caso de uso con hilos reales y `CopyingReservationProcessRepository` | Dobles con demora | Sin escrituras pisadas; otro usuario no espera |
| CA-16 | Salida capturada en caso de uso e integración | Salida capturada | `eventId` y motivo; sin tokens |
| CA-17, CA-18 | Modelo: cada salida de `WAITING_FOR_PHONE`, por evento y por vencimiento; tests de T05 y T07 sin cambios | Unitario | Motivo vacío; marca según D5 |
| CA-19, CA-20 | Lector y modelo; integración con otro mensaje detrás, con la columna real | Kafka y Postgres | Con 500 y con 255 entra; el siguiente se procesa |
| CA-21 | `ReservationResultKafkaIntegrationTest`, con `PHONE_SUBMITTED` escrito directo en la base | App completa y cátedra simulada | `CONFIRMED` con `reservationId`; bloquear otro turno responde bien |
| CA-22 | Suite existente; tests nuevos de T05 y T07 sobre los estados nuevos | `@WebMvcTest` y caso de uso | `409` `INVALID_PROCESS_STATE`; estado ya avanzado |

**Comprobaciones de regresión:** la suite completa (681 tests al empezar), incluidos los de T07; los chequeos de la skill.

**Comandos verificados para compilar y ejecutar tests:** los de `CLAUDE.md`: `./mvnw test -Dtest=…`, `./mvnw clean test` y `./mvnw clean verify`.

**Pruebas con infraestructura real o simulada:** Postgres y Kafka de Testcontainers y la cátedra REST simulada. Sin conexión a la cátedra ni a su Kafka (SPEC, "Sin prueba contra la cátedra real").

**Limitaciones del entorno:** el formato real de los eventos queda sin verificar, porque la cátedra no publica; TASK-02 y TASK-12 de T07 siguen bloqueadas por el mismo motivo.

<!-- Esta sección planifica la validación. Durante la implementación, registra
en TASKS.md o en el informe de validación acordado los resultados y evidencias
reales. Distingue pruebas ejecutadas, fallidas, no ejecutadas y bloqueadas.
Compilar o tener tests en verde no sustituye revisar los criterios de la spec. -->

## Orden de implementación

<!-- Etapas y dependencias principales. El desglose ejecutable se escribe en TASKS.md.
Incluye puntos de comprobación para avanzar con cambios pequeños. -->

1. **Renombres de P2, sin cambiar el comportamiento.** Comprobación: `./mvnw clean test` con el mismo conteo (681).
2. **Modelo y persistencia de `reservation`:** estados, campos, transiciones, cierres por vencimiento, `V5`, entidad y mapper. Comprobación: tests del modelo y de los adaptadores contra Postgres; suite en verde.
3. **Caso de uso, service y gateway de `reservation`,** y el puerto de `catedra` que implementa, con su modelo. Comprobación: tests del caso de uso, incluida la concurrencia.
4. **`catedra`, caso de uso y service.** Comprobación: tests unitarios.
5. **Lector, mensaje y oyente.** Comprobación: tests del lector y del oyente; los de T07 siguen pasando.
6. **Integración con Kafka de Testcontainers.** Comprobación: `ReservationResultKafkaIntegrationTest`.
7. **Cierre:** `CLAUDE.md`, decisión 014, `./mvnw clean verify`, chequeos de la skill y cobertura de los CA.

## Riesgos y decisiones pendientes

<!-- Riesgos concretos de esta solución y cómo se resolverán, sin listas genéricas.
Escribe Ninguna en las decisiones pendientes cuando estén resueltas. -->

- **Riesgos y medidas acordadas:**
  - Los mensajes se arman desde el anexo: el formato real puede diferir. Se revisa al hacer T06 o cuando la cátedra publique.
  - Cambiar `expire` y `expireIfOverdue` toca T04 y T05: los tests existentes lo cubren, y las respuestas no cambian.
  - El caso de uso de T08 repite el esqueleto del de T07 (buscar, candado, duplicado). Se acepta por ahora: son pocas líneas y T06 no lo comparte.
- **Decisiones pendientes:** Ninguna.

  Decisiones tomadas (2026-11-02):

  - **P1 · Forma del resultado (opción a):** un tipo sellado con cinco variantes `record`, una por evento, en el modelo de `catedra` (`AppointmentResult`), y su copia propia en `reservation`. Cada variante tiene solo sus campos, y el compilador avisa si un `switch` olvida un evento. Es la excepción a la skill ya autorizada para los resultados sellados. Costo aceptado: los campos comunes se repiten en cada variante. Alternativa descartada: (b) una sola clase con un `enum` de tipo, con la mayoría de los campos en `null` según el evento.
  - **P2 · Qué se reutiliza del camino de T07 (opción c, criterio del usuario):** un solo puerto de salida y un solo gateway para los eventos de la cátedra, porque su responsabilidad es una sola: conectar esos eventos con `reservation`. Se renombran `AdditionalInformationRequestHandler` → `CatedraEventHandler` (dos métodos), `CatedraPhoneRequestAdapter` → `CatedraEventAdapter`, `AdditionalInformationRequestResult` → `CatedraEventResult`, `AdditionalInformationRequestService` → `CatedraEventService` y `PhoneRequestOutcome` → `ReservationEventOutcome`. Los puertos de entrada y los casos de uso siguen separados, porque la skill pide un método por puerto de entrada. Costo aceptado: T07 cambia de nombres y cada caso de uso de `catedra` ve un método que no usa. Alternativas descartadas: (a) un puerto y un gateway nuevos, casi iguales a los de T07; (b) crear todo aparte sin tocar T07, que duplica piezas.
  - **P3 · Cómo se leen los campos del mensaje (opción a):** un solo DTO con `expiresAt`, `occurredAt`, `date`, `startTime`, `reservationId` y `professionalId` como texto, y el lector los convierte según el tipo de evento. Solo se descarta si está mal un campo que ese evento usa (CA-08), el log conserva el `eventId` y el motivo, y T07 no empieza a descartar pedidos por un campo que no usa. Motivo: nunca se vio un mensaje real, y un formato distinto en un campo que no se usa no debe perder todos los mensajes de ese tipo. Costo aceptado: unas líneas de conversión en el lector. Al implementar se comprueba que Jackson 3 acepte un número en un campo de texto; si no, esos dos se leen como `JsonNode`. Alternativas descartadas: (b) tipos en el DTO, que descarta el mensaje entero, sin `eventId` en el log, y exigía cambiar CA-08; (c) un DTO por evento, seis clases casi iguales para el mismo resultado.

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el plan cubre los requisitos, respeta las exclusiones, reutiliza
componentes verificados, respeta la skill hexagonal-vertical-slicing y permite
demostrar todos los criterios de aceptación.
Resuelve dudas y marcadores pendientes. Si la spec cambió, revisa su impacto.
Tras aprobar el plan, deriva TASKS.md con IDs, dependencias, referencias a RF/CA
y comprobaciones. No marques una tarea terminada sin realizar su validación;
si está bloqueada, registra el motivo.
-->
