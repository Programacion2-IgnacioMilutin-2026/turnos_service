# SPEC: T10 — Consultar mis reservas

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
| Caso de uso T10 (`docs/casos_de_uso/turnos_y_reservas_usecases.md`) | Pasos 1 a 6 y alternativos 2.1, 3.1, 4.1, 4.2 y 5.1. Contexto: T08, T09, T12 y T13 |
| Diagrama de secuencia (`docs/secuencias/turnos_y_reservas_secuencia.md`) | §5, bloque "T10 — Query my reservations" |
| Contrato de turnos (`docs/contratos/turnos_y_reservas_contrato.md`) | §1 (fila T10), §2 (proceso de reserva), §3 (reglas comunes) y §3.8 |
| Contrato común (`docs/contratos/contratos_comunes.md`) | §1.4 (JWT), §2 (errores), §3 (instantes, zona de atención) y §3.1 (paginación) |
| Modelo de dominio de turnos (`docs/turnos_y_reservas_dominio.md`) | `ReservationProcess`, §2; reglas 5, 7, 8, 9, 12, 13 y 16 |
| SPEC de T09 (`docs/sdd/t09-estado-proceso/SPEC.md`) y decisión 013 | D2 y D5 de T09 (lectura sin candado, límite de espera de la base) |
| Caso de uso de la app A10 (`uml/casos_de_uso/app_kmp_usecases.md`) | Solo como contexto: cómo la app pide y muestra el listado |
| Anexo de la cátedra | No aplica: T10 no habla con la cátedra |

## Qué construimos y para quién

<!-- Qué necesidad cubre, qué actor la inicia (la app KMP, otro servicio, el
tiempo o la cátedra) y qué podrá hacer o recibir. Describe el objetivo en
lenguaje de negocio. Resume y referencia: no copies la descripción del caso de uso. -->

La app pide una página con las reservas del usuario para mostrar "Mis reservas" (A10): el proceso abierto, si lo hay, las confirmadas y las canceladas después de confirmarse. Es una lectura local y no modifica ningún dato (D2).

## Situación actual

<!-- Qué existe hoy y es relevante (código, datos, otras funcionalidades ya
implementadas), la limitación que queremos resolver y los comportamientos
existentes que deben conservarse. Si todavía no hay código para esto, indícalo.
No describas la arquitectura. -->

- Están implementados T01 a T09, X01 y la validación del JWT. T11 a T13 todavía no.
- No existe el `GET` que lista los procesos de un usuario, y el slice no tiene ninguna consulta paginada.
- T09 ya dejó lo que T10 reutiliza: la regla del vencido en el modelo, la lectura sin candado y la respuesta con todos los campos. También existe la búsqueda de los procesos abiertos de un usuario.
- Debe conservarse el comportamiento de T01 a T09, X01 y la validación del JWT.

## Dentro del alcance

<!-- Requisitos concretos, con identificadores estables para vincularlos a
criterios, decisiones del plan y tareas. Cada requisito indica el paso o flujo
alternativo del que proviene. No agregues requisitos que los documentos de
referencia no tengan sin consultarlo. -->

- **RF-01** · T10 pasos 1 y 2, alternativo 2.1, contrato §3.8: `GET /api/reservation-processes` exige JWT válido de cualquier rol. El dueño sale del token: solo se listan sus procesos.
- **RF-02** · T10 paso 3, alternativo 3.1, contrato §3.8, contrato común §3.1: `status` es opcional y acepta solo `HELD`, `WAITING_FOR_PHONE`, `PHONE_SUBMITTED`, `CONFIRMED` o `CANCELLED`. `page` es opcional, base 0, por defecto 0. `size` es opcional, de 1 a 100, por defecto 10. Fuera de eso, responde `400` `VALIDATION_ERROR` con `fieldErrors`, sin tocar la base. Un parámetro presente pero vacío cuenta como si no viniera (D3).
- **RF-03** · T10 paso 4, D2, dominio reglas 7, 8 y 12: un proceso del usuario en `HELD` o `WAITING_FOR_PHONE` vencido más allá del margen de seguridad no se lista ni se cuenta en `totalItems`, aunque en la base siga abierto.
- **RF-04** · T10 paso 4, D2, dominio regla 8: un proceso del usuario en `PHONE_SUBMITTED` en esa situación se lista como abierto, porque su resultado puede estar en camino.
- **RF-05** · T10 paso 4, dominio regla 12: se listan los procesos abiertos que RF-03 no excluye, los `CONFIRMED` y los `CANCELLED` con fecha de confirmación que cumplan el filtro. Nunca los `EXPIRED`, los `INVALID` ni los cancelados antes de confirmarse.
- **RF-06** · T10 paso 5, contrato §3.8, D1: el proceso abierto va primero. Después, los turnos que todavía no comenzaron, del más cercano al más lejano. Después, los ya comenzados, del más reciente al más antiguo. Los que tienen la misma fecha y hora se desempatan por `reservationProcessId`, para que el orden sea estable entre páginas.
- **RF-07** · T10 paso 6, alternativos 4.2 y 5.1, contrato común §3.1: responde `200` con `items`, `page`, `size` y `totalItems`. Sin resultados, o con una página más allá de la última, `items` va vacío con el total real.
- **RF-08** · Contrato §2 y §3.8: cada elemento de `items` es el objeto completo del proceso, el mismo de T09.
- **RF-09** · D2, guía de backend (concurrencia): el listado no toma el candado del usuario ni espera a otra operación.
- **RF-10** · T10 alternativo 4.1, contrato §3: si falla la lectura de la base, se registra y responde `500` `INTERNAL_ERROR`.
- **RF-11** · T10 postcondición, D2: no modifica ningún dato: ni cierra un vencido ni lo marca para reconciliar. No llama a la cátedra ni a catálogo.
- **RF-12** · Contrato §2, guía de backend (privacidad): la respuesta nunca expone `holdId`, `requestEventId`, el teléfono ni la marca de reconciliación. El teléfono tampoco aparece en logs ni en errores.

## Fuera de alcance

<!-- Exclusiones acordadas, no deducidas por el agente. Incluye las partes de los
casos de uso que se dejan para otra funcionalidad. Si no hay exclusiones
adicionales, indícalo tras revisarlo con la persona. -->

Confirmado con el usuario:

- La cancelación (T11).
- El barrido periódico (T12) y la reconciliación (T13). Los vencidos que T10 no lista los cierra o marca T12, o la operación que los use (D2).
- Prueba contra la cátedra real: T10 no habla con ella (D4).

## Flujo de la operación

<!-- Cómo se inicia (request, evento o tarea programada), qué verifica y qué
resultado produce, incluidas las alternativas relevantes. Si el caso de uso ya
lo describe, referencia sus pasos en lugar de repetirlos y describe solo lo que
cubre esta funcionalidad. -->

1. La app llama al endpoint (T10 paso 1) y la seguridad valida el JWT (paso 2).
2. Se validan los parámetros (paso 3, RF-02).
3. Se consulta la página y el total, sin los vencidos de RF-03 (RF-03 a RF-06).
4. Se responde la página (RF-07, RF-08).

## Datos y reglas de negocio

<!-- Información que necesita la operación, campos obligatorios, validaciones,
límites y reglas como duplicados u orden de presentación. Describe significado
y comportamiento, sin diseñar tablas, DTO, DAO ni almacenamiento. -->

- El margen de seguridad es el mismo de T04 a T06 y T09 (dominio regla 7).
- Los datos del profesional son los del momento de la reserva (dominio regla 13).
- La exclusión de RF-03 también vale con filtro: `status=HELD` no devuelve un `HELD` vencido. Un proceso abierto que vence entre dos consultas deja de aparecer en la siguiente.
- Filtrar por `CANCELLED` tampoco devuelve los cancelados antes de confirmarse (RF-05).
- "Todavía no comenzó" se compara con la fecha y hora actuales en la zona de atención (contrato común §3). Un turno que comienza justo ahora cuenta como próximo. Una reserva cancelada se ordena igual que las demás.
- `status` distingue mayúsculas: las enumeraciones van en mayúsculas (contrato común §3). `confirmed`, `EXPIRED` e `INVALID` responden `400`.

## Casos alternativos y de error

<!-- Adapta la tabla usando BACKEND_GUIDELINES.md. Cuando el caso de uso ya define
el flujo alternativo, referencia su ID en lugar de repetirlo. Añade escenarios
relevantes. Marca No aplica con su motivo cuando corresponda. Expresa resultados
observables (estado HTTP, código de error, estado final del recurso, evento
publicado), no mecanismos técnicos. -->

| Situación | Comportamiento esperado |
| --- | --- |
| Entrada inválida | T10 3.1: `400` `VALIDATION_ERROR` con `fieldErrors` (RF-02). Un parámetro vacío cuenta como si no viniera (D3) |
| Sin sesión o sin permiso | T10 2.1: `401` o `403` de la capa de seguridad |
| Recurso inexistente o ajeno | No aplica como error: los procesos ajenos no se listan (RF-01), y sin resultados es una página vacía (T10 4.2) |
| Dependencia no disponible (cátedra, catálogo, Redis, Kafka) | No aplica: es una lectura local (RF-11) |
| Timeout o espera excesiva | El listado no espera a otras operaciones (RF-09). Una base que no responde corta la espera por tiempo (D5 de T09) |
| Operación o mensaje repetido | Consultas repetidas no tienen efecto: no se escribe nada (RF-11) |
| Evento tardío o fuera de orden | Un resultado de T08 entre dos consultas se ve en la siguiente. T10 no puede pisarlo, porque no escribe |
| Falla de persistencia | T10 4.1: `500` `INTERNAL_ERROR` al leer (RF-10). No hay escrituras |
| Solicitudes simultáneas | Sin efecto: dos lecturas no se afectan. Una lectura puede mostrar `PHONE_SUBMITTED` mientras T06 publica, aunque después vuelva a `WAITING_FOR_PHONE` |
| Reinicio con operaciones pendientes | No aplica: no hay nada pendiente |
| Otros puntos aplicables de la guía | Paginación (RF-02, RF-07), orden estable entre páginas (RF-06), datos que no se exponen (RF-12) |

**Puntos de la guía no aplicables y motivo:** tareas programadas: T12 y T13. Dependencias externas: lectura local.

## Restricciones del pedido

<!-- Condiciones ya impuestas: compatibilidad, límites de alcance, requisitos
de seguridad o rendimiento medibles, o una tecnología expresamente exigida.
Ejemplo: Respetar el contrato de Documentacion y el anexo de la cátedra puede ser
una restricción; el diseño de los adaptadores va en PLAN.md.
No conviertas una preferencia del agente en una restricción. -->

- Respetar el contrato de turnos §2 y §3.8, y el formato de página del contrato común §3.1.
- La suite normal de tests no se conecta a la cátedra ni a su Kafka.
- Aplicar la skill `hexagonal-vertical-slicing` con las excepciones autorizadas. Lo existente se amplía en lugar de duplicarse.
- No cambiar el comportamiento de T01 a T09, X01 ni la validación del JWT.

## Criterios de aceptación

<!-- Resultados observables que permitan decidir si se cumple cada requisito.
Incluye los casos alternativos acordados: cada flujo alternativo cubierto debería
tener un criterio. No uses Funciona correctamente.
Repite el formato según sea necesario. -->

- **CA-01 · RF-01:** Sin JWT, o con uno inválido o vencido, responde `401`; con un rol no válido, `403`. Sin datos en ninguno de los casos.
- **CA-02 · RF-01:** Solo aparecen los procesos del dueño del token: uno de otro usuario nunca se lista ni se cuenta.
- **CA-03 · RF-02:** Sin parámetros, usa página 0, tamaño 10 y sin filtro. Con cada estado válido, filtra por ese estado.
- **CA-04 · RF-02:** Con un `status` desconocido, en minúsculas, `EXPIRED` o `INVALID`; `page` negativa o no numérica; o `size` 0, mayor que 100 o no numérico: responde `400` `VALIDATION_ERROR` con el campo en `fieldErrors`, sin consultar la base.
- **CA-05 · RF-02, D3:** Con `status`, `page` o `size` presentes pero vacíos, responde igual que si no vinieran.
- **CA-06 · RF-03:** Un `HELD` o `WAITING_FOR_PHONE` vencido más allá del margen no aparece, tampoco filtrando por su estado, y no suma a `totalItems`. Dentro del margen, aparece.
- **CA-07 · RF-04:** Un `PHONE_SUBMITTED` vencido aparece como abierto.
- **CA-08 · RF-05:** Aparecen `CONFIRMED` y `CANCELLED` con fecha de confirmación. No aparecen `EXPIRED`, `INVALID` ni `CANCELLED` sin ella, tampoco filtrando por `CANCELLED`.
- **CA-09 · RF-06:** Con un proceso abierto, turnos futuros y pasados, el orden es: el abierto; los futuros, del más cercano al más lejano; los pasados, del más reciente al más antiguo. La comparación usa la zona de atención, y un turno de hoy se ubica según su hora. Dos con la misma fecha y hora se devuelven siempre en el mismo orden, y ningún proceso se repite ni falta entre páginas.
- **CA-10 · RF-07:** La respuesta tiene `items`, `page`, `size` y `totalItems`. Sin resultados, `items` vacío y total 0. Más allá de la última página, también con un número de página enorme, `items` vacío y el total real.
- **CA-11 · RF-08:** Cada elemento tiene los campos del proceso, según su estado.
- **CA-12 · RF-09:** El listado responde aunque otra operación del usuario tenga el candado.
- **CA-13 · RF-10:** Si falla la base, responde `500` `INTERNAL_ERROR` sin detalles internos, también con Postgres sin responder de verdad.
- **CA-14 · RF-11:** Después de listar, ninguna fila cambió, tampoco la de un vencido: sigue en su estado y sin marca. No hay requests a la cátedra ni a catálogo.
- **CA-15 · RF-12:** Ninguna respuesta contiene `holdId`, `requestEventId`, el teléfono ni la marca. El teléfono no aparece en el log.
- **CA-16 · Restricciones:** La suite existente sigue pasando sin cambios en lo que comprueba.

## Cómo se comprueba el comportamiento

<!-- Una fila por criterio: escenario y resultado que debemos comprobar.
La selección de tests, herramientas, comandos y evidencias se desarrolla en PLAN.md.
No marques los criterios como superados durante la especificación. -->

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | Sin token, inválido, vencido y con un rol no válido | `401` o `403` |
| CA-02 | Procesos de dos usuarios | Cada uno ve solo los suyos |
| CA-03 | Sin parámetros, y con cada estado válido | Valores por defecto y filtro |
| CA-04 | Cada valor inválido | `400` con el campo; sin llamar a la base |
| CA-05 | `?status=`, `?page=` y `?size=` | Igual que sin parámetros |
| CA-06 | `HELD` y `WAITING_FOR_PHONE`, fuera y dentro del margen, con y sin filtro | Fuera: no aparece. Dentro: aparece |
| CA-07 | `PHONE_SUBMITTED` vencido | Aparece |
| CA-08 | Una fila por estado | Solo las listables |
| CA-09 | Abierto, futuros, pasados, de hoy y empatados, recorridos por páginas | Orden exacto, sin repetidos ni faltantes |
| CA-10 | Sin resultados, y página más allá de la última | Vacía, con el total real |
| CA-11 | Un proceso por estado listable | Campos según el estado |
| CA-12 | Candado tomado por otra operación | Responde igual |
| CA-13 | Falla simulada, y Postgres en pausa | `500` `INTERNAL_ERROR` |
| CA-14 | Filas antes y después; servidores simulados | Sin cambios ni requests |
| CA-15 | Fila con teléfono; salida capturada | Sin esos datos |
| CA-16 | Suite existente | Sin cambios de comportamiento |

## Decisiones pendientes

<!-- Al resolverlas, actualiza las secciones afectadas. Escribe Ninguna cuando
no queden pendientes funcionales ni restricciones por decidir. -->

Ninguna.

Decisiones tomadas:

- **D1 · Orden de la lista (opción a, decidida por el usuario el 2026-11-03):** primero los turnos que vienen, del más cercano al más lejano, y después los pasados, del más reciente al más antiguo, como dice T10 paso 6. El orden lo resuelve la consulta y no la app, porque con paginación la app solo podría reordenar lo que ya recibió. Medido en un Postgres temporal, con 200.000 filas y un usuario con 100 reservas, una corrida por caso: este orden tarda lo mismo que el orden simple descendente (9,8 ms y 9,4 ms sin índice, 0,16 ms y 0,23 ms con índice).
- **D2 · Los vencidos en T10 (opción a, propuesta del usuario, decidida el 2026-11-03):** T10 solo lee. La consulta deja afuera los `HELD` y `WAITING_FOR_PHONE` vencidos y lista los `PHONE_SUBMITTED` vencidos; no cierra ni marca nada, y no toma el candado. Los resuelve la operación que los use (T04, T05, T06, T09) o T12, que los busca por fecha. Descartado: que T10 los envíe a T09 o los marque, porque volvería a escribir sin necesidad, y la marca le pediría a T13 consultar a la cátedra por un proceso que T12 cierra igual. Costo aceptado: la regla del vencido queda en el modelo y en la consulta, y hasta que exista T12 un proceso que nadie vuelve a usar queda abierto en la base, sin verse en la lista. Cambio de documentos aprobado por el usuario el 2026-11-03: T10, T12, T13, contrato §3.8, secuencia §5 y dominio, en `documentacion` y en la copia de este servicio.
- **D3 · Parámetro presente pero vacío (opción a, decidida por el usuario el 2026-11-03):** cuenta como si no viniera. `?status=` lista sin filtro y `?size=` usa 10. Comportamiento de Spring sin comprobar: se verifica con un test al implementar.
- **D4 · Prueba contra la cátedra real (opción a, decidida por el usuario el 2026-11-03):** no se agrega al E2E. T10 no habla con la cátedra y no escribe; alcanza con integración sobre Postgres real.
