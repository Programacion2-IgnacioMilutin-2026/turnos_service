# PLAN: T05 — Confirmar reserva (confirmación inicial)

**SPEC de referencia:** `docs/sdd/t05-confirmar-reserva/SPEC.md`
**Versión de la spec revisada:** aprobada por el usuario el 2026-10-28 (D1 a D7), sin commit todavía
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
| Caso de uso de T04 y su candado por usuario | `reservation/application/usecases/CreateReservationProcessUseCaseImpl.java` | Bloquea el turno. El candado por usuario (mapa de `ReentrantLock` con contador, descartado al soltarse) es un campo privado: T05 necesita el mismo candado (D5 de la SPEC) |
| Modelo y estados del proceso | `reservation/domain/model/ReservationProcess.java`, `ReservationStatus.java` | `hasExpired` y `expireIfOverdue` ya implementan la regla de vencimiento con margen (RF-04). Falta la marca de reconciliación (RF-22) |
| Política | `reservation/domain/model/ReservationPolicy.java`, `infrastructure/config/ReservationProperties.java` | Zona y margen (`TURNOS_RESERVATION_SAFETY_MARGIN`, 30 s): T05 usa el mismo margen (D4) |
| Puertos de salida de `reservation` | `reservation/domain/ports/out/ReservationProcessRepository.java`, `UserDirectory.java`, `HoldProvider.java` | `create`, `update(id, proceso)` y `findOpenByUserId`; `exists(userId)`; `requestHold`. T05 los amplía |
| Persistencia del proceso | `reservation/infrastructure/persistence/` y `src/main/resources/db/migration/V2__create_reservation_processes.sql` | Tabla `reservation_processes` con PK `reservation_process_id`, FK a `users` e índice parcial de un abierto por usuario (decisión 012). `update` usa `saveAndFlush` y exige el proceso con `requireNonNull` |
| Controller, DTO de respuesta y mapper web | `reservation/infrastructure/web/controller/ReservationProcessController.java`, `dto/ReservationProcessResponse.java`, `mapper/ReservationProcessDtoMapper.java` | `POST /api/reservation-processes`; errores con `ErrorResponseException` y `code`. La respuesta del proceso (14 campos) se reutiliza tal cual |
| Pasarelas de `reservation` | `reservation/infrastructure/gateway/UserExistenceAdapter.java`, `CatedraHoldAdapter.java`, `CatalogProfessionalAdapter.java` | Implementan los puertos de salida llamando a los puertos de entrada de `user`, `catedra` y `catalog` (decisión 009) |
| Consulta a `user` | `user/domain/ports/in/ExistsUserByIdUseCase.java` y su `Impl` | Solo responde si el usuario existe. T05 necesita además el nombre y el apellido (RF-05) |
| Pieza común de la cátedra | `catedra/application/support/CatedraOperationRunner.java`, `catedra/domain/model/CatedraResponse.java` | Sesión, `401` con una repetición y reintentos de lo `Unavailable` (decisión 011) |
| Hold en la cátedra | `catedra/domain/ports/in/CreateHoldUseCase.java`, `application/usecases/CreateHoldUseCaseImpl.java`, `domain/model/HoldResult.java`, `domain/ports/out/CatedraHoldClient.java`, `infrastructure/client/CatedraHoldClientAdapter.java` y sus DTO y mapper | Crear un hold con un solo intento por llamada del cliente. T05 suma la confirmación del mismo hold |
| Tests de referencia | `CreateReservationProcessUseCaseImplTest`, `JpaReservationProcessRepositoryAdapterTest`, `ReservationProcessControllerTest`, `ReservationProcessIntegrationTest`, `CatedraHoldClientAdapterTest`, `CatedraOperationRunnerTest`, `e2e/ReservationProcessE2ETest` | Patrones de T04 que T05 sigue o amplía |

**Convenciones y slice de referencia:** el slice `reservation` de T04 y la operación de hold de `catedra`, con las excepciones a la skill de `CLAUDE.md`. Las respuestas reales de la confirmación de la verificación de D2 están en el scratchpad de la sesión, sin limpiar ni versionar todavía.

## Solución propuesta

<!-- Explica el enfoque y sus motivos. Describe las responsabilidades y el
recorrido de la solicitud, el evento o la tarea desde que entra hasta la
respuesta, la persistencia o el mensaje publicado. Referencia el diagrama de
secuencia del proyecto si ya lo describe; usa un diagrama nuevo solo si aporta
claridad. -->

El recorrido sigue el diagrama de secuencia §3 (bloque T05) y el caso de uso T05 con las decisiones de la SPEC:

1. `ReservationProcessController` recibe `POST /api/reservation-processes/{reservationProcessId}/confirm` con el JWT ya validado, toma el `sub` como `userId` (decisión 008) y llama a `ReservationService`, que delega en `ConfirmReservationProcessUseCase`.
2. El caso de uso trabaja dentro del candado del usuario (`UserOperationLock`, P1), el mismo que ahora usa T04: busca el proceso del usuario (`findByIdAndUserId`, P4.1), revisa el estado y el vencimiento con el margen de T04, pide el nombre del paciente a `user` por `UserDirectory` (P2) y la confirmación a `catedra` por `HoldProvider` (P3), y guarda el resultado con las transiciones del modelo (`confirm`, `expireIfOverdue`, `markForReconciliation`, P5.1).
3. En `catedra`, `ConfirmHoldUseCaseImpl` usa `CatedraOperationRunner` (sesión, `401` con una repetición, reintentos) y `CatedraHoldClientAdapter.confirmHold`, que hace un intento y clasifica la respuesta: definitivas como `Answered`, y todo lo que no llegó o se perdió como `Unavailable`, que se reintenta (D1).
4. Las fallas siguen P5: la cátedra no disponible deja el proceso en `HELD` marcado (`503`); un guardado que falla después del `202` intenta marcar y responde `500` `LOCAL_STATE_OUT_OF_SYNC`.
5. La respuesta `200` reutiliza `ReservationProcessResponse`.

Las llamadas a la cátedra no ocurren dentro de una transacción de base: cada escritura del repositorio es su propia transacción con `saveAndFlush`, como en T04, y el candado ordena las operaciones del usuario.

## Slices y componentes afectados

<!-- Aplica la skill hexagonal-vertical-slicing: un paquete por slice, con sus
capas domain, application e infrastructure. Identifica los slices afectados, sus
responsabilidades y la dirección de sus dependencias. Una funcionalidad puede
afectar más de un slice. Justifica cualquier slice nuevo o dependencia nueva
entre slices. -->

| Slice | Existe / nuevo | Responsabilidad y cambios | Dependencias con otros slices |
| --- | --- | --- | --- |
| `turnos.reservation` | Existente | Caso de uso de confirmar, endpoint, candado compartido, marca de reconciliación y búsqueda por dueño | Usa `user` (ahora también el nombre), `catedra` (ahora también la confirmación) y `catalog` (sin cambios), siempre por `infrastructure/gateway` |
| `turnos.user` | Existente | Puerto de entrada nuevo para el nombre y apellido (P2) | Ninguna nueva; lo usa `reservation` |
| `turnos.catedra` | Existente | Operación de confirmar un hold (P3) | Ninguna nueva; lo usa `reservation` |

No hay slices nuevos ni dependencias nuevas entre slices: las tres relaciones ya existían en T04.

| Slice y capa | Componente o ruta | Acción | Cambio y responsabilidad | Requisito relacionado |
| --- | --- | --- | --- | --- |
| `user` dominio | `domain/ports/in/GetUserNameUseCase.java` | Crear | Devuelve el nombre y apellido de un usuario, si existe | RF-05 |
| `user` dominio | `domain/model/UserName.java` | Crear | Solo `firstName` y `lastName`: lo único que sale del slice | RF-05 |
| `user` dominio | `domain/ports/out/UserRepository.java` | Modificar | Suma `findById` | RF-05 |
| `user` aplicación | `application/usecases/GetUserNameUseCaseImpl.java` | Crear | Lee el usuario y entrega solo el nombre | RF-05 |
| `user` infraestructura | `infrastructure/persistence/adapter/JpaUserRepositoryAdapter.java` | Modificar | Implementa `findById` | RF-05 |
| `catedra` dominio | `domain/ports/in/ConfirmHoldUseCase.java`, `domain/model/HoldConfirmationResult.java` | Crear | Puerto y resultado sellado de la confirmación | RF-06 a RF-16 |
| `catedra` dominio | `domain/ports/out/CatedraHoldClient.java` | Modificar | Suma `confirmHold` | RF-06 |
| `catedra` aplicación | `application/usecases/ConfirmHoldUseCaseImpl.java` | Crear | Usa `CatedraOperationRunner` y traduce la respuesta | RF-07, RF-08, RF-15 |
| `catedra` infraestructura | `infrastructure/client/CatedraHoldClientAdapter.java` | Modificar | `confirmHold`: un intento, clasificación de D1 | RF-06 a RF-16 |
| `catedra` infraestructura | `infrastructure/client/dto/ConfirmHoldRequest.java`, `dto/HoldConfirmationResponse.java` | Crear | Cuerpo del pedido y de la respuesta `202` | RF-06, RF-17 |
| `catedra` infraestructura | `infrastructure/client/mapper/HoldMapper.java` | Modificar | Convierte la respuesta `202` | RF-17 |
| `reservation` dominio | `domain/model/ReservationProcess.java` | Modificar | `needsReconciliation`, `confirm(expiresAt)` y `markForReconciliation()` | RF-13, RF-17, RF-22 |
| `reservation` dominio | `domain/model/ReservationPatient.java`, `domain/model/ReservationHoldConfirmation.java` | Crear | Nombre del paciente; resultado de confirmar (vencimiento, o "ya confirmado") | RF-05, RF-13, RF-17 |
| `reservation` dominio | `domain/ports/in/ConfirmReservationProcessUseCase.java` | Crear | Puerto de entrada de T05 | RF-01 |
| `reservation` dominio | `domain/ports/out/ReservationProcessRepository.java`, `UserDirectory.java`, `HoldProvider.java` | Modificar | `findByIdAndUserId` y `markForReconciliation`; nombre del paciente; `confirmHold` | RF-02, RF-05, RF-06, RF-18 |
| `reservation` aplicación | `application/support/UserOperationLock.java` | Crear | El candado por usuario, movido desde T04 (P1) | RF-21 |
| `reservation` aplicación | `application/usecases/CreateReservationProcessUseCaseImpl.java` | Modificar | Usa `UserOperationLock` en lugar de su campo privado; mismo comportamiento | RF-21 |
| `reservation` aplicación | `application/usecases/ConfirmReservationProcessUseCaseImpl.java` | Crear | Recorrido de P5 | RF-02 a RF-19, RF-21 |
| `reservation` aplicación | `application/service/ReservationService.java` | Modificar | Suma confirmar | RF-01 |
| `reservation` aplicación | `application/exception/` (6 excepciones de P6) | Crear | Errores propios de T05 | RF-02 a RF-18 |
| `reservation` infraestructura | `infrastructure/persistence/entity/ReservationProcessEntity.java`, `mapper/ReservationProcessMapper.java` | Modificar | Columna `needs_reconciliation` | RF-22 |
| `reservation` infraestructura | `infrastructure/persistence/repository/JpaReservationProcessRepository.java`, `adapter/JpaReservationProcessRepositoryAdapter.java` | Modificar | Búsqueda por id y dueño; `UPDATE` de la marca | RF-02, RF-18 |
| `reservation` infraestructura | `src/main/resources/db/migration/V3__add_reservation_process_reconciliation_flag.sql` | Crear | Columna con `DEFAULT FALSE` | RF-22, CA-15 |
| `reservation` infraestructura | `infrastructure/gateway/UserExistenceAdapter.java` → `UserDirectoryAdapter.java` | Renombrar y modificar | Suma el nombre del paciente con `GetUserNameUseCase` | RF-05 |
| `reservation` infraestructura | `infrastructure/gateway/CatedraHoldAdapter.java` | Modificar | `confirmHold` con `ConfirmHoldUseCase`; rechazos a excepciones; error grave ante `HOLD_OWNER_MISMATCH` | RF-06 a RF-16 |
| `reservation` infraestructura | `infrastructure/web/controller/ReservationProcessController.java` | Modificar | Endpoint de confirmar y sus errores (P6) | RF-01 a RF-19 |

## Datos y contratos

<!-- Completa solo lo aplicable. Si un punto no aplica, indica el motivo. -->

- **Operaciones, eventos y DTO de entrada y salida:** contrato de turnos §3.5 (sin body; respuesta `200` con el proceso de §2) y §4.2.5 (confirmar en la cátedra), ya corregidos según D2. No se publican ni consumen eventos.
- **Identificadores, relaciones y restricciones:** el proceso se busca por `reservationProcessId` y dueño. `externalPatientId` es el `userId` como texto, igual que en T04. El `holdId` sale del proceso guardado y nunca se expone.
- **Origen de los datos devueltos o publicados y transformaciones:** el vencimiento viene del `202` de la cátedra o, con "ya confirmado", es el que el proceso ya tenía (D2). El nombre y apellido del paciente vienen de `user` en cada confirmación. Lo demás es el proceso guardado.
- **Persistencia, consultas y migraciones:** `V3` suma `needs_reconciliation`; `findByIdAndUserId` por clave primaria y dueño; `markForReconciliation` actualiza una sola columna; `update` existente para `EXPIRED`, para `HELD` marcado y para `WAITING_FOR_PHONE`.
- **Convivencia entre datos locales y remotos:** la cátedra es la fuente de verdad (dominio regla 15). Cuando no se sabe si avanzó (4.9, 6.1), el proceso queda marcado para T13.
- **Compatibilidad y migraciones de datos existentes:** los procesos que ya existen quedan con la marca en falso. `V2` no cambia.

## Transacciones, concurrencia y errores

<!-- Cómo se implementan los comportamientos aprobados en la spec.
Referencia RF/CA y aplica las consideraciones relevantes de BACKEND_GUIDELINES.md. -->

- **Estados y transiciones:** `HELD → WAITING_FOR_PHONE` (`confirm`, solo desde `HELD`) y `HELD → EXPIRED` (`expireIfOverdue`, o ante `HOLD_EXPIRED`). Cualquier otro estado corta antes con `INVALID_PROCESS_STATE`. Un estado final nunca se reabre (dominio regla 6), lo que además garantiza el candado (RF-21).
- **Transacciones y consistencia:** sin transacción que abarque la llamada a la cátedra. Cada escritura es atómica por separado. Ante 6.1, la marca se intenta con un `UPDATE` aparte (P4.2); si también falla, queda el error crítico con el `reservationProcessId` (RF-18).
- **Idempotencia y mensajes duplicados:** un reintento propio o de la app sobre una confirmación ya procesada vuelve como "ya confirmado" y termina en `200` (D1, D2). Un reintento de la app sobre un proceso ya registrado en `WAITING_FOR_PHONE` recibe `409` `INVALID_PROCESS_STATE`.
- **Concurrencia y tareas programadas:** un solo candado por usuario para T04 y T05 (P1, RF-21); usuarios distintos no se esperan. No cubre más de una instancia; la base no protege este caso, porque el índice parcial solo impide dos procesos abiertos.
- **Timeouts, reintentos y errores de dependencias:** los tiempos y reintentos de `turnos.catedra.operations` (los de T03 y T04). La confirmación reintenta toda falla que no es una respuesta definitiva (D1). La traducción de cada respuesta está en P3 y P6.
- **Seguridad y autorización:** la cadena de seguridad exige el JWT; el dueño sale del `sub`; un proceso ajeno da `404`, igual que uno inexistente (RF-02, D7). El JWT técnico nunca sale de `catedra`.
- **Otras consideraciones de backend aplicables y su solución:** observabilidad: cada reintento con su número y su motivo (ya lo hace el runner); errores graves para `HOLD_OWNER_MISMATCH` y 6.1; sin tokens ni datos del paciente en los logs, salvo el `reservationProcessId`.

## Dependencias y configuración

<!-- Librerías, servicios externos, propiedades de configuración y secretos
afectados. Verifica compatibilidad con el proyecto y justifica las incorporaciones.
No agregues dependencias por defecto. Los valores sensibles y los parámetros
ajustables se externalizan. -->

- Sin dependencias nuevas. Sin variables nuevas: el margen es el de T04 (D4) y los tiempos y reintentos de la cátedra son los de `turnos.catedra.operations`.

## Estrategia de validación

<!-- Una fila por criterio de la spec. Selecciona el método capaz de demostrarlo:
test unitario, de integración (base de datos, mensajería), de API o prueba manual.
No todos requieren todos los métodos. Identifica tests existentes y separa los
nuevos propuestos. Incluye regresiones relevantes. Una prueba con mocks no
demuestra persistencia, transacciones ni idempotencia. -->

| Criterio | Método y test existente o propuesto | Entorno y datos necesarios | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01 | Unitario del caso de uso (nuevo `ConfirmReservationProcessUseCaseImplTest`); web (`ReservationProcessControllerTest`, ampliado); integración (`ReservationProcessIntegrationTest`, ampliado) con un token de un `sub` sin usuario | Dobles; `@WebMvcTest`; app completa con Postgres de Testcontainers | `404` idéntico; cátedra simulada sin requests |
| CA-02 | Unitario del caso de uso | Procesos en cada estado | `409` con `currentStatus`; proceso igual |
| CA-03 | Unitario con `MutableClock` | Margen por defecto y configurado | 29 s llama; 31 s `EXPIRED` sin llamar |
| CA-04 | Cliente (`CatedraHoldClientAdapterTest`, ampliado) con la grabación `202`; caso de uso; web; integración | Cátedra simulada que guarda requests | Request exacto; proceso guardado; respuesta exacta |
| CA-05, CA-06 | `ConfirmHoldUseCaseImplTest` (nuevo) sobre el runner ya probado | `ScriptedLoginClient`, cliente simulado | Un login y una repetición; `SERVICE_DEGRADED` o `CENTRAL_UNAVAILABLE` |
| CA-07, CA-08, CA-09 | Cliente con las grabaciones (`404` hold, `400` paciente, `404` vencido, `409` ya confirmado) y las armadas a mano (`403` dueño, profesional, turno); gateway (`CatedraHoldAdapterTest`, ampliado); caso de uso | Cátedra simulada | Código de turnos de cada caso; proceso según corresponda; log |
| CA-10 | Cliente (fallas de red y `500`/`503`); `ConfirmHoldUseCaseImplTest` con fallas y después `202` o "ya confirmado"; caso de uso con la cátedra no disponible | Servidor simulado apagado, con demoras y con cada estado | Intentos según la configuración y en el log; proceso `HELD` marcado en la base |
| CA-11, CA-12 | Caso de uso con un repositorio que falla al actualizar (y al marcar); `JpaReservationProcessRepositoryAdapterTest` para `markForReconciliation` contra Postgres real | Doble que falla; Testcontainers | `500` con el código; error crítico; marca en la base |
| CA-13 | Integración y caso de uso con `OutputCaptureExtension` | Salida capturada | Sin el JWT del usuario ni el técnico |
| CA-14 | Caso de uso con hilos reales y la cátedra demorada (como el test de concurrencia de T04); test de `UserOperationLock` | Dobles con demora | Un `200` y un `409`; una sola confirmación; ningún estado final reabierto |
| CA-15 | `JpaReservationProcessRepositoryAdapterTest` (migración sobre procesos existentes; marca que persiste); web (la marca no aparece) | Testcontainers | Marca en falso en los existentes; nunca en las respuestas |
| CA-16 | `ReservationProcessIntegrationTest`, ampliado | App completa, cátedra simulada con las grabaciones | `200` y después `409` |
| CA-17 | `e2e/ReservationProcessE2ETest`, ampliado; corrido por el usuario en su terminal | Cátedra real, credenciales del `.env` | `200` real con el mismo vencimiento; aviso de cancelación; salteado sin credenciales |

**Comprobaciones de regresión:** todos los tests de T04 siguen en verde después de mover el candado (en especial los de concurrencia de `CreateReservationProcessUseCaseImplTest`); el resto de la suite (T01, T02, X01, validación del JWT, T03); los chequeos de la skill.

**Comandos verificados para compilar y ejecutar tests:** los de `CLAUDE.md`: `./mvnw test -Dtest=…`, `./mvnw clean test`, `./mvnw clean verify` y `./mvnw test -Pe2e`.

**Pruebas con infraestructura real o simulada:** Postgres de Testcontainers para persistencia e integración; la cátedra simulada con `FakeHttpServer`/`FakeCatedraServer` y las grabaciones; la cátedra real solo en la grabación (TASK-01), el E2E y la prueba manual, corridos por el usuario después de confirmar cada request.

**Limitaciones del entorno:** el modo automático bloquea las escrituras a la cátedra: el usuario corre los scripts y el E2E. `HOLD_OWNER_MISMATCH`, `500`, `503` y los timeouts de la cátedra real no se pueden provocar: se simulan. La concurrencia entre varias instancias no se prueba (fuera del alcance de D5).

<!-- Esta sección planifica la validación. Durante la implementación, registra
en TASKS.md o en el informe de validación acordado los resultados y evidencias
reales. Distingue pruebas ejecutadas, fallidas, no ejecutadas y bloqueadas.
Compilar o tener tests en verde no sustituye revisar los criterios de la spec. -->

## Orden de implementación

<!-- Etapas y dependencias principales. El desglose ejecutable se escribe en TASKS.md.
Incluye puntos de comprobación para avanzar con cambios pequeños. -->

1. **Grabaciones (P7):** limpiar y versionar las dos de la verificación de D2; script para las otras cuatro (con una espera de unos 10 minutos y medio), corrido por el usuario. Comprobación: archivos limpios y `README` actualizado.
2. **`user`:** `GetUserNameUseCase` y `findById`. Comprobación: sus tests y los de `user` en verde.
3. **`catedra`:** confirmación en el cliente, resultado y caso de uso. Comprobación: tests del cliente con las grabaciones y del caso de uso.
4. **`reservation` dominio y persistencia:** modelo, `V3`, entidad, mapper, repositorio. Comprobación: tests del modelo y del adaptador JPA contra Postgres.
5. **Candado compartido:** mover el candado a `UserOperationLock` y usarlo en T04. Comprobación: los tests de T04 sin cambios de comportamiento.
6. **Pasarelas:** `UserDirectoryAdapter` y `CatedraHoldAdapter`. Comprobación: sus tests.
7. **Caso de uso de T05 y service.** Comprobación: tests del caso de uso, incluida la concurrencia.
8. **Web:** endpoint y errores. Comprobación: tests de la capa web.
9. **Integración con la aplicación completa.** Comprobación: `ReservationProcessIntegrationTest` ampliado y la suite completa.
10. **E2E ampliado,** corrido por el usuario. Comprobación: reportes de Surefire.
11. **Prueba manual con la aplicación levantada** (bloquear, confirmar, confirmar de nuevo), con los requests confirmados antes. Comprobación: requests y respuestas registrados.
12. **Cierre:** `CLAUDE.md`, decisión 013, `./mvnw clean verify`, chequeos de la skill y cobertura de los CA.

## Riesgos y decisiones pendientes

<!-- Riesgos concretos de esta solución y cómo se resolverán, sin listas genéricas.
Escribe Ninguna en las decisiones pendientes cuando estén resueltas. -->

- **Riesgos y medidas acordadas:**
  - Mover el candado puede romper T04: se mueve sin cambiar su código y los tests de concurrencia de T04 lo cubren (paso 5).
  - T07 (Kafka) todavía no existe: un proceso que la cátedra confirmó y turnos no registró (4.9, 6.1) queda en `HELD` marcado hasta T07 o T13. Lo cubre la marca.
  - Confirmar no extiende el plazo: el usuario tiene lo que queda de los 10 minutos del hold para confirmar e informar el teléfono. Es comportamiento de la cátedra; se deja anotado para la app y T06.
  - Cada corrida del E2E y de la prueba manual deja una reserva `PHONE_PENDING` que no se puede cancelar (consulta al profesor pendiente) y vence a los 10 minutos. Hay seis turnos el lunes: más de seis corridas en 10 minutos se quedarían sin turno libre.
  - El script de grabación espera unos 10 minutos y medio; si se corta, se vuelve a correr entero.
- **Decisiones pendientes:**
  - **P1 · Dónde vive el candado por usuario compartido (D5), opción a:** se extrae del caso de uso de T04 a una pieza de `reservation/application/support/` (`UserOperationLock`, con un método que ejecuta un trabajo con exclusividad para un usuario), inyectada en los casos de uso de T04 y T05. Spring crea una sola instancia, así que los dos usan el mismo candado. Es una excepción a la skill del mismo tipo que `catedra/application/support` (decisión 011), autorizada por el usuario: no es un caso de uso ni se ofrece a otros slices. Motivo: la regla ("las operaciones de un mismo usuario se atienden de a una") es de la aplicación y el mecanismo es Java puro (`java.util.concurrent`), sin tecnología externa que pueda cambiar; hoy hay una sola instancia del servicio. Si algún día hay varias, se pasa a la opción b. Alternativas descartadas: (b) un puerto de salida con un adaptador en memoria en `infrastructure`: separa la regla del mecanismo y permitiría un candado distribuido, pero son dos piezas y un paquete más para algo que hoy no hace falta (ya descartado en la decisión 012); (c) que T05 tome el candado llamando a T04: un caso de uso dependería de otro.
  - **P2 · Cómo obtiene `reservation` el nombre y apellido del usuario (RF-05), opción a:** un puerto de entrada nuevo en `user` que devuelve solo el nombre y el apellido, en un tipo propio de `user` con esos dos datos (`GetUserNameUseCase`). En `reservation`, el puerto `UserDirectory` suma un método para el nombre del paciente, y el adaptador existente `UserExistenceAdapter` lo implementa llamando a ese puerto; pasa a llamarse `UserDirectoryAdapter`, porque ya no solo pregunta si existe. Motivo: el mismo criterio que `ExistsUserByIdUseCase` (decisión 009): ningún dato del usuario sale del slice de más, y se amplía lo existente en lugar de duplicarlo. Alternativas descartadas: (b) un puerto que devuelva el `User` completo: saca del slice el hash de la contraseña, el email y los roles, y ata a `reservation` a cualquier cambio del modelo de `user`; (c) guardar el nombre del paciente en el proceso al bloquear: cambia T04 y usa el nombre del momento del bloqueo, no el actual (RF-05).
  - **P3 · Piezas de la confirmación en `catedra` y cómo clasifica las fallas el cliente (RF-06 a RF-16, D1), opción a:** `catedra` suma un caso de uso propio (`ConfirmHoldUseCase` y su `Impl`, que usa `CatedraOperationRunner`) y un resultado sellado propio (`HoldConfirmationResult`: `Confirmed` con el vencimiento y el `reservationProcessId`, `AlreadyConfirmed` sin datos, un caso por cada rechazo de negocio, y `ConfigurationError`, `Unavailable` y `Failed`). El cliente HTTP se amplía: `CatedraHoldClient` y `CatedraHoldClientAdapter` suman `confirmHold`, porque es el mismo recurso (`/api/appointment-holds`) y ya tienen el `RestClient` con sus timeouts y la lectura de errores. Para la confirmación, todo lo que no es una respuesta definitiva (conexión rechazada, timeouts de conexión y de respuesta, corte, `503` y `500`) es `Unavailable`, porque reintentar es seguro (D1); `Uncertain` queda solo para el hold. En `reservation`, `HoldProvider` suma `confirmHold` y lo implementa el `CatedraHoldAdapter` existente: los rechazos se traducen a excepciones de `reservation`, como en T04, y "ya confirmado" vuelve como resultado normal, sin vencimiento. Alternativa descartada: (b) un cliente nuevo para la confirmación: duplica la configuración del `RestClient` y la lectura de errores, u obliga a extraerlas.
  - **P4 · Persistencia (RF-02, RF-18, RF-22):** **P4.1, opción a:** el repositorio suma `findByIdAndUserId`, que solo devuelve el proceso si es de ese usuario; un proceso ajeno es igual a uno inexistente sin que el caso de uso compare dueños, y no queda un `findById` suelto que T09 o T11 pudieran usar sin el control. Descartada: (b) buscar por id y comparar el dueño en el caso de uso. **P4.2, opción a:** el repositorio suma `markForReconciliation(id)`, un `UPDATE` de una sola columna, para 6.1: no vuelve a escribir el estado ni los demás campos, así que no repite el error del guardado que falló. En 4.9 alcanza con el `update` existente (proceso en `HELD` con la marca). Descartada: (b) reusar `update` con el proceso marcado: reescribe la fila entera y probablemente falle igual. **Fijo:** la migración `V3` suma `needs_reconciliation` `BOOLEAN NOT NULL DEFAULT FALSE` (los procesos existentes quedan sin marcar, CA-15); el modelo, la entidad y el mapper de persistencia suman el campo; ninguna respuesta lo expone.
  - **P5 · Recorrido del caso de uso y manejo de 4.9 y 6.1 (RF-15, RF-18, D3):** `ConfirmReservationProcessUseCaseImpl`, todo dentro del candado del usuario (P1): (1) `findByIdAndUserId`, si no está `404` `RESERVATION_PROCESS_NOT_FOUND`; (2) si no está en `HELD`, `409` `INVALID_PROCESS_STATE` con el estado; (3) si venció con el margen, lo guarda `EXPIRED` y `409` `HOLD_EXPIRED` sin llamar a la cátedra; (4) nombre del paciente (P2); (5) confirmación (P3): `HOLD_EXPIRED` guarda `EXPIRED` y responde `409` `HOLD_EXPIRED`; la cátedra no disponible guarda el proceso en `HELD` y marcado y responde `503` `CENTRAL_UNAVAILABLE`; los demás rechazos, su excepción sin tocar el proceso; (6) toma el vencimiento de la cátedra con `202` o el que ya tenía con "ya confirmado", pasa a `WAITING_FOR_PHONE` y guarda; (7) si ese guardado falla, error crítico en el log, intento de `markForReconciliation` y `500` `LOCAL_STATE_OUT_OF_SYNC`. **P5.1, opción a:** las transiciones viven en el modelo, como `expireIfOverdue` de T04: `ReservationProcess` suma `confirm(expiresAt)` (solo desde `HELD`, pasa a `WAITING_FOR_PHONE`) y `markForReconciliation()`; el caso de uso le pide al proceso que cambie y el proceso valida la transición, que se prueba sin base ni Spring. Descartada: (b) setters en el caso de uso: las reglas de transición se repetirían en T06, T07, T08 y T11. **P5.2, opción a:** si en el paso 5 tampoco se puede guardar la marca, responde igual `503` `CENTRAL_UNAVAILABLE` con un error en el log: el problema principal es la cátedra y el proceso sigue en `HELD`, sin nada inconsistente. Descartada: (b) `500` `INTERNAL_ERROR`: escondería que la cátedra tampoco respondió.
  - **P6 · Errores HTTP (confirmada por el usuario):** un método nuevo en `ReservationProcessController` (`POST /api/reservation-processes/{reservationProcessId}/confirm`), con el mismo método auxiliar de errores (`ErrorResponseException` con `code`); `ReservationService` suma el método de confirmar; la respuesta reutiliza `ReservationProcessResponse` y su mapper. Excepciones nuevas en `reservation/application/exception`: `ReservationProcessNotFoundException` (`404` `RESERVATION_PROCESS_NOT_FOUND`), `ReservationInvalidProcessStateException` (`409` `INVALID_PROCESS_STATE` con `currentStatus`), `ReservationHoldExpiredException` (`409` `HOLD_EXPIRED`), `ReservationHoldNotFoundException` (`404` `HOLD_NOT_FOUND`), `ReservationPatientMismatchException` (`409` `PATIENT_MISMATCH`) y `ReservationLocalStateOutOfSyncException` (`500` `LOCAL_STATE_OUT_OF_SYNC`). Se reutilizan las de T04 para profesional inexistente, turno inválido, turno no disponible, servicio degradado y cátedra no disponible. `HOLD_OWNER_MISMATCH` usa la excepción de integración existente (`500` `INTERNAL_ERROR`) y el error grave lo registra el adaptador de `reservation` que la detecta. El `reservationProcessId` de la ruta no se valida: cualquier texto se busca tal cual y uno inexistente (incluso más largo que la columna) da `404`.
  - **P7 · Grabaciones y E2E (D6, confirmada por el usuario):** se versionan en `src/test/resources/catedra/`, limpias y anotadas en su `README.md`: `confirm-202-accepted.json` y `confirm-409-already-confirmed.json` (de la verificación de D2), y `confirm-404-hold-not-found.json`, `confirm-401-invalid-token.json` (vacío), `confirm-400-external-patient-mismatch.json` y `confirm-404-hold-expired.json` (de un script nuevo: login, ocupaciones del lunes, confirmar un `holdId` inventado, confirmar con un token inválido, hold nuevo, confirmarlo con otro paciente, esperar unos 10 minutos y medio y confirmarlo bien), corrido por el usuario después de confirmar la lista exacta. El `409` de cancelar un `WAITING_FOR_PHONE` no se versiona (ningún test lo usa) y se anota en el `README`. Se amplían `ReservationProcessIntegrationTest` (bloquear y confirmar) y el E2E de T04 (confirmar después de bloquear, mismo vencimiento; el intento de cancelar sigue avisando sin fallar). Los unitarios siguen el patrón de T04.
  - **P8 · Texto de decisión, opción a:** decisión nueva `docs/decisiones/013-operaciones-de-un-usuario-de-a-una.md`, corta: el candado sale del caso de uso a `reservation/application/support`, por qué en `application` y no detrás de un puerto, y cuándo convendría cambiarlo; la 012 suma una línea que apunta a ella. Descartadas: (b) una sección de actualización en la 012, que mezclaría persistencia con la estructura de la aplicación; (c) sin texto, solo una línea en `CLAUDE.md`: se perdería el porqué.

  Ninguna decisión pendiente.

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el plan cubre los requisitos, respeta las exclusiones, reutiliza
componentes verificados, respeta la skill hexagonal-vertical-slicing y permite
demostrar todos los criterios de aceptación.
Resuelve dudas y marcadores pendientes. Si la spec cambió, revisa su impacto.
Tras aprobar el plan, deriva TASKS.md con IDs, dependencias, referencias a RF/CA
y comprobaciones. No marques una tarea terminada sin realizar su validación;
si está bloqueada, registra el motivo.
-->
