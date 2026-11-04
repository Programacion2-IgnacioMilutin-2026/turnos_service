# TASKS: T10 — Consultar mis reservas

**SPEC:** `docs/sdd/t10-mis-reservas/SPEC.md` (aprobada el 2026-11-03)
**PLAN:** `docs/sdd/t10-mis-reservas/PLAN.md` (aprobado el 2026-11-03)

Tandas propuestas, cada una deja la suite en verde: 01+02; 03+04; 05; 06; 07+08.

- [x] **TASK-01 · Dominio y puerto**
  - Alcance: `ReservationStatus.isListable`, los `record` `ReservationListCriteria` y `ReservationProcessPage`, el método `findListable` en `ReservationProcessRepository` y su implementación mínima en los cuatro dobles de test que implementan el puerto (`CopyingReservationProcessRepository`, `ControllableProcessRepository`, y los de `CreateReservationProcessUseCaseImplTest` y `ProcessReservationResultAtomicityTest`). Sin consulta real todavía: el adaptador se completa en TASK-02.
  - Depende de: ninguna
  - Resuelve: CA-08 (qué estados son listables)
  - Validación: `./mvnw test -Dtest=ReservationStatusTest`, con cada estado.
  - Resultado: 2026-11-03. `ReservationStatusTest`: 7 tests en verde, uno por estado. El doble de `CopyingReservationProcessRepository` y el de `CreateReservationProcessUseCaseImplTest` lanzan un error si se les pide listar: el listado se prueba contra Postgres.

- [x] **TASK-02 · Consulta sobre Postgres e índice**
  - Alcance: las dos `@Query` en `JpaReservationProcessRepository`, `findListable` en el adaptador (P5 y P6) y la migración `V7`. Tests en `JpaReservationProcessRepositoryAdapterTest`. **Lo primero es comprobar que Hibernate y Postgres aceptan el `CASE` del `ORDER BY`: si no, se frena y se avisa, porque cambiaría P1.** No toca el caso de uso ni la web.
  - Depende de: TASK-01
  - Resuelve: CA-02, CA-06, CA-07, CA-08, CA-09 y CA-10 (consulta), P2
  - Validación: `./mvnw test -Dtest=JpaReservationProcessRepositoryAdapterTest`, con un instante fijo: orden completo, empates, recorrido por páginas sin repetidos ni faltantes, vencido comparado con `hasExpired` en los mismos casos, también en el límite exacto, y el índice presente en la base. Pruebas en negativo: sacar la exclusión de vencidos, sacar la de cancelados sin confirmar, invertir el orden de los pasados, quitar el desempate, sacar P5 y sacar el filtro por usuario.
  - Resultado: 2026-11-03. Hibernate y Postgres aceptan el `CASE` con parámetros en el `ORDER BY`: P1 se mantiene. `JpaReservationProcessRepositoryAdapterTest`: 47 tests en verde, 25 nuevos: orden completo, empates en los dos grupos, recorrido por páginas, más allá de la última, página enorme, sin reservas, otro usuario, filtro por estado, estados no listables, cancelado sin confirmar, vencido contra `hasExpired` en 1 s antes, en el límite exacto y 1 s después, `PHONE_SUBMITTED` vencido, confirmado con vencimiento viejo, todos los campos e índice presente. Pruebas en negativo, todas detectadas: sin excluir vencidos, 2 fallas; límite con `>` en lugar de `>=`, 2; cancelados sin confirmar, 1; pasados ascendentes, 1; próximos descendentes, 1; sin desempate, 2; sin contar primero, error en `anEnormousPageNumberIsJustAnEmptyPage`, que confirma P5; sin filtro por usuario, 1. Archivos restaurados y hash comprobado. Suite completa de la tanda: `./mvnw clean test`, exit 0, 1202 `<testcase>` (1170 + 32), 0 fallas, 0 errores, 0 salteados, 104 XML.

- [x] **TASK-03 · Caso de uso y service**
  - Alcance: `FindReservationProcessesByUserUseCase` y su `Impl`, que arma el criterio con el `Clock` y `ReservationPolicy`, sin candado ni escrituras. `findReservationProcessesByUser` en `ReservationService`. Crea su test y actualiza los que arman el service a mano.
  - Depende de: TASK-02
  - Resuelve: CA-03, CA-06 y CA-09 (el límite del vencido y la fecha y hora en la zona de atención), CA-12 y CA-14 (lógica)
  - Validación: `./mvnw test -Dtest='FindReservationProcessesByUserUseCaseImplTest,ReservationServiceTest,CatedraEventAdapterTest'`. Prueba en negativo: usar otra zona o no restar el margen.
  - Resultado: 2026-11-03. `FindReservationProcessesByUserUseCaseImplTest` (3), `ReservationServiceTest` (3) y `CatedraEventAdapterTest` (20, solo cambió el constructor) en verde. El test usa un instante en que UTC ya es el día siguiente: la fecha y hora salen de la zona de atención, y el límite descuenta el margen. El caso de uso no recibe el candado: solo usa `findListable`. Pruebas en negativo: con UTC falla 1 test; sin el margen, 1. Archivo restaurado y hash comprobado.

- [x] **TASK-04 · El endpoint**
  - Alcance: `ReservationProcessListRequest` (P3), `ReservationProcessPageResponse`, el mapper (valores por defecto, vacío como ausente y página) y el `GET /api/reservation-processes` en el controller, con sus tests.
  - Depende de: TASK-03
  - Resuelve: CA-01, CA-03, CA-04, CA-11 y CA-13 (capa web), P3
  - Validación: `./mvnw test -Dtest='ReservationProcessControllerTest,ReservationProcessDtoMapperTest'`: `401` y `403` sin llamar al service; cada valor inválido con su campo en `fieldErrors`, sin llamar al service; lo que acepta el `@Pattern` coincide con `isListable`; `500` sin motivo interno.
  - Resultado: 2026-11-03. `ReservationProcessControllerTest` (101, 32 nuevos) y `ReservationProcessDtoMapperTest` (19, 5 nuevos) en verde: valores por defecto, parámetros hasta el service y de vuelta en la página, dueño del token con otro rol, campos sin datos privados, cada estado aceptado solo si `isListable`, 12 valores inválidos con su campo y sin llamar al service, límites de página y tamaño, `500` sin motivo interno, `401` y `403`. Parámetros vacíos: `?status=&page=&size=` responde como sin parámetros con `MockMvc`; contra el servidor real queda para TASK-05. Pruebas en negativo: aceptar `EXPIRED` falla 2; sin el máximo de `size`, 1; dueño fijo, 1; tamaño por defecto 20, 9 en el controller y 1 en el mapper. Archivos restaurados y hash comprobado. Suite completa de la tanda: `./mvnw clean test`, exit 0, 1243 `<testcase>` (1202 + 41), 0 fallas, 0 errores, 0 salteados, 105 XML.

- [x] **TASK-05 · Integración con la app completa**
  - Alcance: crea `ReservationProcessListIntegrationTest`, con Postgres real, cátedra y catálogo simulados, y el estado de partida escrito en la base. Cubre el recorrido por HTTP, el parámetro vacío (D3), la página enorme, el candado tomado, sin cambios en las filas ni requests externos, y JSON y log sin datos privados. Si D3 no funciona como se espera, se ajusta el mapper o el DTO y se avisa.
  - Depende de: TASK-04
  - Resuelve: CA-02, CA-03, CA-05, CA-06, CA-07, CA-09, CA-10, CA-11, CA-12, CA-14 y CA-15
  - Validación: `./mvnw test -Dtest=ReservationProcessListIntegrationTest`, registrando qué respondió de verdad con parámetros vacíos.
  - Resultado: 2026-11-03. `ReservationProcessListIntegrationTest`: 37 tests en verde, con Postgres real. **D3 comprobada:** `?status=&page=&size=` contra el servidor real responde exactamente lo mismo que sin parámetros, sin ajustar el mapper ni el DTO. También: valores por defecto, 8 valores inválidos con su campo, `401`, dueño, filtro por estado, vencidos sin listar y sin cambios en la fila, dentro del margen, `PHONE_SUBMITTED` vencido listado y sin marca, no listables, recorrido por páginas con el abierto, turnos de hoy y empates, más allá de la última con un número enorme, campos por estado, respuesta con el candado tomado, ninguna fila cambiada ni request externo, y JSON y log sin datos privados. Pruebas en negativo: si el listado espera el candado, `itAnswersWhileAnotherOperationOfTheUserHoldsTheLock` falla a los 45 s; si escribe la marca, fallan 2; si rechaza el vacío, falla 1. La primera versión de la rotura del candado no compiló y no valió como evidencia: se repitió sin cambiar el constructor. Archivos restaurados y hash comprobado. Suite completa de la tanda: `./mvnw clean test`, exit 0, 1280 `<testcase>` (1243 + 37), 0 fallas, 0 errores, 0 salteados, 106 XML.

- [x] **TASK-06 · Listado con la base caída de verdad**
  - Alcance: amplía `DatabaseDownStatusQueryIntegrationTest` con el listado: Postgres en pausa, con la conexión comprobada y con una recién usada, y al reanudar responde con la página.
  - Depende de: TASK-04
  - Resuelve: CA-13
  - Validación: `./mvnw test -Dtest=DatabaseDownStatusQueryIntegrationTest`
  - Resultado: 2026-11-03. `DatabaseDownStatusQueryIntegrationTest`: 7 tests en verde, 2 nuevos: con Postgres en pausa, tanto al pedir la conexión como con una recién usada, el listado responde `500` `INTERNAL_ERROR`, la fila queda igual y, al reanudar, responde la página. El teléfono de la fila no llega al log. Pruebas en negativo: si el caso de uso convierte la falla en una lista vacía, fallan los 2; sin el límite de espera, la consulta con la conexión recién usada se corta a los 45 s. Archivos restaurados y hash comprobado. Suite completa de la tanda: `./mvnw clean test`, exit 0, 1282 `<testcase>` (1280 + 2), 0 fallas, 0 errores, 0 salteados, 106 XML, 3 min 15 s.

- [x] **TASK-07 · Documentación del servicio**
  - Alcance: `CLAUDE.md` (T10 en el slice y en la lista de `docs/sdd`, `V7`, cómo se resuelve la regla del vencido en la consulta) y `docs/decisiones/012-persistencia-del-proceso-de-reserva.md` (el índice y su motivo). No toca contratos ni casos de uso, que ya se ajustaron en la SPEC.
  - Depende de: TASK-02, TASK-04
  - Resuelve: P2 (documentar el índice)
  - Validación: releer los dos textos contra el código final.
  - Resultado: 2026-11-03. `CLAUDE.md`: T10 en el slice y en la lista de `docs/sdd`, `V7`, dónde vive la regla del vencido y qué estados se listan, y `DatabaseDownStatusQueryIntegrationTest` con el listado. Decisión 012: actualización con el índice y su motivo. Comparado contra el código: `isListable`, `LISTABLE` y el test citado existen.

- [x] **TASK-08 · Suite completa y revisión de los criterios**
  - Alcance: correr toda la suite y revisar CA-01 a CA-16 contra lo observado. Comprobar que los tests existentes solo cambiaron en lo previsto.
  - Depende de: TASK-01 a TASK-07
  - Resuelve: CA-16
  - Validación: `./mvnw clean test`, con el conteo tomado de `target/surefire-reports/TEST-*.xml` por `<testcase>`.
  - Resultado: 2026-11-03. `./mvnw clean test`, exit 0, 1282 `<testcase>` (al empezar T10: 1170), 0 fallas, 0 errores, 0 salteados, 106 XML, 196 s. CA-01 a CA-16 revisados contra la evidencia de TASK-01 a TASK-06: todos cubiertos por tests ejecutados. Tests existentes cambiados, comparando contra `develop`: los dobles del puerto (método nuevo), `CatedraEventAdapterTest` y `ReservationServiceTest` (constructor del service), y agregados en los demás. Copias de contrato, casos de uso, secuencia y dominio iguales a `documentacion`. No verificado: la app levantada con el `.env` real, porque hace el login contra la cátedra (D4), y Postgres caído cerrando sus conexiones; solo se probó congelado.
