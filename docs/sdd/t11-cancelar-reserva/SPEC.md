# SPEC: T11 — Cancelar reserva

**Estado:** Aprobada <!-- Borrador | En revisión | Aprobada -->

<!-- PARA LA PERSONA
Copia esta plantilla como SPEC.md en una carpeta de la funcionalidad.
Pide al agente que la complete contigo usando BACKEND_GUIDELINES.md.
SPEC.md define qué debe cumplirse; PLAN.md desarrolla cómo implementarlo;
TASKS.md organiza los pasos de ejecución.
Los casos de uso, diagramas, contratos y el anexo de la cátedra se referencian
por ID; esta SPEC no los reescribe.
-->

<!-- PARA EL AGENTE
- Lee las instrucciones del proyecto, BACKEND_GUIDELINES.md y los documentos
  citados en Referencias (casos de uso, diagramas, contratos, modelo de dominio
  y anexo de la cátedra). Inspecciona el repositorio para comprobar el
  comportamiento actual. Si falta un documento, pide su ubicación.
- Los casos de uso y los contratos son la fuente de verdad. No los reescribas ni
  los contradigas: referencia sus pasos y flujos alternativos por ID. Si esta
  spec y un documento de referencia difieren, o este es ambiguo, detente y consulta.
- Completa esta spec con la persona: investiga lo comprobable y consulta las
  decisiones pendientes. Haz pocas preguntas por vez y actualiza las respuestas.
- No inventes requisitos ni exclusiones. Distingue propuestas de decisiones
  confirmadas y marca como PENDIENTE lo que aún no esté resuelto.
- Aplica las consideraciones de backend relevantes sin ampliar el alcance automáticamente.
- No incluyas diseño de clases, tablas, componentes, archivos o algoritmos:
  esos detalles pertenecen a PLAN.md. Sí registra restricciones explícitas del pedido.
- Mantén el documento breve y proporcional a la funcionalidad. Conserva los comentarios.
- Un documento completo no está aprobado automáticamente. Solicita aprobación
  antes de marcarlo como Aprobada. No implementes durante esta etapa.
-->

## Referencias

<!-- Documentos de Documentacion en los que se apoya esta funcionalidad. Indica
qué partes cubre esta spec, con IDs exactos de archivo, caso de uso, paso y flujo
alternativo. Una funcionalidad puede cubrir solo una parte de un caso de uso: lo
que no se nombra aquí no forma parte de ella. -->

| Documento | Partes cubiertas |
| --- | --- |
| Caso de uso T11 (`docs/casos_de_uso/turnos_y_reservas_usecases.md`) | Todo el caso de uso, con los cambios de D1 y D2 |
| Diagrama de secuencia (`docs/secuencias/turnos_y_reservas_secuencia.md`) | §6 |
| Contrato de turnos (`docs/contratos/turnos_y_reservas_contrato.md`) | §2, §3, §3.9 y §4.2.7 |
| Contrato común (`docs/contratos/contratos_comunes.md`) | §1.4 (JWT), §2 (errores) y §3 (instantes) |
| Modelo de dominio de turnos (`docs/turnos_y_reservas_dominio.md`) | `ReservationProcess`, §2; reglas 6, 9, 14 y 19 |
| Anexo de la cátedra | §12 (cancelar) y §13.1 (matriz de errores) |
| Decisiones 011, 013 y 015 | `CatedraOperationRunner`, candado por usuario y abandono local |
| Caso de uso de la app A11 (`uml/casos_de_uso/app_kmp_usecases.md`) | Solo como contexto |

## Qué construimos y para quién

<!-- Qué necesidad cubre, qué actor la inicia (la app KMP, otro servicio, el
tiempo o la cátedra) y qué podrá hacer o recibir. Describe el objetivo en
lenguaje de negocio. Resume y referencia: no copies la descripción del caso de uso. -->

La app le pide al servicio cancelar una reserva del usuario. Turnos se lo pide a la cátedra y refleja el resultado localmente. Una reserva confirmada se cancela en la cátedra; un proceso en curso se cierra solo en turnos (D1).

## Situación actual

<!-- Qué existe hoy y es relevante (código, datos, otras funcionalidades ya
implementadas), la limitación que queremos resolver y los comportamientos
existentes que deben conservarse. Si todavía no hay código para esto, indícalo.
No describas la arquitectura. -->

- Están implementados T01 a T10, X01 y la validación del JWT. T12 y T13 todavía no.
- `catedra` no tiene una operación de cancelar. El E2E cancela con una llamada directa, solo como limpieza.
- El modelo ya sabe cancelar (`cancel`), y T08 ya aplica `AppointmentCancelled`.
- **Contradicción entre fuentes, comprobada:** el anexo §13.1 solo permite cancelar `CONFIRMED` o `CANCELLED`. La nota del contrato §4.2.7, T11 y el dominio §2 también permiten `HELD` y `WAITING_FOR_PHONE`. La cátedra real responde `404` en `HELD` y `409` en `WAITING_FOR_PHONE`. Sin respuesta del profesor, se sigue el anexo para lo que se le pide a la cátedra (D1).
- **Sin comprobar contra la cátedra real:** cancelar una reserva `CONFIRMED`. No se puede llegar a ese estado mientras no publique por Kafka, y no hay ninguna grabación de un `200`.
- Debe conservarse el comportamiento de T01 a T10, X01 y la validación del JWT.

## Dentro del alcance

<!-- Requisitos concretos, con identificadores estables para vincularlos a
criterios, decisiones del plan y tareas. Cada requisito indica el paso o flujo
alternativo del que proviene. No agregues requisitos que los documentos de
referencia no tengan sin consultarlo. -->

- **RF-01** · T11 pasos 1 y 2, alternativo 2.1, contrato §3.9: `POST /api/reservation-processes/{reservationProcessId}/cancel` exige JWT válido de cualquier rol. El dueño sale del token.
- **RF-02** · T11 paso 3, alternativo 3.1, D3: el body es opcional. `reason` admite hasta 500 caracteres; si los supera, responde `400` `VALIDATION_ERROR` con `fieldErrors`, sin tocar la base. Vacío o solo con espacios cuenta como si no viniera.
- **RF-03** · T11 paso 4, alternativo 4.1: un proceso inexistente o ajeno responde `404` `RESERVATION_PROCESS_NOT_FOUND`, sin distinguirlos.
- **RF-04** · T11 alternativo 5.1: un proceso ya `CANCELLED` responde `200` con su estado actual, sin cambios y sin llamar a la cátedra.
- **RF-05** · T11 alternativo 5.2, D1: `PHONE_SUBMITTED`, `EXPIRED`, `INVALID`, y `HELD` o `WAITING_FOR_PHONE` marcados para reconciliar responden `409` `INVALID_PROCESS_STATE` con `currentStatus`, sin cambios y sin llamar a la cátedra.
- **RF-06** · T11 alternativo 5.3, D1, D2: un `HELD` o `WAITING_FOR_PHONE` sin marca pasa a `CANCELLED` solo en turnos, con la fecha actual y el motivo, y responde `200`, sin llamar a la cátedra y aunque su plazo haya vencido. El usuario queda libre para bloquear otro turno.
- **RF-07** · T11 pasos 6 y 7, contrato §4.2.7, anexo §12: un `CONFIRMED` se cancela en la cátedra, con el JWT técnico y el motivo, si lo hay.
- **RF-08** · T11 pasos 8 y 9: si la cátedra responde `200`, el proceso pasa a `CANCELLED` con el `cancelledAt` recibido y el motivo, y responde `200`.
- **RF-09** · T11 6.1 y 6.6, anexo §12: un `401` renueva la sesión y repite una vez. Una falla de conexión, un timeout, un `503` o un `500` se reintentan con espera creciente, porque cancelar lo ya cancelado no falla. Agotados, responde `503` `CENTRAL_UNAVAILABLE`, el estado no cambia y queda marcado.
- **RF-10** · T11 6.2: `403` `STUDENT_INTEGRATION_NOT_FOUND` se registra y responde `503` `SERVICE_DEGRADED`, sin marcar.
- **RF-11** · T11 6.3 y 6.5: `404` `RESERVATION_NOT_FOUND` se registra, marca y responde `500` `INTERNAL_ERROR`. `409` `RESERVATION_NOT_CANCELLABLE` se registra, marca y responde ese mismo `409`.
- **RF-12** · T11 6.4: `403` `RESERVATION_OWNER_MISMATCH` se registra como error grave y responde `500` `INTERNAL_ERROR`.
- **RF-13** · T11 8.1, contrato §3: si la cátedra canceló y no se puede guardar, se registra como error crítico, se intenta marcar y responde `500` `LOCAL_STATE_OUT_OF_SYNC`. Si falla guardar un abandono (RF-06), o leer el proceso, responde `500` `INTERNAL_ERROR` y nada cambia.
- **RF-14** · Decisión 013: toda la operación corre con el candado del usuario, también la llamada a la cátedra: un resultado de la cátedra espera a que termine.
- **RF-15** · Contrato §2, guía de backend (privacidad): la respuesta es el objeto del proceso, sin `holdId`, `requestEventId`, teléfono ni marca. El teléfono no aparece en logs ni en errores, y de una falla al guardar se registra solo el tipo.

## Fuera de alcance

<!-- Exclusiones acordadas, no deducidas por el agente. Incluye las partes de los
casos de uso que se dejan para otra funcionalidad. Si no hay exclusiones
adicionales, indícalo tras revisarlo con la persona. -->

Confirmado con el usuario al elegir D1 a D4:

- La reconciliación (T13): T11 solo deja la marca. El barrido (T12).
- Avisarle a la cátedra de un abandono: no hay forma, y su hold vence solo (D1).
- Cancelar `PHONE_SUBMITTED`: su resultado puede estar en camino.

## Flujo de la operación

<!-- Cómo se inicia (request, evento o tarea programada), qué verifica y qué
resultado produce, incluidas las alternativas relevantes. Si el caso de uso ya
lo describe, referencia sus pasos en lugar de repetirlos y describe solo lo que
cubre esta funcionalidad. -->

1. La app llama al endpoint (T11 paso 1) y la seguridad valida el JWT (paso 2).
2. Se valida el motivo (paso 3, RF-02).
3. Con el candado del usuario, se busca el proceso entre los suyos (paso 4, RF-03, RF-14).
4. Según el estado (paso 5):
   - `CANCELLED`: responde como está (RF-04).
   - No cancelable o marcado: `409` (RF-05).
   - `HELD` o `WAITING_FOR_PHONE`: se cierra solo en turnos y responde (RF-06).
   - `CONFIRMED`: sigue en el paso 5.
5. Se pide a la cátedra cancelar (pasos 6 y 7, RF-07, RF-09 a RF-12).
6. Se guarda `CANCELLED` y se responde (pasos 8 y 9, RF-08, RF-13).

## Datos y reglas de negocio

<!-- Información que necesita la operación, campos obligatorios, validaciones,
límites y reglas como duplicados u orden de presentación. Describe significado
y comportamiento, sin diseñar tablas, DTO, DAO ni almacenamiento. -->

- Un estado final no se reabre (dominio regla 6). `CONFIRMED` a `CANCELLED` es la excepción ya prevista.
- Un cierre borra el motivo de rechazo y apaga la marca (dominio regla 9).
- Un abandonado no tiene `reservationId` ni `confirmedAt`, así que T10 no lo lista (dominio regla 12).
- La cátedra no se entera de un abandono: su hold vence solo, y hasta entonces ese turno no figura libre. Un evento tardío de ese proceso no lo reabre (T08).
- En una cancelación con la cátedra, `cancelledAt` es el que ella informa, y el `reservationId` sigue siendo el guardado.

## Casos alternativos y de error

<!-- Adapta la tabla usando BACKEND_GUIDELINES.md. Cuando el caso de uso ya define
el flujo alternativo, referencia su ID en lugar de repetirlo. Añade escenarios
relevantes. Marca No aplica con su motivo cuando corresponda. Expresa resultados
observables (estado HTTP, código de error, estado final del recurso, evento
publicado), no mecanismos técnicos. -->

| Situación | Comportamiento esperado |
| --- | --- |
| Entrada inválida | T11 3.1: `400` `VALIDATION_ERROR` (RF-02). JSON mal escrito: `400` del manejador global. Sin body: válido |
| Sin sesión o sin permiso | T11 2.1: `401` o `403` de la capa de seguridad |
| Recurso inexistente o ajeno | T11 4.1: `404`, sin distinguirlos (RF-03) |
| Dependencia no disponible (cátedra) | T11 6.2 y 6.6: `503`, solo en `CONFIRMED` (RF-09, RF-10). Un abandono no la necesita |
| Timeout o espera excesiva | Los tiempos y reintentos de `CatedraOperationRunner`. La base corta por `TURNOS_DB_SOCKET_TIMEOUT` |
| Operación o mensaje repetido | Una segunda cancelación encuentra `CANCELLED` y responde `200` (RF-04). Un reintento hacia la cátedra es seguro (RF-09) |
| Evento tardío o fuera de orden | `AppointmentCancelled` después de T11 no cambia nada (T08). Un `AppointmentConfirmed` tardío sobre un abandonado se registra como error (D6 de T08); por eso existe la condición de la marca |
| Falla de persistencia | RF-13 |
| Solicitudes simultáneas | El candado las ordena: una cancela y la otra encuentra `CANCELLED` (RF-14) |
| Reinicio con operaciones pendientes | Si se cae después de que la cátedra canceló, lo repara `AppointmentCancelled` (T08) o T13 |
| Otros puntos aplicables de la guía | Datos que no se exponen (RF-15) |

**Puntos de la guía no aplicables y motivo:** paginación: T11 devuelve un solo proceso. Tareas programadas: T12 y T13.

## Restricciones del pedido

<!-- Condiciones ya impuestas: compatibilidad, límites de alcance, requisitos
de seguridad o rendimiento medibles, o una tecnología expresamente exigida.
Ejemplo: Respetar el contrato de Documentacion y el anexo de la cátedra puede ser
una restricción; el diseño de los adaptadores va en PLAN.md.
No conviertas una preferencia del agente en una restricción. -->

- Respetar el anexo de la cátedra §12 y §13.1.
- La suite normal de tests no se conecta a la cátedra ni a su Kafka.
- Aplicar la skill `hexagonal-vertical-slicing` con las excepciones autorizadas. Lo existente se amplía en lugar de duplicarse.
- No cambiar el comportamiento de T01 a T10, X01 ni la validación del JWT.

## Criterios de aceptación

<!-- Resultados observables que permitan decidir si se cumple cada requisito.
Incluye los casos alternativos acordados: cada flujo alternativo cubierto debería
tener un criterio. No uses Funciona correctamente.
Repite el formato según sea necesario. -->

- **CA-01 · RF-01:** Sin JWT, o con uno inválido o vencido, responde `401`; con un rol no válido, `403`. Nada cambia.
- **CA-02 · RF-02:** Con 501 caracteres, `400` `VALIDATION_ERROR` con `reason` en `fieldErrors`, sin tocar la base. Con 500, se acepta.
- **CA-03 · RF-02:** Sin body, con `{}`, con `reason` vacío o solo con espacios: queda sin motivo y no se envía `reason` a la cátedra.
- **CA-04 · RF-03:** Un proceso inexistente y uno ajeno responden el mismo `404`. El ajeno no cambia.
- **CA-05 · RF-04:** Un `CANCELLED` responde `200` con sus datos, sin cambios y sin requests a la cátedra.
- **CA-06 · RF-05:** `PHONE_SUBMITTED`, `EXPIRED`, `INVALID`, y `HELD` o `WAITING_FOR_PHONE` marcados responden `409` `INVALID_PROCESS_STATE` con `currentStatus`, sin cambios y sin requests a la cátedra.
- **CA-07 · RF-06, D2:** Un `HELD` o `WAITING_FOR_PHONE` sin marca, vencido o no, responde `200` con `CANCELLED`, `cancelledAt` y el motivo, y queda igual en la base, sin requests a la cátedra. Después, ese usuario puede bloquear otro turno y el proceso no aparece en T10.
- **CA-08 · RF-07, RF-08:** Un `CONFIRMED` envía a la cátedra el pedido con el JWT técnico y el motivo. Con su `200`, responde `CANCELLED`, con el `cancelledAt` recibido en segundos, el motivo y el `reservationId`, y sin marca.
- **CA-09 · RF-09:** Un `401` renueva y repite una vez. Fallas temporales se reintentan; agotadas, `503` `CENTRAL_UNAVAILABLE`, sigue `CONFIRMED` y queda marcado.
- **CA-10 · RF-10:** `403` `STUDENT_INTEGRATION_NOT_FOUND` responde `503` `SERVICE_DEGRADED`, sin cambios ni marca.
- **CA-11 · RF-11:** `404` `RESERVATION_NOT_FOUND` responde `500` `INTERNAL_ERROR` y marca. `409` `RESERVATION_NOT_CANCELLABLE` responde ese `409` y marca. En los dos sigue `CONFIRMED`.
- **CA-12 · RF-12:** `403` `RESERVATION_OWNER_MISMATCH` responde `500` `INTERNAL_ERROR`, registrado como error grave.
- **CA-13 · RF-13:** Si la cátedra canceló y falla guardar, responde `500` `LOCAL_STATE_OUT_OF_SYNC` y queda registrado como crítico. Si falla guardar un abandono, responde `500` `INTERNAL_ERROR` y el proceso sigue igual.
- **CA-14 · RF-14:** La cancelación espera a otra operación del usuario, y un resultado de la cátedra espera a que termine. Dos cancelaciones simultáneas de un `CONFIRMED` hacen un solo pedido a la cátedra.
- **CA-15 · RF-15:** Ninguna respuesta contiene `holdId`, `requestEventId`, el teléfono ni la marca, y el teléfono no aparece en el log, tampoco al fallar.
- **CA-16 · E2E, D4 (lo corre el usuario):** Contra la cátedra real, un usuario bloquea un turno, lo abandona con T11 y bloquea otro turno libre: `200` y `201`. El turno abandonado sigue sin figurar libre.
- **CA-17 · E2E, bloqueado por la cátedra:** Con la cátedra publicando, el recorrido largo llega a `CONFIRMED` y cancela con T11. Hoy no se puede comprobar.
- **CA-18 · Restricciones:** La suite existente sigue pasando sin cambios en lo que comprueba.

## Cómo se comprueba el comportamiento

<!-- Una fila por criterio: escenario y resultado que debemos comprobar.
La selección de tests, herramientas, comandos y evidencias se desarrolla en PLAN.md.
No marques los criterios como superados durante la especificación. -->

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | Sin token, inválido, vencido, rol no válido | `401` o `403` |
| CA-02 | 500 y 501 caracteres | Se acepta; `400` |
| CA-03 | Sin body, `{}`, vacío y con espacios | Sin motivo, ni en la base ni en el pedido |
| CA-04 | Inexistente y ajeno | Mismo `404` |
| CA-05 | `CANCELLED` | `200`, sin requests |
| CA-06 | Cada estado y cada marcado | `409` con `currentStatus` |
| CA-07 | `HELD` y `WAITING_FOR_PHONE`, vencidos y no; luego bloquear y listar | `200`, sin requests; otro bloqueo posible; no listado |
| CA-08 | `CONFIRMED`, cátedra simulada | Pedido correcto; proceso cancelado |
| CA-09 | `401`, fallas temporales y agotadas | Renueva; reintenta; `503` y marca |
| CA-10 a CA-12 | Cada respuesta de la cátedra | Cada resultado, y lo registrado |
| CA-13 | Falla al guardar en cada camino | Cada `500` |
| CA-14 | Candado tomado; dos pedidos simultáneos | Espera; un solo pedido |
| CA-15 | Fila con teléfono; salida capturada | Sin esos datos |
| CA-16 | E2E real corrido por el usuario | `200` y `201` |
| CA-17 | E2E real, bloqueado | No verificado hasta que la cátedra publique |
| CA-18 | Suite existente | Sin cambios de comportamiento |

## Decisiones pendientes

<!-- Al resolverlas, actualiza las secciones afectadas. Escribe Ninguna cuando
no queden pendientes funcionales ni restricciones por decidir. -->

Ninguna.

Cambio de documentos aprobado por el usuario el 2026-11-04: T11, T12, contrato §3.9 y §4.2.7, secuencia §6, dominio (transiciones y regla 19) y A11, en `documentacion` y en la copia de este servicio.

Decisiones tomadas:

- **D1 · Qué pasa con un proceso abierto que el usuario quiere abandonar (propuesta del usuario, decidida el 2026-11-04):** `CONFIRMED` se cancela en la cátedra, como dice el anexo. `HELD` y `WAITING_FOR_PHONE` pasan a `CANCELLED` solo en turnos, sin llamar a la cátedra, y el usuario puede bloquear otro turno: el límite de un proceso abierto es de turnos, y el anexo no limita los holds por paciente. El turno queda tomado en la cátedra hasta que venza, y después los dos lados coinciden. Excepción: un proceso marcado para reconciliar no se abandona (`409` `INVALID_PROCESS_STATE`), porque un teléfono que creímos no publicado podría confirmar una reserva real. Se documenta en una decisión propia (015). **Sin comprobar contra la cátedra real:** cancelar `CONFIRMED`, y un segundo hold de otro turno con el primero activo.
- **D2 · Un proceso abierto y vencido (opción c, decidida por el usuario el 2026-11-04):** se abandona igual que uno sin vencer y responde `200`. No se compara el vencimiento: el usuario pidió cancelar, y el resultado es el mismo que con `EXPIRED`: no se lista y puede bloquear otro turno. La condición de la marca también vale acá. Se pierde que un proceso vencido figure `EXPIRED` en lugar de `CANCELLED`.
- **D3 · Motivo vacío (opción a, decidida por el usuario el 2026-11-04):** un motivo vacío o solo con espacios cuenta como si no viniera: no se guarda ni se envía a la cátedra.
- **D4 · Prueba contra la cátedra real (opción a, decidida por el usuario el 2026-11-04):** el E2E suma bloquear, abandonar con T11 y bloquear otro turno, que comprueba lo que D1 supone y se puede correr hoy. El recorrido largo pasa a cancelar con T11 (D9 de T04), bloqueado hasta que la cátedra publique. Cada corrida deja dos turnos del lunes tomados hasta 10 minutos.
