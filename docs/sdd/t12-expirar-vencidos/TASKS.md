# TASKS: T12 — Expirar procesos vencidos

**SPEC:** `docs/sdd/t12-expirar-vencidos/SPEC.md` (aprobada el 2026-11-04)
**PLAN:** `docs/sdd/t12-expirar-vencidos/PLAN.md` (aprobado el 2026-11-04)

Al empezar: `./mvnw clean test` en `expirar-procesos-vencidos` (`afd6b48`), exit 0, 1424 `<testcase>`, 0 fallas, 0 errores, 0 omitidos. Copias de `contratos_comunes.md`, X01 y documentos de turnos idénticas.

Tandas propuestas: 01; 02+03; 04; 05+06; 07; 08.

- [x] **TASK-01 · Búsqueda de vencidos**
  - Alcance: `findOverdue(Instant expiredBefore)` en `ReservationProcessRepository`, su consulta en `JpaReservationProcessRepository` y su adaptador, con la falla traducida a `ReservationNotSavedException`. Se suma a los dobles que implementan el puerto. No toca el caso de uso.
  - Depende de: ninguna
  - Resuelve: la búsqueda de CA-03 a CA-07 y CA-15; P4 y P5 del PLAN
  - Validación: `./mvnw test -Dtest=JpaReservationProcessRepositoryAdapterTest` sobre Postgres real: trae cada estado que corresponde, deja afuera los cerrados y los `PHONE_SUBMITTED` marcados, coincide con `hasExpired` en el borde exacto y traduce la falla. Prueba en negativo de la comparación del borde y de la condición de la marca.
  - Resultado: 2026-11-04. `./mvnw test -Dtest=JpaReservationProcessRepositoryAdapterTest` sobre Postgres en contenedor: exit 0, 65 `<testcase>`, 18 nuevos, 0 fallas. Prueba en negativo sobre `JpaReservationProcessRepository`, con el hash restaurado (`8050b22ea0de0257`): `<` por `<=` hace fallar `anOpenProcessIsFoundAsOverdueExactlyWhenTheModelSaysSo`; ignorar la marca, `aMarkedProcessIsFoundAsOverdueOnlyIfTheModelStillHasSomethingToDo`; invertir el orden, `overdueProcessesOfEveryUserComeOldestFirstWithEveryField`. `./mvnw clean test`: exit 0, 1442 `<testcase>` (antes 1424), 0 fallas, 0 errores, 0 omitidos.

- [ ] **TASK-02 · Caso de uso del barrido**
  - Alcance: `ExpireOverdueProcessesUseCase`, `ExpireOverdueProcessesUseCaseImpl` y `ReservationService.expireOverdueProcesses()`: búsqueda, candado por proceso, relectura, `resolveOverdue`, `update`, falla por proceso, falla de la búsqueda y log del resultado. Sin disparador.
  - Depende de: TASK-01
  - Resuelve: CA-03, CA-04, CA-05, CA-06, CA-07, CA-11 y CA-15, y la parte unitaria de CA-10 y CA-12
  - Validación: `./mvnw test -Dtest='ExpireOverdueProcessesUseCaseImplTest,ReservationServiceTest'`, con `CopyingReservationProcessRepository` y `MutableClock`, capturando el log. Prueba en negativo: guardar sin que `resolveOverdue` haya cambiado nada, y no atrapar la falla de un proceso.
  - Resultado: pendiente

- [ ] **TASK-03 · El barrido convive con las demás operaciones**
  - Alcance: solo tests, salvo que muestren un defecto: resultado aplicado entre la búsqueda y la escritura, y candado tomado por otra operación durante el barrido.
  - Depende de: TASK-02
  - Resuelve: CA-08 y CA-09
  - Validación: `./mvnw test -Dtest=ExpireOverdueProcessesUseCaseImplTest`, con hilos y copias. Prueba en negativo: quitar el candado o la relectura y ver fallar el test.
  - Resultado: pendiente

- [ ] **TASK-04 · Disparador y configuración**
  - Alcance: `reservation/infrastructure/scheduling/OverdueProcessSweepScheduler` (`@Scheduled` con espera fija, `@ConditionalOnProperty`), `turnos.reservation.sweep.interval` y `.enabled` en `application.yaml`, `.env.example`, y el apagado en Surefire.
  - Depende de: TASK-02
  - Resuelve: la configuración de RF-01 (D2, D4, P1, P2)
  - Validación: `./mvnw clean test`, exit 0, con el barrido apagado en toda la suite. Comprobar con un contexto mínimo qué hace la aplicación con `1m`, `PT1M`, vacío, negativo y `0`, registrarlo aquí y ajustar el aviso de `.env.example`.
  - Resultado: pendiente

- [ ] **TASK-05 · Integración con la app completa**
  - Alcance: `OverdueSweepIntegrationTest`, con el barrido encendido, Postgres real y cátedra simulada: corre solo y repetido, nunca dos a la vez, corre al arrancar, no llama a la cátedra, y el usuario puede bloquear otro turno después.
  - Depende de: TASK-04
  - Resuelve: CA-01, CA-02, CA-13, CA-14 y CA-16
  - Validación: `./mvnw test -Dtest=OverdueSweepIntegrationTest`. Prueba en negativo: pasar a frecuencia fija para ver si CA-02 lo detecta, y demorar el primer barrido para ver fallar CA-16.
  - Resultado: pendiente

- [ ] **TASK-06 · Falla al guardar un proceso, sobre la base real**
  - Alcance: test de integración con `ControllableProcessRepository`: tres vencidos, falla el segundo, el log no lleva el teléfono, y lo cierra el ciclo siguiente.
  - Depende de: TASK-04
  - Resuelve: CA-10
  - Validación: `./mvnw test -Dtest=OverdueSweepIntegrationTest` o su clase propia, leyendo los estados de la base y el log capturado. Prueba en negativo: registrar el mensaje del error en lugar del tipo.
  - Resultado: pendiente

- [ ] **TASK-07 · Base caída**
  - Alcance: test de integración con Postgres en pausa y `@Timeout`, en clase propia: el barrido falla y registra, y al reanudar cierra los vencidos sin intervención.
  - Depende de: TASK-04
  - Resuelve: CA-12
  - Validación: `./mvnw test -Dtest=<clase nueva>`, con los resguardos de pausa que describe `CLAUDE.md`. Prueba en negativo: no atrapar la falla de la búsqueda.
  - Resultado: pendiente

- [ ] **TASK-08 · Documentación y cierre**
  - Alcance: `CLAUDE.md` (slice `reservation`, excepción de `infrastructure/scheduling`, variables, apagado en los tests, carpeta SDD, y quitar lo que dependía de T12), y revisión de los 16 criterios. Sin cambios en casos de uso, contratos, secuencias ni dominio. Fuera: T13.
  - Depende de: TASK-01 a TASK-07
  - Resuelve: todos los criterios (revisión final)
  - Validación: `./mvnw clean verify`, exit 0, con conteo de `<testcase>` y cobertura de JaCoCo. Comprobar con la skill §12 que no haya imports indebidos. Tabla CA por CA con su evidencia.
  - Resultado: pendiente
