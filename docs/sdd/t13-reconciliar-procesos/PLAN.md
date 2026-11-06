# PLAN: T13 — Reconciliar y recuperar procesos pendientes

**SPEC de referencia:** `docs/sdd/t13-reconciliar-procesos/SPEC.md`
**Versión de la spec revisada:** aprobada el 2026-11-05, sin commitear, sobre `891147a`
**Estado:** Aprobado (2026-11-06) <!-- Borrador | En revisión | Aprobado -->

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

| Componente o archivo existente | Ruta verificada | Responsabilidad y uso previsto |
| --- | --- | --- |
| `ReservationProcess` | `src/main/java/turnos/reservation/domain/model/ReservationProcess.java` | Tiene las transiciones de T05 a T12. `confirmReservation`, `closeAsExpired` y `cancel` exigen un proceso abierto (o `CONFIRMED` para cancelar): no sirven para un `INVALID`. Suma la tabla de T13 (P2) |
| `ReservationResult` | `.../reservation/domain/model/` | Referencia: copia propia de lo que informa la cátedra, como `record` sellado (excepción autorizada de tipos inmutables) |
| `ProcessReservationResultUseCaseImpl` | `.../reservation/application/usecases/` | Referencia: candado del dueño, relectura y warning si el profesional, la fecha o la hora difieren (D3 de T08, D10) |
| `ExpireOverdueProcessesUseCaseImpl` | `.../reservation/application/usecases/` | Referencia: búsqueda sin candado, candado y relectura por proceso, un error no corta a los demás, resumen al final |
| `UserOperationLock` | `.../reservation/application/support/` | Se reutiliza sin cambios. Lo toman también los dos consumidores de Kafka |
| `ReservationProcessRepository` y su adaptador JPA | `.../reservation/domain/ports/out/`, `.../infrastructure/persistence/` | Suma la búsqueda de procesos a reconciliar. `findById` y `update` se reutilizan |
| `AppointmentCanceller` y `CatedraAppointmentAdapter` | `.../reservation/domain/ports/out/`, `.../reservation/infrastructure/gateway/` | Camino de T11 hacia `catedra`. El adaptador se amplía con el puerto nuevo (P3) |
| `CancelAppointmentUseCaseImpl`, `CatedraAppointmentClient` y su adaptador | `.../catedra/application/usecases/`, `.../catedra/domain/ports/out/`, `.../catedra/infrastructure/client/` | Se amplían con el listado. El adaptador ya clasifica `401`, `403` `STUDENT_INTEGRATION_NOT_FOUND`, `500`, `503` y la falta de conexión |
| `CatedraOperationRunner` | `.../catedra/application/support/` | Sesión, `401` y reintentos de lo `Unavailable`. Con la sesión en `CONNECTING` responde enseguida "no disponible": suma la variante que espera (P1) |
| `TechnicalSession`, `InMemoryTechnicalSessionRepositoryAdapter`, `StartTechnicalSessionUseCaseImpl` | `.../catedra/domain/model/`, `.../catedra/infrastructure/persistence/adapter/`, `.../catedra/application/usecases/` | La sesión empieza en `CONNECTING` y el login inicial corre al `ApplicationReadyEvent`, después de la primera ejecución programada. Termina siempre: hasta 5 intentos con timeouts |
| `OverdueProcessSweepScheduler` | `.../reservation/infrastructure/scheduling/` | Referencia para el disparador (P1 y P2 de T12) |
| `SchedulingConfig`, `application.yaml` | `src/main/java/turnos/config/`, `src/main/resources/` | `@EnableScheduling`. Sin `spring.task.scheduling`: un solo hilo para T12, la limpieza del caché de ocupaciones y, si no se cambia, T13 |
| `pom.xml` (Surefire) | `pom.xml` | Apaga Kafka y el barrido de T12 en toda la suite. Mismo lugar para apagar T13 |
| Grabaciones | `src/test/resources/catedra/appointments-*.json` | `PHONE_PENDING`, `FAILED` y el `500` con fechas, de la cátedra real (D9) |
| Dobles de test | `FakeHttpServer`, `FakeCatedraServer`, `CopyingReservationProcessRepository`, `ControllableProcessRepository`, `MutableClock` | Respuestas por ruta en orden y con demora, pedidos recibidos, copias, fallas a pedido, reloj controlado |

**Convenciones y slice de referencia:** slices `reservation` y `catedra`, y `turnos_service/CLAUDE.md`. Comentarios sin citar documentos. Spring Framework 7.0.9 (Boot 4.1.1).

## Solución propuesta

<!-- Explica el enfoque y sus motivos. Describe las responsabilidades y el
recorrido de la solicitud, el evento o la tarea desde que entra hasta la
respuesta, la persistencia o el mensaje publicado. Referencia el diagrama de
secuencia del proyecto si ya lo describe; usa un diagrama nuevo solo si aporta
claridad. -->

Un caso de uso nuevo en `reservation`, disparado por una tarea programada, que consulta a la cátedra por un caso de uso nuevo de `catedra`. La secuencia §7 describe el recorrido. Por ciclo:

1. `ReconciliationScheduler` llama a `ReservationService.reconcilePendingProcesses()` al arrancar y cada minuto, con espera fija y en el segundo hilo de tareas (RF-01, RF-02).
2. `ReconcilePendingProcessesUseCaseImpl` busca sin candado con `findToReconcile(includeOpen)`. `includeOpen` vale verdadero mientras la revisión de los abiertos siga pendiente: empieza encendida y se apaga al terminar un ciclo que no se cortó (RF-03, D3). Si la búsqueda falla, registra y termina; la revisión sigue pendiente.
3. Por cada proceso, sin candado: guarda el estado y la marca leídos y pide el registro con `AppointmentFinder.find(externalPatientId, reservationProcessId)` (RF-04, D6).
   - En `catedra`, `FindAppointmentUseCaseImpl` pide las páginas de 20 por `CatedraOperationRunner.runAfterInitialLogin` (P1): si el login inicial está en curso, espera a que termine. Para en la primera página con menos de 20 registros. Elige el registro del mismo `reservationProcessId` y el mismo paciente, y valida solo ese (RF-06b, RF-12).
   - `CatedraAppointmentAdapter` devuelve el registro o vacío, o lanza la excepción que ya existe: no disponible (`ReservationCentralUnavailableException`), configuración (`ReservationServiceDegradedException`) o mal formado (`ReservationIntegrationException`).
4. No disponible o configuración: corta el ciclo, registra una línea con cuántos quedaron y no apaga la revisión de abiertos (RF-06, D7). Mal formado u otro error del proceso: registra y sigue (RF-06b).
5. Con `userOperationLock.exclusivelyFor(userId, ...)`: vuelve a leer. Si el estado o la marca cambiaron desde el paso 3, descarta y registra (RF-11). Si no, aplica `reconcileWith(registro)` o `reconcileWithoutRecord(now, margen)` (P2) y guarda con `update` si cambió el estado o la marca (RF-07, RF-07b, RF-08). Una falla al guardar se registra y el proceso queda para el ciclo siguiente (RF-10).
6. Registra el resultado del proceso y, al final, el resumen del ciclo (RF-09).

**El modelo decide; el caso de uso coordina.** La tabla vive en `ReservationProcess` (P2), como `resolveOverdue` en T12. El caso de uso solo busca, consulta, toma el candado, compara y guarda.

## Slices y componentes afectados

<!-- Aplica la skill hexagonal-vertical-slicing: un paquete por slice, con sus
capas domain, application e infrastructure. Identifica los slices afectados, sus
responsabilidades y la dirección de sus dependencias. Una funcionalidad puede
afectar más de un slice. Justifica cualquier slice nuevo o dependencia nueva
entre slices. -->

| Slice | Existe / nuevo | Responsabilidad y cambios | Dependencias con otros slices |
| --- | --- | --- | --- |
| `reservation` (`src/main/java/turnos/reservation`) | Existente | Reconciliación: modelo, búsqueda, caso de uso, puerto hacia la cátedra y disparador | La que ya existe hacia `catedra` (por su puerto de entrada, desde `infrastructure/gateway`). Ninguna nueva |
| `catedra` (`src/main/java/turnos/catedra`) | Existente | Buscar el registro de un proceso en el listado, con la espera del login inicial | Ninguna nueva: no conoce a `reservation` |

<!-- Distingue lo que se reutiliza, modifica o crea. Las rutas nuevas son
propuestas. Señala impacto sobre modelos, contratos o componentes compartidos. -->

| Slice y capa | Componente o ruta | Acción | Cambio y responsabilidad | Requisito relacionado |
| --- | --- | --- | --- | --- |
| `reservation/domain/model` | `ReservationRecord`, `ReservationRecordStatus` | Crear | Copia propia del registro de la cátedra, como `record` (excepción ya autorizada, como `ReservationResult`). Estado: `PHONE_PENDING`, `CONFIRMED`, `CANCELLED`, `FAILED` | RF-07, D10 |
| `reservation/domain/model` | `ReconciliationOutcome` | Crear | Enum: `CORRECTED` (cambió el estado), `MATCHED` (ya coincidía), `STILL_PENDING` (`PHONE_SUBMITTED` con `PHONE_PENDING`), `UNRESOLVED` (cerrado sin registro o con `PHONE_PENDING`) | RF-07b, RF-09 |
| `reservation/domain/model` | `ReservationProcess` | Modificar | `reconcileWith(ReservationRecord)` y `reconcileWithoutRecord(now, margin)`: la tabla de RF-07, RF-07b y RF-08, incluidas las salidas de `INVALID`. `clearReconciliationMark` privado | RF-07, RF-07b, RF-08 |
| `reservation/domain/ports/in` | `ReconcilePendingProcessesUseCase` | Crear | Entrada del ciclo | RF-01 a RF-12 |
| `reservation/domain/ports/out` | `AppointmentFinder` | Crear | `Optional<ReservationRecord> find(String externalPatientId, String reservationProcessId)`; las fallas, como excepciones de `reservation` | RF-04, RF-06 |
| `reservation/domain/ports/out` | `ReservationProcessRepository` | Modificar | Suma `findToReconcile(boolean includeOpen)` | RF-03 |
| `reservation/application/usecases` | `ReconcilePendingProcessesUseCaseImpl` | Crear | Recorrido completo, revisión de abiertos pendiente (D3), corte del ciclo, candado y relectura, logs | RF-02 a RF-11 |
| `reservation/application/service` | `ReservationService` | Modificar | Suma `reconcilePendingProcesses()` | RF-01 |
| `reservation/infrastructure/gateway` | `CatedraAppointmentAdapter` | Modificar | Implementa también `AppointmentFinder`: traduce el resultado de `catedra` y arma `ReservationRecord` | RF-04, RF-06 |
| `reservation/infrastructure/persistence` | `JpaReservationProcessRepository` y su adaptador | Modificar | Consulta nueva y traducción de su falla a `ReservationNotSavedException` | RF-03 |
| `reservation/infrastructure/scheduling` | `ReconciliationScheduler` | Crear | `@Scheduled(fixedDelayString)` y `@ConditionalOnProperty`, como T12: solo llama al service | RF-01, RF-02 |
| `catedra/domain/model` | `Appointment`, `AppointmentStatus`, `AppointmentLookupResult` | Crear | Registro del listado (`record`); estado con `UNKNOWN` para lo no reconocido; resultado sellado: `Found`, `NotFound`, `ConfigurationError`, `Unavailable`, `Failed`, como `AppointmentCancellationResult` | RF-04, RF-06b |
| `catedra/domain/ports/in` | `FindAppointmentUseCase` | Crear | `findAppointment(externalPatientId, reservationProcessId)` | RF-04 |
| `catedra/domain/ports/out` | `CatedraAppointmentClient` | Modificar | Suma `listAppointments(externalPatientId, page, size, token)` | RF-04 |
| `catedra/application/usecases` | `FindAppointmentUseCaseImpl` | Crear | Páginas, elección del registro y validación (P3) | RF-04, RF-06b, RF-12 |
| `catedra/application/support` | `CatedraOperationRunner` | Modificar | Suma `runAfterInitialLogin`: con la sesión en `CONNECTING`, espera hasta que cambie o hasta el tope, y sigue como `run` (P1) | RF-01, CA-01 |
| `catedra/infrastructure/client` | `CatedraAppointmentClientAdapter`, `dto/AppointmentResponse`, `mapper/AppointmentMapper` | Modificar / crear | `GET /api/appointments?externalPatientId=&page=&size=`: `200` lista; `401` rechazo; `403` `STUDENT_INTEGRATION_NOT_FOUND` configuración; `500`, `503`, sin conexión o timeout no disponible; cuerpo ilegible u otro estado, `Failed` | RF-05, RF-06, RF-06b |
| `catedra/infrastructure/client` | `CatedraProperties.Operations` | Modificar | Suma `initialLoginWait` (2 minutos) | RF-01 |
| Configuración | `application.yaml`, `.env.example`, `pom.xml` | Modificar | `turnos.reservation.reconciliation.enabled` e `.interval`; `spring.task.scheduling.pool.size: 2`; apagado en Surefire | RF-01 |
| Tests | Unitarios, de adaptador e integración | Crear / ampliar | Ver la estrategia de validación. `FakeCatedraServer` suma un ayudante para armar listados | CA-01 a CA-18 |
| Documentación | `CLAUDE.md`, decisiones 013 y 016 | Modificar / crear | Tarea nueva, segundo hilo, variables; actualización de 013 por D6; 016 explica la reconciliación (pedida por el usuario el 2026-11-06) | — |

## Datos y contratos

<!-- Completa solo lo aplicable. Si un punto no aplica, indica el motivo. -->

- **Operaciones, eventos y DTO de entrada y salida:** sin endpoint ni eventos propios. Consume el contrato §4.2.6, ya actualizado. `AppointmentResponse` lee los campos que usa la SPEC ("Datos y reglas de negocio"); los demás (nombre y teléfono del paciente, nombres del profesional) no se mapean.
- **Identificadores, relaciones y restricciones:** el paciente es `userId.toString()` (dominio regla 2), como en T04 y T05. Se usa el `userId` del proceso para el candado.
- **Origen de los datos devueltos o publicados y transformaciones:** del registro elegido salen `ReservationRecord` y los cambios del proceso. `appointmentDate` y `startTime` se leen como fecha y hora locales (dominio regla 16). `cancellationReason` pasa por el mismo recorte que hoy (`storable`).
- **Persistencia, consultas y migraciones:** consulta nueva: `needsReconciliation = true OR (:includeOpen = true AND status IN (HELD, WAITING_FOR_PHONE, PHONE_SUBMITTED))`, ordenada por `expiresAt` y `reservationProcessId`, sin paginar ni índice, por el mismo motivo que P4 de T12: los marcados son pocos y los abiertos, uno por usuario. Sin cambios de esquema.
- **Convivencia entre datos locales y remotos:** la cátedra manda (dominio regla 15), con los límites de D2. Un resultado que llegó por Kafka mientras tanto gana (D6).
- **Compatibilidad y migraciones de datos existentes:** los marcados que ya existan (por ejemplo, `PHONE_SUBMITTED` de T09 y T12) se reconcilian en el primer ciclo.

## Transacciones, concurrencia y errores

<!-- Cómo se implementan los comportamientos aprobados en la spec.
Referencia RF/CA y aplica las consideraciones relevantes de BACKEND_GUIDELINES.md. -->

- **Estados y transiciones:** las del contrato §4.2.6 y D2, en `reconcileWith` y `reconcileWithoutRecord`. Un proceso nunca pasa de cerrado a abierto. `HELD` con `PHONE_PENDING` pasa a `WAITING_FOR_PHONE` conservando su `expiresAt` y guardando `reservationId`.
- **Transacciones y consistencia:** cada `update` es su propia transacción, fuera de la llamada a la cátedra. No hay transacción por ciclo: una falla no deshace a los demás (RF-10).
- **Idempotencia y mensajes duplicados:** un proceso reconciliado queda sin marca y no vuelve a buscarse; uno consultado otra vez por D3 da `MATCHED` y no se guarda si no cambió.
- **Concurrencia y tareas programadas:** espera fija: un ciclo no empieza antes de que termine el anterior (RF-02). Dos hilos de tareas: T13 no demora a T12 (RF-01). La llamada va sin candado; la escritura, con candado y relectura (RF-11). La revisión de abiertos pendiente es un `AtomicBoolean` del caso de uso, en memoria (D3): un reinicio la vuelve a encender.
- **Timeouts, reintentos y errores de dependencias:** los de `CatedraOperationRunner` y las operaciones (2 s, 3 s, 3 intentos, espera creciente), por página. Un timeout de lectura es "no disponible", porque la consulta no cambia nada. La espera del login inicial tiene tope (P1). Las páginas tienen un máximo (50): si se alcanza, el resultado es `Failed`, para no recorrer sin fin una respuesta anómala.
- **Seguridad y autorización:** sin usuario ni endpoint. El JWT técnico no se registra. El log lleva ids, estados y tipos de error; nunca nombre ni teléfono (RF-12): no se mapean.
- **Otras consideraciones de backend aplicables y su solución:** una excepción inesperada en un proceso se atrapa y cuenta como fallida, para que la tarea no termine con una excepción.

## Dependencias y configuración

<!-- Librerías, servicios externos, propiedades de configuración y secretos
afectados. Verifica compatibilidad con el proyecto y justifica las incorporaciones.
No agregues dependencias por defecto. Los valores sensibles y los parámetros
ajustables se externalizan. -->

- Sin dependencias nuevas.
- Propiedades nuevas: `turnos.reservation.reconciliation.enabled` (`TURNOS_RESERVATION_RECONCILIATION_ENABLED`, verdadero) e `.interval` (`TURNOS_RESERVATION_RECONCILIATION_INTERVAL`, 1 minuto); `turnos.catedra.operations.initial-login-wait` (`TURNOS_CATEDRA_OPERATIONS_INITIAL_LOGIN_WAIT`, 2 minutos: el login inicial tarda como mucho unos 80 s con sus valores actuales).
- `spring.task.scheduling.pool.size: 2`, fijo en `application.yaml`.
- `.env.example` suma las tres, comentadas. `pom.xml`: Surefire apaga la reconciliación en toda la suite.
- Sin secretos.

## Estrategia de validación

<!-- Una fila por criterio de la spec. Selecciona el método capaz de demostrarlo:
test unitario, de integración (base de datos, mensajería), de API o prueba manual.
No todos requieren todos los métodos. Identifica tests existentes y separa los
nuevos propuestos. Incluye regresiones relevantes. Una prueba con mocks no
demuestra persistencia, transacciones ni idempotencia. -->

| Criterio | Método y test existente o propuesto | Entorno y datos necesarios | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01 | Integración nueva `ReconciliationAtStartupIntegrationTest` (proceso guardado antes de arrancar, como en T12) y `ReconciliationIntegrationTest` | Postgres en contenedor, cátedra simulada, intervalo largo / corto | Reconciliado antes del intervalo, aunque el login inicial termine después; con la reconciliación apagada, ningún pedido al listado |
| CA-02 | Integración: cátedra que demora el listado, con T12 encendido | Postgres en contenedor, `respondAfter` | Nunca dos listados a la vez; el vencido de T12 se cierra durante la espera |
| CA-03, CA-04 | Unitario del caso de uso e integración del adaptador JPA | Copias y buscador falso; Postgres real para la consulta | Procesos pedidos en cada ciclo; la revisión de abiertos se repite hasta un ciclo completo |
| CA-05, CA-06 | Unitario de `FindAppointmentUseCaseImpl` y del cliente con `FakeHttpServer` | Dos páginas; registro de otro paciente | Parámetros y JWT de cada pedido; `NotFound`; log sin nombre ni teléfono |
| CA-07 | Tests existentes del runner, más uno del cliente para `401` | `FakeHttpServer` | Renovación y una repetición; el segundo `401` corta |
| CA-08, CA-09 | Unitarios del cliente, del caso de uso de `catedra` y del de `reservation` | `500`, `503`, timeout, `403`, cuerpo ilegible, estado desconocido, `CONFIRMED` sin `confirmedAt` | Clasificación; corte con una línea; los demás siguen ante un mal formado |
| CA-10 a CA-14 | Unitarios de `ReservationProcess`, una combinación por test, y una integración por grupo | Modelo; Postgres real | Estado, marca y datos guardados |
| CA-15 | Unitario, capturando el log | Ciclo con cambios y sin cambios | Una línea por proceso y el resumen |
| CA-16 | Integración con `ControllableProcessRepository` | Falla a pedido en un `update` | Los demás reconciliados; el fallido, en el ciclo siguiente |
| CA-17 | Unitario con hilos | Buscador falso que retiene la respuesta; candado tomado por el test | La operación no espera; descarte si cambió, aplicación si no |
| CA-18 | Unitario del cliente con las grabaciones | `appointments-200-phone-pending.json`, `-failed.json`, `-500-date-filter.json` | `PHONE_PENDING`, `FAILED` y "no disponible" |

**Comprobaciones de regresión:** tests de `CatedraOperationRunner` (`run` sin cambios), de T08, T11 y T12, y del adaptador JPA. Prueba en negativo de cada grupo.

**Comandos verificados para compilar y ejecutar tests:** `./mvnw test -Dtest=...` y `./mvnw clean test` (`CLAUDE.md`). Al cerrar, `./mvnw clean verify`.

**Pruebas con infraestructura real o simulada:** Postgres real en contenedor y cátedra simulada (`FakeCatedraServer`), con las grabaciones reales. Kafka apagado, salvo que un test lo necesite. Sin cátedra real: ya se probó en D9.

**Limitaciones del entorno:** no se puede producir en la cátedra real un `CONFIRMED` ni un `CANCELLED` (requieren el teléfono por Kafka): se arman a mano a partir del contrato.

## Orden de implementación

<!-- Etapas y dependencias principales. El desglose ejecutable se escribe en TASKS.md.
Incluye puntos de comprobación para avanzar con cambios pequeños. -->

1. Modelo: `ReservationRecord`, `ReconciliationOutcome` y la tabla en `ReservationProcess`, con sus unitarios (CA-10 a CA-14).
2. `catedra`: cliente del listado con las grabaciones, `FindAppointmentUseCaseImpl` y `runAfterInitialLogin` (CA-05 a CA-09, CA-18).
3. `reservation`: puerto, adaptador del gateway y búsqueda en Postgres (CA-03).
4. Caso de uso con el recorrido completo y sus unitarios; suma al service (CA-04, CA-08, CA-09, CA-15, CA-17).
5. Disparador, propiedades, dos hilos y apagado en Surefire, con la suite en verde.
6. Integración con la app completa: CA-01, CA-02, CA-16 y un caso por grupo de la tabla.
7. Documentación (`CLAUDE.md`, `.env.example`, decisión 013) y suite completa con los criterios revisados.

## Riesgos y decisiones pendientes

<!-- Riesgos concretos de esta solución y cómo se resolverán, sin listas genéricas.
Escribe Ninguna en las decisiones pendientes cuando estén resueltas. -->

- **Riesgos y medidas acordadas:**
  - Con la reconciliación encendida, la suite consultaría la cátedra simulada y cambiaría procesos preparados a propósito. Se apaga en Surefire y solo la encienden sus tests, con `@DirtiesContext`.
  - El listado real se vuelve lento con muchas reservas por paciente. Hoy son pocas; el tope de páginas evita un recorrido sin fin.
  - `runAfterInitialLogin` mal usado demoraría a un usuario. Solo lo usa la consulta de T13, y un test del runner comprueba que `run` no espera.
  - Más de una instancia: no se contempla (decisión 013).
- **Decisiones técnicas:**
  - **P1 · Primera ejecución y login inicial (opción a, decidida por el usuario el 2026-11-05):** la consulta de T13 espera a que termine el login inicial si está en curso, con una variante de `CatedraOperationRunner` que hay que pedir explícitamente; las demás operaciones no cambian. Tope configurable de 2 minutos; agotado, sigue como `run` y da "no disponible". T12 no la necesita: no habla con la cátedra. Descartadas: (b) aceptar un minuto de demora, que no cumple CA-01; (c) una demora inicial fija.
  - **P2 · La tabla en el modelo (opción a, decidida por el usuario el 2026-11-05):** `reconcileWith` y `reconcileWithoutRecord` en `ReservationProcess`, que devuelven un `ReconciliationOutcome`. No es una excepción a la skill ("business rules ... as plain methods"). El registro es un `record` propio de `reservation`, con la excepción ya autorizada de tipos inmutables. Descartada: (b) un `switch` en el caso de uso, que reparte las reglas y abre métodos del modelo a cualquier caso de uso.
  - **P3 · Reparto entre slices (opción a, decidida por el usuario el 2026-11-05):** `catedra` recorre las páginas, elige y valida solo el registro buscado; `reservation` recibe ese registro o vacío. Los datos de otros registros no salen de `catedra`, y uno mal formado ajeno no afecta. Páginas de 20, el tamaño probado con la cátedra real. Descartadas: (b) `reservation` recorre las páginas; (c) `catedra` devuelve todos los registros.
  - **P4 · Propuestas menores (aprobadas con el plan el 2026-11-06):** nombres y valores de las tres propiedades; dos hilos de tareas fijos (sin variable); tope de 50 páginas; la revisión de abiertos como `AtomicBoolean` del caso de uso. El usuario consideró un intervalo de 1 minuto y 30 segundos y eligió mantener 1 minuto (D8).
- **Decisiones pendientes:** Ninguna.

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el plan cubre los requisitos, respeta las exclusiones, reutiliza
componentes verificados, respeta la skill hexagonal-vertical-slicing y permite
demostrar todos los criterios de aceptación.
Resuelve dudas y marcadores pendientes. Si la spec cambió, revisa su impacto.
Tras aprobar el plan, deriva TASKS.md con IDs, dependencias, referencias a RF/CA
y comprobaciones. No marques una tarea terminada sin realizar su validación;
si está bloqueada, registra el motivo.
-->
