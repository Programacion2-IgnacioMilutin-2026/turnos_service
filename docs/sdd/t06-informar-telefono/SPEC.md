# SPEC: T06 — Informar teléfono

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
| Caso de uso T06 (`docs/casos_de_uso/turnos_y_reservas_usecases.md`) | Pasos 1 a 7 y alternativos 2.1, 2.2, 3.1, 3.2, 3.3, 4.1, 4.2, 5.1, 6.1, 6.2, 6.3 y 6.4 (actualizados por D0, D5 y D6). Contexto: T05, T07, T08, T09, T12 y T13 |
| Diagrama de secuencia (`docs/secuencias/turnos_y_reservas_secuencia.md`) | §4, bloque "T06 — Submit phone number" (actualizado por D0) |
| Contrato de turnos (`docs/contratos/turnos_y_reservas_contrato.md`) | §2 (el proceso nunca expone el teléfono ni la marca), §3 (reglas comunes), §3.6, §4.2.6 (estados del listado, para la condición sobre T13), §4.3.1 y §4.3.3 (§3.6 y §4.3.3 actualizados por D0) |
| Contrato común (`docs/contratos/contratos_comunes.md`) | §1.4 (JWT y dueño), §2 (errores), §3 (instantes en UTC) |
| Modelo de dominio de turnos (`docs/turnos_y_reservas_dominio.md`) | `ReservationProcess` (`phoneNumber`, `requestEventId`, `rejectionReason`, `needsReconciliation`), `ReservationStatus`, §2; reglas 5, 6, 7, 8, 9, 10 y 11 (actualizados por D0: reglas 6 y 11, §2 y §4) |
| SPEC de T08 (`docs/sdd/t08-resultado-reserva/SPEC.md`) y decisiones 013 y 014 | D2 y D5 de T08 (condiciones para T06), candado por usuario y Kafka con la cátedra |
| Anexo de la cátedra (`enunciado/INTEGRATION_REFERENCE-v2.md`) | §15.1, §15.2, §15.5, §15.7, §18.3 y §18.4 |
| Caso de uso de la app A08 (`uml/casos_de_uso/app_kmp_usecases.md`) | Solo como contexto: cómo la app trata cada respuesta |

## Qué construimos y para quién

<!-- Qué necesidad cubre, qué actor la inicia (la app KMP, otro servicio, el
tiempo o la cátedra) y qué podrá hacer o recibir. Describe el objetivo en
lenguaje de negocio. Resume y referencia: no copies la descripción del caso de uso. -->

La app envía el teléfono que el usuario ingresó en respuesta al pedido de la cátedra (T07). Turnos lo valida, deja el proceso a la espera del resultado y publica el teléfono por Kafka. El resultado llega después y lo procesa T08. Es la primera publicación de turnos en Kafka: hasta ahora solo consumía.

## Situación actual

<!-- Qué existe hoy y es relevante (código, datos, otras funcionalidades ya
implementadas), la limitación que queremos resolver y los comportamientos
existentes que deben conservarse. Si todavía no hay código para esto, indícalo.
No describas la arquitectura. -->

- Están implementados T01 a T05, T07, T08, X01 y la validación del JWT. T09 a T13 todavía no.
- El proceso ya tiene `WAITING_FOR_PHONE`, `PHONE_SUBMITTED`, `requestEventId` y `rejectionReason`, y T08 ya sabe salir de `PHONE_SUBMITTED`. Nada lleva todavía un proceso a `PHONE_SUBMITTED` y no se guarda el teléfono.
- Turnos no publica en Kafka. El login técnico ya informa el topic de teléfono y el `groupId`.
- **La cátedra real no publica en el topic de acciones** (decisión 014 §4, última comprobación del 2026-10-31; el profesor no respondió al 2026-11-02): contra la cátedra real, un proceso nunca recibe `requestEventId` y T06 respondería siempre `PHONE_REQUEST_NOT_READY`.
- Un `PHONE_SUBMITTED` no se cierra por reloj (dominio regla 8) y cuenta como proceso abierto: sin T12 ni T13, si su resultado no llega, el usuario recibe `OPEN_PROCESS_EXISTS` al bloquear otro turno.
- Debe conservarse el comportamiento de T01 a T05, T07, T08, X01 y la validación del JWT.

## Dentro del alcance

<!-- Requisitos concretos, con identificadores estables para vincularlos a
criterios, decisiones del plan y tareas. Cada requisito indica el paso o flujo
alternativo del que proviene. No agregues requisitos que los documentos de
referencia no tengan sin consultarlo. -->

**Entrada y verificaciones**

- **RF-01** · T06 pasos 1 y 2, alternativos 2.1 y 2.2, contrato §3.6: `POST /api/reservation-processes/{reservationProcessId}/phone` exige JWT válido de cualquier rol. El dueño sale del token; un proceso inexistente o ajeno responde `404` `RESERVATION_PROCESS_NOT_FOUND`, sin distinguirlos.
- **RF-02** · T06 paso 3 y alternativo 3.1: un proceso que no está en `WAITING_FOR_PHONE` responde `409` `INVALID_PROCESS_STATE` con `currentStatus`.
- **RF-03** · T06 alternativo 3.2, dominio regla 7: si `expiresAt` más el margen de seguridad ya venció, el proceso pasa a `EXPIRED` y responde `409` `HOLD_EXPIRED`, sin publicar nada.
- **RF-04** · T06 alternativo 3.3: sin `requestEventId` guardado responde `409` `PHONE_REQUEST_NOT_READY`, sin publicar nada.
- **RF-05** · T06 paso 4 y alternativo 4.1, contrato §3.6, anexo §15.5: el teléfono se valida y se normaliza antes de consultar la base y de tomar el candado (D7). Un `phoneNumber` que falta responde `400` `VALIDATION_ERROR`. Uno vacío o que no cumple el formato responde `400` `INVALID_PHONE_NUMBER`. Solo se quitan espacios, guiones, paréntesis y puntos; cualquier otro carácter lo hace inválido.

**Guardar y publicar**

- **RF-16** · T06 alternativo 4.2, D5: antes de guardar se comprueba la sesión técnica; si no hay, se intenta obtener una. Sin sesión o con un error de configuración, responde `503` `CENTRAL_UNAVAILABLE`, el motivo queda en el log y no se guarda, publica ni marca nada.
- **RF-06** · T06 paso 5 y alternativo 5.1, T08 D2 y D5, dominio regla 11, D3: antes de publicar se guarda el teléfono normalizado, se borra el motivo de rechazo y el proceso pasa a `PHONE_SUBMITTED`. Si este guardado falla, no se publica nada, el proceso queda como estaba y responde `500` `INTERNAL_ERROR`.
- **RF-07** · T06 paso 6, contrato §4.3.3, anexo §15.5: se publica `AdditionalInformationSubmitted` en el topic de teléfono informado por la integración, con todos sus campos y `reservationProcessId` como clave. `groupId`, `holdId`, `requestEventId` y el teléfono salen de los datos vigentes; `occurredAt` es el instante de la publicación, en UTC.
- **RF-08** · T06 paso 7, contrato §3.6: con la publicación aceptada por Kafka responde `200` con el proceso en `PHONE_SUBMITTED`. La respuesta no expone el teléfono ni la marca (contrato §2).
- **RF-09** · T06 alternativo 6.1, anexo §18.4: si Kafka no está disponible, se reintenta con espera creciente y un máximo de intentos, con el mismo `eventId` y el mismo contenido. Agotados, se registra, el proceso vuelve a `WAITING_FOR_PHONE`, queda marcado para reconciliar y responde `503` `CENTRAL_UNAVAILABLE`; el usuario puede reintentar mientras no venza `expiresAt`. El motivo de rechazo anterior no se restaura y el teléfono guardado queda el del intento fallido (D2).
- **RF-10** · T06 alternativo 6.2, D6: si Kafka rechaza las credenciales, se trata como RF-09 sin reintentar, y el motivo queda en el log. No se hace un login técnico.
- **RF-11** · T06 alternativo 6.4, T08 D5, D1: una caída del servicio entre guardar y publicar no tiene recuperación propia en T06. El proceso queda en `PHONE_SUBMITTED` sin nada publicado y lo resuelve T13 como cualquier `PHONE_SUBMITTED`: en su ejecución de arranque lo consulta y lo cierra cuando la cátedra lo da por vencido. Después de publicar no hay ninguna escritura.

**Concurrencia, tiempo y privacidad**

- **RF-12** · T08 D2, decisión 013, D4: T06 toma el candado del dueño del proceso desde las verificaciones hasta terminar de publicar o de volver atrás, de modo que un resultado de T08 nunca llega antes de que `PHONE_SUBMITTED` esté guardado, y ningún evento u operación del usuario se mete en la mitad.
- **RF-14** · T06 alternativo 6.3, D3: si después de agotar los reintentos no se puede volver a `WAITING_FOR_PHONE`, se registra un error grave y responde `500` `LOCAL_STATE_OUT_OF_SYNC`. El proceso queda en `PHONE_SUBMITTED`, sin marca, y lo resuelve T13 como en RF-11.
- **RF-15** · Guía de backend (dependencias), T06 alternativo 6.1, D4: el usuario recibe respuesta en un máximo de 10 s desde que empieza a publicarse, también con Kafka caído. El tope es configurable.
- **RF-13** · Guía de backend (privacidad), contrato §2: el teléfono no aparece en logs, respuestas ni mensajes de error. Solo se guarda en el proceso y se envía a la cátedra.

## Fuera de alcance

<!-- Exclusiones acordadas, no deducidas por el agente. Incluye las partes de los
casos de uso que se dejan para otra funcionalidad. Si no hay exclusiones
adicionales, indícalo tras revisarlo con la persona. -->

Confirmadas por el usuario (2026-11-02):

- Mostrar el resultado a la app (T09, T10) y procesarlo (T08, ya hecho).
- Implementar la reconciliación (T13) y el barrido (T12). T06 solo deja lo que ellos necesitan.
- Limpiar los valores de los mensajes rechazados antes de escribirlos en el log (pregunta abierta de T08).

## Flujo de la operación

<!-- Cómo se inicia (request, evento o tarea programada), qué verifica y qué
resultado produce, incluidas las alternativas relevantes. Si el caso de uso ya
lo describe, referencia sus pasos en lugar de repetirlos y describe solo lo que
cubre esta funcionalidad. -->

1. La app llama al endpoint (T06 paso 1) y la seguridad valida el JWT.
2. Valida el formato del teléfono, sin consultar la base (RF-05, D7).
3. Con el candado del usuario: verificaciones de T06 pasos 2 y 3 (RF-01 a RF-04) y de la sesión técnica (RF-16).
4. Guarda el proceso en `PHONE_SUBMITTED` y publica (D0, RF-06 y RF-07).
5. Responde (RF-08), o aplica la alternativa que corresponda (RF-06, RF-09, RF-10, RF-11, RF-14).

## Datos y reglas de negocio

<!-- Información que necesita la operación, campos obligatorios, validaciones,
límites y reglas como duplicados u orden de presentación. Describe significado
y comportamiento, sin diseñar tablas, DTO, DAO ni almacenamiento. -->

- El teléfono se guarda normalizado; `phoneNumber` es el último que el usuario intentó informar, aunque no haya llegado a salir (D2). Normalizado tiene como máximo 16 caracteres.
- `requestEventId` no cambia entre intentos (dominio regla 10; contrato §4.3.3).
- `eventId` es único en cada envío: un nuevo envío del usuario lleva uno nuevo, y los reintentos de una publicación mantienen el mismo (T06 paso 6, contrato §4.3.3).
- `rejectionReason` solo lo escribe T08 y T06 lo borra al guardar `PHONE_SUBMITTED`, aunque la publicación falle después (D2).

## Casos alternativos y de error

<!-- Adapta la tabla usando BACKEND_GUIDELINES.md. Cuando el caso de uso ya define
el flujo alternativo, referencia su ID en lugar de repetirlo. Añade escenarios
relevantes. Marca No aplica con su motivo cuando corresponda. Expresa resultados
observables (estado HTTP, código de error, estado final del recurso, evento
publicado), no mecanismos técnicos. -->

| Situación | Comportamiento esperado |
| --- | --- |
| Entrada inválida | T06 4.1 y contrato §3.6, antes de consultar la base (RF-05, D7) |
| Sin sesión o sin permiso | T06 2.1: `401` o `403` de la capa de seguridad |
| Recurso inexistente o ajeno | T06 2.2: `404`, sin distinguirlos (RF-01) |
| Dependencia no disponible (cátedra, catálogo, Redis, Kafka) | Kafka: T06 6.1 y 6.2 (RF-09, RF-10). Sin sesión técnica o con error de configuración: T06 4.2 (RF-16) |
| Timeout o espera excesiva | T06 6.1, con el proceso de vuelta en `WAITING_FOR_PHONE` (RF-09), dentro del tope de 10 s (RF-15) |
| Operación o mensaje repetido | Un segundo envío encuentra `PHONE_SUBMITTED` y responde `409` (T06 3.1) |
| Evento tardío o fuera de orden | Un resultado de T08 no puede adelantarse al guardado (RF-12) |
| Falla de persistencia | Al guardar: T06 5.1 (RF-06). Al volver atrás: T06 6.3 (RF-14) |
| Solicitudes simultáneas | Mismo usuario: de a una, también durante la publicación (RF-12) |
| Reinicio con operaciones pendientes | Queda `PHONE_SUBMITTED` sin nada publicado; lo resuelve T13 y el usuario puede perder el turno (RF-11, D1) |
| Otros puntos aplicables de la guía | Teléfono fuera de logs y respuestas (RF-13) |

**Puntos de la guía no aplicables y motivo:** paginación y caché: T06 no los usa. Tareas programadas: T12 y T13.

## Restricciones del pedido

<!-- Condiciones ya impuestas: compatibilidad, límites de alcance, requisitos
de seguridad o rendimiento medibles, o una tecnología expresamente exigida.
Ejemplo: Respetar el contrato de Documentacion y el anexo de la cátedra puede ser
una restricción; el diseño de los adaptadores va en PLAN.md.
No conviertas una preferencia del agente en una restricción. -->

- Respetar el anexo §15.5, que no se modifica.
- Guardar `PHONE_SUBMITTED` antes de publicar y dentro del candado por usuario (D2 y D5 de T08).
- La suite normal de tests no se conecta al Kafka ni a la API de la cátedra. Antes de cualquier conexión al Kafka real se confirma con el usuario qué se hará, y la corre el usuario (decisiones 010 y 014).
- Las decisiones de Kafka se suman a la decisión 014. Todo lo de Kafka vive en `catedra/infrastructure/kafka` (014 §3.7).
- Aplicar la skill `hexagonal-vertical-slicing` con las excepciones autorizadas. Lo existente se amplía en lugar de duplicarse.
- No cambiar el comportamiento de T01 a T05, T07, T08, X01 ni la validación del JWT.

## Criterios de aceptación

<!-- Resultados observables que permitan decidir si se cumple cada requisito.
Incluye los casos alternativos acordados: cada flujo alternativo cubierto debería
tener un criterio. No uses Funciona correctamente.
Repite el formato según sea necesario. -->

- **CA-01 · RF-06, RF-07, RF-08:** Dado un proceso propio en `WAITING_FOR_PHONE`, con `requestEventId` y sin vencer, cuando el usuario envía `+54 9 261 555-1234`, entonces responde `200` con el proceso en `PHONE_SUBMITTED`, sin el teléfono ni la marca; la base tiene `+5492615551234` y el motivo de rechazo vacío; el topic de teléfono tiene un solo mensaje, con clave `reservationProcessId` y todos los campos del anexo §15.5 con los valores del contrato §4.3.3.
- **CA-02 · RF-01:** Sin JWT, o con uno inválido o vencido, responde `401`. Un proceso inexistente y uno ajeno responden el mismo `404` `RESERVATION_PROCESS_NOT_FOUND`. En ningún caso cambia un proceso ni se publica nada.
- **CA-03 · RF-02:** Dado un proceso en cualquier estado distinto de `WAITING_FOR_PHONE`, cuando se envía un teléfono válido, entonces responde `409` `INVALID_PROCESS_STATE` con `currentStatus`, sin cambios y sin publicar. Un segundo envío después de un `200` es uno de estos casos.
- **CA-04 · RF-03:** Dado un proceso con `expiresAt` más el margen ya vencido, entonces responde `409` `HOLD_EXPIRED`, queda `EXPIRED` y no se publica nada. Vencido pero dentro del margen, se procesa normalmente.
- **CA-05 · RF-04:** Dado un proceso en `WAITING_FOR_PHONE` sin `requestEventId`, entonces responde `409` `PHONE_REQUEST_NOT_READY`, sin cambios y sin publicar.
- **CA-06 · RF-05, D7:** Sin `phoneNumber` responde `400` `VALIDATION_ERROR` con `fieldErrors`. Con `""`, solo espacios, 6 dígitos, 16 dígitos, letras, un `+` que no está al principio o un tabulador, responde `400` `INVALID_PHONE_NUMBER`. Los dos casos responden lo mismo aunque el proceso no exista, sin consultar la base. Se aceptan 7 y 15 dígitos, con y sin `+`, y con espacios, guiones, paréntesis y puntos.
- **CA-07 · RF-16, D5:** Sin sesión técnica y sin poder obtener una, o con un error de configuración, responde `503` `CENTRAL_UNAVAILABLE`; el proceso no cambia ni se marca, no se publica nada y el log tiene el motivo.
- **CA-08 · RF-09, D2:** Dado Kafka caído y un proceso con motivo de rechazo, cuando se envía un teléfono, entonces hay tantos intentos como el máximo configurado, con esperas crecientes; responde `503` `CENTRAL_UNAVAILABLE`, y el proceso queda en `WAITING_FOR_PHONE`, marcado, sin motivo y con el teléfono del intento fallido. Cuando Kafka vuelve, un nuevo envío responde `200` y su mensaje lleva otro `eventId` y el mismo `requestEventId`.
- **CA-09 · RF-09:** Los reintentos de una misma publicación llevan el mismo `eventId` y el mismo contenido.
- **CA-10 · RF-10, D6:** Si Kafka rechaza las credenciales, no se reintenta ni se hace un login técnico. El resultado es el de CA-08, con el motivo en el log.
- **CA-11 · RF-06, D3:** Si falla el guardado inicial, responde `500` `INTERNAL_ERROR`, el proceso queda como estaba y no se publica nada.
- **CA-12 · RF-14, D3:** Si Kafka falla y también falla la vuelta atrás, responde `500` `LOCAL_STATE_OUT_OF_SYNC`, el log tiene un error grave y el proceso queda en `PHONE_SUBMITTED`.
- **CA-13 · RF-11, D1:** Dado un proceso guardado en `PHONE_SUBMITTED` cuya publicación no ocurrió, cuando la aplicación se reinicia, entonces sigue en `PHONE_SUBMITTED`, sin marca y sin nada publicado, y un nuevo envío responde `409` `INVALID_PROCESS_STATE`.
- **CA-14 · RF-12, D4:** Con dos envíos simultáneos del mismo usuario, uno responde `200`, el otro `409` `INVALID_PROCESS_STATE` y se publica un solo mensaje. Un envío de otro usuario no espera.
- **CA-15 · RF-12, D4:** Dado un envío en curso con la publicación demorada, cuando llega un resultado de ese proceso, entonces se procesa después de que T06 termina, y ninguna escritura pisa a la otra.
- **CA-16 · RF-15, D4:** Con Kafka caído, la respuesta llega dentro de los 10 s desde que empieza a publicarse, y ese tope se puede cambiar por configuración.
- **CA-17 · RF-13:** En todos los escenarios, el teléfono no aparece en el log ni en las respuestas o errores, y el log no contiene el JWT técnico ni credenciales.
- **CA-18 · RF-01 a RF-08, T07, T08:** Con la aplicación completa, base real, Kafka en contenedor y la cátedra REST simulada: un usuario bloquea y confirma, llega el pedido de teléfono, informa el teléfono y llega un `AdditionalInformationRejected`. El proceso vuelve a `WAITING_FOR_PHONE` con el motivo; un segundo envío lo borra y publica otro `eventId` con el mismo `requestEventId`; llega un `AppointmentConfirmed` y queda `CONFIRMED`. Reemplaza el `PHONE_SUBMITTED` que el recorrido de T08 escribía en la base.
- **CA-19 · Restricciones:** La suite existente sigue pasando sin cambiar el comportamiento de T01 a T05, T07, T08, X01 ni la validación del JWT. Solo cambian los tests que escribían `PHONE_SUBMITTED` para el recorrido completo.
- **CA-20 · D8 (bloqueado por la cátedra):** Con la cátedra real publicando, el E2E bloquea, confirma, recibe el pedido, informa el teléfono y recibe `200`. Hoy no se puede comprobar, porque la cátedra no publica el pedido: queda no verificado.

## Cómo se comprueba el comportamiento

<!-- Una fila por criterio: escenario y resultado que debemos comprobar.
La selección de tests, herramientas, comandos y evidencias se desarrolla en PLAN.md.
No marques los criterios como superados durante la especificación. -->

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | Envío válido; se lee el topic de teléfono | Respuesta, base y mensaje como indica el criterio |
| CA-02 | Sin token, token inválido o vencido, proceso inexistente y proceso ajeno | `401`; mismo `404`; nada cambia ni se publica |
| CA-03 | Un envío por cada estado distinto de `WAITING_FOR_PHONE`; envío repetido | `409` con `currentStatus` |
| CA-04 | Vencido más allá del margen y vencido dentro del margen | `EXPIRED` y `409`; procesado |
| CA-05 | Sin `requestEventId` | `409` `PHONE_REQUEST_NOT_READY` |
| CA-06 | Cada formato inválido, sobre un proceso existente e inexistente, y cada formato válido | `400` iguales, sin consultar la base; válidos aceptados |
| CA-07 | Sesión degradada con login fallido; error de configuración | `503`; nada cambia; motivo en el log |
| CA-08 | Kafka pausado y proceso con motivo; Kafka de vuelta | Intentos registrados, `503` y estado indicado; el nuevo envío publica |
| CA-09 | Publicación que falla y después funciona | Mismo `eventId` y contenido |
| CA-10 | Rechazo de credenciales | Un solo intento, sin login, resultado de CA-08 |
| CA-11 | Falla al guardar | `500` `INTERNAL_ERROR`; nada publicado |
| CA-12 | Kafka caído y falla al volver atrás | `500` `LOCAL_STATE_OUT_OF_SYNC`; error grave |
| CA-13 | Proceso en `PHONE_SUBMITTED` sin publicar; reinicio; nuevo envío | Sin cambios; `409` |
| CA-14 | Envíos simultáneos del mismo usuario y de otro | Uno `200`, otro `409`, un mensaje; el otro usuario no espera |
| CA-15 | Publicación demorada y resultado durante la espera | Orden correcto; nada pisado |
| CA-16 | Kafka caído; tiempo medido; tope cambiado por configuración | Dentro del tope |
| CA-17 | Salida capturada de los escenarios | Sin teléfono, tokens ni credenciales |
| CA-18 | Recorrido completo con la aplicación levantada | Rechazo, nuevo envío y confirmación en la base y en el topic |
| CA-19 | Suite existente | Sin cambios de comportamiento |
| CA-20 | E2E contra la cátedra real, corrido por el usuario | Bloqueado mientras la cátedra no publique |

**Sin verificar contra la cátedra real (D8):** que el topic de teléfono exista, que acepte lo que publica turnos y que la cátedra lo consuma. Los mensajes se comprueban contra el anexo §15.5 en un Kafka en contenedor.

## Decisiones pendientes

<!-- Al resolverlas, actualiza las secciones afectadas. Escribe Ninguna cuando
no queden pendientes funcionales ni restricciones por decidir. -->

Ninguna.

Decisiones tomadas:

- **D0 · Guardar antes de publicar (decidida en D2 y D5 de T08; cambio de documentos aprobado por el usuario el 2026-11-02):** T06 guarda `PHONE_SUBMITTED` antes de publicar. T06 (pasos 5 y 6, alternativo 6.1 y postcondición), la secuencia §4, el contrato §3.6 y §4.3.3 y el dominio (reglas 6 y 11, tabla de estados y §2) dicen hoy lo contrario. También el dominio §2 (transición nueva, D2) y §4 (`phoneNumber`). **Actualizados el 2026-11-02** en `documentacion` y en la copia de turnos. T06 renumera sus alternativos: 5.1 y 5.2 pasan a 6.1 y 6.2; 5.1 y 6.3 son nuevos, y 6.4 es la caída. Las SPEC aprobadas de T07 y T08 conservan la numeración anterior, que describen con palabras.
- **D1 · Caída entre guardar y publicar (RF-11, opción a, decidida por el usuario el 2026-11-02):** T06 no agrega nada para este caso. Motivo: es una falla poco común y su consecuencia no justifica otra escritura en cada envío. Limitación aceptada: el usuario ve "Confirmando teléfono", no puede reenviar y pierde el turno cuando T13 lo cierra como `EXPIRED`; hasta que exista T13 (M6), ese proceso queda abierto y el usuario recibe `OPEN_PROCESS_EXISTS` al bloquear otro turno. T13 paso 5 y el contrato §4.2.6 no cambian. Alternativas descartadas: (b) encender la marca al guardar y devolverla tras el acuse de Kafka, para que T13 devuelva el proceso a `WAITING_FOR_PHONE`; (c) guardar el mensaje pendiente y reenviarlo al arrancar. Texto en la decisión 014 §3.15.
- **D2 · Falla al publicar después de guardar (RF-09, opción b, decidida por el usuario el 2026-11-02):** el proceso vuelve a `WAITING_FOR_PHONE` y queda marcado (T06 5.1), sin restaurar nada. Motivo: el usuario puede reintentar enseguida y lo que se pierde no es grave. Limitación aceptada: si había un motivo de rechazo, el usuario ya no lo ve al reintentar, y `phoneNumber` puede ser uno que nunca salió. Es una transición nueva del dominio §2 (`PHONE_SUBMITTED` → `WAITING_FOR_PHONE` por T06). Si un timeout escondía un mensaje que sí llegó, una confirmación se aplica y un rechazo se ignora (D2 de T08). Alternativas descartadas: (a) restaurar el motivo y el teléfono anteriores; (c) quedarse en `PHONE_SUBMITTED` marcado y aceptar un reenvío. Texto en la decisión 014 §3.15.
- **D3 · Fallas de la base (RF-06, RF-14, opción a, decidida por el usuario el 2026-11-02):** si falla el guardado inicial, `500` `INTERNAL_ERROR` (regla general del contrato §3); si Kafka falló y tampoco se puede volver atrás, `500` `LOCAL_STATE_OUT_OF_SYNC`, el código existente con otro significado, y un error grave en el log. Motivo: distingue "no pasó nada" de "el estado local quedó mal". Como no se escribe nada después de publicar, el caso original de T06 6.1 ya no existe. Alternativa descartada: (b) `INTERNAL_ERROR` en los dos. Texto en la decisión 014 §3.15.
- **D4 · Alcance del candado y tope de tiempo (RF-12, RF-15, opción a, decidida por el usuario el 2026-11-02):** todo T06 va dentro del candado, también la publicación y la vuelta atrás, con un tope de 10 s configurable. Motivo: el mismo criterio de T05, y la vuelta atrás no necesita comprobar nada más. Limitación aceptada: durante esa espera esperan las operaciones del mismo usuario y el consumidor de Kafka si llega un evento suyo; es raro, porque con Kafka caído tampoco hay eventos. Alternativa descartada: (b) publicar fuera del candado, que obliga a volver a leer y comprobar el estado antes de la vuelta atrás. Texto en la decisión 013.
- **D5 · Sin sesión técnica o con error de configuración (RF-16, opción b, decidida por el usuario el 2026-11-02):** siempre `503` `CENTRAL_UNAVAILABLE`, comprobado antes de guardar. Motivo: la app trata igual todo `503` (A08 5.8), y es una rama menos. Limitación aceptada: T06 es la única operación que no informa `SERVICE_DEGRADED` ante un error de configuración; el motivo queda solo en el log. Documentos cambiados con aprobación: T06 (alternativo 4.2), secuencia §4 y contrato §3.6. Alternativa descartada: (a) separar los dos casos. Texto en la decisión 014 §3.16.
- **D6 · Credenciales rechazadas al publicar (RF-10, opción b, decidida por el usuario el 2026-11-02):** se trata como Kafka no disponible, sin reintentar y sin login técnico. Motivo: hoy no puede ocurrir (decisión 014 §3.1), y ese código no se ejecutaría nunca. Limitación aceptada: si la cátedra empezara a pedir credenciales o las cambiara, T06 fallaría hasta reiniciar o hasta que otro login renueve la sesión. Documentos cambiados con aprobación: T06 6.2, secuencia §4 y contrato §3.6, §4.2.2 y §4.3.3. Alternativa descartada: (a) login técnico y un reintento. Texto en la decisión 014 §3.16.
- **D8 · Prueba contra la cátedra real (opción a, decidida por el usuario el 2026-11-02):** en la suite normal, un test lee del topic de teléfono de un Kafka en contenedor lo que T06 publicó y lo compara con el anexo §15.5. El E2E suma el paso de informar el teléfono y queda bloqueado mientras la cátedra no publique (CA-20), como la TASK-12 de T07. Limitación aceptada: el productor no se prueba contra el Kafka real. Se puede sumar después como una tarea, sin cambiar el comportamiento de T06. Alternativa descartada: (b) un programa de una sola vez que publica en el topic real con un `requestEventId` inventado, porque no se sabe qué hace la cátedra con él. Texto en la decisión 014 §3.17.
- **D7 · Validar el teléfono antes de consultar la base (RF-05, CA-06):** pedido del usuario y aprobado el 2026-11-02: todo el formato se valida apenas validado el JWT. Documentos cambiados con aprobación: T06 (paso 4 y alternativo 4.1, sin renumerar) y secuencia §4. El contrato no fija ese orden y no cambia. Motivo: el formato no necesita la base, y así un dato inválido no gasta una lectura ni espera el candado, como pide la guía de backend. Costo aceptado: un teléfono inválido sobre un proceso vencido responde `400`, y el `409` llega recién cuando el formato es válido. Alternativas descartadas: (a) `""` como formato inválido después de leer el proceso; (b) `""` como `VALIDATION_ERROR` al entrar, que solo adelanta ese caso.
