# SPEC: T03 — Consultar turnos libres de un profesional

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
| Caso de uso T03 (`docs/casos_de_uso/turnos_y_reservas_usecases.md`) | Precondición, pasos 1 y 3 a 9, alternativos 3.1, 4.1 a 4.5, 5.1 a 5.3 y 8.1, y postcondición. El 4.5 reemplaza al antiguo 7.1 (D9). El paso 2 y el alternativo 2.1 ya los resuelve la validación del JWT (`docs/sdd/validacion-jwt/`) |
| Diagrama de secuencia (`docs/secuencias/turnos_y_reservas_secuencia.md`) | §2 completo |
| Contrato de turnos (`docs/contratos/turnos_y_reservas_contrato.md`) | §1 (fila T03), §3 (reglas comunes), §3.3 completo, §4.1 (uso de C06), §4.2.1 (reglas comunes de la cátedra) y §4.2.3 (ocupaciones) |
| Contrato común (`docs/contratos/contratos_comunes.md`) | §1.4 y §1.5 (el JWT del usuario se reenvía a catálogo), §2 (formato de errores) y §3 (fechas, horas, instantes y zona horaria de atención) |
| Contrato de catálogo (`../catalog_service/docs/contratos/catalogo_y_sincronizacion_contrato.md`) | §3.1 (C06), como lo que turnos espera recibir |
| Modelo de dominio de turnos (`docs/turnos_y_reservas_dominio.md`) | Regla 18 (caché de ocupaciones) |
| Modelo de dominio de catálogo (`../catalog_service/docs/catalogo_y_sincronizacion_dominio.md`) | Reglas 4 a 6 (`enabled` independientes; solo los horarios habilitados generan turnos; cómo se generan) |
| X01 y su SPEC (`docs/sdd/x01-cuenta-tecnica/`) y decisión 005 | Cómo se pide la sesión técnica y cómo se pide un login nuevo tras un `401` o con la integración degradada (RF-08 de X01) |
| Anexo de la cátedra (`enunciado/INTEGRATION_REFERENCE-v2.md`) | §4 (horas `HH:mm` y `HH:mm:ss`), §8 (ocupaciones), §13 (errores) y §18.4 (reintentos limitados y observables) |
| Enunciado (`enunciado/PROJECT_STATEMENT-v1.md`) | §7 (la disponibilidad combina reglas de catálogo y ocupaciones de la cátedra) y §8 (sin catálogo no se inician operaciones que validan información vigente) |
| Caso de uso de la app A05 (`documentacion/uml/casos_de_uso/app_kmp_usecases.md`) | Solo como contexto: cómo usa la app `stale`, `dataAsOf` y `emptyReason` |

## Qué construimos y para quién

<!-- Qué necesidad cubre, qué actor la inicia (la app KMP, otro servicio, el
tiempo o la cátedra) y qué podrá hacer o recibir. Describe el objetivo en
lenguaje de negocio. Resume y referencia: no copies la descripción del caso de uso. -->

Un usuario con sesión iniciada elige un profesional y una fecha en la app, y recibe los turnos libres de ese día para poder bloquear uno (T04). Turnos combina los horarios semanales vigentes que informa catálogo (C06) con las ocupaciones que informa la cátedra. Si la cátedra no responde, usa la última consulta exitosa para ese profesional y esa fecha, y lo avisa. La inicia la app KMP (A05).

## Situación actual

<!-- Qué existe hoy y es relevante (código, datos, otras funcionalidades ya
implementadas), la limitación que queremos resolver y los comportamientos
existentes que deben conservarse. Si todavía no hay código para esto, indícalo.
No describas la arquitectura. -->

- Están implementados T01, T02, X01 y la validación del JWT en los endpoints protegidos. `GET /api/free-slots` todavía no existe: con un token válido responde `404`, y sin token `401`.
- La sesión técnica (X01) ofrece el JWT técnico y permite pedir un login nuevo. Hoy solo indica si hay una sesión habilitada o no, sin distinguir un error de configuración de una cátedra caída.
- No hay ningún cliente hacia catálogo ni hacia las ocupaciones de la cátedra. El servicio de catálogo todavía no existe: se simula según su contrato.
- Debe conservarse el comportamiento de T01, T02, X01 y la validación del JWT.

## Dentro del alcance

<!-- Requisitos concretos, con identificadores estables para vincularlos a
criterios, decisiones del plan y tareas. Cada requisito indica el paso o flujo
alternativo del que proviene. No agregues requisitos que los documentos de
referencia no tengan sin consultarlo. -->

**Entrada**

- **RF-01** · T03 paso 1, contrato §3.3: `GET /api/free-slots?professionalId=&date=`, protegido (cualquier rol). La identidad no se usa en T03; el token del usuario sí, para catálogo.
- **RF-02** · T03 paso 3 y alternativo 3.1, contrato §3.3: `professionalId` obligatorio y numérico; `date` obligatoria, `yyyy-MM-dd` y no anterior a hoy en la zona horaria de atención. Si no, `400` `VALIDATION_ERROR` con `fieldErrors`, sin llamar a catálogo ni a la cátedra. `professionalId` debe ser un entero positivo dentro del rango de un `Long`: `0`, un negativo o uno demasiado grande también responden `400` (D7). "Hoy" se calcula en la zona horaria de atención, `America/Argentina/Mendoza` por defecto y configurable (D4).

**Catálogo (C06)**

- **RF-03** · T03 paso 4, contrato §4.1, contrato común §1.5: pide a catálogo el profesional y sus horarios semanales, reenviando sin modificar el JWT que mandó la app.
- **RF-04** · T03 alternativo 4.1, contrato §4.1: si catálogo responde `401` o `403`, registra un error de integración interna y responde `500` `INTERNAL_ERROR`, sin llamar a la cátedra.
- **RF-05** · T03 alternativo 4.2, contrato §3.3: si responde `404` `PROFESSIONAL_NOT_FOUND`, responde `404` `PROFESSIONAL_NOT_FOUND`, sin llamar a la cátedra.
- **RF-06** · T03 alternativo 4.3, contrato §4.1: ante timeout, corte de conexión o `503` (incluido `CATALOG_NOT_INITIALIZED`), reintenta con espera creciente y un máximo de intentos (D2); agotados, responde `503` `CATALOG_UNAVAILABLE`, sin llamar a la cátedra.
- **RF-07** · Contrato §4.1 (última fila): cualquier otra respuesta de catálogo (por ejemplo `400` o `500`) se registra y responde `500` `INTERNAL_ERROR`.
- **RF-08** · T03 alternativo 4.4: si el profesional tiene `enabled` en falso, responde `409` `PROFESSIONAL_DISABLED`, sin llamar a la cátedra. El `enabled` de la categoría no se tiene en cuenta (D5).

**Cátedra (ocupaciones)**

- **RF-09** · T03 paso 5, contrato §4.2.3, anexo §8: pide las ocupaciones del profesional con `from` y `to` iguales a la fecha, con el JWT técnico vigente de la sesión de X01.
- **RF-10** · T03 alternativo 5.1, contrato §4.2.1: si la cátedra responde `401`, pide a X01 un login nuevo, informando el JWT rechazado, y repite la consulta una sola vez con la sesión nueva. Si X01 no consigue una sesión, se sigue como en RF-12.
- **RF-11** · T03 alternativo 5.2, contrato §4.2.1: si la cátedra responde `403` `STUDENT_INTEGRATION_NOT_FOUND`, registra un error de configuración y responde `503` `SERVICE_DEGRADED` sin exponer detalles. Lo mismo si, al momento del request, la integración está en error de configuración (D1).
- **RF-12** · T03 alternativo 5.3, contrato §4.2.3: ante timeout, corte de conexión, `503` o `500`, reintenta con espera creciente y un máximo de intentos (D2); agotados, recurre al caché (RF-14). Si al momento del request la integración está degradada, primero pide a X01 un login nuevo; si no se consigue sesión, también recurre al caché (D1). Cualquier otra respuesta de la cátedra (por ejemplo `400`) se registra y responde `500` `INTERNAL_ERROR`, sin usar el caché: es un error no previsto (contrato §3, D10).
- **RF-13** · T03 paso 6, dominio de turnos regla 18: con `200`, guarda o reemplaza la entrada del caché para ese `professionalId` y esa `date`, con las ocupaciones y el instante en que se obtuvieron (`dataAsOf`).

**Caché**

- **RF-14** · T03 alternativo 5.3, dominio regla 18: si existe una entrada para ese profesional y esa fecha y no supera la antigüedad máxima configurada, se usa para calcular los turnos y la respuesta lleva `stale: true` con su `dataAsOf`. Si existe pero la supera, se elimina y se responde `503` `CENTRAL_UNAVAILABLE`; si no existe, `503` `CENTRAL_UNAVAILABLE`. Las entradas vencidas también se descartan solas, sin esperar una consulta. El caché vive en memoria y no se persiste. Antigüedad máxima: 60 minutos por defecto, configurable (D3).

**Cálculo de turnos**

- **RF-15** · T03 paso 7, dominio de catálogo reglas 5 y 6: toma los horarios semanales habilitados del día de la semana de la fecha y genera, para cada uno, los turnos que empiezan en `startTime` y se repiten cada `slotDurationMinutes`, incluyendo solo los que terminan en o antes de `endTime`. Si la fecha es hoy, descarta los que ya comenzaron, según la hora actual en la zona de atención (D4). Si dos horarios generan la misma hora de inicio, el turno aparece una sola vez, con la duración del horario que empieza antes (D6).
- **RF-16** · T03 paso 8, contrato §4.2.3, anexo §4 y §8: descarta los turnos cuya hora de inicio coincide con una ocupación `CONFIRMED` o `HELD`; las horas se comparan aceptando `HH:mm` y `HH:mm:ss`.
- **RF-17** · T03 paso 9, contrato §3.3: responde `200` con los turnos libres ordenados por hora de inicio, cada uno con `professionalId`, `date`, `startTime` (`HH:mm`) y `durationMinutes`; `emptyReason` en `null`; `stale` en `false` y `dataAsOf` el instante de la consulta en vivo (o los valores del caché, según RF-14).
- **RF-18** · T03 alternativo 4.5 (antes 7.1), contrato §3.3: si el profesional no tiene ningún horario habilitado el día de la semana de la fecha, responde `200` con `slots` vacío y `emptyReason` `PROFESSIONAL_NOT_WORKING`, sin consultar a la cátedra: se evalúa apenas llega la respuesta de catálogo, después de RF-08 (D9).
- **RF-19** · T03 alternativo 8.1, contrato §3.3: si no queda ningún turno libre (todos ocupados o ya comenzados), responde `200` con `slots` vacío y `emptyReason` `NO_FREE_SLOTS`.

**Transversales**

- **RF-20** · Guía de backend (dependencias externas y configuración), contrato común §3, decisiones D2 a D4: la URL de catálogo, los timeouts y los reintentos hacia catálogo y hacia las ocupaciones, la antigüedad máxima del caché y la zona horaria de atención se leen de la configuración externa, con valores por defecto, y se pueden cambiar sin tocar código. Si falta la URL de catálogo, o no es una URL `http` o `https` válida (por ejemplo, entre comillas), el servicio arranca igual, registra un warning que nombra la propiedad y T03 responde `503` `CATALOG_UNAVAILABLE` sin intentar la llamada; T01 y T02 siguen funcionando (D8).
- **RF-21** · Anexo §18.4, guía de backend (privacidad): cada reintento queda registrado con su número y su motivo. Ni el JWT del usuario ni el JWT técnico aparecen en logs ni en respuestas.
- **RF-22** · T03 postcondición: no se modifica ningún dato de negocio; el caché es un dato auxiliar.

## Fuera de alcance

<!-- Exclusiones acordadas, no deducidas por el agente. Incluye las partes de los
casos de uso que se dejan para otra funcionalidad. Si no hay exclusiones
adicionales, indícalo tras revisarlo con la persona. -->

Confirmadas por el usuario (2026-10-24):

- La validación del JWT del usuario (paso 2 y alternativo 2.1): ya está hecha.
- Bloquear un turno (T04), aunque use el mismo C06: cada caso de uso lo consulta por su cuenta.
- El servicio de catálogo real: se simula según su contrato. La prueba contra el catálogo real queda para cuando exista.
- Persistir el caché en la base o compartirlo entre instancias del servicio. El caché en memoria sí es parte de T03 (RF-13 y RF-14), y solo lo usa T03: T04 valida siempre en vivo.
- Cambiar cómo X01 decide cuándo hacer un login: T03 solo lo pide, con las reglas de la decisión 005.

## Flujo de la operación

<!-- Cómo se inicia (request, evento o tarea programada), qué verifica y qué
resultado produce, incluidas las alternativas relevantes. Si el caso de uso ya
lo describe, referencia sus pasos en lugar de repetirlos y describe solo lo que
cubre esta funcionalidad. -->

1. La app pide los turnos libres con el JWT del usuario (T03 paso 1). La cadena de seguridad valida el token antes de todo.
2. El servicio sigue los pasos 3 a 9 de T03 y el diagrama de secuencia §2, con sus alternativos.
3. Lo que agrega esta spec: qué pasa si la integración con la cátedra no está habilitada al momento del request (RF-11, RF-12; D1), los límites de los reintentos (D2), la configuración (D3, D4, D8), las reglas de cálculo (D5 a D7) y que "no atiende ese día" se responde sin consultar a la cátedra (D9).

## Datos y reglas de negocio

<!-- Información que necesita la operación, campos obligatorios, validaciones,
límites y reglas como duplicados u orden de presentación. Describe significado
y comportamiento, sin diseñar tablas, DTO, DAO ni almacenamiento. -->

- De catálogo se usan `enabled` del profesional (no el de su categoría, D5) y, de cada horario semanal, `dayOfWeek`, `startTime`, `endTime`, `slotDurationMinutes` y `enabled`. Catálogo devuelve también lo deshabilitado, con `enabled` (C06).
- De la cátedra se usan, de cada ocupación, `startTime` y `status`; `CONFIRMED` y `HELD` cuentan igual como ocupado, aunque el `HELD` tenga un `expiresAt` ya pasado. Una lista vacía es válida.
- `dataAsOf` es un instante en UTC (contrato común §3); `date` y `startTime` son locales de atención, sin zona.
- La clave del caché es (`professionalId`, `date`). Una consulta en vivo exitosa siempre reemplaza la entrada anterior.
- Ningún dato del profesional ni de los turnos se guarda fuera del caché.

## Casos alternativos y de error

<!-- Adapta la tabla usando BACKEND_GUIDELINES.md. Cuando el caso de uso ya define
el flujo alternativo, referencia su ID en lugar de repetirlo. Añade escenarios
relevantes. Marca No aplica con su motivo cuando corresponda. Expresa resultados
observables (estado HTTP, código de error, estado final del recurso, evento
publicado), no mecanismos técnicos. -->

| Situación | Comportamiento esperado |
| --- | --- |
| Entrada inválida | T03 3.1: `400` `VALIDATION_ERROR` con `fieldErrors`, sin llamar a nadie (RF-02) |
| Sin sesión o sin permiso | Resuelto por la validación del JWT: `401` o `403` antes de llegar a T03 |
| Recurso inexistente o ajeno | Profesional inexistente: T03 4.2, `404` `PROFESSIONAL_NOT_FOUND` (RF-05). No hay recursos ajenos: los turnos libres no son de un usuario |
| Profesional deshabilitado | T03 4.4: `409` `PROFESSIONAL_DISABLED` (RF-08) |
| Catálogo rechaza el token reenviado | T03 4.1: `500` `INTERNAL_ERROR`, error registrado (RF-04) |
| Catálogo no disponible | T03 4.3: reintentos y `503` `CATALOG_UNAVAILABLE`, sin caché (RF-06) |
| Cátedra rechaza el JWT técnico | T03 5.1: login nuevo con X01 y una sola repetición (RF-10) |
| Integración en error de configuración | T03 5.2: `503` `SERVICE_DEGRADED` (RF-11, D1) |
| Cátedra no disponible o integración degradada | T03 5.3: reintentos o login a X01, y después caché o `503` `CENTRAL_UNAVAILABLE` (RF-12, RF-14, D1) |
| Timeout o espera excesiva | Cuenta como servicio no disponible; tiempos acotados por configuración (D2) |
| Operación o mensaje repetido | No aplica: la consulta no modifica nada; dos consultas iguales consultan dos veces y la segunda reemplaza el caché |
| Evento tardío o fuera de orden | No aplica: no hay eventos |
| Falla de persistencia | No aplica: no se persiste nada |
| Solicitudes simultáneas | Pueden leer y reemplazar la misma entrada del caché a la vez sin ver datos a medio actualizar |
| Reinicio con operaciones pendientes | No hay operaciones pendientes; el caché arranca vacío |
| Profesional sin horarios ese día | T03 4.5: `200` con `PROFESSIONAL_NOT_WORKING`, sin consultar a la cátedra (RF-18, D9) |
| Sin turnos libres | T03 8.1: `200` con `NO_FREE_SLOTS` (RF-19) |

**Puntos de la guía no aplicables y motivo:** idempotencia de mensajes, estados, transacciones, persistencia, paginación y tareas programadas: T03 es una lectura que no persiste ni consume mensajes.

## Restricciones del pedido

<!-- Condiciones ya impuestas: compatibilidad, límites de alcance, requisitos
de seguridad o rendimiento medibles, o una tecnología expresamente exigida.
Ejemplo: Respetar el contrato de Documentacion y el anexo de la cátedra puede ser
una restricción; el diseño de los adaptadores va en PLAN.md.
No conviertas una preferencia del agente en una restricción. -->

- Respetar el contrato de turnos §3.3, §4.1 y §4.2.3, el contrato común §2 y §3, y el C06 del contrato de catálogo, sin cambiarlos.
- Turnos no guarda copia del catálogo: lo consulta en cada operación (enunciado §3, contrato §4.1).
- El JWT técnico nunca sale hacia la app ni hacia catálogo; el del usuario se reenvía a catálogo sin modificarlo.
- La suite normal de tests (`./mvnw test`) no llama a la cátedra real ni al catálogo: los simula, con respuestas grabadas de la cátedra real y limpiadas antes de versionarse. La cátedra real se prueba en una suite E2E separada (`src/test/java/e2e/`, `./mvnw test -Pe2e`), que lee las credenciales del `.env` local y queda salteada si no están (decisión 010, por indicación del usuario del 2026-10-24).
- Los controllers obtienen la identidad y el token con la convención de la decisión 008.
- Aplicar la skill `hexagonal-vertical-slicing` con las excepciones autorizadas; la dependencia con `catedra` (antes `integration`) se decide en el PLAN (skill §9).
- No cambiar el comportamiento de T01, T02, X01 ni la validación del JWT.

## Criterios de aceptación

<!-- Resultados observables que permitan decidir si se cumple cada requisito.
Incluye los casos alternativos acordados: cada flujo alternativo cubierto debería
tener un criterio. No uses Funciona correctamente.
Repite el formato según sea necesario. -->

- **CA-01 · RF-02:** Cuando falta `professionalId` o `date`, `professionalId` no es numérico o no es un entero positivo dentro del rango de un `Long` (`0`, `-1`, `99999999999999999999`), `date` no es `yyyy-MM-dd` o es anterior a hoy, entonces responde `400` `VALIDATION_ERROR` con el campo en `fieldErrors`, y ni catálogo ni la cátedra reciben requests.
- **CA-02 · RF-03:** Dado un request válido, cuando turnos consulta a catálogo, entonces catálogo recibe `GET /api/professionals/{professionalId}` con el mismo `Authorization` que mandó la app.
- **CA-03 · RF-04:** Cuando catálogo responde `401` o `403`, entonces turnos responde `500` `INTERNAL_ERROR`, registra el error y la cátedra no recibe requests.
- **CA-04 · RF-05:** Cuando catálogo responde `404`, entonces turnos responde `404` `PROFESSIONAL_NOT_FOUND` y la cátedra no recibe requests.
- **CA-05 · RF-06:** Cuando catálogo responde `503`, demora más que el timeout o rechaza la conexión en todos los intentos, entonces hay tantos intentos como el máximo configurado, con esperas crecientes, y turnos responde `503` `CATALOG_UNAVAILABLE` sin llamar a la cátedra; si un intento posterior responde `200`, la consulta sigue normalmente.
- **CA-06 · RF-07:** Cuando catálogo responde `400` o `500`, entonces turnos responde `500` `INTERNAL_ERROR`.
- **CA-07 · RF-08:** Dado un profesional con `enabled` en falso, entonces turnos responde `409` `PROFESSIONAL_DISABLED` y la cátedra no recibe requests. Dado un profesional habilitado de una categoría deshabilitada, entonces la consulta sigue normalmente.
- **CA-08 · RF-09, RF-13:** Dado un profesional habilitado, cuando turnos consulta a la cátedra, entonces la cátedra recibe `GET /api/appointment-occupancies` con `professionalId`, `from` y `to` iguales a la fecha y `Authorization: Bearer <JWT técnico vigente>`, y el caché queda con esas ocupaciones y su `dataAsOf`.
- **CA-09 · RF-10:** Cuando la cátedra responde `401` y X01 consigue un JWT nuevo, entonces la consulta se repite una sola vez con el JWT nuevo; si vuelve a responder `401`, no se repite de nuevo.
- **CA-10 · RF-11:** Cuando la cátedra responde `403` `STUDENT_INTEGRATION_NOT_FOUND`, o la integración está en error de configuración, entonces turnos responde `503` `SERVICE_DEGRADED` sin detalles.
- **CA-11 · RF-12, RF-14:** Dada una entrada vigente en el caché, cuando la cátedra no responde en ningún intento, o la integración está degradada y X01 no consigue sesión, entonces turnos responde `200` con los turnos calculados con el caché, `stale: true` y el `dataAsOf` guardado.
- **CA-12 · RF-14:** Dada una entrada que supera la antigüedad máxima, o ninguna entrada, cuando la cátedra no responde, entonces turnos responde `503` `CENTRAL_UNAVAILABLE` y la entrada vencida ya no existe.
- **CA-13 · RF-15:** Dado un horario habilitado de 09:00 a 12:00 cada 30 minutos para el día de la fecha, entonces los turnos posibles son 09:00, 09:30, 10:00, 10:30, 11:00 y 11:30; un horario de 09:00 a 10:15 cada 30 minutos genera 09:00 y 09:30; los horarios deshabilitados y los de otros días no generan turnos.
- **CA-22 · RF-15:** Dados dos horarios habilitados del mismo día, de 09:00 a 12:00 cada 30 minutos y de 11:00 a 13:00 cada 60 minutos, entonces 11:00 aparece una sola vez, con 30 minutos de duración, y 12:00 aparece con 60 minutos.
- **CA-14 · RF-15:** Dada la fecha de hoy, cuando ya pasó la hora de inicio de algunos turnos en la zona de atención, entonces esos turnos no se devuelven.
- **CA-15 · RF-16:** Dadas ocupaciones `CONFIRMED` a las `09:00:00` y `HELD` a las `09:30`, entonces esos dos turnos no se devuelven y el resto sí.
- **CA-16 · RF-17:** En una respuesta exitosa en vivo, los turnos vienen ordenados por hora de inicio, con `professionalId`, `date`, `startTime` en `HH:mm` y `durationMinutes`, y `emptyReason` `null`, `stale` `false` y `dataAsOf` igual al instante de la consulta.
- **CA-17 · RF-18:** Dado un profesional sin horarios habilitados el día de la fecha, entonces responde `200` con `slots` vacío y `emptyReason` `PROFESSIONAL_NOT_WORKING`, y la cátedra no recibe requests; la respuesta es la misma con la cátedra caída.
- **CA-18 · RF-19:** Dado que todos los turnos del día están ocupados o ya comenzaron, entonces responde `200` con `slots` vacío y `emptyReason` `NO_FREE_SLOTS`.
- **CA-19 · RF-20:** Cuando se cambian por configuración los intentos, esperas y timeouts, la antigüedad máxima del caché y la zona horaria, entonces el servicio usa los valores nuevos sin cambios de código; sin configurarlos, usa los valores por defecto de D2 a D4.
- **CA-20 · RF-21:** En todos los escenarios, cada reintento queda en el log con su número y su motivo, y la salida no contiene el JWT del usuario ni el técnico.
- **CA-21 · RF-01 a RF-19:** Con la aplicación completa, catálogo y cátedra simulados, un usuario que inició sesión pide turnos libres y recibe la respuesta esperada.
- **CA-23 · RF-20:** Dado que falta la URL de catálogo, o no es `http`/`https` válida, cuando el servicio arranca, entonces arranca, registra un warning que nombra la propiedad, T03 responde `503` `CATALOG_UNAVAILABLE` sin enviar ningún request, y T01 y T02 responden normalmente.
- **CA-25 · RF-12:** Cuando la cátedra responde una respuesta que no está prevista para las ocupaciones (por ejemplo `400`), entonces turnos registra el error y responde `500` `INTERNAL_ERROR`, aunque haya una entrada vigente en el caché.
- **CA-26 · RF-09, RF-16, RF-17:** Con la cátedra real (suite E2E) y catálogo simulado con un profesional que existe en la cátedra, cuando un usuario registrado inicia sesión y pide turnos libres, entonces recibe `200` con turnos calculados con las ocupaciones reales de esa fecha. Sin credenciales en el `.env`, el test queda salteado.
- **CA-24 · RF-22:** Cuando se consultan turnos, con éxito, desde el caché o con error, entonces la base de datos de turnos no cambia; lo único que cambia es el caché en memoria.

## Cómo se comprueba el comportamiento

<!-- Una fila por criterio: escenario y resultado que debemos comprobar.
La selección de tests, herramientas, comandos y evidencias se desarrolla en PLAN.md.
No marques los criterios como superados durante la especificación. -->

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | Requests sin cada parámetro, con `professionalId` `abc`, `0`, `-1` y `99999999999999999999`, `date` `20-10-2026` y ayer | `400` con el campo; catálogo y cátedra simulados sin requests |
| CA-02 | Catálogo simulado que guarda los requests | Ruta y `Authorization` iguales al del usuario |
| CA-03 | Catálogo simulado con `401` y con `403` | `500` `INTERNAL_ERROR`; log del error; cátedra sin requests |
| CA-04 | Catálogo simulado con `404` | `404` `PROFESSIONAL_NOT_FOUND` |
| CA-05 | Catálogo simulado con `503`, con demora y apagado; luego `503` y después `200` | Intentos igual al máximo y `503`; o éxito en el segundo intento |
| CA-06 | Catálogo simulado con `400` y con `500` | `500` `INTERNAL_ERROR` |
| CA-07 | Profesional con `enabled: false`; profesional habilitado con categoría deshabilitada | `409` y cátedra sin requests; consulta normal |
| CA-08 | Cátedra simulada que guarda los requests | Parámetros y JWT técnico correctos; caché actualizado |
| CA-09 | Cátedra simulada con `401` y después `200`; con `401` dos veces | Un login nuevo y una repetición; nunca una segunda |
| CA-10 | Cátedra con `403` `STUDENT_INTEGRATION_NOT_FOUND`; sesión en error de configuración | `503` `SERVICE_DEGRADED` |
| CA-11 | Una consulta exitosa y después la cátedra caída; y con la sesión degradada | `200` con `stale: true` y el `dataAsOf` de la primera |
| CA-12 | Reloj controlado más allá de la antigüedad máxima; y sin entrada | `503` `CENTRAL_UNAVAILABLE`; entrada eliminada |
| CA-13 | Horarios de ejemplo, uno que no cierra exacto, uno deshabilitado y uno de otro día | Exactamente los turnos indicados |
| CA-22 | Horarios superpuestos de 09:00–12:00 cada 30 min y 11:00–13:00 cada 60 min | `11:00` una vez con 30 min; `12:00` con 60 min |
| CA-14 | Fecha de hoy con un reloj controlado a las 10:10 en la zona de atención | Sin los turnos de 09:00 a 10:00 |
| CA-15 | Ocupaciones `CONFIRMED` `09:00:00` y `HELD` `09:30` | Esos dos turnos ausentes |
| CA-16 | Respuesta en vivo | Formato del contrato §3.3 y orden por hora |
| CA-17 | Profesional sin horarios ese día, con la cátedra simulada respondiendo y apagada | `200`, `PROFESSIONAL_NOT_WORKING`; cátedra sin requests |
| CA-18 | Todos los turnos ocupados | `200`, `NO_FREE_SLOTS` |
| CA-19 | Arrancar sin configurar nada y con valores distintos de intentos, timeouts, antigüedad y zona | Por defecto los de D2 a D4; si no, los configurados |
| CA-20 | Revisar la salida de los escenarios de reintento | Intentos registrados; sin tokens |
| CA-21 | Registrar, iniciar sesión y pedir turnos con la aplicación completa | Respuesta según CA-16 |
| CA-23 | Arrancar sin URL de catálogo y con una URL entre comillas; pedir turnos, registrar e iniciar sesión | Arranca; warning con la propiedad; `503` `CATALOG_UNAVAILABLE` sin requests; T01 y T02 normales |
| CA-25 | Cátedra simulada con `400`, con y sin entrada en el caché | `500` `INTERNAL_ERROR`; error en el log; caché sin usar |
| CA-26 | Suite E2E: `./mvnw test -Pe2e` con y sin credenciales en el `.env` | `200` con ocupaciones reales; sin credenciales, salteado |
| CA-24 | Con la aplicación completa, comparar el contenido de la base antes y después de consultas exitosas, desde el caché y con error | Sin cambios en la base |

## Decisiones pendientes

<!-- Al resolverlas, actualiza las secciones afectadas. Escribe Ninguna cuando
no queden pendientes funcionales ni restricciones por decidir. -->

Ninguna.

Decisiones tomadas (2026-10-24):

- **D1 · Integración no habilitada al momento del request (RF-11, RF-12):** en error de configuración, `503` `SERVICE_DEGRADED`, como en 5.2. Degradada, se pide a X01 un login nuevo (respetando su intervalo mínimo) y, si no se consigue sesión, se trata como "la cátedra no responde" (5.3): caché si hay, o `503` `CENTRAL_UNAVAILABLE`. Así cada código conserva su significado y el caché sirve cuando la cátedra está caída. Implica que `integration` informe por qué no hay sesión (se resuelve en el PLAN). Alternativa descartada: `503` `SERVICE_DEGRADED` para cualquier integración no habilitada, sin caché.
- **D2 · Tiempo máximo de un request (RF-06, RF-12, RF-20):** valores propios para las llamadas de un usuario, distintos de los de X01, configurables por variables de entorno documentadas en `.env.example`, para ajustarlos cuando se pruebe con los servicios reales. Por defecto, para catálogo y para las ocupaciones de la cátedra por separado: 3 intentos, esperas de 0,5 s y 1 s, timeouts de 2 s para conectar y 3 s para leer. Peor caso: unos 11 s por servicio. Alternativa descartada: reusar los valores de X01 (5 intentos, hasta 8 s de espera y 10 s de timeout), con más de un minuto de espera posible.
- **D3 · Antigüedad máxima del caché (RF-14):** 60 minutos por defecto, configurable por variable de entorno igual que los valores de D2. Cubre cortes largos de la cátedra; el riesgo de mostrar como libre un turno ya ocupado lo acota T04, que valida en vivo al bloquear. Alternativas descartadas: 5 y 15 minutos.
- **D4 · Zona horaria de atención (RF-02, RF-15):** `America/Argentina/Mendoza` por defecto, configurable por variable de entorno. Es la zona del proyecto y de la cátedra, sin horario de verano. Alternativa descartada: la zona del servidor, que en un servidor en UTC haría que "hoy" cambie a las 21:00.
- **D5 · Categoría deshabilitada (RF-08):** no se tiene en cuenta; solo cuentan el `enabled` del profesional y el de cada horario. Lo dicen el dominio de catálogo (regla 4, `enabled` independientes) y T03 4.4, y coincide con la cátedra, que rechaza un hold por el profesional y no por la categoría. Alternativa descartada: tratar la categoría deshabilitada como profesional deshabilitado, una regla que ningún documento define.
- **D6 · Turnos repetidos entre dos horarios del mismo día (RF-15):** cada hora de inicio aparece una sola vez; si las duraciones difieren, se toma la del horario que empieza antes. Para la cátedra un turno es profesional, fecha y hora de inicio, así que dos iguales son el mismo. Alternativa descartada: mostrar los dos.
- **D7 · Rango válido de `professionalId` (RF-02):** entero positivo dentro del rango de un `Long`; si no, `400` `VALIDATION_ERROR` sin llamar a catálogo. Los ids los asigna la cátedra y son positivos, así que otro valor no puede existir. Alternativa descartada: aceptar cualquier entero y dejar que catálogo responda `404`, una llamada que no puede tener éxito.
- **D8 · URL de catálogo faltante o inválida (RF-20):** el servicio arranca con un warning que nombra la propiedad y T03 responde `503` `CATALOG_UNAVAILABLE` sin llamar; una URL que no es `http`/`https` válida se trata igual. Es lo mismo que hace X01 con la cátedra, y T01 y T02 no dependen de catálogo. Alternativa descartada: que el servicio no arranque, que dejaría sin funcionar también el registro y el login.
- **Pruebas con la cátedra real (restricción del pedido, CA-26, desde el PLAN):** la suite normal sigue simulando la cátedra, ahora con respuestas reales grabadas y limpiadas; la cátedra real se prueba en una suite E2E separada, opcional y salteada sin credenciales (decisión 010). Cambia la regla de X01 "los tests nunca usan la cátedra real", que pasa a valer solo para la suite normal. Indicación del usuario del 2026-10-24.
- **D10 · Respuesta de la cátedra no prevista para las ocupaciones (RF-12, desde el PLAN):** se registra y responde `500` `INTERNAL_ERROR`, sin usar el caché, como cualquier error no previsto (contrato §3). Una respuesta así indica un problema propio o del contrato, no una cátedra caída. Alternativa descartada: tratarla como cátedra no disponible (caché o `503`). Decidida por el usuario el 2026-10-24 durante el PLAN; la SPEC aprobada se actualizó con su indicación.
- **D9 · "No atiende ese día" antes de consultar a la cátedra (RF-18):** se evalúa apenas llega la respuesta de catálogo. Si ese día no hay horarios, las ocupaciones no cambian la respuesta: se evita una llamada inútil y la app recibe la respuesta correcta aunque la cátedra esté caída. Cambia el orden del caso de uso T03 y del diagrama §2: el antiguo 7.1 pasa a ser el alternativo 4.5, sin renumerar los pasos que citan el contrato y el dominio. Alternativa descartada: respetar el orden anterior, con un `503` en un caso que se puede responder bien. Con el OK del usuario se actualizaron el caso de uso T03 (alternativo 4.5 agregado, 7.1 borrado) y el diagrama de secuencia §2 (el bloque "No enabled schedule that day" antes de la consulta a la cátedra), en `documentacion` y en la copia de `turnos_service/docs`, que quedaron idénticas. Se revisó que ningún otro documento cite el 7.1 ni el orden anterior: el contrato §3.3, el dominio (regla 18), A05 y el contrato de catálogo no cambian.

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el alcance está acordado, las referencias apuntan a pasos y flujos
que existen, los flujos son coherentes, los puntos de backend relevantes están
cubiertos y cada requisito tiene criterios comprobables.
Resuelve las dudas y los marcadores pendientes. Mantén el diseño técnico en PLAN.md.
-->
