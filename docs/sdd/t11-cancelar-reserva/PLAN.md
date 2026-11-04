# PLAN: T11 — Cancelar reserva

**SPEC de referencia:** `docs/sdd/t11-cancelar-reserva/SPEC.md`
**Versión de la spec revisada:** aprobada el 2026-11-04 (D1 a D4, RF-01 a RF-15, CA-01 a CA-18), sin commitear
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
| Proceso de reserva | `reservation/domain/model/ReservationProcess.java` | Ya tiene `cancel`, que usa T08 y usará T11 con `CONFIRMED`, y `markForReconciliation`. Suma el abandono |
| Forma del caso de uso | `reservation/application/usecases/ConfirmReservationProcessUseCaseImpl.java` | Patrón: candado, llamada a la cátedra dentro, marca si no hay respuesta, y `LOCAL_STATE_OUT_OF_SYNC` si no se puede guardar |
| Candado | `reservation/application/support/UserOperationLock.java` | Sin cambios |
| Runner de la cátedra | `catedra/application/support/CatedraOperationRunner.java` | Sesión, `401` y reintentos, sin cambios |
| Forma del caso de uso de `catedra` | `catedra/application/usecases/ConfirmHoldUseCaseImpl.java` | Patrón para traducir `CatedraResponse` a un resultado sellado |
| Forma del cliente HTTP | `catedra/infrastructure/client/CatedraHoldClientAdapter.java` | Patrón: un solo intento, y clasificación de cada respuesta |
| Forma del gateway | `reservation/infrastructure/gateway/CatedraHoldAdapter.java` | Patrón para traducir el resultado a excepciones de `reservation` |
| Excepciones | `reservation/application/exception/` | Se reutilizan `ReservationProcessNotFoundException`, `ReservationInvalidProcessStateException`, `ReservationServiceDegradedException`, `ReservationCentralUnavailableException`, `ReservationLocalStateOutOfSyncException`, `ReservationNotSavedException` y `ReservationIntegrationException` |
| Controller y mapper web | `reservation/infrastructure/web/controller/ReservationProcessController.java`, `web/mapper/ReservationProcessDtoMapper.java` | Suman el `POST` y el motivo. `toResponse` sin cambios |
| Tests | `src/test/java/turnos/catedra/FakeCatedraServer.java`, `reservation/ReservationProcessStatusIntegrationTest.java`, `src/test/java/e2e/ReservationProcessE2ETest.java` | Patrón o se amplían. No hay grabaciones reales de la cancelación: las respuestas se arman desde el anexo §12 y §13 |

**Convenciones y slice de referencia:** `reservation` y `catedra`, como T05.

## Solución propuesta

<!-- Explica el enfoque y sus motivos. Describe las responsabilidades y el
recorrido de la solicitud, el evento o la tarea desde que entra hasta la
respuesta, la persistencia o el mensaje publicado. Referencia el diagrama de
secuencia del proyecto si ya lo describe; usa un diagrama nuevo solo si aporta
claridad. -->

El recorrido es el de la secuencia §6, ya ajustada:

1. `ReservationProcessController` recibe el `POST` con el motivo opcional, lo valida y saca el usuario del JWT.
2. `ReservationService` llama a `CancelReservationProcessUseCase`, y el caso de uso toma el candado del usuario y busca el proceso.
3. Según el estado: un `CANCELLED` se devuelve; un `HELD` o `WAITING_FOR_PHONE` sin marca se abandona y se guarda; un `CONFIRMED` sigue al paso 4; lo demás responde `INVALID_PROCESS_STATE`.
4. El caso de uso llama al puerto `AppointmentCanceller`. `CatedraAppointmentAdapter` entra por `CancelAppointmentUseCase`, que usa `CatedraOperationRunner` y `CatedraAppointmentClient`.
5. Con el `200`, `process.cancel` y `update`. Con un error, marca cuando corresponde y lanza la excepción, que el controller traduce.

**Decisiones técnicas:**

- **P1 · Dónde vive la llamada a la cátedra (opción a, decidida por el usuario el 2026-11-04):** piezas nuevas de "appointments", en `catedra` y en `reservation`, porque cancelar no es una operación sobre un hold. T13 va a listar reservas en la misma ruta y se suma ahí. Descartado: sumarlo a `CatedraHoldClient` y `HoldProvider`.
- **P2 · Una respuesta no prevista de la cátedra también marca.** Los documentos no la nombran. Responde `500` `INTERNAL_ERROR`, como dice el contrato §3, y marca, porque no se sabe qué hizo la cátedra.
- **P3 · Cada escritura según T05.** La marca después de un error usa `update`. Después de fallar al guardar, usa la escritura de una columna, para no repetir la que falló.
- **P4 · Sin motivo, el pedido a la cátedra va sin body.** El body es opcional en el anexo §12, y el E2E ya llamó así.

## Slices y componentes afectados

<!-- Aplica la skill hexagonal-vertical-slicing: un paquete por slice, con sus
capas domain, application e infrastructure. Identifica los slices afectados, sus
responsabilidades y la dirección de sus dependencias. Una funcionalidad puede
afectar más de un slice. Justifica cualquier slice nuevo o dependencia nueva
entre slices. -->

<!-- Distingue lo que se reutiliza, modifica o crea. Las rutas nuevas son
propuestas. Señala impacto sobre modelos, contratos o componentes compartidos. -->

| Slice | Existe / nuevo | Responsabilidad y cambios | Dependencias con otros slices |
| --- | --- | --- | --- |
| `catedra` | Existente | Suma la operación de cancelar en la cátedra | Ninguna nueva |
| `reservation` | Existente | Suma el caso de uso y el endpoint | Usa `catedra` por un puerto propio y un gateway, como T05 |

| Slice y capa | Componente o ruta | Acción | Cambio y responsabilidad | Requisito relacionado |
| --- | --- | --- | --- | --- |
| `reservation`, dominio | `domain/model/ReservationProcess.java` | Modificar | `canBeAbandoned` y `abandon` | RF-05, RF-06 |
| `catedra`, dominio | `domain/model/AppointmentCancellationResult.java` | Crear | Resultado sellado: cancelado, inexistente, de otra cuenta, no cancelable, configuración, no disponible, falla | RF-07 a RF-12 |
| `catedra`, dominio | `domain/ports/in/CancelAppointmentUseCase.java`, `domain/ports/out/CatedraAppointmentClient.java` | Crear | P1 | RF-07 |
| `catedra`, aplicación | `application/usecases/CancelAppointmentUseCaseImpl.java` | Crear | Usa `CatedraOperationRunner` | RF-09, RF-10 |
| `catedra`, infraestructura | `infrastructure/client/CatedraAppointmentClientAdapter.java` y sus DTO | Crear | Un intento, y clasifica cada respuesta | RF-07 a RF-12 |
| `reservation`, dominio | `domain/ports/out/AppointmentCanceller.java` | Crear | Puerto hacia la cátedra | RF-07 |
| `reservation`, infraestructura | `infrastructure/gateway/CatedraAppointmentAdapter.java` | Crear | Traduce el resultado a excepciones | RF-09 a RF-12 |
| `reservation`, aplicación | `application/exception/ReservationNotCancellableException.java`, `ReservationAppointmentNotFoundException.java` | Crear | Los dos casos que marcan y responden distinto | RF-11 |
| `reservation`, dominio | `domain/ports/in/CancelReservationProcessUseCase.java` | Crear | Puerto de entrada | RF-01 |
| `reservation`, aplicación | `application/usecases/CancelReservationProcessUseCaseImpl.java`, `application/service/ReservationService.java` | Crear y modificar | El flujo de la SPEC con el candado | RF-03 a RF-14 |
| `reservation`, web | `infrastructure/web/dto/ReservationCancellationRequest.java`, `web/mapper/ReservationProcessDtoMapper.java`, `web/controller/ReservationProcessController.java` | Crear y modificar | Motivo opcional y validado, y el `POST` con sus errores | RF-01, RF-02, RF-15 |
| Tests E2E | `src/test/java/e2e/ReservationProcessE2ETest.java` | Modificar | D4 | CA-16, CA-17 |
| Documentación | `CLAUDE.md`, decisión 015 | Modificar | T11 y dónde está en el código | Ninguno |

## Datos y contratos

<!-- Completa solo lo aplicable. Si un punto no aplica, indica el motivo. -->

- **Operaciones, eventos y DTO de entrada y salida:** contrato §3.9 y §4.2.7, ya ajustados, y anexo §12. La respuesta es la de T09.
- **Identificadores, relaciones y restricciones:** el dueño sale del token. Se busca por id y usuario.
- **Origen de los datos devueltos o publicados y transformaciones:** el motivo se normaliza en el mapper: vacío o solo con espacios es `null` (D3), y se le quita el carácter nulo antes de enviarlo a la cátedra y de guardarlo. `cancelledAt` viene de la cátedra, o de la hora actual en un abandono.
- **Persistencia, consultas y migraciones:** sin migraciones: las columnas existen desde T08.
- **Convivencia entre datos locales y remotos:** decisión 015. Un `CONFIRMED` que quedó sin saber qué hizo la cátedra queda marcado para T13.
- **Compatibilidad y migraciones de datos existentes:** T08 sigue usando `cancel` sin cambios.

## Transacciones, concurrencia y errores

<!-- Cómo se implementan los comportamientos aprobados en la spec.
Referencia RF/CA y aplica las consideraciones relevantes de BACKEND_GUIDELINES.md. -->

- **Estados y transiciones:** en el modelo, como pide `CLAUDE.md`. `canBeAbandoned` es `HELD` o `WAITING_FOR_PHONE` sin marca, y `abandon` reutiliza el cierre común. Un `CONFIRMED` usa `cancel`.
- **Transacciones y consistencia:** una sola escritura por camino, con `update`. No hay transacción que incluya a la cátedra: si ella canceló y no se puede guardar, se registra, se marca con la escritura de una columna y responde `LOCAL_STATE_OUT_OF_SYNC`, como T05.
- **Idempotencia y mensajes duplicados:** un segundo pedido encuentra `CANCELLED`. El reintento a la cátedra es seguro: cancelar lo cancelado responde igual (anexo §12).
- **Concurrencia y tareas programadas:** todo con `UserOperationLock`, también la espera a la cátedra, como T05. Un `AppointmentCancelled` de T08 espera y después no cambia nada.
- **Timeouts, reintentos y errores de dependencias:** el cliente informa toda falla de red, `500` y `503` como `Unavailable`, y el runner reintenta. Una falla no prevista, como un `200` que no se puede leer, se trata como `Failed`: responde `500` `INTERNAL_ERROR` y marca, porque no se sabe qué hizo la cátedra (P2). Si no se puede marcar, se registra y se informa el error original.
- **Seguridad y autorización:** la cadena de seguridad exige JWT y rol. El JWT técnico solo vive en `catedra`.
- **Otras consideraciones de backend aplicables y su solución:** de una falla al guardar se registra solo el tipo, porque la fila puede llevar el teléfono. El controller atrapa `ReservationException`, para que el manejador global no registre esa causa.

## Dependencias y configuración

<!-- Librerías, servicios externos, propiedades de configuración y secretos
afectados. Verifica compatibilidad con el proyecto y justifica las incorporaciones.
No agregues dependencias por defecto. Los valores sensibles y los parámetros
ajustables se externalizan. -->

- Sin dependencias nuevas.

## Estrategia de validación

<!-- Una fila por criterio de la spec. Selecciona el método capaz de demostrarlo:
test unitario, de integración (base de datos, mensajería), de API o prueba manual.
No todos requieren todos los métodos. Identifica tests existentes y separa los
nuevos propuestos. Incluye regresiones relevantes. Una prueba con mocks no
demuestra persistencia, transacciones ni idempotencia. -->

| Criterio | Método y test existente o propuesto | Entorno y datos necesarios | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01 | Web, en `ReservationProcessControllerTest` | `@WebMvcTest`, `TestTokens` | `401` y `403`; el service no se llama |
| CA-02 | Web | 500 y 501 caracteres | Se acepta; `400` con `reason` |
| CA-03 | Mapper, cliente con `FakeHttpServer` e integración | Sin body, `{}`, vacío y espacios | Sin motivo en la base y sin `reason` en el pedido |
| CA-04 | Caso de uso e integración | Inexistente y ajeno | Mismo `404`; el ajeno igual |
| CA-05 | Caso de uso e integración | `CANCELLED` | `200`, sin requests |
| CA-06 | Modelo, caso de uso e integración | Cada estado y cada marcado | `409` con `currentStatus`; sin cambios ni requests |
| CA-07 | Modelo, caso de uso e integración | Vencidos y no; después T04 y T10 | `200`; sin requests; otro bloqueo posible; no listado |
| CA-08 | Cliente, caso de uso e integración | `CONFIRMED`, cátedra simulada | Pedido con JWT y motivo; fila `CANCELLED` sin marca |
| CA-09 | Cliente, `CancelAppointmentUseCaseImplTest` e integración | `401`, `503`, cortes y timeouts | Renueva; reintenta; `503` y marca |
| CA-10 a CA-12 | Cliente, gateway, caso de uso y web | Cada respuesta armada desde el anexo | Cada resultado y lo registrado |
| CA-13 | Caso de uso; integración con `ControllableProcessRepository` | Falla al guardar sobre la base real | Cada `500`; sin teléfono en el log |
| CA-14 | Caso de uso con `CopyingReservationProcessRepository` | Candado tomado; dos pedidos a la vez | Espera; un solo pedido |
| CA-15 | Web e integración | Fila con teléfono; salida capturada | Sin esos datos |
| CA-16 | `ReservationProcessE2ETest`, lo corre el usuario | Cátedra real | `200` y `201` |
| CA-17 | `ReservationProcessE2ETest`, bloqueado | Cátedra publicando | Queda sin verificar |
| CA-18 | Suite completa | `./mvnw clean test` | Sin cambios en lo que comprueban |

**Comprobaciones de regresión:** suite completa, y tests de T08 sobre `AppointmentCancelled`. Pruebas en negativo previstas: abandonar un marcado, llamar a la cátedra en un abandono, no marcar en cada caso de error, no tomar el candado, registrar la causa al fallar, y enviar un motivo vacío.

**Comandos verificados para compilar y ejecutar tests:** `./mvnw test -Dtest=NombreDeLaClase`, `./mvnw clean test`. El conteo sale de `target/surefire-reports/TEST-*.xml`. El E2E lo corre el usuario con `./mvnw test -Pe2e -Dtest='ReservationProcessE2ETest#...'`.

**Pruebas con infraestructura real o simulada:** Postgres real en Testcontainers; cátedra y catálogo simulados, con `FakeCatedraServer`; Kafka apagado, porque el evento de T08 ya está probado.

**Limitaciones del entorno:** cancelar `CONFIRMED` contra la cátedra real sigue bloqueado hasta que publique por Kafka: el formato del `200` sale del anexo, sin grabación.

<!-- Esta sección planifica la validación. Durante la implementación, registra
en TASKS.md o en el informe de validación acordado los resultados y evidencias
reales. Distingue pruebas ejecutadas, fallidas, no ejecutadas y bloqueadas.
Compilar o tener tests en verde no sustituye revisar los criterios de la spec. -->

## Orden de implementación

<!-- Etapas y dependencias principales. El desglose ejecutable se escribe en TASKS.md.
Incluye puntos de comprobación para avanzar con cambios pequeños. -->

1. Modelo: `canBeAbandoned` y `abandon`. Comprobación: `ReservationProcessTest`.
2. `catedra`: resultado, cliente y caso de uso. Comprobación: tests del cliente y del caso de uso.
3. `reservation`: puerto, gateway, excepciones, caso de uso y service. Comprobación: tests del caso de uso y del gateway.
4. Web: DTO, mapper y controller. Comprobación: tests web.
5. Integración sobre Postgres, con la cátedra simulada.
6. E2E: se compila; lo corre el usuario.
7. Documentación, suite completa y conteo.

## Riesgos y decisiones pendientes

<!-- Riesgos concretos de esta solución y cómo se resolverán, sin listas genéricas.
Escribe Ninguna en las decisiones pendientes cuando estén resueltas. -->

- **Riesgos y medidas acordadas:**
  - **Cancelar `CONFIRMED` nunca se ejecutó contra la cátedra real:** el `200` se simula desde el anexo. Queda como CA-17 sin verificar.
  - **Un segundo hold con el primero activo:** el anexo no lo prohíbe, sin comprobar. Lo comprueba CA-16. Si la cátedra lo rechaza, D1 se revisa.
  - **Espera con el candado tomado:** como en T05, otra operación del usuario espera mientras la cátedra responde. Es el costo ya aceptado en la decisión 013.
  - **El E2E ocupa turnos:** dos del lunes por corrida, hasta 10 minutos.
  - **Riesgo aceptado en la decisión 015:** el turno de un proceso abandonado sigue tomado hasta que vence.
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
