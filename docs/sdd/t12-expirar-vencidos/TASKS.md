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

- [x] **TASK-02 · Caso de uso del barrido**
  - Alcance: `ExpireOverdueProcessesUseCase`, `ExpireOverdueProcessesUseCaseImpl` y `ReservationService.expireOverdueProcesses()`: búsqueda, candado por proceso, relectura, `resolveOverdue`, `update`, falla por proceso, falla de la búsqueda y log del resultado. Sin disparador.
  - Depende de: TASK-01
  - Resuelve: CA-03, CA-04, CA-05, CA-06, CA-07, CA-11 y CA-15, y la parte unitaria de CA-10 y CA-12
  - Validación: `./mvnw test -Dtest='ExpireOverdueProcessesUseCaseImplTest,ReservationServiceTest'`, con `CopyingReservationProcessRepository` y `MutableClock`, capturando el log. Prueba en negativo: guardar sin que `resolveOverdue` haya cambiado nada, y no atrapar la falla de un proceso.
  - Resultado: 2026-11-04. `./mvnw test -Dtest='ExpireOverdueProcessesUseCaseImplTest,ReservationServiceTest,CatedraEventAdapterTest'`: exit 0; `ExpireOverdueProcessesUseCaseImplTest` 25 `<testcase>` (TASK-02 y TASK-03), `ReservationServiceTest` 5, 0 fallas. Cubre con repositorio en memoria: CA-03 a CA-07, CA-11, CA-15, y la parte unitaria de CA-10 y CA-12. Sin base real ni disparador: CA-10 y CA-12 siguen en TASK-06 y TASK-07. Prueba en negativo sobre `ExpireOverdueProcessesUseCaseImpl`, con el hash restaurado (`642a605b421bc752`): guardar sin que cambie hace fallar, entre otros, `aReservationConfirmedAfterTheSearchIsNotOverwritten`; no atrapar la falla de un proceso, `aFailureSavingOneProcessDoesNotStopTheOthersAndTheNextSweepClosesIt`; no atrapar la de la búsqueda, `aFailureSearchingEndsTheSweepWithoutPropagatingTheError`; registrar el mensaje del error, el mismo test de la falla al guardar, por el teléfono en el log. `./mvnw clean test`: exit 0, 1468 `<testcase>` (antes 1442), 0 fallas, 0 errores, 0 omitidos.

- [x] **TASK-03 · El barrido convive con las demás operaciones**
  - Alcance: solo tests, salvo que muestren un defecto: resultado aplicado entre la búsqueda y la escritura, y candado tomado por otra operación durante el barrido.
  - Depende de: TASK-02
  - Resuelve: CA-08 y CA-09
  - Validación: `./mvnw test -Dtest=ExpireOverdueProcessesUseCaseImplTest`, con hilos y copias. Prueba en negativo: quitar el candado o la relectura y ver fallar el test.
  - Resultado: 2026-11-04. Solo tests, sin defectos encontrados, en `ExpireOverdueProcessesUseCaseImplTest`: resultado aplicado entre la búsqueda y la escritura (confirmado, cancelado y rechazo con nuevo plazo) y candado tomado por otra operación (espera, no escribe mientras tanto, y respeta lo que quedó). Se sumó `afterNextOverdueSearch` a `CopyingReservationProcessRepository`. Prueba en negativo con el mismo hash restaurado: sin candado fallan `theSweepWaitsForTheOperationThatHoldsTheLockAndRespectsWhatItLeft` y `aSweepClosesTheProcessOnceTheOperationThatHoldsTheLockLeavesItOverdue`; sin relectura fallan los tres de resultado aplicado, entre otros. CA-08 y CA-09 quedan comprobados sobre el repositorio en memoria, que trabaja con copias como la base; no contra Postgres.

- [x] **TASK-04 · Disparador y configuración**
  - Alcance: `reservation/infrastructure/scheduling/OverdueProcessSweepScheduler` (`@Scheduled` con espera fija, `@ConditionalOnProperty`), `turnos.reservation.sweep.interval` y `.enabled` en `application.yaml`, `.env.example`, y el apagado en Surefire.
  - Depende de: TASK-02
  - Resuelve: la configuración de RF-01 (D2, D4, P1, P2)
  - Validación: `./mvnw clean test`, exit 0, con el barrido apagado en toda la suite. Comprobar con un contexto mínimo qué hace la aplicación con `1m`, `PT1M`, vacío, negativo y `0`, registrarlo aquí y ajustar el aviso de `.env.example`.
  - Resultado: 2026-11-04. `./mvnw test -Dtest=OverdueProcessSweepSchedulerTest`: exit 0, 11 `<testcase>`, 0 fallas, con un contexto mínimo. Valores comprobados del intervalo: `1m`, `PT1M` y `60000` arrancan y corren enseguida; `50ms` repite; `0`, `-1`, vacío y `abc` no dejan arrancar. Con `enabled` en `false` o vacío, el barrido no existe; sin la propiedad, existe. Corrige la suposición del PLAN: `0` no corre sin pausa, impide arrancar. Prueba en negativo sobre `OverdueProcessSweepScheduler`, con el hash restaurado (`147a7e4c8fcb8f0e`): primera corrida demorada hace fallar `theFirstSweepRunsWhenTheApplicationStartsWithoutWaitingForTheInterval`; sin la condición, `theSweepDoesNotExistIfItIsTurnedOffOrTheVariableIsEmpty`; sin encendido por defecto, `theSweepExistsIfNothingTurnsItOff`. `./mvnw clean test`: exit 0, 1479 `<testcase>` (antes 1468), 0 fallas, 0 errores, 0 omitidos; en su log, 0 barridos en el hilo programado: el barrido queda apagado en la suite.

- [x] **TASK-05 · Integración con la app completa**
  - Alcance: `OverdueSweepIntegrationTest`, con el barrido encendido, Postgres real y cátedra simulada: corre solo y repetido, nunca dos a la vez, corre al arrancar, no llama a la cátedra, y el usuario puede bloquear otro turno después.
  - Depende de: TASK-04
  - Resuelve: CA-01, CA-02, CA-13, CA-14 y CA-16
  - Validación: `./mvnw test -Dtest=OverdueSweepIntegrationTest`. Prueba en negativo: pasar a frecuencia fija para ver si CA-02 lo detecta, y demorar el primer barrido para ver fallar CA-16.
  - Resultado: 2026-11-04. `./mvnw test -Dtest='OverdueSweepIntegrationTest,OverdueSweepAtStartupIntegrationTest'`: exit 0, 12 + 1 `<testcase>`, 0 fallas, con la aplicación completa, Postgres real y cátedra simulada. CA-16 usa un bean que guarda el proceso antes de que empiecen las tareas, con intervalo de 1 hora. Además de los previstos, se comprueban sobre Postgres real CA-03, CA-04, CA-05, CA-06, CA-07, CA-08, CA-09, CA-11 y CA-15. Las dos clases descartan su contexto al terminar: si quedara en el caché, el barrido seguiría corriendo durante la suite. Se amplió `ControllableProcessRepository` (búsquedas contadas y demoradas, acción después de buscar). Prueba en negativo, con los hashes restaurados: la primera corrida demorada hace fallar `aProcessThatWasOverdueBeforeStartingIsClosedWithoutWaitingForTheInterval`; sin relectura, `aReservationConfirmedAfterTheSearchIsNotOverwritten` y `theSweepWaitsForTheOperationThatHoldsTheLockAndRespectsWhatItLeft`. **Frecuencia fija sola: el test pasa, no la detecta**, porque el planificador actual no solapa una misma tarea. Con frecuencia fija e hilos virtuales, falla `twoSweepsNeverRunAtTheSameTimeEvenIfOneTakesLongerThanTheInterval`; con espera fija e hilos virtuales, pasa. Se precisó el comentario del disparador con esto. `./mvnw clean test`: exit 0, 1492 `<testcase>` (antes 1479), 0 fallas, 0 errores, 0 omitidos; 35 barridos en el hilo programado, solo de estas clases.

- [x] **TASK-06 · Falla al guardar un proceso, sobre la base real**
  - Alcance: test de integración con `ControllableProcessRepository`: tres vencidos, falla el segundo, el log no lleva el teléfono, y lo cierra el ciclo siguiente.
  - Depende de: TASK-04
  - Resuelve: CA-10
  - Validación: `./mvnw test -Dtest=OverdueSweepIntegrationTest` o su clase propia, leyendo los estados de la base y el log capturado. Prueba en negativo: registrar el mensaje del error en lugar del tipo.
  - Resultado: 2026-11-04. Dentro de `OverdueSweepIntegrationTest` (`aFailureSavingOneProcessDoesNotStopTheOthersAndTheNextSweepClosesIt`): sobre Postgres real, con `ControllableProcessRepository` fallando solo en el segundo proceso y con el teléfono en el mensaje del error: el primero y el tercero quedan `EXPIRED`, el log nombra al segundo con el tipo del error y sin el teléfono, y al dejar de fallar el barrido siguiente lo cierra. Prueba en negativo con el hash restaurado: registrar el mensaje del error hace fallar el test.

- [x] **TASK-07 · Base caída**
  - Alcance: test de integración con Postgres en pausa y `@Timeout`, en clase propia: el barrido falla y registra, y al reanudar cierra los vencidos sin intervención.
  - Depende de: TASK-04
  - Resuelve: CA-12
  - Validación: `./mvnw test -Dtest=<clase nueva>`, con los resguardos de pausa que describe `CLAUDE.md`. Prueba en negativo: no atrapar la falla de la búsqueda.
  - Resultado: 2026-11-04. `./mvnw test -Dtest=DatabaseDownOverdueSweepIntegrationTest`: exit 0, 3 `<testcase>`, 0 fallas, con Postgres en pausa de verdad y `@Timeout`. Cubre la caída en los tres puntos: en la búsqueda, en la relectura y al guardar. El log registra `ReservationNotSavedException` en las dos lecturas y `JpaSystemException` al guardar, sin el teléfono; al reanudar, el barrido siguiente resuelve el proceso sin intervención. Ningún contenedor quedó en pausa. Prueba en negativo con el hash restaurado (`642a605b421bc752`): sin atrapar la falla de un proceso fallan los tests de lectura y de guardado; sin el `try-catch` de la búsqueda falla `aSearchThatFailsIsLoggedAndTheSweepClosesTheProcessWhenTheDatabaseComesBack`. Propagar el error conservando la línea del log no lo detecta este test, porque el barrido sigue corriendo; lo detecta el unitario `aFailureSearchingEndsTheSweepWithoutPropagatingTheError` (TASK-02). Con el error propagado Spring igual sigue programando el barrido (lo registra como error inesperado): atraparlo da el mensaje propio, no la continuidad. `./mvnw clean test`: exit 0, 1495 `<testcase>` (antes 1492), 0 fallas, 0 errores, 0 omitidos.

- [x] **TASK-08 · Documentación y cierre**
  - Alcance: `CLAUDE.md` (slice `reservation`, excepción de `infrastructure/scheduling`, variables, apagado en los tests, carpeta SDD, y quitar lo que dependía de T12), y revisión de los 16 criterios. Sin cambios en casos de uso, contratos, secuencias ni dominio. Fuera: T13.
  - Depende de: TASK-01 a TASK-07
  - Resuelve: todos los criterios (revisión final)
  - Validación: `./mvnw clean verify`, exit 0, con conteo de `<testcase>` y cobertura de JaCoCo. Comprobar con la skill §12 que no haya imports indebidos. Tabla CA por CA con su evidencia.
  - Resultado: 2026-11-04. `./mvnw clean verify`: exit 0, 1495 `<testcase>` (al empezar T12: 1424), 0 fallas, 0 errores, 0 omitidos. JaCoCo del servicio: 98,2 % de líneas y 93,2 % de ramas; `ExpireOverdueProcessesUseCaseImpl` y `OverdueProcessSweepScheduler` sin líneas ni ramas sin cubrir. Skill §12: las comprobaciones 1 a 4 no imprimen nada, y lo nuevo no importa otros slices. Sin referencias a documentos en el código nuevo. `CLAUDE.md` actualizado; también una línea en la decisión 013, que no estaba prevista. Sin cambios en casos de uso, contratos, secuencias ni dominio. Revisión de criterios, todos con ejecución real:

    | Criterio | Test | Sobre |
    | --- | --- | --- |
    | CA-01 | `theSweepRunsOnItsOwnAndKeepsRunning`, `theSweepRepeatsAtTheInterval` | App completa y Postgres |
    | CA-02 | `twoSweepsNeverRunAtTheSameTimeEvenIfOneTakesLongerThanTheInterval` | App completa y Postgres |
    | CA-03, CA-15 | `anOverdueProcessWaitingForTheUserIsClosed`, `aMarkedProcessWaitingForTheUserIsClosedAndTheMarkIsTurnedOff` | Memoria y Postgres |
    | CA-04, CA-07 | `aProcessInsideTheMarginAndAClosedOneAreNotTouched`, `anOpenProcessIsFoundAsOverdueExactlyWhenTheModelSaysSo`, `aClosedProcessIsNotFoundAsOverdueNoMatterHowOldItIs` | Postgres |
    | CA-05, CA-06 | `aProcessWithThePhoneSubmittedIsMarkedOnceAndKeepsItsData` | App completa y Postgres |
    | CA-08 | `aReservationConfirmedAfterTheSearchIsNotOverwritten` | Memoria y Postgres |
    | CA-09 | `theSweepWaitsForTheOperationThatHoldsTheLockAndRespectsWhatItLeft` | Memoria y Postgres |
    | CA-10 | `aFailureSavingOneProcessDoesNotStopTheOthersAndTheNextSweepClosesIt` | Memoria y Postgres |
    | CA-11 | `aSweepWithoutOverdueProcessesLogsZero`, `aSweepLogsWhatItActuallyDid`, `theSweepLogsItsResult` | Memoria y Postgres |
    | CA-12 | Las tres pruebas de `DatabaseDownOverdueSweepIntegrationTest` | Postgres en pausa |
    | CA-13 | `theSweepDoesNotCallTheCatedra` | App completa, cátedra simulada |
    | CA-14 | `aUserWhoseProcessWasClosedByTheSweepSeesItExpiredAndCanBlockAnotherSlot` | App completa y Postgres |
    | CA-16 | `aProcessThatWasOverdueBeforeStartingIsClosedWithoutWaitingForTheInterval` | App completa y Postgres |

    Sin ejecutar, por decisión (D3): prueba contra la cátedra real. Sin arrancar la aplicación con el `.env` del usuario: los tests usan la app completa, y T12 no usa nada de ese archivo. Sin comprobar: que la cátedra respete `expiresAt` al procesar el teléfono (D1), porque no publica los pedidos. RF-02: el ciclo no se omite con una línea en el log como en T12 2.1, sino que no empieza (P3).
