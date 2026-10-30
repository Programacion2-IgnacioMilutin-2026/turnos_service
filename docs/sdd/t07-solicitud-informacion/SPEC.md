# SPEC: T07 — Procesar solicitud de información adicional

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
| Caso de uso T07 (`docs/casos_de_uso/turnos_y_reservas_usecases.md`) | Precondición, pasos 1 a 7, alternativos 2.1, 3.1, 4.1, 4.2, 5.1 y 6.1, y postcondición: completo |
| Caso de uso T05 (mismo archivo) | Alternativos 4.9 y 6.1 ("si el pedido de teléfono llega por Kafka, el proceso avanza solo"), como antecedente |
| Diagrama de secuencia (`docs/secuencias/turnos_y_reservas_secuencia.md`) | §3, bloque "T07 — Process additional information request" |
| Contrato de turnos (`docs/contratos/turnos_y_reservas_contrato.md`) | §1 (T07 no expone endpoint), §2 (el proceso nunca expone `requestEventId`), §4.2.2 (campos de `integration` que usa turnos), §4.3 (tabla de eventos), §4.3.1 (reglas comunes) y §4.3.2 (`AdditionalInformationRequested`) completos. §4.3.4 solo como contexto: los eventos de resultado viajan por el mismo topic (D3) |
| Contrato común (`docs/contratos/contratos_comunes.md`) | §3 (instantes en UTC) |
| Modelo de dominio de turnos (`docs/turnos_y_reservas_dominio.md`) | `ReservationProcess` (`requestEventId`, `expiresAt`, `needsReconciliation`), `ProcessedEvent` (§3), §2 (transición `HELD → WAITING_FOR_PHONE` por T07); reglas 4, 6, 9, 10, 14, 15, 16 y 17 |
| X01, su SPEC (`docs/sdd/x01-cuenta-tecnica/`) y decisiones 005, 009 y 013 | De dónde salen los datos de Kafka; la cátedra solo se usa a través de `catedra`; operaciones de un usuario de a una |
| SPEC de T05 (`docs/sdd/t05-confirmar-reserva/`) | D5 (operaciones de un mismo usuario de a una), como antecedente de D2 |
| Anexo de la cátedra (`enunciado/INTEGRATION_REFERENCE-v2.md`) | §2 y §2.1 (la cuenta técnica da acceso a Kafka; `groupId` aísla topics y consumer group), §4 (entrega al menos una vez, campos desconocidos), §5.2 (`integration`), §15.1, §15.2, §15.4, §18.1 (mensaje duplicado) y §18.4 (reintentos) |
| Enunciado (`enunciado/PROJECT_STATEMENT-v1.md`) | §7 (pasos 3 y 4 del proceso de reserva; REST y Kafka pueden llegar en distinto orden y deben converger) y §8 (mensajes duplicados, reinicio con operaciones pendientes) |

## Qué construimos y para quién

<!-- Qué necesidad cubre, qué actor la inicia (la app KMP, otro servicio, el
tiempo o la cátedra) y qué podrá hacer o recibir. Describe el objetivo en
lenguaje de negocio. Resume y referencia: no copies la descripción del caso de uso. -->

Cuando la cátedra acepta la confirmación inicial de un hold (T05), publica por Kafka un pedido del teléfono del paciente. Turnos empieza a escuchar ese canal: asocia el pedido con el proceso de reserva del usuario y guarda lo que T06 necesita para responderlo (el identificador del pedido) junto con el vencimiento vigente. No interactúa con el usuario. Es la primera funcionalidad del proyecto que usa Kafka.

## Situación actual

<!-- Qué existe hoy y es relevante (código, datos, otras funcionalidades ya
implementadas), la limitación que queremos resolver y los comportamientos
existentes que deben conservarse. Si todavía no hay código para esto, indícalo.
No describas la arquitectura. -->

- Están implementados T01 a T05, X01 y la validación del JWT. No hay nada de Kafka: ni dependencia, ni conexión, ni consumidor.
- El login técnico (X01) ya lee y guarda en memoria los datos de Kafka de `integration`: `groupId`, servidores, consumer group y los topics de acciones y de teléfono. Nadie los usa todavía. `integration` no trae credenciales propias de Kafka (a diferencia de Redis, que trae usuario y contraseña): ver D1.
- El proceso de reserva no guarda el identificador del pedido de teléfono, y no existe registro de eventos procesados.
- T05 deja el proceso en `WAITING_FOR_PHONE`, o en `HELD` marcado para reconciliar cuando no se supo si la cátedra confirmó (T05 4.9 y 6.1). En este segundo caso, el pedido de T07 es la prueba de que la cátedra sí confirmó.
- El pedido puede llegar antes de que T05 termine de guardar `WAITING_FOR_PHONE`. Hoy cada escritura del proceso reescribe la fila completa: sin una protección, T05 podría pisar lo que T07 guardó (D2).
- Cada corrida del E2E y de la prueba manual de T05 provocó un pedido de teléfono real que nadie leyó: esos mensajes están en el topic y corresponden a procesos que la base local no conoce (D6).
- Debe conservarse el comportamiento de T01 a T05, X01 y la validación del JWT. Si Kafka no está disponible, los endpoints REST siguen funcionando igual.

## Dentro del alcance

<!-- Requisitos concretos, con identificadores estables para vincularlos a
criterios, decisiones del plan y tareas. Cada requisito indica el paso o flujo
alternativo del que proviene. No agregues requisitos que los documentos de
referencia no tengan sin consultarlo. -->

**Conexión y recepción**

- **RF-01** · T07 precondición y paso 1, contrato §4.2.2 y §4.3, anexo §15.2: turnos consume el topic de acciones con el consumer group y los servidores que informa `integration` en el login técnico; ninguno de esos valores se fija en el código ni en la configuración. El consumer group se puede reemplazar por configuración externa; sin ella, se usa el de `integration` (D8). Si el servidor exige credenciales, se usan las de la cuenta técnica; el protocolo y el mecanismo son configurables (D1). Se empieza a consumir apenas la integración queda habilitada, al arrancar o con un login posterior; la conexión conserva los datos del primer login exitoso hasta reiniciar, y si un login posterior trae otros, se registra un warning (D7).
- **RF-02** · Contrato §4.3.1: los mensajes se distinguen por `eventType`. `AdditionalInformationRequested` se procesa según esta spec. Un `eventType` ausente o desconocido se descarta, se registra y se confirma el offset. Mientras T08 no exista, los cinco eventos de resultado se tratan igual: se descartan, se registran con su `eventType` y se confirma el offset, sin registrarlos como procesados (D3).
- **RF-03** · T07 paso 2 y alternativo 2.1, contrato §4.3.1 y §4.3.2, anexo §4: un pedido se valida antes de buscar el proceso: presentes `eventId`, `reservationProcessId`, `holdId`, `requiredField`, `expiresAt` y `schemaVersion` (`message` y `occurredAt` no son obligatorios, D4), `schemaVersion` igual a `1` y `requiredField` igual a `PHONE_NUMBER`. Los campos desconocidos se ignoran. Un mensaje inválido (incluido uno que no es JSON o con un instante mal escrito) se descarta sin efectos, se registra con su motivo y se confirma el offset.

**Correlación**

- **RF-04** · T07 paso 3 y alternativo 3.1, dominio regla 17, anexo §18.1: un `eventId` ya procesado no repite ningún efecto y se confirma el offset.
- **RF-05** · T07 paso 4 y alternativo 4.1: si no existe un proceso local con ese `reservationProcessId`, se registra como evento huérfano y se confirma el offset.
- **RF-06** · T07 paso 4 y alternativo 4.2: si el `holdId` no coincide con el del proceso, se registra como inconsistencia, se confirma el offset y el proceso no cambia.
- **RF-07** · T07 paso 5 y alternativo 5.1: si el proceso no está en `HELD` ni en `WAITING_FOR_PHONE`, se registra (el estado ya avanzó), se confirma el offset y el proceso no cambia.

**Efecto**

- **RF-08** · T07 pasos 6 y 7, contrato §4.3.2, dominio §2 y reglas 10 y 17: el proceso guarda el `eventId` del pedido como `requestEventId` y el `expiresAt` recibido; si estaba en `HELD`, pasa a `WAITING_FOR_PHONE`. En la misma operación atómica se registra el `eventId` como procesado, con el instante en que se terminó de procesar. Recién después se confirma el offset. La marca de reconciliación no se toca (regla 9).
- **RF-09** · T07 alternativo 6.1, contrato §4.3.1, anexo §18.4: si falla la persistencia, se reintenta con espera creciente y un máximo de intentos, cada uno registrado; agotados, se registra el error y no se confirma el offset, para que el mensaje se vuelva a procesar. Tras una pausa configurable se vuelve a intentar el mismo mensaje, y los que vienen detrás en su partición esperan hasta que se guarde (D5).
- **RF-14** · Guía de backend (arranque), anexo §4: si el consumer group todavía no tiene una posición confirmada, se empieza a leer desde el mensaje más antiguo que Kafka conserve (D6).
- **RF-10** · Contrato §2: `requestEventId` nunca se expone a la app; la respuesta del proceso (T04, T05) no cambia.

**Transversales**

- **RF-11** · Enunciado §7, guía de backend (concurrencia), D2: un pedido que llega mientras una operación del mismo usuario (bloquear o confirmar) está en curso se procesa cuando esa operación termina, y el resultado conserva lo de las dos: un proceso confirmado por T05 y alcanzado por T07 queda en `WAITING_FOR_PHONE` con su `requestEventId`. Las operaciones de usuarios distintos no se esperan.
- **RF-12** · Guía de backend (observabilidad y privacidad): cada descarte, evento huérfano, inconsistencia y estado ya avanzado queda registrado con el `eventId`, el `reservationProcessId` (si lo trae) y el motivo. Ni el JWT técnico ni las credenciales de Kafka aparecen en los logs.
- **RF-13** · Guía de backend (dependencias externas y arranque), enunciado §8: si Kafka no está disponible o la integración no está habilitada, turnos sigue atendiendo los endpoints REST; los pedidos esperan en Kafka y se procesan cuando se puede consumir, sin perder ni duplicar efectos.

## Fuera de alcance

<!-- Exclusiones acordadas, no deducidas por el agente. Incluye las partes de los
casos de uso que se dejan para otra funcionalidad. Si no hay exclusiones
adicionales, indícalo tras revisarlo con la persona. -->

Confirmadas por el usuario (2026-10-29):

- Los efectos de los cinco eventos de resultado (T08) y la publicación del teléfono (T06), incluido el caso 5.2 de T06 (Kafka rechaza las credenciales al publicar).
- Exponer el estado del proceso a la app (T09) y la reconciliación (T13), que es la que limpia la marca.
- El topic de catálogo (`kafkaCatalogTopic`): lo usa `catalog_service`.
- Borrar eventos procesados viejos: el dominio no define una limpieza, así que se conservan.

## Flujo de la operación

<!-- Cómo se inicia (request, evento o tarea programada), qué verifica y qué
resultado produce, incluidas las alternativas relevantes. Si el caso de uso ya
lo describe, referencia sus pasos en lugar de repetirlos y describe solo lo que
cubre esta funcionalidad. -->

1. Con la integración habilitada (X01), turnos consume el topic de acciones (RF-01).
2. Cada mensaje sigue los pasos 1 a 7 de T07 y el diagrama de secuencia §3 (bloque T07), con sus alternativos.
3. Lo que agrega esta spec: cómo se tratan los eventos de T08 mientras T08 no existe (RF-02, D3), qué campos son requeridos (RF-03, D4), qué pasa con los mensajes siguientes cuando la base falla (RF-09, D5), desde dónde se lee la primera vez (RF-14, D6), cuándo se conecta y con qué datos (RF-01, D7) y el orden con las operaciones del usuario (RF-11, D2).

## Datos y reglas de negocio

<!-- Información que necesita la operación, campos obligatorios, validaciones,
límites y reglas como duplicados u orden de presentación. Describe significado
y comportamiento, sin diseñar tablas, DTO, DAO ni almacenamiento. -->

- `eventId`, `reservationProcessId`, `holdId` y `requestEventId` son textos opacos que asigna la cátedra (dominio regla 4); no se asume su formato.
- `expiresAt` es un instante en UTC; guardarlo no extiende el vencimiento en la cátedra.
- `message` no se usa (contrato §4.3.2).
- El registro de eventos procesados guarda el `eventId` y el instante en que se terminó de procesar (dominio §3); es la clave de idempotencia.
- Un pedido no reabre ni hace retroceder un proceso en otro estado (dominio regla 6).

## Casos alternativos y de error

<!-- Adapta la tabla usando BACKEND_GUIDELINES.md. Cuando el caso de uso ya define
el flujo alternativo, referencia su ID en lugar de repetirlo. Añade escenarios
relevantes. Marca No aplica con su motivo cuando corresponda. Expresa resultados
observables (estado HTTP, código de error, estado final del recurso, evento
publicado), no mecanismos técnicos. -->

| Situación | Comportamiento esperado |
| --- | --- |
| Entrada inválida | T07 2.1: mensaje descartado, registrado y offset confirmado (RF-03). `eventType` desconocido: igual (RF-02) |
| Sin sesión o sin permiso | No aplica a la app: no hay endpoint. Sin integración habilitada no se consume; se empieza con el próximo login exitoso (RF-01, RF-13, D7). Ante Kafka, credenciales de la cuenta técnica si las exige (D1) |
| Recurso inexistente o ajeno | T07 4.1 (huérfano) y 4.2 (`holdId` distinto): registrado, offset confirmado, sin cambios (RF-05, RF-06) |
| Dependencia no disponible (Kafka) | Los endpoints REST siguen funcionando; los pedidos esperan en Kafka (RF-13) |
| Timeout o espera excesiva | Falla de la base al guardar: reintentos y, agotados, sin confirmar el offset (RF-09, D5) |
| Operación o mensaje repetido | T07 3.1: sin efectos, offset confirmado (RF-04) |
| Evento tardío o fuera de orden | T07 5.1: proceso en otro estado, sin cambios (RF-07). Pedido que llega antes de que T05 guarde: se procesa después de T05 (RF-11, D2) |
| Falla de persistencia | T07 6.1 (RF-09, D5) |
| Solicitudes simultáneas | Con una operación del mismo usuario: de a una (RF-11, D2) |
| Reinicio con operaciones pendientes | Un mensaje cuyo offset no se confirmó se vuelve a entregar; si su efecto ya se había guardado, RF-04 evita repetirlo |
| Mensajes anteriores a la primera conexión | Se leen desde el más antiguo que Kafka conserve (RF-14, D6); los de procesos que la base no conoce son huérfanos (RF-05) |

**Puntos de la guía no aplicables y motivo:** entrada HTTP, autorización del usuario y paginación: T07 no tiene endpoint. Tareas programadas: T07 reacciona a mensajes; el barrido periódico es T12.

## Restricciones del pedido

<!-- Condiciones ya impuestas: compatibilidad, límites de alcance, requisitos
de seguridad o rendimiento medibles, o una tecnología expresamente exigida.
Ejemplo: Respetar el contrato de Documentacion y el anexo de la cátedra puede ser
una restricción; el diseño de los adaptadores va en PLAN.md.
No conviertas una preferencia del agente en una restricción. -->

- Respetar el contrato de turnos §2 y §4.3 (§4.3.1 y §4.3.2) y el anexo §15.
- Los datos de conexión de Kafka salen del login técnico, nunca de valores fijos; las credenciales, si hacen falta, van por configuración externa y no aparecen en logs ni en el repositorio (D1).
- La suite normal de tests no se conecta al Kafka ni a la API de la cátedra: los simula. Antes de cualquier conexión al Kafka real se confirma con el usuario qué se hará, y la corre el usuario (decisión 010).
- Ninguna prueba afecta lo de otros alumnos ni rompe el servicio de turnos real ni la cátedra; las pruebas no usan el consumer group del servicio (D8).
- Aplicar la skill `hexagonal-vertical-slicing` con las excepciones autorizadas. Lo existente se amplía en lugar de duplicarse; la cátedra (también por Kafka) se usa a través de `catedra` (decisión 009), y las operaciones de un usuario usan el candado compartido (decisión 013).
- No cambiar el comportamiento de T01 a T05, X01 ni la validación del JWT.

## Criterios de aceptación

<!-- Resultados observables que permitan decidir si se cumple cada requisito.
Incluye los casos alternativos acordados: cada flujo alternativo cubierto debería
tener un criterio. No uses Funciona correctamente.
Repite el formato según sea necesario. -->

- **CA-01 · RF-01, RF-08:** Dado un proceso en `WAITING_FOR_PHONE`, cuando llega un `AdditionalInformationRequested` válido por el topic de acciones de `integration`, entonces el proceso guarda el `eventId` como `requestEventId` y el `expiresAt` recibido, sigue en `WAITING_FOR_PHONE`, el `eventId` queda registrado como procesado y el offset se confirma.
- **CA-02 · RF-08:** Dado un proceso en `HELD` (también uno marcado para reconciliar), cuando llega un pedido válido, entonces pasa a `WAITING_FOR_PHONE` con el `requestEventId` y el `expiresAt` recibidos, y la marca no cambia.
- **CA-03 · RF-03:** Cuando llega un pedido sin uno de los campos requeridos, con `schemaVersion` distinta de `1`, con `requiredField` distinto de `PHONE_NUMBER`, que no es JSON o con un instante mal escrito, entonces ningún proceso cambia, no se registra el `eventId`, queda en el log el motivo y el offset se confirma. Un pedido válido con campos desconocidos, o sin `message` ni `occurredAt`, se procesa normalmente.
- **CA-04 · RF-02:** Cuando llega un mensaje sin `eventType` o con uno desconocido, entonces se descarta, se registra y se confirma el offset. Cuando llega cualquiera de los cinco eventos de resultado, ocurre lo mismo: ningún proceso cambia, el log lo registra con su `eventType` y su `eventId` no queda registrado como procesado.
- **CA-05 · RF-04:** Cuando el mismo pedido llega dos veces, entonces el efecto se aplica una sola vez y las dos entregas confirman su offset. Si entre las dos entregas el proceso cambió (por ejemplo, pasó a `PHONE_SUBMITTED`), la segunda no lo modifica.
- **CA-06 · RF-05, RF-06, RF-07:** Cuando llega un pedido de un `reservationProcessId` inexistente, con un `holdId` distinto del guardado o para un proceso en `PHONE_SUBMITTED` o `EXPIRED`, entonces ningún proceso cambia, queda en el log como huérfano, inconsistencia o estado ya avanzado, y el offset se confirma.
- **CA-07 · RF-09:** Dado que la base falla al guardar el efecto en todos los intentos, entonces hay tantos intentos como el máximo configurado, con esperas crecientes y registrados; el offset no se confirma y, cuando la base vuelve, el mensaje se procesa y el proceso queda como en CA-01. Mientras tanto, un pedido publicado después en la misma partición no se procesa antes que él, y se procesa enseguida después. Si la aplicación se reinicia con el mensaje sin confirmar, se vuelve a entregar y su efecto se aplica una sola vez.
- **CA-15 · RF-14, RF-05:** Dado un consumer group sin posición confirmada y un topic con un pedido de un proceso inexistente y otro de un proceso local, cuando turnos empieza a consumir, entonces lee los dos: el primero queda como huérfano y el segundo se aplica.
- **CA-16 · RF-01, RF-13:** Dado que el login del arranque falló y la integración quedó degradada, entonces no se consume; cuando un login posterior sale bien, se empieza a consumir y los pedidos publicados mientras tanto se procesan. Con la integración en error de configuración, nunca se consume.
- **CA-17 · RF-01:** Dado que ya se consume, cuando un login posterior trae otros datos de Kafka, entonces el log registra un warning y el consumo sigue con los datos originales.
- **CA-08 · RF-08:** Si falla registrar el `eventId` como procesado, el cambio del proceso tampoco queda guardado, y al revés.
- **CA-09 · RF-11:** Dado un proceso en `HELD` cuya confirmación (T05) espera la respuesta de la cátedra simulada, cuando llega su pedido de teléfono, entonces el pedido se procesa después de que T05 guarda, y el proceso termina en `WAITING_FOR_PHONE` con el `requestEventId`. Un pedido de otro usuario no espera.
- **CA-10 · RF-10:** Las respuestas de T04 y T05 no incluyen `requestEventId`.
- **CA-11 · RF-12:** En todos los escenarios, el log identifica el `eventId` y el motivo, y no contiene el JWT técnico ni credenciales de Kafka.
- **CA-12 · RF-13:** Con Kafka no disponible al arrancar o durante la ejecución, los endpoints REST responden igual que antes; cuando Kafka vuelve, los pedidos pendientes se procesan.
- **CA-13 · RF-01 a RF-08:** Con la aplicación completa, base real, Kafka simulado y la cátedra REST simulada, un usuario bloquea y confirma un turno, llega el pedido de teléfono y el proceso queda con su `requestEventId` en la base.
- **CA-14 · RF-01, RF-03, RF-08:** Con la cátedra y su Kafka reales y catálogo simulado (suite E2E), un usuario registrado bloquea un turno libre y lo confirma; entonces, en un tiempo acotado, el proceso queda en `WAITING_FOR_PHONE` con el `requestEventId` del pedido real en la base del test. Con (a), lo consume el servicio con el consumer group de tests; con el plan B (a'), el test lee el pedido sin consumer group y se lo entrega al procesamiento de turnos. Ninguna variante usa el consumer group del servicio. Sin credenciales, el test se saltea.

## Cómo se comprueba el comportamiento

<!-- Una fila por criterio: escenario y resultado que debemos comprobar.
La selección de tests, herramientas, comandos y evidencias se desarrolla en PLAN.md.
No marques los criterios como superados durante la especificación. -->

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | Proceso `WAITING_FOR_PHONE`; pedido válido publicado en el Kafka simulado | `requestEventId` y `expiresAt` guardados; `eventId` registrado; offset confirmado |
| CA-02 | Proceso `HELD`, con y sin marca; pedido válido | `WAITING_FOR_PHONE`; marca igual |
| CA-03 | Un mensaje por cada defecto (sin cada uno de los seis campos obligatorios, otra versión, otro `requiredField`, no JSON, instante mal escrito); uno válido con campos extra y otro sin `message` ni `occurredAt` | Sin cambios y offset confirmado; los válidos se procesan |
| CA-04 | Mensaje sin `eventType`, con uno inventado y uno de cada evento de resultado | Descartado, registrado y offset confirmado; ningún `eventId` registrado como procesado |
| CA-05 | El mismo pedido dos veces; con un cambio del proceso entre las dos | Un solo efecto |
| CA-06 | Proceso inexistente, `holdId` distinto, proceso `PHONE_SUBMITTED` y `EXPIRED` | Sin cambios; log; offset confirmado |
| CA-07 | Base que falla en todos los intentos y después se recupera, con otro pedido detrás en la misma partición; reinicio con el mensaje sin confirmar | Intentos registrados; offset sin confirmar; procesados en orden al recuperarse; un solo efecto tras el reinicio |
| CA-08 | Falla al registrar el evento procesado; falla al guardar el proceso | Ninguno de los dos queda guardado |
| CA-09 | T05 demorado por la cátedra simulada y el pedido publicado durante la espera | `WAITING_FOR_PHONE` con `requestEventId` |
| CA-10 | Respuestas de T04 y T05 | Sin `requestEventId` |
| CA-11 | Salida capturada de los escenarios | `eventId` y motivo; sin tokens ni credenciales |
| CA-12 | Kafka apagado al arrancar y durante la ejecución | REST igual; pedidos procesados al volver |
| CA-13 | Registro, login, bloqueo, confirmación y pedido con la aplicación completa | `requestEventId` en la base |
| CA-14 | Suite E2E: `./mvnw test -Pe2e` con y sin credenciales, corrida por el usuario; consumer group de tests o plan B según la prueba de conexión | Proceso con el `requestEventId` real; consumer group del servicio intacto; sin credenciales, salteado |
| CA-15 | Consumer group nuevo; topic con un pedido huérfano y uno de un proceso local, publicados antes de conectarse | Huérfano registrado; el otro aplicado |
| CA-16 | Login del arranque fallido y después uno exitoso, con un pedido publicado en el medio; sesión en error de configuración | Sin consumo y después el pedido procesado; con error de configuración, nunca se consume |
| CA-17 | Renovación de la sesión con otros datos de Kafka | Warning en el log; el consumo sigue igual |

## Decisiones pendientes

<!-- Al resolverlas, actualiza las secciones afectadas. Escribe Ninguna cuando
no queden pendientes funcionales ni restricciones por decidir. -->

Ninguna.

Decisiones tomadas (2026-10-29):

- **D1 · Autenticación ante Kafka (RF-01, opción a):** `integration` dice a dónde conectarse (servidores, topics, consumer group), pero no cómo autenticarse, y no trae credenciales de Kafka (a diferencia de Redis). Se avanza con la hipótesis del usuario: si el servidor exige credenciales, son las de la cuenta técnica, las mismas de la configuración externa de X01; si no las exige, alcanza con `integration`. El protocolo y el mecanismo de autenticación quedan configurables por configuración externa, para no fijar una suposición en el código. Se verifica de dos formas: el usuario lo consulta con el profesor, y en la primera tarea (junto con las grabaciones de D8) una prueba de conexión de solo lectura, corrida por el usuario y confirmada antes: pide la lista de topics, sin unirse al consumer group, sin leer mensajes ni publicar. Lo que muestre se refleja en el PLAN y en la configuración por defecto. Alternativa descartada: (b) esperar la respuesta del profesor antes de definir la conexión, que bloqueaba el avance.
- **D2 · Pedido que llega mientras opera el usuario (RF-11, decidida por el usuario):** el procesamiento del pedido usa el mismo candado por usuario que bloquear y confirmar (decisión 013), así que un pedido que llega mientras T05 confirma ese proceso se procesa cuando T05 termina. Motivo: hoy cada escritura reescribe el proceso completo, y T05 guardaría su copia leída antes del pedido, borrando el `requestEventId`. Si además se agrega un control de versión (una escritura falla si alguien guardó el proceso después de leerlo, en lugar de pisarlo) como protección adicional se decide en el PLAN.
- **D3 · Eventos de resultado mientras T08 no existe (RF-02, opción a):** los cinco eventos de resultado (contrato §4.3.4) se descartan, se registran en el log con su `eventType`, `eventId` y `reservationProcessId`, y se confirma el offset, igual que un `eventType` desconocido; no se registran como procesados. Motivo: mientras T08 no existe solo pueden llegar de procesos de prueba (por ejemplo, el vencimiento de las reservas que deja el E2E de T05), y T08 se implementa antes de que T06 publique teléfonos reales. Lo que se descarte en ese lapso no se recupera después: lo cubriría T13. Alternativa descartada: (b) no confirmar el offset hasta que exista T08: Kafka entrega en orden por partición, así que un evento sin confirmar detiene a los que vienen detrás, incluidos los pedidos de teléfono.
- **D4 · Campos requeridos del pedido (RF-03, opción a):** son obligatorios los seis que turnos usa según el contrato §4.3.2: `eventId`, `reservationProcessId`, `holdId`, `requiredField`, `expiresAt` y `schemaVersion` (además de `eventType`, que decide quién lo procesa). `message` (el texto para mostrar al usuario) y `occurredAt` (cuándo lo generó la cátedra) se aceptan si vienen, pero su falta no descarta el pedido: turnos no los usa. Motivo: un descarte por 2.1 no tiene vuelta atrás, y perder un pedido válido por un dato que no se usa deja al usuario sin poder informar el teléfono. No contradice T07, cuyo paso 2 dice "campos requeridos" sin enumerarlos, ni el contrato; no se cambia ningún documento. Alternativa descartada: (b) exigir los ocho campos que enumera el paso 1.
- **D5 · Mensajes siguientes cuando la base falla (RF-09, opción a):** agotados los reintentos, se registra el error, se hace una pausa configurable y se vuelve a intentar el mismo mensaje, sin confirmar su offset; los mensajes que vienen detrás en su partición esperan en Kafka, sin perderse, hasta que ese se guarde. Si el servicio se reinicia mientras tanto, Kafka vuelve a entregarlo, y RF-04 evita repetir un efecto que sí se había guardado. Solo se reintenta así una falla de la base; qué se hace con un error inesperado que no es de la base, para que no detenga la partición para siempre, se define en el PLAN. Motivo: confirmar un offset marca como leído todo lo anterior, así que no se puede dejar un mensaje pendiente y seguir con los siguientes; esperar conserva el orden de los eventos de un mismo proceso y no pierde ninguno, y si la base está caída tampoco se podría procesar otro mensaje. Alternativas descartadas: (b) apartarlo para reintentarlo después, en un topic de reintentos o una tabla local: mucho más complejo, rompe el orden y en el Kafka de la cátedra probablemente no se puedan crear topics; (c) descartarlo después del máximo de intentos: contradice T07 6.1 y el contrato §4.3.1.
- **D6 · Desde dónde se lee el topic la primera vez (opción a):** cuando el consumer group no tiene una posición confirmada (la primera conexión, o después de que Kafka la borra por inactividad del grupo), se empieza por el mensaje más antiguo que Kafka conserve. Los pedidos viejos de procesos que la base no conoce se registran como huérfanos y se descartan (RF-05). Motivo: nunca se pierde un pedido de un proceso que sí existe localmente, por ejemplo uno confirmado justo antes de que turnos se conectara. Alternativa descartada: (b) leer solo los mensajes nuevos: sin ruido de pruebas viejas, pero un pedido publicado mientras turnos no estaba conectado se pierde y el usuario no puede informar el teléfono.
- **D7 · Cuándo se empieza a consumir y qué pasa si cambian los datos de Kafka (RF-01, opción a en las dos partes):** **Inicio:** se empieza a consumir apenas la integración queda habilitada, con el login del arranque o con cualquier login posterior que salga bien. Si al arrancar la cátedra no estaba disponible, el consumo empieza con el próximo login exitoso, que hoy ocurre a pedido, cuando una operación de un usuario necesita la cátedra (decisión 005); mientras tanto los pedidos esperan en Kafka (D6). Alternativas descartadas: (b) solo con el login del arranque, que deja el servicio sin eventos hasta reiniciar si la cátedra estaba caída al arrancar; (c) además reintentar el login periódicamente mientras la integración esté degradada, que cambia X01 y queda fuera de T07. **Cambio de datos:** la conexión se mantiene con los datos de Kafka del primer login exitoso hasta reiniciar el servicio; si un login posterior trae otros servidores, topics o consumer group, se registra un warning y no se cambia la conexión. Es la misma limitación que ya tiene la sesión técnica, y un cambio así implica en la práctica otra cuenta técnica, nueva configuración y reinicio (anexo §5.4). Alternativa descartada: (b) desconectarse y volver a conectarse con los datos nuevos: más completo, pero obliga a cortar el consumo a mitad de un mensaje y a decidir qué pasa con las posiciones del consumer group anterior.
- **D8 · Pruebas contra la cátedra real (CA-14, opción a con a' como plan B):** criterio del usuario: ninguna prueba puede afectar lo de otros alumnos ni romper el servicio de turnos real ni la cátedra; puede dejar algo escrito en lo propio si no tiene efecto. El E2E verifica turnos con los servicios reales; no verifica catálogo (sigue simulado) ni el comportamiento de la cátedra en sí. Las pruebas que hablan con la cátedra real las corre el usuario en su terminal, después de confirmar qué hace cada una. **Acordado en cualquier caso:** (1) una prueba de conexión que solo pide la lista de topics, probando la conexión sin credenciales y con las de la cuenta técnica (D1), y que además consulta, sin leer mensajes, si la cátedra permite un consumer group con otro nombre; (2) grabaciones leyendo el topic de acciones **sin consumer group** y sin confirmar nada, para no mover la posición del servicio: los pedidos de teléfono que ya estén en el topic (y los eventos de resultado que haya, como referencia para T08). Si no queda ninguno, se crea con un bloqueo y una confirmación, como el E2E de T05 (ocupa un turno 10 minutos). Se limpian como las grabaciones REST (`groupId` en los nombres, cualquier dato sensible) antes de versionarlas. **(a) E2E con un consumer group propio para tests**, si la prueba de conexión muestra que la cátedra lo permite: el E2E de T05 se amplía para esperar que el proceso confirmado tenga su `requestEventId` en la base del test. Prueba el camino completo del servicio contra el Kafka real (autenticación, consumo y posición) sin tocar la posición del consumer group del servicio. Para eso el consumer group se puede cambiar por configuración externa; por defecto es el de `integration`, y solo el E2E lo cambia. **(a') Plan B, si la cátedra no permite otro nombre:** el E2E lee sin consumer group el pedido real del proceso que confirmó y se lo entrega al procesamiento de turnos, que debe aplicarlo (proceso con su `requestEventId`); no deja nada escrito en Kafka, y el consumo real del servicio (autenticación y consumer group) se prueba recién en el E2E de T06. Sin prueba manual con la aplicación levantada en T07. Alternativas descartadas: (b) E2E con el consumer group del servicio: podía consumir el pedido de un proceso real abierto en la app local, que nunca lo recibiría; (c) sin E2E en T07: no se comprobaba con datos reales que turnos procesa los pedidos de la cátedra.

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el alcance está acordado, las referencias apuntan a pasos y flujos
que existen, los flujos son coherentes, los puntos de backend relevantes están
cubiertos y cada requisito tiene criterios comprobables.
Resuelve las dudas y los marcadores pendientes. Mantén el diseño técnico en PLAN.md.
-->
