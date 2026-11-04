# PLAN: T12 — Expirar procesos vencidos

**SPEC de referencia:** `docs/sdd/t12-expirar-vencidos/SPEC.md`
**Versión de la spec revisada:** aprobada el 2026-11-04 (D1 a D4), sobre `afd6b48`, sin commitear
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

| Componente o archivo existente | Ruta verificada | Responsabilidad y uso previsto |
| --- | --- | --- |
| `ReservationProcess` | `src/main/java/turnos/reservation/domain/model/ReservationProcess.java` | `needsOverdueResolution` y `resolveOverdue` ya aplican D1 y RF-04/05: cierran un `HELD` o `WAITING_FOR_PHONE` aunque esté marcado, y marcan un `PHONE_SUBMITTED` una sola vez. No cambian |
| `GetReservationProcessByIdUseCaseImpl` | `.../reservation/application/usecases/` | Referencia: candado, relectura, `resolveOverdue` y `update` (D2 de T09) |
| `UserOperationLock` | `.../reservation/application/support/` | Se reutiliza. Espera sin tope, y las operaciones que lo toman tienen sus propios tiempos |
| `ReservationProcessRepository` y su adaptador JPA | `.../reservation/domain/ports/out/`, `.../infrastructure/persistence/adapter/` | Se amplían con la búsqueda de vencidos. `findById` y `update` se reutilizan |
| `JpaReservationProcessRepository` | `.../reservation/infrastructure/persistence/repository/` | Suma la consulta. Referencia: `LISTABLE`, con la misma comparación del vencimiento |
| `ReservationService` | `.../reservation/application/service/` | Entrada al slice para el disparador (skill §5.3) |
| `ReservationProperties`, `ReservationPolicy` | `.../reservation/infrastructure/config/`, `.../domain/model/` | `safetyMargin` se reutiliza. Ninguna propiedad del servicio se valida hoy |
| `SchedulingConfig` | `src/main/java/turnos/config/` | `@EnableScheduling` ya activo |
| `@Scheduled` existente | `.../availability/infrastructure/persistence/adapter/InMemoryOccupancyCacheAdapter.java` | Único caso. Comparte el hilo de tareas con T12: no hay `spring.task.scheduling` en `application.yaml` |
| `TechnicalSessionStartup` | `.../catedra/infrastructure/startup/` | Paquete `infrastructure/startup`, excepción autorizada para disparadores al arrancar |
| `pom.xml` (Surefire) | `pom.xml` | Pasa `turnos.catedra.kafka.enabled=false` a toda la suite, también a `-Pe2e`. Mismo lugar para apagar el barrido |
| Dobles de test | `CopyingReservationProcessRepository`, `ControllableProcessRepository`, `MutableClock`, `FakeCatedraServer`, `DatabaseDownStatusQueryIntegrationTest` | Concurrencia con copias, fallas a pedido, reloj controlado, pedidos registrados, base en pausa |

**Convenciones y slice de referencia:** slice `reservation` y `turnos_service/CLAUDE.md`. Comentarios sin citar documentos. Spring Framework 7.0.9 (Boot 4.1.1).

## Solución propuesta

<!-- Explica el enfoque y sus motivos. Describe las responsabilidades y el
recorrido de la solicitud, el evento o la tarea desde que entra hasta la
respuesta, la persistencia o el mensaje publicado. Referencia el diagrama de
secuencia del proyecto si ya lo describe; usa un diagrama nuevo solo si aporta
claridad. -->

Un caso de uso nuevo en `reservation`, disparado por una tarea programada. Recorrido:

1. El disparador (P1, P2) llama a `ReservationService.expireOverdueProcesses()`. Spring no empieza otra corrida hasta que termine esta (RF-02, P3).
2. `ExpireOverdueProcessesUseCaseImpl` busca sin candado con `reservationProcessRepository.findOverdue(expiredBefore)`, donde `expiredBefore` es el instante actual menos el margen. Si falla, registra y termina (RF-09).
3. Para cada proceso encontrado: `userOperationLock.exclusivelyFor(userId, ...)`, `findById`, `resolveOverdue(clock.instant(), margen)` y, solo si cambió, `update`. Si desapareció o ya no corresponde, no escribe (RF-04 a RF-06).
4. Una falla en un proceso se atrapa, se registra con su id y el tipo del error, y sigue con el siguiente (RF-07).
5. Registra el resultado: instante, cerrados, marcados y fallidos (RF-08).

El caso de uso no devuelve nada: el barrido no tiene quien reciba un resultado, y su salida es lo guardado y el log.

**La búsqueda solo propone; decide el modelo.** La consulta trae candidatos y `resolveOverdue` decide con el proceso releído bajo el candado. Si la consulta trajera de más, no se escribe nada. Si trajera de menos, un test del borde lo detecta (P5).

## Slices y componentes afectados

<!-- Aplica la skill hexagonal-vertical-slicing: un paquete por slice, con sus
capas domain, application e infrastructure. Identifica los slices afectados, sus
responsabilidades y la dirección de sus dependencias. Una funcionalidad puede
afectar más de un slice. Justifica cualquier slice nuevo o dependencia nueva
entre slices. -->

| Slice | Existe / nuevo | Responsabilidad y cambios | Dependencias con otros slices |
| --- | --- | --- | --- |
| `reservation` (`src/main/java/turnos/reservation`) | Existente | Suma el barrido: caso de uso, consulta y disparador | Ninguna nueva: T12 es local (RF-10) |

<!-- Distingue lo que se reutiliza, modifica o crea. Las rutas nuevas son
propuestas. Señala impacto sobre modelos, contratos o componentes compartidos. -->

| Slice y capa | Componente o ruta | Acción | Cambio y responsabilidad | Requisito relacionado |
| --- | --- | --- | --- | --- |
| `domain/model` | `ReservationProcess` | Reutilizar | `resolveOverdue`, sin cambios | RF-04, RF-05 |
| `domain/ports/in` | `ExpireOverdueProcessesUseCase` | Crear | Entrada del barrido | RF-01 a RF-09 |
| `domain/ports/out` | `ReservationProcessRepository` | Modificar | Suma `findOverdue(Instant expiredBefore)` | RF-03 |
| `application/usecases` | `ExpireOverdueProcessesUseCaseImpl` | Crear | Recorrido completo, candado por proceso, log | RF-03 a RF-09 |
| `application/service` | `ReservationService` | Modificar | Suma `expireOverdueProcesses()` | RF-01 |
| `application/support` | `UserOperationLock` | Reutilizar | Sin cambios | RF-06 |
| `infrastructure/persistence` | `JpaReservationProcessRepository` y su adaptador | Modificar | Consulta y traducción de su falla | RF-03, RF-09 |
| `infrastructure/config` | `application.yaml` | Modificar | `turnos.reservation.sweep.interval` y `.enabled` (P2). `@Scheduled` y la condición las leen directo, así que `ReservationProperties` no cambia | RF-01 |
| `infrastructure/scheduling` | `OverdueProcessSweepScheduler` | Crear | `@Scheduled` (P1, P2): solo llama al service | RF-01 |
| Tests | Unitarios del caso de uso, del adaptador, y `OverdueSweepIntegrationTest` | Crear | Ver la estrategia de validación | CA-01 a CA-16 |
| Build | `pom.xml`, `.env.example` | Modificar | Apagado en Surefire y variables | RF-01 |

## Datos y contratos

<!-- Completa solo lo aplicable. Si un punto no aplica, indica el motivo. -->

- **Operaciones, eventos y DTO de entrada y salida:** no aplica: T12 no tiene endpoint ni publica eventos (contrato §1).
- **Identificadores, relaciones y restricciones:** se usa `reservationProcessId` y el `userId` de cada proceso para el candado.
- **Origen de los datos devueltos o publicados y transformaciones:** no devuelve ni publica datos. El log solo lleva cantidades, instante, ids y tipos de error.
- **Persistencia, consultas y migraciones:** consulta nueva: `expiresAt < :expiredBefore` y (`status` en `HELD` o `WAITING_FOR_PHONE`, o `status` = `PHONE_SUBMITTED` sin marca). Devuelve `List<ReservationProcess>` sin paginar, porque el índice único permite un solo proceso abierto por usuario: el resultado nunca supera la cantidad de usuarios. Sin índice (P4).
- **Convivencia entre datos locales y remotos:** T12 no consulta a la cátedra. Lo que difiera lo resuelve T08 o T13.
- **Compatibilidad y migraciones de datos existentes:** los procesos vencidos que ya existan se resuelven en el primer barrido. Sin cambios de esquema.

## Transacciones, concurrencia y errores

<!-- Cómo se implementan los comportamientos aprobados en la spec.
Referencia RF/CA y aplica las consideraciones relevantes de BACKEND_GUIDELINES.md. -->

- **Estados y transiciones:** sin transiciones nuevas: `resolveOverdue` (dominio §2, regla 8).
- **Transacciones y consistencia:** cada `update` es su propia transacción. No hay una transacción para todo el barrido: una falla no debe deshacer a los demás (RF-07), y no debe mantenerse abierta mientras espera un candado.
- **Idempotencia y mensajes duplicados:** repetir el barrido no tiene efecto: un cerrado ya no aparece, y un `PHONE_SUBMITTED` marcado queda fuera de la consulta y de `needsOverdueResolution`.
- **Concurrencia y tareas programadas:** candado y relectura por proceso (RF-06). La relectura ve datos frescos porque `open-in-view` no aplica fuera de un request, y cada lectura usa su propia sesión. El barrido no se solapa consigo mismo porque usa espera fija (P3). La espera de un candado demora el barrido y la limpieza del caché, que comparte el hilo, sin efecto visible: esa limpieza tolera atrasos.
- **Timeouts, reintentos y errores de dependencias:** sin dependencias externas. La base usa su límite de espera (D5 de T09). Sin reintentos: el ciclo siguiente es el reintento.
- **Seguridad y autorización:** no hay usuario ni endpoint. Del error solo se registra el tipo, y `ReservationProcess` no genera `toString`, así que el teléfono no llega al log (RF-07).
- **Otras consideraciones de backend aplicables y su solución:** la búsqueda traduce su falla a `ReservationNotSavedException`, como las demás lecturas del adaptador. El caso de uso la atrapa, para que la tarea no termine con una excepción (RF-09).

## Dependencias y configuración

<!-- Librerías, servicios externos, propiedades de configuración y secretos
afectados. Verifica compatibilidad con el proyecto y justifica las incorporaciones.
No agregues dependencias por defecto. Los valores sensibles y los parámetros
ajustables se externalizan. -->

- Sin dependencias nuevas.
- Propiedades nuevas bajo `turnos.reservation.sweep` (P2): `TURNOS_RESERVATION_SWEEP_INTERVAL` (1 minuto) y `TURNOS_RESERVATION_SWEEP_ENABLED` (verdadero).
- `application.yaml` y `.env.example` suman las dos, comentadas en este último.
- `pom.xml`: Surefire pasa el barrido apagado a toda la suite (P2).
- Sin secretos.

## Estrategia de validación

<!-- Una fila por criterio de la spec. Selecciona el método capaz de demostrarlo:
test unitario, de integración (base de datos, mensajería), de API o prueba manual.
No todos requieren todos los métodos. Identifica tests existentes y separa los
nuevos propuestos. Incluye regresiones relevantes. Una prueba con mocks no
demuestra persistencia, transacciones ni idempotencia. -->

| Criterio | Método y test existente o propuesto | Entorno y datos necesarios | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01 | Integración nueva `OverdueSweepIntegrationTest`, barrido encendido | Postgres en contenedor, intervalo corto | Dos vencidos cerrados en ciclos distintos, sin pedidos HTTP |
| CA-02 | Integración nueva: barrido encendido, con una búsqueda demorada más que el intervalo | Postgres en contenedor, repositorio que cuenta las búsquedas en curso | Nunca hay dos a la vez, y las corridas siguen después |
| CA-03 a CA-07, CA-15 | Unitario nuevo e integración del adaptador | Copias; Postgres real para la consulta | Estado y marca de cada proceso |
| CA-08, CA-09 | Unitario con hilos (nuevo) | Copias; candado tomado por el test | El resultado de la operación se conserva |
| CA-10 | Integración con `ControllableProcessRepository` | Postgres real, falla a pedido | Dos cerrados, log sin teléfono, cerrado en el ciclo siguiente |
| CA-11 | Unitario, capturando el log | Con y sin vencidos | Línea con las cantidades |
| CA-12 | Integración con la base en pausa, en una clase propia por cerrar conexiones | Postgres pausado, `@Timeout` | Error en el log y recuperación sola |
| CA-13 | Integración, con el registro de `FakeCatedraServer` | Cátedra simulada, Kafka apagado | Ningún pedido fuera del login técnico |
| CA-14 | Integración con la app completa | Barrido y luego `POST` de T04 y `GET` de T09 | Bloqueo aceptado y `EXPIRED` |
| CA-16 | Integración: proceso guardado antes de arrancar, intervalo largo | Contexto propio con el barrido encendido | Cerrado sin esperar el intervalo |

**Comprobaciones de regresión:** `aProcessWaitingForTheUserIsListedExactlyUntilTheModelSaysItExpired` y los tests de `resolveOverdue` y de T09 siguen pasando. Un test nuevo del borde compara la consulta con `hasExpired` (P5). Prueba en negativo de cada grupo.

**Comandos verificados para compilar y ejecutar tests:** `./mvnw test -Dtest=...` y `./mvnw clean test` (`CLAUDE.md`). Al cerrar, `./mvnw clean verify`.

**Pruebas con infraestructura real o simulada:** Postgres real en contenedor, cátedra simulada (`FakeCatedraServer`). Kafka apagado, salvo que haga falta. Sin cátedra real (D3).

**Limitaciones del entorno:** no se comprueba que la cátedra respete `expiresAt` al procesar el teléfono (D1): no publica los pedidos.

## Orden de implementación

<!-- Etapas y dependencias principales. El desglose ejecutable se escribe en TASKS.md.
Incluye puntos de comprobación para avanzar con cambios pequeños. -->

1. Búsqueda de vencidos: puerto, consulta y test del borde sobre Postgres.
2. Caso de uso con el recorrido completo y sus unitarios; suma al service.
3. Disparador, propiedades y apagado en Surefire, con la suite en verde.
4. Integración con la app completa: CA-01, CA-10, CA-13, CA-14 y CA-16.
5. Base en pausa: CA-12.
6. Documentación (`CLAUDE.md`, `.env.example`) y suite completa con los criterios revisados.

## Riesgos y decisiones pendientes

<!-- Riesgos concretos de esta solución y cómo se resolverán, sin listas genéricas.
Escribe Ninguna en las decisiones pendientes cuando estén resueltas. -->

- **Riesgos y medidas acordadas:**
  - Si la suite corriera con el barrido encendido, cerraría procesos que otros tests preparan a propósito. Se apaga en Surefire y solo lo encienden sus tests.
  - La regla del vencido queda en tres lugares: `hasExpired`, `LISTABLE` y esta consulta. El test del borde obliga a mantenerlos iguales (P5).
  - T13 va a sumar esperas largas al hilo de tareas: se resuelve en su PLAN. T12 solo espera candados.
  - Más de una instancia: no se contempla (decisión 013).
- **Decisiones técnicas:**
  - **P1 · Dónde vive el disparador (opción a, decidida por el usuario el 2026-11-04):** paquete nuevo `reservation/infrastructure/scheduling`, excepción a la skill que se anota en `CLAUDE.md`. Descartadas: (b) `infrastructure/startup`, que hoy significa "al arrancar"; (c) `@Scheduled` en `application`, que mete Spring donde la skill no lo permite.
  - **P2 · Cómo se programa y se apaga (opción a, decidida por el usuario el 2026-11-04):** `@Scheduled` con espera fija entre corridas, y la clase se crea solo si `turnos.reservation.sweep.enabled` es verdadero (`@ConditionalOnProperty`). Con espera fija y sin demora inicial, corre al arrancar (D4). Surefire lo apaga, y sus tests lo encienden. **Comprobado en TASK-04:** acepta `1m`, `PT1M` y un número en milisegundos; vacío, `0`, negativo o mal escrito impiden arrancar. Con la variable de encendido vacía, el barrido queda apagado. `.env.example` lo avisa. Descartada: (b) programarlo por código con `TaskScheduler`, que controla esos valores a cambio de más código y un mecanismo distinto del que ya usa el servicio.
  - **P3 · Cómo se evita que dos barridos se solapen (opción a, decidida por el usuario el 2026-11-04):** con la garantía de `@Scheduled` con espera fija: la siguiente corrida no empieza hasta que termina la anterior. Un comentario en el disparador lo avisa, y un test lo comprueba (CA-02). El ciclo no se omite con una línea en el log como en T12 2.1: no empieza, con el mismo resultado (RF-02). Descartada: (b) una bandera en el caso de uso, que hoy solo se ejecutaría en los tests. T13 tiene dos disparadores y lo decide en su PLAN.
  - **P4 · Índice para la búsqueda (opción a, decidida por el usuario el 2026-11-04):** sin índice. Medido en un Postgres 18 temporal, con la misma condición sobre una tabla simplificada, de 200.000 procesos cerrados y 100 abiertos: 22 a 24 ms recorriendo toda la tabla, una vez por minuto. Descartada: (b) índice parcial sobre `expires_at` para procesos abiertos, una migración para un problema que no existe.
  - **P5 · Test del borde.** La consulta se compara con `hasExpired` en el instante exacto del vencimiento más el margen, como el test que ya lo hace con `LISTABLE`.
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
