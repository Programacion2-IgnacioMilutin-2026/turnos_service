# TASKS: T13 — Reconciliar y recuperar procesos pendientes

**SPEC:** `docs/sdd/t13-reconciliar-procesos/SPEC.md` (aprobada el 2026-11-05)
**PLAN:** `docs/sdd/t13-reconciliar-procesos/PLAN.md` (aprobado el 2026-11-06)

Al empezar: rama `reconciliar-procesos` desde `891147a`, que cerró T12 con `./mvnw clean verify` en verde (1495 tests). Copias de `contratos_comunes.md`, X01 y documentos de turnos idénticas; las de turnos se actualizaron el 2026-11-05 por D1, D2, D5, D7 y D10 de la SPEC.

Tandas propuestas: 01; 02+03; 04+05; 06+07; 08; 09; 10.

- [ ] **TASK-01 · La tabla de reconciliación en el modelo**
  - Alcance: `ReservationRecord` y `ReservationRecordStatus` (copia propia del registro, como `record`), `ReconciliationOutcome`, y `reconcileWith(ReservationRecord)` y `reconcileWithoutRecord(now, margin)` en `ReservationProcess`. Solo `domain/model` y sus tests. No toca casos de uso ni la cátedra.
  - Depende de: ninguna
  - Resuelve: la parte del modelo de CA-10, CA-11, CA-12, CA-13 y CA-14 (P2)
  - Validación: `./mvnw test -Dtest=ReservationProcessTest`, una combinación de estado local y respuesta de la cátedra por test: estado, marca, datos guardados y resultado devuelto. Prueba en negativo: reabrir un cerrado, apagar la marca de un `PHONE_SUBMITTED` con `PHONE_PENDING` y perder el `expiresAt` de un `HELD` que pasa a `WAITING_FOR_PHONE`.
  - Resultado: pendiente

- [ ] **TASK-02 · Cliente HTTP del listado**
  - Alcance: `listAppointments(externalPatientId, page, size, token)` en `CatedraAppointmentClient` y `CatedraAppointmentClientAdapter`; `Appointment` y `AppointmentStatus` (con `UNKNOWN`) en `catedra/domain/model`; `dto/AppointmentResponse` y `mapper/AppointmentMapper`, sin nombre ni teléfono del paciente. Clasificación: `200`, `401`, `403` `STUDENT_INTEGRATION_NOT_FOUND`, `500`, `503`, sin conexión, timeout, cuerpo ilegible y otros estados. No toca el caso de uso.
  - Depende de: ninguna
  - Resuelve: la parte del cliente de CA-05, CA-07, CA-08, CA-09 y CA-18
  - Validación: `./mvnw test -Dtest='CatedraAppointmentClientAdapterTest,AppointmentMapperTest'` con `FakeHttpServer`: parámetros y JWT del pedido (sin `from` ni `to`), las tres grabaciones de la cátedra real y cada clasificación. Los tests de la cancelación siguen pasando. Prueba en negativo: un `500` clasificado como `Failed` y un estado desconocido que corte la lectura de la página.
  - Resultado: pendiente

- [ ] **TASK-03 · Buscar el registro de un proceso en `catedra`**
  - Alcance: `FindAppointmentUseCase`, `AppointmentLookupResult` y `FindAppointmentUseCaseImpl`: páginas de 20 por el runner, parar en la primera página incompleta, tope de 50 páginas, elegir el registro del mismo proceso y paciente, y validarlo (estado conocido, `confirmedAt` en `CONFIRMED`, `cancelledAt` en `CANCELLED`). Usa `run`; la espera del login llega en TASK-04.
  - Depende de: TASK-02
  - Resuelve: CA-05, CA-06 y la parte de `catedra` de CA-08 y CA-09 (P3)
  - Validación: `./mvnw test -Dtest=FindAppointmentUseCaseImplTest`, con cliente y sesión falsos: registro en la segunda página, registro de otro paciente, mal formado propio y ajeno, tope de páginas, y cada respuesta del runner traducida. Prueba en negativo: elegir sin comparar el paciente y validar todos los registros de la página.
  - Resultado: pendiente

- [ ] **TASK-04 · Esperar el login inicial**
  - Alcance: `runAfterInitialLogin` en `CatedraOperationRunner` y `initialLoginWait` en `CatedraProperties.Operations` (2 minutos, `TURNOS_CATEDRA_OPERATIONS_INITIAL_LOGIN_WAIT`); `FindAppointmentUseCaseImpl` pasa a usarlo. `run` no cambia.
  - Depende de: TASK-03
  - Resuelve: la parte unitaria de CA-01 (P1)
  - Validación: `./mvnw test -Dtest='CatedraOperationRunnerTest,FindAppointmentUseCaseImplTest'`: con la sesión en `CONNECTING` espera y sigue cuando se habilita; con el login fallido da "no disponible"; agotado el tope, sigue como `run`; `run` responde enseguida en `CONNECTING`. Prueba en negativo: que `run` también espere.
  - Resultado: pendiente

- [ ] **TASK-05 · Puerto hacia la cátedra y búsqueda en la base, en `reservation`**
  - Alcance: `AppointmentFinder` y su implementación en `CatedraAppointmentAdapter` (registro, vacío o las excepciones existentes); `findToReconcile(boolean includeOpen)` en `ReservationProcessRepository`, su consulta y su adaptador, con la falla traducida a `ReservationNotSavedException`. Se suma a los dobles que implementan el puerto.
  - Depende de: TASK-01, TASK-03
  - Resuelve: la búsqueda de CA-03 y la traducción de CA-08 y CA-09
  - Validación: `./mvnw test -Dtest='CatedraAppointmentAdapterTest,JpaReservationProcessRepositoryAdapterTest'`, este último sobre Postgres real: con y sin `includeOpen` trae los que corresponden de cada estado y deja afuera los cerrados sin marca. Prueba en negativo: ignorar `includeOpen` y traer los cerrados sin marca.
  - Resultado: pendiente

- [ ] **TASK-06 · Caso de uso de la reconciliación**
  - Alcance: `ReconcilePendingProcessesUseCase`, `ReconcilePendingProcessesUseCaseImpl` y `ReservationService.reconcilePendingProcesses()`: búsqueda, revisión de abiertos pendiente (D3), consulta sin candado, corte del ciclo (D7), salto de un proceso mal formado, candado y relectura con descarte (D6), `reconcileWith` o `reconcileWithoutRecord`, `update` solo si cambió, warning si el turno difiere (D10), falla al guardar, log por proceso y resumen. Sin disparador.
  - Depende de: TASK-05
  - Resuelve: CA-03, CA-04, CA-08, CA-09, CA-15 y la parte unitaria de CA-10 a CA-14 y CA-16
  - Validación: `./mvnw test -Dtest='ReconcilePendingProcessesUseCaseImplTest,ReservationServiceTest'`, con `CopyingReservationProcessRepository`, un buscador falso y `MutableClock`, capturando el log. Prueba en negativo: apagar la revisión de abiertos en un ciclo cortado, seguir después de un corte, guardar sin cambios y registrar datos del paciente.
  - Resultado: pendiente

- [ ] **TASK-07 · La reconciliación convive con las demás operaciones**
  - Alcance: solo tests, salvo que muestren un defecto: operación o evento que cambia el proceso mientras la cátedra retiene la respuesta, y el usuario que no espera a la cátedra.
  - Depende de: TASK-06
  - Resuelve: CA-17
  - Validación: `./mvnw test -Dtest=ReconcilePendingProcessesUseCaseImplTest`, con hilos y copias. Prueba en negativo: tomar el candado antes de la llamada y quitar la relectura.
  - Resultado: pendiente

- [ ] **TASK-08 · Disparador y configuración**
  - Alcance: `reservation/infrastructure/scheduling/ReconciliationScheduler` (`@Scheduled` con espera fija, `@ConditionalOnProperty`), `turnos.reservation.reconciliation.enabled` e `.interval` (1 minuto), `spring.task.scheduling.pool.size: 2`, `.env.example` y el apagado en Surefire.
  - Depende de: TASK-06
  - Resuelve: la configuración de RF-01 y RF-02 (D8, P4)
  - Validación: `./mvnw test -Dtest=ReconciliationSchedulerTest` con un contexto mínimo: corre al arrancar, no corre apagada, existe sin la propiedad, y T12 y T13 corren en hilos distintos. `./mvnw clean test`, exit 0, con la reconciliación apagada en toda la suite. Prueba en negativo: un solo hilo y la condición quitada.
  - Resultado: pendiente

- [ ] **TASK-09 · Integración con la app completa**
  - Alcance: `ReconciliationIntegrationTest` y `ReconciliationAtStartupIntegrationTest`, con Postgres real y cátedra simulada que responde las grabaciones y listados armados: ciclo periódico, apagada sin pedidos, primera ejecución con el login inicial en curso, nunca dos listados a la vez con T12 cerrando vencidos, un caso por grupo de la tabla, y falla al guardar con `ControllableProcessRepository`. `FakeCatedraServer` suma un ayudante para armar listados.
  - Depende de: TASK-04, TASK-08
  - Resuelve: CA-01, CA-02, CA-16, CA-18 y la integración de CA-10 a CA-14
  - Validación: `./mvnw test -Dtest='Reconciliation*IntegrationTest'`. Prueba en negativo: usar `run` en vez de `runAfterInitialLogin` hace fallar el arranque, y un solo hilo de tareas hace fallar CA-02.
  - Resultado: pendiente

- [ ] **TASK-10 · Documentación y cierre**
  - Alcance: `CLAUDE.md` (tarea, segundo hilo, variables, tests), `.env.example`, actualización de la decisión 013 por D6, revisión de la decisión 016 contra lo implementado, y los criterios revisados uno por uno.
  - Depende de: TASK-09
  - Resuelve: todos los CA, revisados
  - Validación: `./mvnw clean verify`, exit 0, con la cantidad de tests y la cobertura de JaCoCo; copias de los documentos comparadas.
  - Resultado: pendiente
