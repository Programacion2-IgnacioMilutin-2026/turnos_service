# SPEC: T08 — Procesar resultado de la reserva

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
| Caso de uso T08 (`docs/casos_de_uso/turnos_y_reservas_usecases.md`) | Pasos 1 a 6 y alternativos 2.1, 3.1, 4.1, 4.2, 5.1 y 5.2. Contexto: T05, T06 (que publica el teléfono) y T07 (primer consumidor del mismo topic) |
| Diagrama de secuencia (`docs/secuencias/turnos_y_reservas_secuencia.md`) | §4, bloque "T08 — Process reservation result" |
| Contrato de turnos (`docs/contratos/turnos_y_reservas_contrato.md`) | §2 (el proceso nunca expone `requestEventId`; `expiresAt` solo en procesos abiertos), §4.3 (tabla de eventos), §4.3.1 (reglas comunes) y §4.3.4 completo (los cinco eventos, efecto de cada uno y casos comunes) |
| Contrato común (`docs/contratos/contratos_comunes.md`) | §3 (instantes en UTC) |
| Modelo de dominio de turnos (`docs/turnos_y_reservas_dominio.md`) | `ReservationProcess` (`reservationId`, `confirmedAt`, `cancelledAt`, `cancellationReason`, `rejectionReason`, `needsReconciliation`), `ReservationStatus`, §2 (transiciones y tabla de cierres por eventos de la cátedra), `ProcessedEvent` (§3); reglas 6, 9, 10, 11, 12, 14, 15, 16 y 17 |
| SPEC de T07 (`docs/sdd/t07-solicitud-informacion/`) y decisiones 013 y 014 | D3 de T07 (los eventos de resultado se descartaban hasta T08), consumo de Kafka y candado por usuario |
| Anexo de la cátedra (`enunciado/INTEGRATION_REFERENCE-v2.md`) | §15.1 (convenciones), §15.6 a §15.10 (los cinco eventos), §18.1 (mensaje duplicado), §18.3 (proceso vencido) y §18.4 (reintentos) |

## Qué construimos y para quién

<!-- Qué necesidad cubre, qué actor la inicia (la app KMP, otro servicio, el
tiempo o la cátedra) y qué podrá hacer o recibir. Describe el objetivo en
lenguaje de negocio. Resume y referencia: no copies la descripción del caso de uso. -->

La cátedra publica por Kafka el resultado de un proceso de reserva: confirmada, teléfono rechazado, vencida, inválida o cancelada. Turnos lo recibe, lo correlaciona con el proceso local y actualiza su estado para que la app lo vea (T09, T10). No interactúa con el usuario. Completa lo que T07 dejó descartando: el segundo consumidor del topic de acciones.

## Situación actual

<!-- Qué existe hoy y es relevante (código, datos, otras funcionalidades ya
implementadas), la limitación que queremos resolver y los comportamientos
existentes que deben conservarse. Si todavía no hay código para esto, indícalo.
No describas la arquitectura. -->

- Están implementados T01 a T05, T07, X01 y la validación del JWT. T06, T09 y los siguientes todavía no.
- El consumo del topic de acciones existe (T07), pero los cinco eventos de resultado se descartan con un registro en el log y se confirma el offset, sin registrarlos como procesados (D3 de T07).
- El proceso de reserva no tiene los estados `CONFIRMED`, `CANCELLED` ni `INVALID`, ni guarda `reservationId`, `confirmedAt`, `cancelledAt`, `cancellationReason` ni `rejectionReason`.
- Existen el registro de eventos procesados, la transacción conjunta del cambio y del registro, y el candado por usuario (T07, decisión 013).
- **La cátedra real no publica en el topic de acciones** (verificado en T07): los mensajes de prueba se arman desde el anexo, y no se puede comprobar contra la cátedra real el formato ni el orden de los eventos.
- Debe conservarse el comportamiento de T01 a T05, T07, X01 y la validación del JWT. El procesamiento del pedido de teléfono de T07 no cambia.

## Dentro del alcance

<!-- Requisitos concretos, con identificadores estables para vincularlos a
criterios, decisiones del plan y tareas. Cada requisito indica el paso o flujo
alternativo del que proviene. No agregues requisitos que los documentos de
referencia no tengan sin consultarlo. -->

**Recepción y validación**

- **RF-01** · T08 paso 1, contrato §4.3.1: los cinco eventos de resultado del topic de acciones se procesan; dejan de descartarse (D3 de T07).
- **RF-02** · T08 paso 2 y alternativo 2.1, contrato §4.3.1 y §4.3.4: se validan los campos requeridos según el tipo (tabla de D8) y `schemaVersion` igual a `1`; para `AppointmentCancelled` no se exigen `requestEventId` ni `reservationId`. Los campos desconocidos se ignoran. Un mensaje inválido (incluido uno que no es JSON o con un valor mal escrito en un campo que se lee) o con `eventType` desconocido se descarta, se registra con su motivo y se confirma el offset.

**Correlación e idempotencia**

- **RF-03** · T08 paso 3 y alternativo 3.1, dominio regla 17, anexo §18.1: un `eventId` ya procesado no repite ningún efecto y se confirma el offset.
- **RF-04** · T08 paso 4 y alternativos 4.1 y 4.2: sin proceso local con ese `reservationProcessId` (huérfano) o con un `holdId` distinto, se registra, se confirma el offset y el proceso no cambia; el `eventId` no se registra como procesado.
- **RF-05** · T08 alternativo 5.1, dominio regla 6: si el proceso ya está en un estado final (`CONFIRMED`, `EXPIRED`, `CANCELLED` o `INVALID`), no se reabre: se ignora el efecto, se registra el `eventId` como procesado y se confirma el offset. Excepción: `AppointmentCancelled` sobre `CONFIRMED` se aplica. Un `AppointmentConfirmed` sobre `EXPIRED` o `INVALID` además deja un error en el log (D6).

**Efecto de cada evento** (T08 paso 5, contrato §4.3.4 "Efecto de cada evento", dominio §2)

- **RF-06** · `AppointmentConfirmed`: el proceso pasa a `CONFIRMED`, guarda `reservationId` y `confirmedAt` (el `occurredAt` del evento). Si el evento trae `professionalId`, `date` o `startTime`, se actualizan con esos valores y, si difieren de los guardados, se registra un warning; si no los trae, quedan los guardados (D3, D8).
- **RF-07** · `AdditionalInformationRejected`: un proceso en `PHONE_SUBMITTED` vuelve a `WAITING_FOR_PHONE`, guarda `expiresAt` y `rejectionReason` (el `reason` del evento); el `requestEventId` no cambia (dominio regla 10). Un `reason` de más de 500 caracteres se recorta (D4). En `HELD` o `WAITING_FOR_PHONE` no se aplica: se registra en el log y el `eventId` como procesado, y se confirma el offset (D2).
- **RF-08** · `AppointmentProcessExpired`: el proceso pasa a `EXPIRED`.
- **RF-09** · `AppointmentProcessInvalid`: el proceso pasa a `INVALID` y queda marcado para reconciliar (contrato §4.3.4, D5).
- **RF-10** · `AppointmentCancelled`: el proceso pasa a `CANCELLED`, guarda `cancelledAt` (el `occurredAt`) y `cancellationReason` (el `reason`, que puede ser `null`). Un `reason` de más de 500 caracteres se recorta (D4).
- **RF-15** · Contrato §2, dominio §4 y regla 11, T10 paso 7, D7: `rejectionReason` solo tiene valor en `WAITING_FOR_PHONE`. Se borra en toda transición que saque al proceso de ese estado, también en los cierres por vencimiento que ya existen.
- **RF-16** · Dominio regla 9, D5: pasar a `CONFIRMED`, `CANCELLED` o `EXPIRED` apaga la marca de reconciliación, también en los cierres por vencimiento que ya existen. El rechazo no la toca.

**Consistencia y errores**

- **RF-11** · T08 paso 6, contrato §4.3.1, dominio regla 17: el cambio del proceso y el registro del `eventId` como procesado se guardan en una sola operación atómica; recién después se confirma el offset.
- **RF-12** · T08 alternativo 5.2, contrato §4.3.1, anexo §18.4: si falla la persistencia, se reintenta con espera creciente y un máximo de intentos; agotados, se registra el error y no se confirma el offset. El comportamiento ante la falla de la base es el de T07 (D5 de T07, decisión 014 §3.3).
- **RF-13** · Guía de backend (concurrencia), decisión 013: el procesamiento toma el candado del dueño del proceso, como T07, para no pisar una operación del mismo usuario.
- **RF-14** · Guía de backend (observabilidad y privacidad): cada descarte, huérfano, inconsistencia y estado final ignorado queda en el log con `eventId`, `reservationProcessId` (si lo trae) y motivo; sin JWT técnico ni credenciales.
- **RF-17** · Guía de backend (consistencia y dependencias), D4, indicado por el usuario: un valor demasiado largo no puede frenar el consumo. Un `eventId`, `reservationProcessId` o `holdId` de más de 255 caracteres hace inválido el mensaje (se trata como RF-02), en los cinco eventos y también en el pedido de teléfono de T07. Un `reason` largo se recorta (D4) y deja un warning. Lo mismo vale para el carácter nulo (`\u0000`), que la base no acepta en un texto (agregado el 2026-11-02, aprobado por el usuario): un identificador que lo contiene hace inválido el mensaje, y a un `reason` se le quita, con un warning.

## Fuera de alcance

<!-- Exclusiones acordadas, no deducidas por el agente. Incluye las partes de los
casos de uso que se dejan para otra funcionalidad. Si no hay exclusiones
adicionales, indícalo tras revisarlo con la persona. -->

Confirmadas por el usuario (2026-11-02):

- La publicación del teléfono (T06).
- Mostrarle a la app el motivo de rechazo, `reservationId` y las fechas (T09, T10). T08 solo los guarda.
- La reconciliación por REST de los procesos `INVALID` y de los marcados (T13) y la cancelación iniciada por el usuario (T11).
- Borrar eventos procesados viejos: el dominio no define una limpieza.

## Flujo de la operación

<!-- Cómo se inicia (request, evento o tarea programada), qué verifica y qué
resultado produce, incluidas las alternativas relevantes. Si el caso de uso ya
lo describe, referencia sus pasos en lugar de repetirlos y describe solo lo que
cubre esta funcionalidad. -->

1. El mensaje llega por el topic de acciones, ya consumido desde T07.
2. Sigue los pasos 1 a 6 de T08 y el diagrama de secuencia §4 (bloque T08), con sus alternativos.
3. Lo que agrega esta spec: qué cambia respecto de T07 (RF-01), qué campos son requeridos (RF-02, D8), desde qué estados se aplica el rechazo (RF-07, D2), qué se hace con los datos del turno (RF-06, D3), qué pasa con el motivo de rechazo y la marca al cerrar (RF-15, RF-16, D5, D7), los valores demasiado largos (RF-17, D4) y el orden con las operaciones del usuario (RF-13).

## Datos y reglas de negocio

<!-- Información que necesita la operación, campos obligatorios, validaciones,
límites y reglas como duplicados u orden de presentación. Describe significado
y comportamiento, sin diseñar tablas, DTO, DAO ni almacenamiento. -->

- `eventId`, `reservationProcessId`, `holdId` y `requestEventId` son textos opacos que asigna la cátedra (dominio regla 4).
- `reservationId` es un número que asigna la cátedra; solo lo tienen los procesos confirmados y los cancelados después de confirmarse.
- Los instantes son UTC (`occurredAt`, `expiresAt`); `date` y `startTime` son locales y sin zona horaria (dominio regla 16).
- Un evento tardío nunca reabre un estado final; la excepción es la cancelación de un `CONFIRMED` (dominio regla 6).
- `rejectionReason` solo lo escribe T08 (dominio regla 11) y solo tiene valor en `WAITING_FOR_PHONE` (D7).
- `needsReconciliation` la limpia T13 y también se apaga al pasar a `CONFIRMED`, `CANCELLED` o `EXPIRED`; `INVALID` queda marcado (dominio regla 9, D5).
- Los estados finales son `CONFIRMED`, `EXPIRED`, `CANCELLED` e `INVALID`; los abiertos, `HELD`, `WAITING_FOR_PHONE` y `PHONE_SUBMITTED`.

## Casos alternativos y de error

<!-- Adapta la tabla usando BACKEND_GUIDELINES.md. Cuando el caso de uso ya define
el flujo alternativo, referencia su ID en lugar de repetirlo. Añade escenarios
relevantes. Marca No aplica con su motivo cuando corresponda. Expresa resultados
observables (estado HTTP, código de error, estado final del recurso, evento
publicado), no mecanismos técnicos. -->

| Situación | Comportamiento esperado |
| --- | --- |
| Entrada inválida | T08 2.1: mensaje descartado, registrado y offset confirmado (RF-02) |
| Sin sesión o sin permiso | No aplica a la app: no hay endpoint |
| Recurso inexistente o ajeno | T08 4.1 y 4.2: registrado, offset confirmado, sin cambios (RF-04) |
| Dependencia no disponible (Kafka) | Igual que T07: los eventos esperan en Kafka y se procesan cuando se puede consumir |
| Timeout o espera excesiva | Falla de la base: T08 5.2 (RF-12) |
| Operación o mensaje repetido | T08 3.1: sin efectos, offset confirmado (RF-03) |
| Evento tardío o fuera de orden | T08 5.1: estado final ignorado, `eventId` registrado (RF-05); rechazo sobre un proceso que no está en `PHONE_SUBMITTED`: igual (RF-07, D2) |
| Falla de persistencia | T08 5.2 (RF-12) |
| Solicitudes simultáneas | Con una operación del mismo usuario: de a una (RF-13) |
| Reinicio con operaciones pendientes | Un mensaje sin confirmar se vuelve a entregar; si su efecto ya se había guardado, RF-03 evita repetirlo |
| Otros puntos aplicables de la guía | `AppointmentConfirmed` sobre un proceso `EXPIRED` o `INVALID`: ignorado, con un error en el log (RF-05, D6) |

**Puntos de la guía no aplicables y motivo:** entrada HTTP, autorización del usuario y paginación: T08 no tiene endpoint. Tareas programadas: T08 reacciona a mensajes; el barrido periódico es T12.

## Restricciones del pedido

<!-- Condiciones ya impuestas: compatibilidad, límites de alcance, requisitos
de seguridad o rendimiento medibles, o una tecnología expresamente exigida.
Ejemplo: Respetar el contrato de Documentacion y el anexo de la cátedra puede ser
una restricción; el diseño de los adaptadores va en PLAN.md.
No conviertas una preferencia del agente en una restricción. -->

- Respetar el contrato de turnos §2 y §4.3 (§4.3.1 y §4.3.4) y el anexo §15.
- La suite normal de tests no se conecta al Kafka ni a la API de la cátedra: los simula. Antes de cualquier conexión al Kafka real se confirma con el usuario qué se hará, y la corre el usuario (decisión 010). La cátedra real no publica, así que los mensajes se arman desde el anexo.
- Las decisiones de Kafka se suman a la decisión 014.
- Aplicar la skill `hexagonal-vertical-slicing` con las excepciones autorizadas. Lo existente se amplía en lugar de duplicarse; la cátedra (también por Kafka) se usa a través de `catedra` (decisión 009), y las operaciones de un usuario usan el candado compartido (decisión 013).
- No cambiar el comportamiento de T01 a T05, T07, X01 ni la validación del JWT. Excepción: los cierres por vencimiento también borran el motivo de rechazo y apagan la marca (D5, D7), sin efecto visible en las respuestas de T04 y T05.

## Criterios de aceptación

<!-- Resultados observables que permitan decidir si se cumple cada requisito.
Incluye los casos alternativos acordados: cada flujo alternativo cubierto debería
tener un criterio. No uses Funciona correctamente.
Repite el formato según sea necesario. -->

- **CA-01 · RF-01, RF-06:** Dado un proceso en `PHONE_SUBMITTED`, `WAITING_FOR_PHONE` o `HELD`, cuando llega un `AppointmentConfirmed` válido por el topic de acciones, entonces pasa a `CONFIRMED` con `reservationId` y `confirmedAt` igual al `occurredAt`, el `eventId` queda registrado como procesado y el offset se confirma.
- **CA-02 · RF-06, D3:** Cuando el evento trae un `professionalId`, `date` o `startTime` distinto del guardado, entonces el proceso guarda el del evento y el log tiene un warning. Si coinciden, no hay warning; si no los trae, quedan los guardados.
- **CA-03 · RF-07:** Dado un proceso en `PHONE_SUBMITTED`, cuando llega un `AdditionalInformationRejected` válido, entonces vuelve a `WAITING_FOR_PHONE` con el `expiresAt` recibido y `rejectionReason` igual al `reason`, y conserva su `requestEventId`.
- **CA-04 · RF-07, D2:** Dado un proceso en `HELD` o `WAITING_FOR_PHONE`, cuando llega un `AdditionalInformationRejected`, entonces el proceso no cambia (tampoco un motivo vigente ni el vencimiento), el log lo registra, el `eventId` queda registrado y el offset se confirma.
- **CA-05 · RF-08:** Dado un proceso en cualquier estado abierto, cuando llega un `AppointmentProcessExpired`, entonces pasa a `EXPIRED`.
- **CA-06 · RF-09:** Dado un proceso en cualquier estado abierto, cuando llega un `AppointmentProcessInvalid`, entonces pasa a `INVALID` y queda marcado para reconciliar.
- **CA-07 · RF-10:** Dado un proceso abierto o `CONFIRMED`, cuando llega un `AppointmentCancelled`, entonces pasa a `CANCELLED` con `cancelledAt` igual al `occurredAt` y `cancellationReason` igual al `reason`. Se procesa también con `reason` `null` y sin `requestEventId` ni `reservationId`. Un proceso `CONFIRMED` conserva `reservationId` y `confirmedAt`.
- **CA-08 · RF-02, D8:** Cuando llega un mensaje sin uno de sus campos obligatorios (tabla de D8), con `schemaVersion` distinta de `1`, con un `eventType` desconocido, que no es JSON o con un valor mal escrito en un campo que se lee, entonces ningún proceso cambia, el `eventId` no se registra, el log tiene el motivo y el offset se confirma. Un evento válido con campos desconocidos o sin los campos opcionales se procesa normalmente.
- **CA-09 · RF-03:** Cuando el mismo evento llega dos veces, entonces el efecto se aplica una sola vez y las dos entregas confirman su offset. Si entre las dos el proceso cambió, la segunda no lo modifica.
- **CA-10 · RF-04:** Cuando llega un evento de un `reservationProcessId` inexistente o con un `holdId` distinto del guardado, entonces ningún proceso cambia, el log lo registra como huérfano o inconsistencia, el `eventId` no se registra y el offset se confirma.
- **CA-11 · RF-05:** Dado un proceso en `CONFIRMED`, `EXPIRED`, `CANCELLED` o `INVALID`, cuando llega cualquiera de los cinco eventos, entonces el proceso no cambia, el `eventId` queda registrado y el offset se confirma. La única excepción es `AppointmentCancelled` sobre `CONFIRMED` (CA-07).
- **CA-12 · RF-05, D6:** Cuando llega un `AppointmentConfirmed` sobre un proceso `EXPIRED` o `INVALID`, entonces además el log tiene un error con el `reservationProcessId` y el `reservationId`. Sobre `CONFIRMED` o `CANCELLED` no hay error.
- **CA-13 · RF-11:** Si falla registrar el `eventId`, el cambio del proceso tampoco queda guardado, y al revés.
- **CA-14 · RF-12:** Dado que la base falla al guardar en todos los intentos, entonces hay tantos intentos como el máximo configurado, con esperas crecientes y registrados; el offset no se confirma y, cuando la base vuelve, el evento se procesa. Un evento publicado después no se procesa antes que él. Si la aplicación se reinicia con el mensaje sin confirmar, se vuelve a entregar y su efecto se aplica una sola vez.
- **CA-15 · RF-13:** Dada una operación del mismo usuario en curso (por ejemplo, T05 esperando a la cátedra simulada), cuando llega un evento de resultado de ese usuario, entonces se procesa después de que la operación guarda, y ninguna de las dos escrituras pisa a la otra. Un evento de otro usuario no espera.
- **CA-16 · RF-14:** En todos los escenarios, el log identifica el `eventId` y el motivo, y no contiene el JWT técnico ni credenciales.
- **CA-17 · RF-15, D7:** Dado un proceso en `WAITING_FOR_PHONE` con motivo de rechazo, cuando sale de ese estado por un evento o por vencimiento (`expire` y `expireIfOverdue`), entonces el motivo queda vacío.
- **CA-18 · RF-16, D5:** Dado un proceso marcado, cuando pasa a `CONFIRMED`, `CANCELLED` o `EXPIRED`, por un evento o por vencimiento, entonces la marca se apaga. Con un rechazo aplicado, la marca no cambia. Un proceso marcado que T05 o T07 pasan a `WAITING_FOR_PHONE` sigue marcado.
- **CA-19 · RF-07, RF-10, RF-17, D4:** Cuando llega un evento con un `reason` de más de 500 caracteres, entonces el efecto se aplica con los primeros 500, el log tiene un warning, el offset se confirma y el evento siguiente se procesa.
- **CA-20 · RF-17:** Cuando llega un evento o un pedido de teléfono con un `eventId`, `reservationProcessId` o `holdId` de más de 255 caracteres, entonces se descarta como CA-08 y el mensaje siguiente se procesa. Con exactamente 255, se procesa. Un identificador con el carácter nulo se descarta igual, y un `reason` con ese carácter se guarda sin él, con un warning, y el mensaje siguiente se procesa.
- **CA-21 · RF-01 a RF-11:** Con la aplicación completa, base real, Kafka en contenedor y la cátedra REST simulada, un usuario bloquea y confirma un turno, llega el pedido de teléfono, el proceso se deja en `PHONE_SUBMITTED` directamente en la base (T06 no existe) y llega un `AppointmentConfirmed`; entonces la base tiene el proceso `CONFIRMED` con su `reservationId`, y el usuario puede bloquear otro turno.
- **CA-22 · Restricciones:** Las respuestas de T04 y T05 no cambian. Confirmar un proceso en `CONFIRMED`, `CANCELLED` o `INVALID` responde el `409` que T05 ya da para un proceso que no está en `HELD`, y un pedido de teléfono sobre esos estados se trata como estado ya avanzado (T07 5.1).

## Cómo se comprueba el comportamiento

<!-- Una fila por criterio: escenario y resultado que debemos comprobar.
La selección de tests, herramientas, comandos y evidencias se desarrolla en PLAN.md.
No marques los criterios como superados durante la especificación. -->

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | Un evento por cada estado abierto | `CONFIRMED`, datos guardados, `eventId` registrado, offset confirmado |
| CA-02 | Evento con datos distintos, iguales y sin ellos | Datos del evento con warning; sin warning; datos locales |
| CA-03 | Proceso `PHONE_SUBMITTED` con `requestEventId`; rechazo válido | `WAITING_FOR_PHONE`, motivo y vencimiento nuevos, mismo `requestEventId` |
| CA-04 | Rechazo sobre `HELD`, sobre `WAITING_FOR_PHONE` y sobre uno con motivo vigente | Sin cambios; log; `eventId` registrado |
| CA-05 | Un evento por cada estado abierto | `EXPIRED` |
| CA-06 | Un evento por cada estado abierto | `INVALID` y marcado |
| CA-07 | Abierto y `CONFIRMED`; `reason` con valor y `null`; sin los campos no exigidos | `CANCELLED`; datos de confirmación conservados |
| CA-08 | Un mensaje por cada defecto y por cada tipo; válidos con campos extra y sin opcionales | Descartados sin cambios y sin registrar; los válidos se procesan |
| CA-09 | Mismo evento dos veces; con un cambio del proceso entre las dos | Un solo efecto |
| CA-10 | Proceso inexistente y `holdId` distinto | Sin cambios; log; sin registrar; offset confirmado |
| CA-11 | Cada evento sobre cada estado final | Sin cambios salvo la excepción; `eventId` registrado |
| CA-12 | Confirmación sobre los cuatro estados finales | Error en el log solo con `EXPIRED` e `INVALID` |
| CA-13 | Falla al registrar el evento; falla al guardar el proceso | Ninguno de los dos queda guardado |
| CA-14 | Base que falla y se recupera, con otro evento detrás; reinicio con el mensaje sin confirmar | Intentos registrados; en orden al recuperarse; un solo efecto |
| CA-15 | T05 demorado por la cátedra simulada y evento publicado durante la espera; evento de otro usuario | Orden correcto y nada pisado; el otro no espera |
| CA-16 | Salida capturada de los escenarios | `eventId` y motivo; sin tokens ni credenciales |
| CA-17 | Motivo vigente y cada salida de `WAITING_FOR_PHONE` | Motivo vacío |
| CA-18 | Proceso marcado y cada transición | Apagada en los cierres; igual en rechazo y en `WAITING_FOR_PHONE` |
| CA-19 | `reason` de 501 caracteres en rechazo y cancelación, con otro evento detrás | Efecto con 500; warning; el siguiente se procesa |
| CA-20 | Identificadores de 256 y de 255 caracteres, y con el carácter nulo; `reason` con el carácter nulo; con otro mensaje detrás | Descartado, procesado, descartado y guardado sin el carácter, respectivamente; sin frenar el consumo |
| CA-21 | Recorrido completo con la aplicación levantada | Proceso `CONFIRMED` en la base; nuevo bloqueo posible |
| CA-22 | Suite existente; T05 y pedido de teléfono sobre los estados nuevos | Sin cambios en las respuestas; `409`; estado ya avanzado |

**Sin prueba contra la cátedra real** (actualización del 2026-11-03: el E2E de reserva ya espera `CONFIRMED` y `CANCELLED`, y lo comprueba si la cátedra publica; sigue sin correrse): no hay un criterio E2E para T08, porque la cátedra no publica en el topic de acciones y T06 todavía no existe, así que no se puede provocar un resultado real. Los mensajes se arman desde el anexo. Queda sin verificar el formato real de los eventos; se revisa al hacer T06 o cuando la cátedra publique (decisión 014 §4).

## Decisiones pendientes

<!-- Al resolverlas, actualiza las secciones afectadas. Escribe Ninguna cuando
no queden pendientes funcionales ni restricciones por decidir. -->

Ninguna.

Decisiones tomadas (2026-11-02):

- **D1 · Forma de la entrega (decidida por el usuario):** un solo caso de uso por slice para los cinco eventos, con un único camino de entrega desde `catedra` hasta `reservation`. Motivo: T08 es un solo caso de uso, y validar, correlacionar, registrar el evento y tomar el candado es igual para los cinco; solo cambia el efecto. Alternativa descartada: un caso de uso por evento, que repite ese esqueleto cinco veces.
- **D2 · `AdditionalInformationRejected` fuera de `PHONE_SUBMITTED` (RF-07, decidida por el usuario):** el rechazo solo se aplica a un proceso en `PHONE_SUBMITTED` (dominio §2). En `HELD` o `WAITING_FOR_PHONE` no cambia nada: se registra en el log, se registra el `eventId` como procesado y se confirma el offset, igual que sobre un estado final. Motivo: un rechazo solo tiene sentido después de enviar un teléfono, y uno suelto no debe pisar un motivo vigente ni el vencimiento. **Condición para T06:** tiene que guardar `PHONE_SUBMITTED` antes de publicar (D5) y hacerlo dentro del candado por usuario (decisión 013); así un resultado nunca llega antes de ese guardado y un rechazo no se pierde. Alternativa descartada: aplicarlo también en `WAITING_FOR_PHONE`.
- **D3 · `professionalId`, `date` y `startTime` de `AppointmentConfirmed` (RF-06, opción a):** se actualizan con los valores del evento y, si difieren de los guardados, se registra un warning. Motivo: T08 paso 5 y el contrato §4.3.4 dicen "crea o actualiza", y la cátedra es la fuente de verdad de las reservas confirmadas (dominio regla 15). Limitación aceptada: si cambiara `professionalId`, el nombre y la categoría guardados quedarían del profesional anterior, porque el evento no los trae; no se espera que ocurra y el warning lo deja a la vista. `AppointmentCancelled` también trae esos datos, pero su efecto no los usa. Alternativas descartadas: (b) conservar los locales, que se aparta del caso de uso; (c) conservarlos y marcar para reconciliar, que suma trabajo a T13 para un caso no esperado.
- **D8 · Campos requeridos por tipo (RF-02, opción a):** se exige solo lo que el efecto necesita, con el criterio de D4 de T07: un descarte no tiene vuelta atrás. No contradice T08 paso 2, que no los enumera.

  | Evento | Campos obligatorios, además de `eventId`, `eventType`, `reservationProcessId`, `holdId` y `schemaVersion` |
  | --- | --- |
  | `AppointmentConfirmed` | `reservationId`, `occurredAt` |
  | `AdditionalInformationRejected` | `reason`, `expiresAt` |
  | `AppointmentProcessExpired` | Ninguno |
  | `AppointmentProcessInvalid` | Ninguno (`reason` solo va al log) |
  | `AppointmentCancelled` | `occurredAt` |

  `professionalId`, `date` y `startTime` son opcionales en `AppointmentConfirmed` (D3). Un valor mal escrito en un campo que se lee descarta el mensaje, aunque ese campo sea opcional, como en T07. Alternativas descartadas: (b) exigir además esos tres datos, que descartaría una confirmación real aunque el proceso ya los tenga; (c) exigir todos los campos del anexo, que descartaría resultados válidos por campos que no se usan, y nunca se vio un mensaje real.
- **D4 · Textos más largos de lo que se guarda (RF-07, RF-10, RF-17, opción a):** `reason` se recorta a 500 caracteres y deja un warning, en `cancellationReason` y en `rejectionReason`. Motivo: un valor más largo que la columna haría fallar el guardado, y eso se trata como una caída de la base: se reintentaría sin fin y frenaría los eventos de todos los usuarios. El proceso igual cambia de estado. Limitación aceptada: en un caso muy raro se pierde el final del texto, que queda solo en el warning. Alternativas descartadas: (b) columnas sin límite, que cambia el dominio y deja a la app recibir un texto de cualquier largo desde un Kafka sin control de acceso; (c) descartar el mensaje, que pierde un resultado real por un texto.
- **D5 · Marca `needsReconciliation` (RF-09, RF-16, opción b ampliada, criterio del usuario):** un proceso que llegó a un estado final, por la cátedra o por turnos, no tiene nada que reconciliar. Pasar a `CONFIRMED`, `CANCELLED` o `EXPIRED` apaga la marca, también en los cierres por vencimiento que ya existen; `AppointmentProcessInvalid` la enciende (contrato §4.3.4, anexo §15.9); el rechazo no la toca, porque el proceso sigue abierto. T11 puede volver a marcar un `CONFIRMED` si no sabe si la cátedra canceló. **Documentos cambiados con aprobación del usuario:** dominio regla 9 (en `documentacion` y en la copia de turnos) y RF-22 y D3 de la SPEC de T05. **Riesgo y cómo se evita (decisión del usuario):** si T06 publicara y no lograra guardar, el proceso seguiría en `WAITING_FOR_PHONE`, vencería por reloj y una confirmación posterior se ignoraría, porque un estado final no se reabre (anexo §18.3). Por eso **T06 tiene que guardar `PHONE_SUBMITTED` antes de publicar**: si falla el guardado, no se publicó nada. Esto cambia los pasos 5 y 6 y el alternativo 6.1 de T06 y la secuencia §4; no se tocan ahora y se definen en la SPEC de T06, junto con qué pasa si el servicio se cae entre guardar y publicar (queda `PHONE_SUBMITTED` sin nada publicado, y T06 y T13 tienen que resolverlo). Si aun así llega una confirmación sobre un proceso cerrado, D6 lo deja a la vista. **Para T13:** su paso 6 cierra como `EXPIRED` lo que la cátedra no lista, y podría cerrar mal un `CONFIRMED` marcado; hay que limitarlo a procesos abiertos al especificar T13. Alternativa descartada: (a) que solo T13 limpie la marca.
- **D6 · `AppointmentConfirmed` sobre un proceso `EXPIRED` o `INVALID` (RF-05, opción a):** se ignora como manda T08 5.1 y el anexo §18.3 (no se reabre), se registra el `eventId` y queda un error en el log con el `reservationProcessId` y el `reservationId`, para poder encontrar el caso: la cátedra tiene una reserva que turnos da por cerrada. Limitación aceptada: el usuario sigue viendo el proceso cerrado y el turno queda ocupado en la cátedra hasta que alguien lo corrija a mano. Alternativas descartadas: (b) marcarlo para T13, que contradice el criterio de D5; (c) registrarlo como cualquier evento tardío, que lo haría difícil de notar.
- **D7 · `rejectionReason` cuando el proceso cambia de estado (RF-15, opción a):** se borra siempre que el proceso sale de `WAITING_FOR_PHONE`, también al vencer por reloj: solo un proceso en `WAITING_FOR_PHONE` puede tener motivo de rechazo. Motivo: una sola regla, en el modelo, sin importar quién cerró el proceso; cumple el contrato §2 ("solo mientras") y T10 ("si está abierta"), y un usuario con el proceso cerrado ya no puede corregir el teléfono. El cierre por vencimiento que ya existe cambia por dentro, sin efecto visible en T04 ni T05, porque el motivo todavía no se expone. Limitación aceptada: el motivo no queda en la base de un proceso cerrado; sí en el log. Alternativas descartadas: (b) conservarlo al vencer por reloj, porque el resultado dependería de quién cerró el proceso; (c) no borrarlo y filtrarlo en T09 y T10, que reparte la regla y se aparta del dominio §4.
- **D9 · SPEC aprobada de T07 (opción a):** se agregó una nota de actualización en RF-02, CA-04 y D3 de T07, que indica que T08 reemplaza el descarte, y en RF-03 por RF-17. Motivo: conserva lo decidido y aclara qué cambió, como en T05. Alternativa descartada: (b) dejarlo solo en esta spec y en la decisión 014.
