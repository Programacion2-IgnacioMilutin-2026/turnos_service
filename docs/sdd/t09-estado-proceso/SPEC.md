# SPEC: T09 — Consultar estado de un proceso

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
| Caso de uso T09 (`docs/casos_de_uso/turnos_y_reservas_usecases.md`) | Pasos 1 a 5 y alternativos 2.1, 3.1 y 3.2. Contexto: T06, T08, T10, T12 y T13 |
| Diagrama de secuencia (`docs/secuencias/turnos_y_reservas_secuencia.md`) | §5, bloque "T09 — Query process status" |
| Contrato de turnos (`docs/contratos/turnos_y_reservas_contrato.md`) | §1 (fila T09), §2 (proceso de reserva), §3 (reglas comunes) y §3.7 |
| Contrato común (`docs/contratos/contratos_comunes.md`) | §1.4 (JWT y dueño), §2 (errores), §3 (instantes en UTC) |
| Modelo de dominio de turnos (`docs/turnos_y_reservas_dominio.md`) | `ReservationProcess`, `ReservationStatus`, §2; reglas 6, 7, 8, 9, 14 y 16 |
| SPEC de T08 (`docs/sdd/t08-resultado-reserva/SPEC.md`) y decisión 013 | D5 y D7 de T08 (marca y motivo de rechazo al cerrar), candado por usuario |
| Caso de uso de la app A09 (`uml/casos_de_uso/app_kmp_usecases.md`) | Solo como contexto: cómo la app consulta y trata cada respuesta |
| Anexo de la cátedra | No aplica: T09 no habla con la cátedra |

## Qué construimos y para quién

<!-- Qué necesidad cubre, qué actor la inicia (la app KMP, otro servicio, el
tiempo o la cátedra) y qué podrá hacer o recibir. Describe el objetivo en
lenguaje de negocio. Resume y referencia: no copies la descripción del caso de uso. -->

La app consulta el estado de un proceso de reserva del usuario, para seguir su progreso sin depender de otro aviso: el servicio no le envía notificaciones, así que la app pregunta cada pocos segundos mientras espera el resultado del teléfono (A09). Es una lectura local. Su único efecto es el cierre reactivo de un proceso vencido (T09 paso 4).

## Situación actual

<!-- Qué existe hoy y es relevante (código, datos, otras funcionalidades ya
implementadas), la limitación que queremos resolver y los comportamientos
existentes que deben conservarse. Si todavía no hay código para esto, indícalo.
No describas la arquitectura. -->

- Están implementados T01 a T08, X01 y la validación del JWT. T10 a T13 todavía no.
- No existe ningún `GET` sobre los procesos de reserva.
- El proceso ya guarda todos los datos que T09 informa, y T08 ya los escribe. Pero la respuesta de T04, T05 y T06 deja siempre en `null` `rejectionReason`, `reservationId`, `confirmedAt`, `cancelledAt` y `cancellationReason` (fuera de alcance en T08). En los estados que devuelven T04, T05 y T06 esos campos están vacíos, así que informarlos no cambia sus respuestas.
- T04, T05 y T06 ya cierran por vencimiento con el margen de seguridad. Ninguna operación marca hoy un `PHONE_SUBMITTED` vencido, y hasta que existan T12 y T13 nadie lo resuelve.
- Debe conservarse el comportamiento de T01 a T08, X01 y la validación del JWT.

## Dentro del alcance

<!-- Requisitos concretos, con identificadores estables para vincularlos a
criterios, decisiones del plan y tareas. Cada requisito indica el paso o flujo
alternativo del que proviene. No agregues requisitos que los documentos de
referencia no tengan sin consultarlo. -->

- **RF-01** · T09 pasos 1 y 2, alternativo 2.1, contrato §3.7: `GET /api/reservation-processes/{reservationProcessId}` exige JWT válido de cualquier rol. El dueño sale del token.
- **RF-02** · T09 paso 3 y alternativo 3.1: un proceso inexistente o ajeno responde `404` `RESERVATION_PROCESS_NOT_FOUND`, sin distinguirlos.
- **RF-03** · T09 paso 5, contrato §2 y §3.7: responde `200` con el objeto completo del proceso, el mismo que devuelven T04, T05 y T06, ahora con todos sus campos informados según el estado: `rejectionReason`, `reservationId`, `confirmedAt`, `cancelledAt` y `cancellationReason` (D1). Los instantes salen en el mismo formato que `expiresAt`.
- **RF-04** · T09 paso 4, dominio reglas 7 y 8: un proceso en `HELD` o `WAITING_FOR_PHONE` con `expiresAt` más el margen de seguridad ya vencido pasa a `EXPIRED` antes de responder, y responde `200` con ese estado.
- **RF-05** · T09 paso 4, dominio regla 8: un proceso en `PHONE_SUBMITTED` en esa situación no se cierra: queda marcado para reconciliar y responde `200` con `PHONE_SUBMITTED`.
- **RF-06** · T09 postcondición: en cualquier otro caso la consulta no modifica nada. Tampoco llama a la cátedra ni a catálogo.
- **RF-07** · Guía de backend (concurrencia), decisión 013: la consulta no espera a las demás operaciones del usuario, salvo cuando tiene que escribir. Antes de cerrar o marcar, se asegura de que nadie la haya adelantado: si el proceso ya cambió, no lo pisa y responde lo que encontró. Un proceso ya marcado no se vuelve a escribir (D2).
- **RF-08** · T09 alternativo 3.2, contrato §3: si falla la lectura de la base, o la escritura de RF-04 o RF-05, se registra y responde `500` `INTERNAL_ERROR`, sin informar un estado que no se guardó. La próxima consulta reintenta sola, porque el proceso sigue vencido. Al fallar una escritura se registra solo el tipo de error, porque la fila puede llevar el teléfono (D3).
- **RF-09** · Contrato §2, guía de backend (privacidad): la respuesta nunca expone `holdId`, `requestEventId`, el teléfono ni la marca de reconciliación. El teléfono tampoco aparece en logs ni en errores.

## Fuera de alcance

<!-- Exclusiones acordadas, no deducidas por el agente. Incluye las partes de los
casos de uso que se dejan para otra funcionalidad. Si no hay exclusiones
adicionales, indícalo tras revisarlo con la persona. -->

Confirmado con el usuario:

- El listado de "Mis reservas" (T10) y su cierre de vencidos.
- La cancelación (T11).
- El barrido periódico (T12) y la reconciliación (T13). T09 solo deja la marca que T13 necesita.
- Prueba contra la cátedra real: T09 no habla con ella (D4).

## Flujo de la operación

<!-- Cómo se inicia (request, evento o tarea programada), qué verifica y qué
resultado produce, incluidas las alternativas relevantes. Si el caso de uso ya
lo describe, referencia sus pasos en lugar de repetirlos y describe solo lo que
cubre esta funcionalidad. -->

1. La app llama al endpoint (T09 paso 1) y la seguridad valida el JWT (paso 2).
2. Se busca el proceso entre los del usuario (paso 3, RF-02).
3. Si esa lectura muestra un proceso abierto, vencido y todavía sin resolver, se toma el candado del usuario, se vuelve a leer y se aplica el cierre reactivo a lo que diga esa segunda lectura (paso 4, RF-04, RF-05, RF-07). En cualquier otro caso no se toma el candado.
4. Se responde el proceso (paso 5, RF-03).

## Datos y reglas de negocio

<!-- Información que necesita la operación, campos obligatorios, validaciones,
límites y reglas como duplicados u orden de presentación. Describe significado
y comportamiento, sin diseñar tablas, DTO, DAO ni almacenamiento. -->

- `reservationProcessId` es un texto opaco que asigna la cátedra (dominio regla 4): no se le valida un formato. Un valor que no corresponde a ningún proceso es un `404`.
- El margen de seguridad es el mismo de T04, T05 y T06 (dominio regla 7).
- Un estado final no se reabre (dominio regla 6). Un proceso cerrado no tiene nada que reconciliar: la marca se apaga al pasar a `EXPIRED` y T09 no se la pone a un proceso cerrado (dominio regla 9, D5 de T08).

## Casos alternativos y de error

<!-- Adapta la tabla usando BACKEND_GUIDELINES.md. Cuando el caso de uso ya define
el flujo alternativo, referencia su ID en lugar de repetirlo. Añade escenarios
relevantes. Marca No aplica con su motivo cuando corresponda. Expresa resultados
observables (estado HTTP, código de error, estado final del recurso, evento
publicado), no mecanismos técnicos. -->

| Situación | Comportamiento esperado |
| --- | --- |
| Entrada inválida | No hay body ni parámetros: un id desconocido es T09 3.1 (RF-02). PENDIENTE de comprobar en la implementación qué responde un id con caracteres que la ruta o la base rechazan; no debe ser `500` (CA-13) |
| Sin sesión o sin permiso | T09 2.1: `401` o `403` de la capa de seguridad |
| Recurso inexistente o ajeno | T09 3.1: `404`, sin distinguirlos (RF-02) |
| Dependencia no disponible (cátedra, catálogo, Redis, Kafka) | No aplica: es una lectura local (RF-06) |
| Timeout o espera excesiva | La consulta común no espera. La que tiene que escribir espera lo que dure la operación en curso del usuario (T05 o T06, D2) |
| Operación o mensaje repetido | Consultas repetidas responden lo mismo. Un proceso ya marcado o cerrado no se vuelve a escribir (RF-06, RF-07) |
| Evento tardío o fuera de orden | Un resultado de T08 entre dos consultas se ve en la siguiente (T09 postcondición). Si llega mientras la consulta decide cerrar, gana el resultado: la segunda lectura lo ve y no se pisa (RF-07). Si llega después del cierre, se aplica lo aceptado en D6 de T08 |
| Falla de persistencia | `500` `INTERNAL_ERROR` al leer (T09 3.2) y al escribir (RF-08). El estado en la base no cambia |
| Solicitudes simultáneas | Dos consultas del mismo proceso vencido: una escribe y la otra ve el resultado, sin escribir de nuevo (RF-07). Una lectura puede mostrar `PHONE_SUBMITTED` mientras T06 publica, aunque después vuelva a `WAITING_FOR_PHONE`: lo admite la postcondición de T09 |
| Reinicio con operaciones pendientes | No aplica: si se cae antes de escribir, la próxima consulta repite el cierre reactivo |
| Otros puntos aplicables de la guía | Datos que no se exponen (RF-09) |

**Puntos de la guía no aplicables y motivo:** paginación: T09 devuelve un solo proceso (es T10). Tareas programadas: T12 y T13. Dependencias externas: lectura local.

## Restricciones del pedido

<!-- Condiciones ya impuestas: compatibilidad, límites de alcance, requisitos
de seguridad o rendimiento medibles, o una tecnología expresamente exigida.
Ejemplo: Respetar el contrato de Documentacion y el anexo de la cátedra puede ser
una restricción; el diseño de los adaptadores va en PLAN.md.
No conviertas una preferencia del agente en una restricción. -->

- Respetar el contrato de turnos §2 y §3.7.
- La suite normal de tests no se conecta a la cátedra ni a su Kafka.
- Aplicar la skill `hexagonal-vertical-slicing` con las excepciones autorizadas. Lo existente se amplía en lugar de duplicarse.
- No cambiar el comportamiento de T01 a T08, X01 ni la validación del JWT.
- La lectura sin candado es una excepción a lo que dicen `CLAUDE.md` y la decisión 013: se anota en ambos (D2).

## Criterios de aceptación

<!-- Resultados observables que permitan decidir si se cumple cada requisito.
Incluye los casos alternativos acordados: cada flujo alternativo cubierto debería
tener un criterio. No uses Funciona correctamente.
Repite el formato según sea necesario. -->

- **CA-01 · RF-01:** Sin JWT, o con uno inválido o vencido, responde `401`, sin datos y sin cambiar ningún proceso.
- **CA-02 · RF-02:** Un proceso inexistente y uno ajeno responden el mismo `404` `RESERVATION_PROCESS_NOT_FOUND`. El proceso ajeno no cambia, aunque esté vencido.
- **CA-03 · RF-04:** Dado un proceso propio en `HELD` o `WAITING_FOR_PHONE` vencido más allá del margen, responde `200` con `EXPIRED` y `expiresAt` en `null`, y la base lo tiene `EXPIRED`, sin marca y sin motivo de rechazo. Vencido pero dentro del margen, responde su estado sin cambios.
- **CA-04 · RF-05:** Dado un proceso propio en `PHONE_SUBMITTED` vencido más allá del margen, responde `200` con `PHONE_SUBMITTED` y la base lo tiene marcado, con el mismo estado. Dentro del margen, no se marca.
- **CA-05 · RF-06:** Dado un proceso en `CONFIRMED`, `CANCELLED`, `EXPIRED` o `INVALID`, la consulta no cambia nada en la base. No hay ninguna llamada a la cátedra ni a catálogo.
- **CA-06 · RF-09:** Ninguna respuesta contiene `holdId`, `requestEventId`, el teléfono ni la marca, en ningún estado. El teléfono no aparece en el log.
- **CA-07 · Restricciones:** La suite existente sigue pasando sin cambiar las respuestas de T04, T05 y T06.
- **CA-08 · RF-03:** La respuesta trae todos los campos del objeto y, según el estado: `WAITING_FOR_PHONE` rechazado, `rejectionReason` y `expiresAt`; `CONFIRMED`, `reservationId` y `confirmedAt`, sin `expiresAt`; `CANCELLED`, `cancelledAt` y `cancellationReason`, con `reservationId` si llegó a confirmarse; `EXPIRED` e `INVALID`, sin `expiresAt`.
- **CA-09 · RF-07:** Una consulta de un proceso no vencido responde sin esperar aunque otra operación del usuario tenga el candado. La de un proceso vencido espera a que termine.
- **CA-10 · RF-07:** Si entre la primera lectura y el candado el proceso pasó a `CONFIRMED` u otro estado final, la consulta responde ese estado y no lo cambia: ni lo pasa a `EXPIRED` ni le pone la marca.
- **CA-11 · RF-07:** Un `PHONE_SUBMITTED` vencido ya marcado responde sin tomar el candado y sin escribir.
- **CA-12 · RF-08:** Si falla la lectura, o la escritura de un cierre o de una marca, responde `500` `INTERNAL_ERROR` sin detalles internos. Tras fallar la escritura, el proceso sigue como estaba y la consulta siguiente, con la base disponible, aplica el cierre o la marca. Si la fila tiene teléfono, no aparece en el log.
- **CA-13 · Entrada inválida:** Un id con caracteres que la ruta o la base rechazan, como el carácter nulo, no responde `500`.

## Cómo se comprueba el comportamiento

<!-- Una fila por criterio: escenario y resultado que debemos comprobar.
La selección de tests, herramientas, comandos y evidencias se desarrolla en PLAN.md.
No marques los criterios como superados durante la especificación. -->

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | Sin token, token inválido y vencido | `401` |
| CA-02 | Proceso inexistente y proceso ajeno, también uno ajeno vencido | Mismo `404`; el ajeno no cambia |
| CA-03 | `HELD` y `WAITING_FOR_PHONE`, vencidos fuera y dentro del margen | `EXPIRED` en respuesta y base; sin cambios dentro del margen |
| CA-04 | `PHONE_SUBMITTED` vencido fuera y dentro del margen | Sigue igual; marcado solo fuera del margen |
| CA-05 | Cada estado cerrado | Sin cambios en la base y sin llamadas externas |
| CA-06 | Una consulta por estado; salida capturada | Sin esos datos en respuestas ni log |
| CA-07 | Suite existente | Sin cambios de comportamiento |
| CA-08 | Una consulta por estado, con cada dato cargado | Cada campo según el estado |
| CA-09 | Candado tomado por otra operación; proceso no vencido y vencido | El primero responde enseguida; el segundo, al soltarse |
| CA-10 | Proceso vencido que un resultado cierra entre la lectura y el candado | Responde el estado final, sin cambios en la base |
| CA-11 | `PHONE_SUBMITTED` vencido y marcado, con el candado tomado | Responde enseguida, sin escritura |
| CA-12 | Falla al leer y al escribir el cierre y la marca; salida capturada; luego una consulta con la base disponible | `500`; sin cambios ni teléfono en el log; la siguiente lo resuelve |
| CA-13 | Consulta con un id con el carácter nulo | Rechazo de la ruta o `404`, nunca `500` |

## Decisiones pendientes

<!-- Al resolverlas, actualiza las secciones afectadas. Escribe Ninguna cuando
no queden pendientes funcionales ni restricciones por decidir. -->

Ninguna.

Decisiones tomadas:

- **D1 · Campos de la respuesta (opción a, decidida por el usuario el 2026-11-03):** se usa el nombre que ya tiene el proyecto, `rejectionReason` (contrato §2, dominio y código), con el objeto completo. Se corrigieron T09 paso 5, T10 paso 7, A08 paso 1, A09 3.1 y la secuencia §5, en `documentacion` y en la copia de este servicio. El `reason` de T08 (campo del evento de la cátedra) y el de T11 (entrada de la cancelación) son correctos y no cambian.
- **D2 · Convivencia con las demás operaciones del usuario (opción a, decidida por el usuario el 2026-11-03):** leer sin candado y, solo si hay que escribir, tomarlo, volver a leer, comprobar de nuevo y escribir. La consulta se repite cada pocos segundos y no debe esperar a T05 o T06. Es una excepción a `CLAUDE.md` y a la decisión 013, que se anota en ambos.
- **D3 · Falla al escribir el cierre reactivo (opción a, decidida por el usuario el 2026-11-03):** `500` `INTERNAL_ERROR`, como contrato §3 y T10 4.1. La próxima consulta reintenta, y A09 1.5 ya trata ese error como "no pudo actualizar".
- **D4 · Prueba contra la cátedra real (opción a, decidida por el usuario el 2026-11-03):** no se agrega al E2E. T09 no habla con la cátedra; alcanza con integración sobre Postgres real.
