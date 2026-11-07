# SPEC: T13 — Reconciliar y recuperar procesos pendientes

**Estado:** Aprobada (2026-11-05) <!-- Borrador | En revisión | Aprobada -->

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
| Caso de uso T13 (`docs/casos_de_uso/turnos_y_reservas_usecases.md`) | Todo el caso de uso, con los cambios de D1. Contexto: T05, T06, T08, T09, T11 y T12, que dejan la marca |
| Diagrama de secuencia (`docs/secuencias/turnos_y_reservas_secuencia.md`) | §7, parte de T13 |
| Contrato de turnos (`docs/contratos/turnos_y_reservas_contrato.md`) | §2 (estados), §4.2.1 (reglas comunes) y §4.2.6 (listar reservas) |
| Caso de uso X01 (`docs/casos_de_uso/cuenta_tecnica_configuracion_usecases.md`) | Precondición y renovación ante `401` |
| Modelo de dominio de turnos (`docs/turnos_y_reservas_dominio.md`) | `ReservationProcess`, §2 y tabla de transiciones; reglas 6, 9, 15 y 19 |
| Anexo de la cátedra | §11 (listar reservas), §13 (errores REST) y §18.4 (reintentos) |
| Decisiones 011, 013, 014 y 015 | `CatedraOperationRunner`, candado por usuario, caída entre guardar y publicar (014 §3.15), abandono local |
| SPEC de T06, T08 y T12 | D1 de T06 (caída entre guardar y publicar), D5 y D6 de T08 (marca y confirmación tardía), D1 y D4 de T12 (cierre de marcados y barrido al arrancar) |
| Respuestas reales (`src/test/resources/catedra/README.md`) | Listado de reservas, 2026-11-05 |

## Qué construimos y para quién

<!-- Qué necesidad cubre, qué actor la inicia (la app KMP, otro servicio, el
tiempo o la cátedra) y qué podrá hacer o recibir. Describe el objetivo en
lenguaje de negocio. Resume y referencia: no copies la descripción del caso de uso. -->

El tiempo dispara una reconciliación que consulta a la cátedra el estado real de los procesos que quedaron en duda, y corrige el estado local si difiere. Es la red de seguridad del proceso de reserva: resuelve lo que dejaron marcado las demás operaciones y lo que quedó a mitad de camino por un reinicio. El usuario ve el estado correcto al consultar (T09 y T10) sin haber hecho nada.

## Situación actual

<!-- Qué existe hoy y es relevante (código, datos, otras funcionalidades ya
implementadas), la limitación que queremos resolver y los comportamientos
existentes que deben conservarse. Si todavía no hay código para esto, indícalo.
No describas la arquitectura. -->

- Están implementados T01 a T12, X01 y la validación del JWT.
- Dejan la marca `needsReconciliation`: T05 (confirmación dudosa, `HELD`), T06 (publicación fallida, `WAITING_FOR_PHONE`), T08 (`INVALID`), T09 y T12 (`PHONE_SUBMITTED` vencido) y T11 (cancelación dudosa, `CONFIRMED`). Hoy nadie la resuelve: un `PHONE_SUBMITTED` marcado no se cierra, y su usuario recibe `OPEN_PROCESS_EXISTS` al bloquear otro turno (D1 de T06).
- `catedra` ya tiene `CatedraAppointmentClient` (cancelar, T11) y `CatedraOperationRunner` (sesión, `401` y reintentos). No hay consulta del listado.
- El barrido de T12 corre al arrancar y cada minuto, y cierra los `HELD` y `WAITING_FOR_PHONE` vencidos aunque estén marcados (D1 de T12).
- **La cátedra real (2026-11-05):** el listado con `from` y `to` responde siempre `500`; sin fechas funciona por `externalPatientId`, por `status` y paginado. Una reserva vencida sin teléfono figura `FAILED`. Los holds no confirmados no aparecen. Una reserva recién confirmada figura de inmediato en `PHONE_PENDING` (D9).
- Debe conservarse: un estado final no se reabre salvo la corrección de T13 (dominio regla 6); un resultado de la cátedra que llega mientras tanto no se pisa; un abandonado (decisión 015) no se toca, porque no queda marcado.

## Dentro del alcance

<!-- Requisitos concretos, con identificadores estables para vincularlos a
criterios, decisiones del plan y tareas. Cada requisito indica el paso o flujo
alternativo del que proviene. No agregues requisitos que los documentos de
referencia no tengan sin consultarlo. -->

- **RF-01** · T13 paso 1, D8: la reconciliación corre al arrancar el servicio y después cada minuto, contado desde que termina la anterior. El intervalo es configurable y se puede apagar, como el barrido de T12. No demora el barrido de T12 ni la demora él.
- **RF-02** · T13 paso 2 y alternativo 2.1, D8: nunca corren dos reconciliaciones a la vez. Hay un solo disparador, y la siguiente empieza cuando termina la anterior, así que 2.1 no puede darse.
- **RF-03** · T13 paso 3: busca los procesos marcados y, en la ejecución de arranque, también todos los abiertos. Los cerrados marcados también se consultan (D2). Si la revisión de los abiertos no se completa porque la cátedra no contestó por todos, se repite en cada ciclo hasta completarla (D3).
- **RF-04** · T13 paso 4, D1: consulta a la cátedra el listado filtrado solo por el `externalPatientId` del dueño, recorriendo todas las páginas, con el JWT técnico, y busca el registro con el mismo `reservationProcessId`.
- **RF-05** · T13 alternativo 4.1: ante un `401`, renueva la sesión técnica (X01) y repite una sola vez.
- **RF-06** · T13 alternativo 4.2, D7: si la cátedra no está disponible (timeout, corte de conexión, `500` o `503`), reintenta con espera creciente y un máximo; agotados, ese proceso y los que faltan quedan como estaban para el ciclo siguiente, y el ciclo termina con una sola línea de log que dice cuántos quedaron. Lo mismo ante un error de configuración (credenciales, `403` `STUDENT_INTEGRATION_NOT_FOUND`) o un JWT rechazado después de renovarlo.
- **RF-06b** · D7, D10: un problema de un solo proceso se registra, ese proceso queda como estaba y se sigue con los demás, como en 7.1. Lo son un `4xx` inesperado y una respuesta mal formada: cuerpo ilegible, un `status` desconocido, `CONFIRMED` sin `confirmedAt` o `CANCELLED` sin `cancelledAt`.
- **RF-07** · T13 paso 5 y alternativo 5.1: a un proceso abierto le aplica el estado de la cátedra según la tabla del contrato §4.2.6 (`CONFIRMED`→`CONFIRMED`, `CANCELLED`→`CANCELLED`, `PHONE_PENDING`→`WAITING_FOR_PHONE` conservando su vencimiento, `FAILED`→`EXPIRED`), guarda `reservationId`, `confirmedAt`, `cancelledAt` y `cancellationReason` cuando los informa, y apaga la marca. Al pasar a `CONFIRMED` toma además el profesional, la fecha y la hora de la cátedra, con un warning si difieren de los guardados (D10). `PHONE_PENDING` con el teléfono ya enviado: sigue `PHONE_SUBMITTED`, la marca queda como estaba y se registra (D4).
- **RF-07b** · D2, dominio regla 6: a un proceso cerrado y marcado (`CONFIRMED` de T11, `INVALID` de T08) solo lo lleva a un estado final y nunca lo reabre. `CONFIRMED` con la cátedra en `CONFIRMED`: sigue igual y se apaga la marca. `CONFIRMED` con `CANCELLED`: pasa a `CANCELLED` con la fecha y el motivo de la cátedra. `INVALID` con `CONFIRMED` o `CANCELLED`: toma ese estado, con los datos que informa la cátedra. `CONFIRMED` o `INVALID` con `FAILED`: pasa a `EXPIRED` (corregido el 2026-11-06 en TASK-01, con OK del usuario: faltaba el `CONFIRMED`, que el contrato §4.2.6 y T13 paso 5 ya cubrían). Cualquier cerrado sin registro o con `PHONE_PENDING`: queda en su estado, se apaga la marca y se registra un error.
- **RF-08** · T13 paso 6, D2: sin registro en la cátedra, un `HELD` no vencido sigue abierto, y cualquier otro proceso abierto pasa a `EXPIRED`. Los cerrados siguen RF-07b. Ese `HELD` apaga la marca (D5).
- **RF-09** · T13 paso 7 y alternativo 5.1: registra el resultado de cada proceso, haya cambiado o no, y al final del ciclo cuántos se corrigieron, cuántos ya coincidían y cuántos quedaron pendientes.
- **RF-10** · T13 alternativo 7.1: si falla la persistencia de un proceso, se registra, queda pendiente para el ciclo siguiente y se sigue con los demás.
- **RF-11** · Guía de backend (concurrencia), decisión 013, D6: no pisa un resultado de la cátedra ni otra operación del usuario. Consulta la cátedra sin el candado del dueño; después lo toma, vuelve a leer el proceso y aplica el resultado solo si el estado y la marca siguen como antes de la llamada. Si cambiaron, descarta el resultado y lo registra.
- **RF-12** · Contrato §4.2.6, guía de backend (privacidad): nunca expone reservas ajenas, y los datos personales del listado (nombre y teléfono del paciente) no van a logs ni se guardan.

## Fuera de alcance

<!-- Exclusiones acordadas, no deducidas por el agente. Incluye las partes de los
casos de uso que se dejan para otra funcionalidad. Si no hay exclusiones
adicionales, indícalo tras revisarlo con la persona. -->

- El hold huérfano de T04 (7.1): fuera de alcance a propósito, según la descripción de T13.
- Corregir procesos cerrados sin marca, como un abandonado (decisión 015) o un `EXPIRED` que la cátedra confirmó tarde (D6 de T08).
- Más de una instancia del servicio (decisión 013).

## Flujo de la operación

<!-- Cómo se inicia (request, evento o tarea programada), qué verifica y qué
resultado produce, incluidas las alternativas relevantes. Si el caso de uso ya
lo describe, referencia sus pasos en lugar de repetirlos y describe solo lo que
cubre esta funcionalidad. -->

Los pasos 1 a 7 de T13 y la secuencia §7 describen el flujo, con estos agregados:

1. Una sola tarea programada: la primera ejecución es la de arranque; las siguientes, cada minuto desde que termina la anterior (RF-01, RF-02).
2. Busca los marcados, abiertos o cerrados; en la ejecución de arranque, y mientras esa revisión siga pendiente, también los abiertos sin marca (RF-03).
3. Por cada proceso, sin el candado del dueño: lista en la cátedra las reservas del paciente, todas las páginas, y toma la del mismo `reservationProcessId` (RF-04). Una falla general corta el ciclo; una de ese proceso lo saltea (RF-06, RF-06b).
4. Con el candado del dueño: vuelve a leer el proceso; si cambió desde antes de la llamada, descarta el resultado (RF-11). Si no, aplica RF-07, RF-07b o RF-08 y guarda si hubo cambios.
5. Registra el resultado del proceso y, al final, el resumen del ciclo (RF-09).

## Datos y reglas de negocio

<!-- Información que necesita la operación, campos obligatorios, validaciones,
límites y reglas como duplicados u orden de presentación. Describe significado
y comportamiento, sin diseñar tablas, DTO, DAO ni almacenamiento. -->

- El dueño se identifica ante la cátedra con `externalPatientId()` (dominio regla 2). El listado es de toda la cuenta técnica: solo se usa el registro con el mismo `reservationProcessId` y el mismo `externalPatientId`.
- La cátedra es la fuente de verdad (dominio regla 15).
- Del listado se usan `reservationProcessId`, `externalPatientId`, `status`, `reservationId`, `confirmedAt`, `cancelledAt`, `cancellationReason` y, al confirmar, `professionalId`, `appointmentDate` y `startTime` (D10). El listado no trae `expiresAt`: un proceso que pasa a `WAITING_FOR_PHONE` conserva el suyo.
- `cancellationReason` es opcional. `confirmedAt` y `cancelledAt` son obligatorios en `CONFIRMED` y `CANCELLED` (RF-06b).
- Los instantes son UTC; la fecha y la hora del turno son locales y sin zona (dominio regla 16).

## Casos alternativos y de error

<!-- Adapta la tabla usando BACKEND_GUIDELINES.md. Cuando el caso de uso ya define
el flujo alternativo, referencia su ID en lugar de repetirlo. Añade escenarios
relevantes. Marca No aplica con su motivo cuando corresponda. Expresa resultados
observables (estado HTTP, código de error, estado final del recurso, evento
publicado), no mecanismos técnicos. -->

| Situación | Comportamiento esperado |
| --- | --- |
| Entrada inválida | No aplica: lo dispara el tiempo. Una respuesta de la cátedra mal formada saltea ese proceso (RF-06b) |
| Sin sesión o sin permiso | `401`: T13 4.1 (RF-05). `403` `STUDENT_INTEGRATION_NOT_FOUND` y credenciales incorrectas: cortan el ciclo (RF-06) |
| Recurso inexistente o ajeno | Sin registro: T13 paso 6 (RF-08). Un registro de otro paciente no se usa (RF-12) |
| Dependencia no disponible (cátedra, catálogo, Redis, Kafka) | T13 4.2 (RF-06) |
| Timeout o espera excesiva | Tiempos de `CatedraOperationRunner`. La espera no retiene el candado del usuario (RF-11). Con la cátedra caída el ciclo termina en segundos (RF-06) y no demora a T12 (RF-01) |
| Operación o mensaje repetido | Reconciliar dos veces lo mismo no repite efectos: un proceso ya reconciliado no queda marcado, y uno que se consulta otra vez (D3) no cambia |
| Evento tardío o fuera de orden | Un evento o una operación que cambió el proceso durante la consulta gana: el resultado de la cátedra se descarta (RF-11) |
| Falla de persistencia | T13 7.1 (RF-10) |
| Solicitudes simultáneas | RF-02 y RF-11 |
| Reinicio con operaciones pendientes | Es el propósito de la ejecución de arranque (RF-03) |
| Otros puntos aplicables de la guía | Privacidad (RF-12), observabilidad (RF-09) |

**Puntos de la guía no aplicables y motivo:** entrada, autenticación de usuario y contrato HTTP propio: no hay endpoint.

## Restricciones del pedido

<!-- Condiciones ya impuestas: compatibilidad, límites de alcance, requisitos
de seguridad o rendimiento medibles, o una tecnología expresamente exigida.
Ejemplo: Respetar el contrato de Documentacion y el anexo de la cátedra puede ser
una restricción; el diseño de los adaptadores va en PLAN.md.
No conviertas una preferencia del agente en una restricción. -->

- Seguir el anexo aunque la cátedra real falle (indicación del usuario del 2026-11-05). D1 elige, entre usos válidos del anexo, uno que se puede verificar hoy.
- Toda llamada a la cátedra pasa por `catedra` y por `CatedraOperationRunner` (decisión 011).

## Criterios de aceptación

<!-- Resultados observables que permitan decidir si se cumple cada requisito.
Incluye los casos alternativos acordados: cada flujo alternativo cubierto debería
tener un criterio. No uses Funciona correctamente.
Repite el formato según sea necesario. -->

- **CA-01 · RF-01, D8:** Dado un proceso marcado al arrancar, cuando la aplicación arranca con la reconciliación encendida, entonces queda reconciliado sin esperar el intervalo; uno marcado después se reconcilia en el ciclo siguiente. Con la reconciliación apagada no se consulta a la cátedra.
- **CA-02 · RF-01, RF-02, D8:** Cuando una reconciliación está esperando a la cátedra, entonces no empieza otra hasta que termina, y el barrido de T12 sigue cerrando vencidos mientras tanto.
- **CA-03 · RF-03:** Dados procesos abiertos sin marca (`HELD`, `WAITING_FOR_PHONE`, `PHONE_SUBMITTED`), marcados abiertos y cerrados, y cerrados sin marca, cuando corre la ejecución de arranque, entonces se consultan todos menos los cerrados sin marca; en los ciclos siguientes, solo los marcados.
- **CA-04 · RF-03, D3:** Dado que en la ejecución de arranque la cátedra no contesta por todos los procesos, cuando corre el ciclo siguiente, entonces vuelve a consultar los abiertos sin marca; después del primer ciclo que la completa, solo los marcados.
- **CA-05 · RF-04, D1:** Cuando se reconcilia un proceso, entonces el pedido a la cátedra lleva el JWT técnico y el `externalPatientId` del dueño, sin fechas, y recorre las páginas hasta encontrar el registro, aunque esté en la segunda.
- **CA-06 · RF-04, RF-12:** Dado un registro con el mismo `reservationProcessId` pero otro `externalPatientId`, entonces se trata como sin registro; ni el log ni la base tienen el nombre o el teléfono del paciente.
- **CA-07 · RF-05:** Dado que la cátedra responde `401`, entonces se renueva la sesión técnica y se repite una vez; si vuelve a responder `401`, el ciclo se corta como en CA-08.
- **CA-08 · RF-06, D7:** Dado que la cátedra no está disponible (timeout, corte, `500` o `503`) o hay un error de configuración, cuando se agotan los reintentos con espera creciente, entonces ese proceso y los que faltan quedan sin cambios y con su marca, el log tiene una sola línea con cuántos quedaron, y el ciclo siguiente los vuelve a consultar.
- **CA-09 · RF-06b:** Dada una respuesta mal formada o un `4xx` inesperado para un proceso, entonces ese proceso queda sin cambios, se registra, y los demás se reconcilian.
- **CA-10 · RF-07, D10:** Dado un proceso abierto y marcado, cuando la cátedra informa `CONFIRMED`, `CANCELLED`, `PHONE_PENDING` (para un `HELD`) o `FAILED`, entonces queda `CONFIRMED` (con `reservationId`, `confirmedAt`, profesional, fecha y hora de la cátedra, y un warning si difieren), `CANCELLED` (con fecha y motivo), `WAITING_FOR_PHONE` (con su vencimiento) o `EXPIRED`, sin marca.
- **CA-11 · RF-07, 5.1:** Dado un `WAITING_FOR_PHONE` marcado y `PHONE_PENDING` en la cátedra, entonces sigue `WAITING_FOR_PHONE`, sin marca, y el resultado se registra.
- **CA-12 · RF-07, D4:** Dado un `PHONE_SUBMITTED` y `PHONE_PENDING` en la cátedra, entonces sigue `PHONE_SUBMITTED` con la marca que tenía, se registra, y si estaba marcado el ciclo siguiente lo vuelve a consultar.
- **CA-13 · RF-07b, D2:** Dado un `CONFIRMED` o un `INVALID` marcados, entonces quedan como dice RF-07b para cada respuesta de la cátedra, y ninguno vuelve a un estado abierto.
- **CA-14 · RF-08, D5:** Dado que la cátedra no tiene registro, entonces un `HELD` no vencido sigue `HELD` sin marca; un `HELD` vencido, un `WAITING_FOR_PHONE` y un `PHONE_SUBMITTED` quedan `EXPIRED` sin marca.
- **CA-15 · RF-09:** Cuando termina un ciclo, entonces el log tiene una línea por proceso consultado, también si no cambió, y una línea final con los totales.
- **CA-16 · RF-10, 7.1:** Dado que falla la base al guardar un proceso, entonces se registra, ese proceso sigue marcado, los demás se reconcilian y el ciclo siguiente lo corrige.
- **CA-17 · RF-11, D6:** Mientras la reconciliación espera a la cátedra, una operación del mismo usuario o un evento de Kafka suyo no espera; si cambió el proceso, el resultado de la cátedra se descarta y se registra; si no lo cambió, se aplica.
- **CA-18 · RF-04, D9:** Las respuestas grabadas de la cátedra real (`PHONE_PENDING`, `FAILED` y el `500` del listado con fechas) se interpretan como en CA-10, CA-14 y CA-08.

## Cómo se comprueba el comportamiento

<!-- Una fila por criterio: escenario y resultado que debemos comprobar.
La selección de tests, herramientas, comandos y evidencias se desarrolla en PLAN.md.
No marques los criterios como superados durante la especificación. -->

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | App completa con Postgres real y una cátedra simulada; un proceso marcado antes de arrancar y otro después | Los dos reconciliados, el primero sin esperar el intervalo; con la reconciliación apagada, la cátedra no recibe pedidos |
| CA-02 | Cátedra simulada que retiene la respuesta; un proceso vencido para T12 | Un solo pedido a la vez; el vencido se cierra mientras tanto |
| CA-03, CA-04 | Procesos de cada clase en Postgres; la cátedra falla en el arranque y responde después | Pedidos recibidos por la cátedra en cada ciclo |
| CA-05, CA-06 | Listado de dos páginas; registro de otro paciente | Parámetros del pedido; estado final; log sin datos personales |
| CA-07, CA-08, CA-09 | Cátedra que responde `401`, `500`, `503`, timeout, error de configuración o un cuerpo mal formado | Estado y marca de cada proceso; líneas del log; pedidos en el ciclo siguiente |
| CA-10 a CA-14 | Una combinación por fila de estado local y respuesta de la cátedra | Estado, marca y datos guardados, leídos de la base |
| CA-15 | Ciclo con procesos que cambian y que no | Líneas del log |
| CA-16 | Falla al guardar a pedido en un proceso | Los demás reconciliados; el fallido, en el ciclo siguiente |
| CA-17 | Una operación o evento cambia el proceso mientras la cátedra retiene la respuesta | La operación no espera; el resultado se descarta; sin cambios, se aplica |
| CA-18 | Cátedra simulada que devuelve las grabaciones | Mismo resultado que con las respuestas armadas a mano |

## Decisiones pendientes

<!-- Al resolverlas, actualiza las secciones afectadas. Escribe Ninguna cuando
no queden pendientes funcionales ni restricciones por decidir. -->

- **D1 · Filtros del listado (opción b, decidida por el usuario el 2026-11-05):** solo `externalPatientId`, recorriendo todas las páginas, en lugar de paciente y rango de fechas. Cumple el anexo §11, que dice que los filtros son opcionales y combinables, y funciona contra la cátedra real, donde todo listado con `from` y `to` responde `500` (grabado en `appointments-500-date-filter.json`). Costo: trae todas las reservas de ese usuario y no solo las de la fecha. Descartada: (a) paciente y fechas, como dicen T13 paso 4 y el contrato §4.2.6, que hoy dejaría todo pendiente. **Documentos cambiados (aprobado por el usuario el 2026-11-05):** T13 paso 4, contrato §4.2.6 y secuencia §7, en `documentacion` y en la copia de este servicio.
- **D2 · Procesos cerrados pero marcados (RF-07b, opción a, decidida por el usuario el 2026-11-05):** la cátedra manda solo hacia un estado final y un cerrado nunca se reabre. Resuelve una contradicción: T13 paso 6 pasaría a `EXPIRED` "cualquier otro caso", incluido un `CONFIRMED`; D5 de T08 limita ese paso a los abiertos; y la tabla del contrato reabriría un `INVALID` con `PHONE_PENDING`, que chocaría con el único proceso abierto por usuario. Limitación aceptada: un cerrado sin registro o con `PHONE_PENDING` no se corrige; queda a la vista en el log, como en D6 de T08. Descartadas: (b) reconciliar solo los abiertos, que dejaría `CONFIRMED` para siempre una reserva que la cátedra canceló; (c) seguir los documentos tal cual. **Documentos cambiados (aprobado por el usuario el 2026-11-05):** T13 pasos 5 y 6, contrato §4.2.6 y secuencia §7, en `documentacion` y en la copia de este servicio.
- **D3 · Ejecución de arranque sin cátedra o sin login técnico (RF-03, opción a, decidida por el usuario el 2026-11-05):** la revisión de los abiertos sin marca queda pendiente en memoria y se repite en cada ciclo hasta uno en que la cátedra contesta por todos. No toca la base; un reinicio dispara otra ejecución de arranque. Costo: puede consultar de nuevo un proceso ya reconciliado, sin efecto. Descartadas: (b) marcar los que no se pudieron consultar, que impediría abandonarlos (decisión 015) mientras la cátedra no vuelva; (c) un solo intento, que deja el arreglo a T12 cuando el proceso vence. No cambia documentos.
- **D4 · `PHONE_SUBMITTED` con `PHONE_PENDING` en la cátedra (RF-07, opción a, decidida por el usuario el 2026-11-05):** sigue `PHONE_SUBMITTED` y la marca queda como estaba: si estaba marcado se vuelve a consultar en el ciclo siguiente hasta que la cátedra diga `CONFIRMED` o `FAILED`; si no, lo resuelven el evento de Kafka o T12 al vencer. Se registra. Coincide con D1 de T06. Un `WAITING_FOR_PHONE` con `PHONE_PENDING` coincide: se apaga la marca y el usuario puede reenviar. Descartadas: (b) apagar la marca, que T12 volvería a encender cada minuto si ya venció; (c) volver a `WAITING_FOR_PHONE`, que contradice la tabla y podría mandar dos teléfonos. No cambia documentos.
- **D5 · `HELD` marcado, sin registro y sin vencer (RF-08, opción a, decidida por el usuario el 2026-11-05):** sigue `HELD` y se apaga la marca, como pide el dominio regla 9: según el anexo una reserva confirmada figura como `PHONE_PENDING`, así que la confirmación no llegó. El usuario puede volver a confirmar o abandonar; si la confirmación sí llegó, el evento de T07 igual lo lleva a `WAITING_FOR_PHONE`. La cátedra real lista de inmediato una reserva recién confirmada como `PHONE_PENDING` (D9). Descartadas: (b) mantener la marca hasta el vencimiento, que impide abandonar; (c) apagarla tras dos consultas sin registro, más lógica para un caso raro. Se refleja en T13 paso 6 y en la secuencia §7, junto con D2.
- **D6 · La llamada a la cátedra y el candado (RF-11, opción a, decidida por el usuario el 2026-11-05):** consulta sin candado; para escribir toma el candado del dueño, vuelve a leer y aplica solo si el estado y la marca no cambiaron desde antes de la llamada; si cambiaron, descarta y registra, y si sigue marcado lo toma el ciclo siguiente. Es el criterio de T09 y T12 (decisión 013): ni el usuario ni el consumidor de Kafka esperan a la cátedra por T13. Descartadas: (b) candado durante toda la operación, que con la cátedra lenta frena al usuario y a todos los eventos; (c) aplicar igual un resultado más definitivo, que decide con datos viejos. No cambia documentos; suma una actualización a la decisión 013.
- **D7 · Cátedra no disponible (RF-06, RF-06b, opción a, decidida por el usuario el 2026-11-05):** una falla general corta el ciclo: la cátedra no disponible después de los reintentos, un error de configuración o un JWT rechazado después de renovarlo. Un problema de un solo proceso lo saltea y sigue. Cuentan como no disponible el timeout, el corte de conexión, `500` y `503`, como en las ocupaciones de T03; el timeout de lectura se reintenta porque la consulta no cambia nada. Descartadas: (b) seguir siempre con todos, que con la cátedra caída alarga el ciclo a minutos y repite la falla en el log; (c) seguir sin reintentos, con la complejidad de a) sin su ventaja. Aunque 4.2 hablaba solo del proceso, el usuario aprobó reflejarlo (2026-11-05): `500` y el corte del ciclo en T13 4.2, en la fila de respuestas del contrato §4.2.6 y en la secuencia §7.
- **D8 · Disparo, intervalo y hilo (RF-01, RF-02, opción a, decidida por el usuario el 2026-11-05):** una sola tarea con espera fija, como T12: la primera ejecución, al arrancar, es la de arranque; después, cada minuto, con variable propia e interruptor (Surefire la apaga). Las tareas programadas pasan a tener dos hilos, para que T12 y T13 no se demoren entre sí. Sin marcados, un ciclo no llama a la cátedra. Descartadas: (b) compartir el hilo con T12; (c) dos disparadores con una guarda explícita. No cambia documentos.
- **D9 · Prueba contra la cátedra real (opción a, decidida por el usuario el 2026-11-05):** un script que corre el usuario: login, hold del profesional 201, confirmación sin teléfono y listado por `externalPatientId` (hasta 4 veces, cada 5 s), para comprobar que una reserva vigente figura en `PHONE_PENDING` (riesgo de D5). **Resultado (2026-11-05, script corrido por el usuario):** la reserva recién confirmada figura en el primer listado como `PHONE_PENDING`, con `reservationId` 1664; grabado en `appointments-200-phone-pending.json`. Se cumple el anexo y desaparece el riesgo de D5. El profesional 201 quedó deshabilitado en la cátedra; la prueba usó el 203. Descartadas: (b) sin más pruebas reales; (c) además una prueba de punta a punta.
- **D10 · Profesional, fecha y hora al confirmar (RF-07, RF-06b, opción a, decidida por el usuario el 2026-11-05):** como T08 (su D3): al pasar a `CONFIRMED` toma `professionalId`, `appointmentDate` y `startTime` de la cátedra, con un warning si difieren de los guardados. Una confirmación queda igual por Kafka o por T13. Un `CONFIRMED` sin `confirmedAt` es una respuesta mal formada (RF-06b). Descartadas: (b) solo `reservationId` y `confirmedAt`, como dice T13 paso 5; (c) además los nombres del profesional, que T08 tampoco actualiza. El usuario aprobó reflejarlo en T13 paso 5 y en el contrato §4.2.6 (2026-11-05).

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el alcance está acordado, las referencias apuntan a pasos y flujos
que existen, los flujos son coherentes, los puntos de backend relevantes están
cubiertos y cada requisito tiene criterios comprobables.
Resuelve las dudas y los marcadores pendientes. Mantén el diseño técnico en PLAN.md.
-->
