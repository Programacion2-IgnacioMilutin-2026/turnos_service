# PLAN: T09 — Consultar estado de un proceso

**SPEC de referencia:** `docs/sdd/t09-estado-proceso/SPEC.md`
**Versión de la spec revisada:** aprobada el 2026-11-03 (D1 a D5, RF-01 a RF-10, CA-01 a CA-14; D5, RF-10 y CA-14 se sumaron por indicación del usuario al implementar TASK-10)
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
| Proceso de reserva | `reservation/domain/model/ReservationProcess.java` | Ya guarda todo lo que T09 informa. Tiene `hasExpired`, `expireIfOverdue` y `markForReconciliation`. Falta la regla completa del vencido, con los dos casos juntos |
| Candado | `reservation/application/support/UserOperationLock.java` | Se reutiliza sin cambios. Espera sin límite de tiempo a que se libere |
| Forma del caso de uso | `reservation/application/usecases/SubmitPhoneUseCaseImpl.java` | Patrón de la escritura: atrapa la falla, registra solo el tipo y lanza `ReservationNotSavedException` |
| Service | `reservation/application/service/ReservationService.java` | Suma un método |
| Puerto de salida | `reservation/domain/ports/out/ReservationProcessRepository.java` | `findByIdAndUserId` y `update` alcanzan: no suma métodos |
| Adaptador de persistencia | `reservation/infrastructure/persistence/adapter/JpaReservationProcessRepositoryAdapter.java` | Sin cambios. `findByIdAndUserId` no traduce las fallas de la base, que llegan a `ApiExceptionHandler` |
| Controller y mapper web | `reservation/infrastructure/web/controller/ReservationProcessController.java`, `web/mapper/ReservationProcessDtoMapper.java` | El controller suma el `GET`. `toResponse` suma los cinco campos que hoy no llena |
| DTO de respuesta | `reservation/infrastructure/web/dto/ReservationProcessResponse.java` | Ya tiene todos los campos: sin cambios |
| Manejo global de errores | `config/ApiExceptionHandler.java` | Lo no previsto responde `500` `INTERNAL_ERROR` y registra la excepción completa |
| Configuración | `src/main/resources/application.yaml` | `open-in-view: false`: cada lectura trabaja con datos frescos de la base, y por eso la segunda lectura sirve (P2) |
| Tests | `src/test/java/turnos/reservation/`: `ReservationProcessIntegrationTest`, `DatabaseDownKafkaIntegrationTest`, `PhoneSubmissionKafkaIntegrationTest`, `application/usecases/CopyingReservationProcessRepository`, `infrastructure/web/controller/ReservationProcessControllerTest`, `infrastructure/web/mapper/ReservationProcessDtoMapperTest`, `domain/model/ReservationProcessTest` | Se amplían o sirven de patrón |

**Convenciones y slice de referencia:** `reservation`, con los mismos patrones de T05 y T06.

## Solución propuesta

<!-- Explica el enfoque y sus motivos. Describe las responsabilidades y el
recorrido de la solicitud, el evento o la tarea desde que entra hasta la
respuesta, la persistencia o el mensaje publicado. Referencia el diagrama de
secuencia del proyecto si ya lo describe; usa un diagrama nuevo solo si aporta
claridad. -->

El recorrido es el de la secuencia §5, ya ajustada:

1. `ReservationProcessController` recibe el `GET`, saca el usuario del JWT y llama a `ReservationService.getReservationProcessById`.
2. El service llama al caso de uso. Si vuelve vacío, lanza `ReservationProcessNotFoundException`, que el controller traduce a `404`.
3. El caso de uso hace la **lectura 1**, sin candado. Si el modelo dice que no hay nada que resolver, devuelve lo leído y termina. Es el camino de casi todas las consultas.
4. Si hay algo que resolver, toma el candado del usuario y hace la **lectura 2**. Le pide al modelo que resuelva el vencimiento sobre ese dato:
   - Si el modelo cambió algo, guarda y devuelve lo guardado.
   - Si no cambió nada, otra operación se adelantó: devuelve lo leído, sin escribir.
5. El controller arma la respuesta con el mapper, ya con todos los campos.

**Decisiones técnicas (P2 es un hecho comprobado, no una decisión):**

- **P1 · La regla del vencido vive en el modelo (opción a, decidida por el usuario el 2026-11-03).** Dos métodos nuevos en `ReservationProcess`:
  - `needsOverdueResolution(now, margin)`: dice si queda algo por hacer. Es verdadero para un `HELD` o `WAITING_FOR_PHONE` vencido, y para un `PHONE_SUBMITTED` vencido sin marca.
  - `resolveOverdue(now, margin)`: lo hace y devuelve si cambió algo. Reutiliza `expireIfOverdue` y `markForReconciliation`.
  - T10 y T12 necesitan la misma regla, pero hoy solo la usa T09.
- **P2 · Las dos lecturas son la misma consulta.** La segunda ve datos frescos porque `open-in-view` está apagado. Con esa opción encendida, Hibernate devolvería el objeto ya leído en el mismo request y la protección dejaría de funcionar sin que falle ningún test simulado. Por eso CA-10 también se prueba sobre Postgres real.
- **P3 · Una sola forma de escribir: `update`, también para la marca (opción a, decidida por el usuario el 2026-11-03).** Con el candado y recién leído, nadie puede pisar la fila. El caso de uso no necesita saber qué cambió el modelo. `markForReconciliation` del repositorio existe para cuando guardar el proceso acaba de fallar, y no es este caso. Costo: reescribe la fila entera en un caso poco frecuente.
- **P4 · El caso de uso devuelve `Optional`, y el service lanza el `404` (opción a, decidida por el usuario el 2026-11-03).** Así lo define la skill para `Get{Entity}ByIdUseCase`. T05 y T06 lanzan desde el caso de uso porque no son una consulta.
- **P5 · Un id con el carácter nulo, CA-13 (opción a, decidida por el usuario el 2026-11-03).** Primero se comprueba con un test contra el servidor real, sin cambiar código. Si no llega a la base, el test queda como evidencia. Si llega y Postgres lo rechaza, el caso de uso lo trata como proceso inexistente (`Optional` vacío, `404`), sin ir a la base.

- **P6 · Límite de espera de la base (D5, agregado al implementar TASK-10).** `socketTimeout` del driver de Postgres, por `spring.datasource.hikari.data-source-properties`, con `TURNOS_DB_SOCKET_TIMEOUT` y 30 s por defecto. Se configura ahí y no en la URL porque los tests con `@ServiceConnection` reemplazan la URL. Al vencer, el driver cierra la conexión y lanza una falla de base, que cada operación ya sabe tratar. Si la variable se define vacía, la aplicación no arranca: el driver exige un entero. Eso se documenta en `.env.example` y `CLAUDE.md`, sin agregar código para evitarlo.

## Slices y componentes afectados

<!-- Aplica la skill hexagonal-vertical-slicing: un paquete por slice, con sus
capas domain, application e infrastructure. Identifica los slices afectados, sus
responsabilidades y la dirección de sus dependencias. Una funcionalidad puede
afectar más de un slice. Justifica cualquier slice nuevo o dependencia nueva
entre slices. -->

| Slice | Existe / nuevo | Responsabilidad y cambios | Dependencias con otros slices |
| --- | --- | --- | --- |
| `reservation` | Existente | Suma la consulta, la regla del vencido y los campos de la respuesta | Ninguna nueva. T09 no usa `catedra`, `catalog` ni `user` |

<!-- Distingue lo que se reutiliza, modifica o crea. Las rutas nuevas son
propuestas. Señala impacto sobre modelos, contratos o componentes compartidos. -->

| Slice y capa | Componente o ruta | Acción | Cambio y responsabilidad | Requisito relacionado |
| --- | --- | --- | --- | --- |
| `reservation`, dominio | `domain/model/ReservationProcess.java` | Modificar | `needsOverdueResolution` y `resolveOverdue` (P1) | RF-04, RF-05, RF-06 |
| `reservation`, dominio | `domain/ports/in/GetReservationProcessByIdUseCase.java` | Crear | Puerto de entrada, con `userId` e id (P4) | RF-01, RF-02 |
| `reservation`, aplicación | `application/usecases/GetReservationProcessByIdUseCaseImpl.java` | Crear | Las dos lecturas, el candado, la escritura y su falla | RF-02, RF-04 a RF-08 |
| `reservation`, aplicación | `application/service/ReservationService.java` | Modificar | `getReservationProcessById`, con `orElseThrow` (P4) | RF-02 |
| `reservation`, web | `infrastructure/web/controller/ReservationProcessController.java` | Modificar | `GET /api/reservation-processes/{id}` y su traducción de errores | RF-01, RF-02, RF-08 |
| `reservation`, web | `infrastructure/web/mapper/ReservationProcessDtoMapper.java` | Modificar | Cinco campos más; los instantes en segundos, como `expiresAt` | RF-03 |
| `reservation`, puerto y adaptador | `ReservationProcessRepository`, `JpaReservationProcessRepositoryAdapter` | Reutilizar | `findByIdAndUserId` y `update`, sin cambios | RF-02, RF-04, RF-05 |
| `reservation`, soporte | `UserOperationLock` | Reutilizar | Sin cambios | RF-07 |
| Configuración | `src/main/resources/application.yaml`, `.env.example` | Modificar | Límite de espera de la base (P6) | RF-10 |
| Documentación | `CLAUDE.md`, `docs/decisiones/013-operaciones-de-un-usuario-de-a-una.md` | Modificar | Anotar la excepción de la lectura sin candado (D2), sumar T09 al slice y a la lista de `docs/sdd` | RF-07 |

## Datos y contratos

<!-- Completa solo lo aplicable. Si un punto no aplica, indica el motivo. -->

- **Operaciones, eventos y DTO de entrada y salida:** contrato §3.7 y §2. No cambia el contrato. No hay DTO nuevo.
- **Identificadores, relaciones y restricciones:** `reservationProcessId` es un texto opaco (P5). La propiedad se resuelve con la consulta por id y usuario.
- **Origen de los datos devueltos o publicados y transformaciones:** todo sale de la fila local. `confirmedAt` y `cancelledAt` se recortan a segundos, como `expiresAt`. `expiresAt` sigue en `null` si el proceso está cerrado.
- **Persistencia, consultas y migraciones:** sin migraciones ni consultas nuevas.
- **Convivencia entre datos locales y remotos:** no aplica: T09 no consulta a la cátedra. Lo que escribe T08 se ve en la siguiente consulta.
- **Compatibilidad y migraciones de datos existentes:** las respuestas de T04, T05 y T06 no cambian, porque en los estados que devuelven esos campos están vacíos (CA-07).

## Transacciones, concurrencia y errores

<!-- Cómo se implementan los comportamientos aprobados en la spec.
Referencia RF/CA y aplica las consideraciones relevantes de BACKEND_GUIDELINES.md. -->

- **Estados y transiciones:** `HELD` o `WAITING_FOR_PHONE` vencidos pasan a `EXPIRED`, por el cierre común que borra el motivo de rechazo y apaga la marca. `PHONE_SUBMITTED` vencido solo enciende la marca. El vencimiento usa `ReservationPolicy.safetyMargin` y el `Clock` del servicio.
- **Transacciones y consistencia:** la consulta no necesita una transacción propia: la única escritura es un `update`, que ya tiene la suya.
- **Idempotencia y mensajes duplicados:** una vez resuelto, `needsOverdueResolution` da falso: la siguiente consulta no toma el candado ni escribe (CA-11).
- **Concurrencia y tareas programadas:** lectura sin candado y relectura con candado (D2, P2). El candado es el mismo de T04 a T08, así que un resultado de T08 y un cierre de T09 se excluyen. El dueño es el usuario del token: una consulta de un proceso ajeno no llega a tomar el candado, porque la lectura 1 ya vuelve vacía.
- **Timeouts, reintentos y errores de dependencias:** sin dependencias externas. La falla de una lectura llega a `ApiExceptionHandler` (`500` `INTERNAL_ERROR`). La falla del `update` se atrapa en el caso de uso, que registra solo el tipo y el id, y lanza `ReservationNotSavedException`. El controller la traduce a `500` `INTERNAL_ERROR` (D3).
- **Seguridad y autorización:** la cadena de seguridad ya exige JWT y rol en toda ruta no pública: la ruta nueva no se agrega a ninguna lista. El dueño sale del token.
- **Otras consideraciones de backend aplicables y su solución:** privacidad: el DTO no tiene `holdId`, `requestEventId`, teléfono ni marca, y `ReservationProcess` no genera `toString`. Se comprueba capturando el log con un teléfono en la fila.

## Dependencias y configuración

<!-- Librerías, servicios externos, propiedades de configuración y secretos
afectados. Verifica compatibilidad con el proyecto y justifica las incorporaciones.
No agregues dependencias por defecto. Los valores sensibles y los parámetros
ajustables se externalizan. -->

- Sin dependencias nuevas. Se reutiliza `TURNOS_RESERVATION_SAFETY_MARGIN`. Propiedad nueva, opcional: `TURNOS_DB_SOCKET_TIMEOUT` (P6). También limita a las migraciones de Flyway, que usan la misma conexión.

## Estrategia de validación

<!-- Una fila por criterio de la spec. Selecciona el método capaz de demostrarlo:
test unitario, de integración (base de datos, mensajería), de API o prueba manual.
No todos requieren todos los métodos. Identifica tests existentes y separa los
nuevos propuestos. Incluye regresiones relevantes. Una prueba con mocks no
demuestra persistencia, transacciones ni idempotencia. -->

| Criterio | Método y test existente o propuesto | Entorno y datos necesarios | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01 | Web, nuevo en `ReservationProcessControllerTest` | `@WebMvcTest` con `TestSecurityConfiguration` y `TestTokens` | `401` sin token, inválido y vencido; el service no se llama |
| CA-02 | Integración, nuevo `ReservationProcessStatusIntegrationTest` | Postgres en Testcontainers, dos usuarios | Mismo `404`; la fila ajena vencida no cambia |
| CA-03 | Unitario en `ReservationProcessTest` y `GetReservationProcessByIdUseCaseImplTest` (`MutableClock`); integración | Postgres; `expiresAt` escrito en la base | `EXPIRED` en respuesta y fila; sin cambios dentro del margen |
| CA-04 | Los mismos | Igual | Fila marcada y `PHONE_SUBMITTED`; sin marca dentro del margen |
| CA-05 | Unitario e integración | Una fila por estado cerrado; cátedra y catálogo simulados | Sin escritura (`history` vacío), fila igual, y ningún request a los servidores simulados por la consulta |
| CA-06 | Mapper (test existente) e integración con log capturado | Fila con teléfono; `OutputCaptureExtension` | El JSON no tiene esas claves; el teléfono no está en el log |
| CA-07 | Suite completa | `./mvnw clean test` | Tests existentes de T04, T05 y T06 en verde, sin cambiar lo que comprueban de la respuesta |
| CA-08 | Mapper (se actualiza `everyExposedFieldOfAnOpenProcessReachesTheResponse` y se suma un caso por estado) e integración | Una fila por estado con cada dato | Cada campo según el estado; instantes en segundos |
| CA-09 | `GetReservationProcessByIdUseCaseImplTest` | El test toma el candado y consulta desde otro hilo | No vencido responde con el candado tomado; vencido queda esperando y responde al soltarse |
| CA-10 | `GetReservationProcessByIdUseCaseImplTest` con `CopyingReservationProcessRepository`; integración sobre Postgres real (P2) | El test toma el candado, consulta, escribe `CONFIRMED` y suelta | Responde `CONFIRMED`; sin `EXPIRED` ni marca en la fila |
| CA-11 | `GetReservationProcessByIdUseCaseImplTest` | `PHONE_SUBMITTED` vencido marcado, con el candado tomado | Responde enseguida; `history` vacío |
| CA-12 | Unitario con `failUpdatesWhen`; web; integración con un repositorio que falla sobre la base real (patrón de `PhoneSubmissionKafkaIntegrationTest`); nuevo `DatabaseDownStatusQueryIntegrationTest`, con Postgres propio en pausa | Fila con teléfono; log capturado; `hikari.connection-timeout` corto | `500` `INTERNAL_ERROR`; fila igual; sin teléfono en el log; la consulta siguiente resuelve |
| CA-14 | `DatabaseDownStatusQueryIntegrationTest` con Postgres en pausa después de la lectura y con la escritura pendiente, y `pg_sleep`; `TurnosServiceApplicationTests` para el valor por defecto | `TURNOS_DB_SOCKET_TIMEOUT` corto en el test | `500`, candado libre y la consulta siguiente resuelve; con el límite en 0 los tests no terminan a tiempo |
| CA-13 | Integración contra el servidor real (P5) | `RestTestClient` con `%00` | Rechazo de la ruta o `404`, nunca `500` |

**Comprobaciones de regresión:** suite completa, y tests existentes del mapper, del controller y de integración de T04 a T08. Además, pruebas en negativo: quitar la lectura 2 (CA-10 tiene que fallar), tomar siempre el candado (CA-09 y CA-11), ignorar la marca (CA-11), sacar un campo del mapper (CA-08), y registrar la excepción completa al fallar el `update` (CA-12).

**Comandos verificados para compilar y ejecutar tests:** `./mvnw test -Dtest=NombreDeLaClase`, `./mvnw clean test`. El conteo sale de `target/surefire-reports/TEST-*.xml`.

**Pruebas con infraestructura real o simulada:** Postgres real en Testcontainers; cátedra y catálogo simulados; Kafka apagado, porque T09 no lo usa. El caso de T08 se simula escribiendo en la base con el candado tomado: el recorrido con Kafka ya está probado en T08.

**Limitaciones del entorno:** no se levanta la app con `.env`, porque el arranque hace el login técnico contra la cátedra real, y T09 no necesita esa prueba (D4). El comportamiento HTTP se comprueba con la app completa sobre un puerto aleatorio en los `*IntegrationTest`.

<!-- Esta sección planifica la validación. Durante la implementación, registra
en TASKS.md o en el informe de validación acordado los resultados y evidencias
reales. Distingue pruebas ejecutadas, fallidas, no ejecutadas y bloqueadas.
Compilar o tener tests en verde no sustituye revisar los criterios de la spec. -->

## Orden de implementación

<!-- Etapas y dependencias principales. El desglose ejecutable se escribe en TASKS.md.
Incluye puntos de comprobación para avanzar con cambios pequeños. -->

1. Modelo: `needsOverdueResolution` y `resolveOverdue`, con sus tests. Comprobación: `ReservationProcessTest`.
2. Mapper: los cinco campos, con sus tests. Comprobación: `ReservationProcessDtoMapperTest` y los tests de T04 a T06, que no deben cambiar.
3. Caso de uso y service: lecturas, candado, escritura y falla. Comprobación: `GetReservationProcessByIdUseCaseImplTest` y sus pruebas en negativo.
4. Controller. Comprobación: `ReservationProcessControllerTest`.
5. Integración sobre Postgres, incluidos CA-10 real y CA-13. Si CA-13 da `500`, se aplica P5 acá.
6. Base caída: `DatabaseDownStatusQueryIntegrationTest`.
7. Documentación (`CLAUDE.md` y decisión 013), suite completa y conteo.

## Riesgos y decisiones pendientes

<!-- Riesgos concretos de esta solución y cómo se resolverán, sin listas genéricas.
Escribe Ninguna en las decisiones pendientes cuando estén resueltas. -->

- **Riesgos y medidas acordadas:**
  - **Volver a encender `open-in-view` rompería la relectura en silencio:** lo detecta CA-10 sobre Postgres real (P2).
  - **Espera con la base caída:** el tiempo para obtener una conexión de Hikari sigue en su valor por defecto, 30 s según su documentación, sin medir. El límite de P6 se suma a ese tiempo. Una consulta podría tardar eso antes del `500`, igual que cualquier otro endpoint del servicio. La app ya trata esa demora como "no pudo actualizar" (A09 1.1).
  - **El límite de P6 corta cualquier operación más lenta:** hoy las consultas son simples, y las migraciones existentes no se midieron. Una migración futura que tarde más de 30 s necesitaría subir la variable.
  - **Una escritura cortada por tiempo pudo haberse aplicado:** si la respuesta se pierde después de que Postgres confirmó, se informa una falla aunque el cambio quedó guardado. En T09 no daña: la consulta siguiente ve el estado real.
  - **Espera sin límite del candado:** una consulta que tiene que escribir espera lo que tarde la operación en curso. T06 tiene un tope de 10 s, y T05 depende de la cátedra. No se agrega otro tope: pasa poco y la espera termina cuando termina esa operación.
  - **Riesgo heredado, ya aceptado (D6 de T08):** T09 puede cerrar como `EXPIRED` un proceso marcado, y una confirmación tardía se ignora con un error en el log.
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
