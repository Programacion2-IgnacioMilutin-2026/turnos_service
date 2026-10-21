# SPEC: X01 — Autenticar cuenta técnica y obtener configuración

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
| Caso de uso X01 (`docs/casos_de_uso/cuenta_tecnica_configuracion_usecases.md`) | Precondición, pasos 1 a 5, alternativos 2.1, 2.2 y 4.1, postcondición. La parte de la descripción "cada vez que la cátedra rechaza el JWT (`401`) en otro caso de uso", según D1 |
| Diagrama de secuencia (`docs/secuencias/cuenta_tecnica_configuracion_secuencia.md`) | §1 completo |
| Contrato de turnos (`docs/contratos/turnos_y_reservas_contrato.md`) | §4.2.1, regla del JWT técnico solo en memoria y del `401` (según D1), y §4.2.2 completo |
| Contrato común (`docs/contratos/contratos_comunes.md`) | §1.1: el JWT técnico es distinto del JWT del usuario, vive en memoria y no viaja a la app ni entre servicios |
| Anexo de la cátedra (`enunciado/INTEGRATION_REFERENCE-v2.md`) | §2 (identidades), §3 (valores de acceso externalizados), §4 (convenciones), §5.1 (estados de `provisioningStatus` y guarda segura del JWT), §5.2 (login técnico), §13 (errores) y §18.4 (reintentos limitados y observables) |

## Qué construimos y para quién

<!-- Qué necesidad cubre, qué actor la inicia (la app KMP, otro servicio, el
tiempo o la cátedra) y qué podrá hacer o recibir. Describe el objetivo en
lenguaje de negocio. Resume y referencia: no copies la descripción del caso de uso. -->

Al arrancar, el servicio de turnos inicia sesión con la cuenta técnica del proyecto ante la cátedra y obtiene el JWT técnico y la configuración de la integración. Lo inicia el tiempo (el arranque) y lo usan los casos de uso que hablan con la cátedra (T03 en adelante): sin X01 completado no pueden ejecutarse. Si la cátedra no responde o la cuenta está mal configurada, el servicio queda degradado en lugar de fallar en cada operación (X01, descripción y postcondición).

## Situación actual

<!-- Qué existe hoy y es relevante (código, datos, otras funcionalidades ya
implementadas), la limitación que queremos resolver y los comportamientos
existentes que deben conservarse. Si todavía no hay código para esto, indícalo.
No describas la arquitectura. -->

- Están implementados T01 y T02. Ninguna funcionalidad llama todavía a la cátedra, y no hay cliente HTTP hacia ella.
- La cuenta técnica ya existe (creada a mano por el usuario con `POST /api/student/register`) y sus datos están disponibles fuera del repositorio.
- No hay configuración de la cátedra en el servicio: ni URL base, ni credenciales.
- Debe conservarse el comportamiento de T01 y T02, que no dependen de la cátedra.

## Dentro del alcance

<!-- Requisitos concretos, con identificadores estables para vincularlos a
criterios, decisiones del plan y tareas. Cada requisito indica el paso o flujo
alternativo del que proviene. No agregues requisitos que los documentos de
referencia no tengan sin consultarlo. -->

- **RF-01** · X01 paso 1, precondición; anexo §3: la URL base de la API de la cátedra y el `login` y la `password` de la cuenta técnica se leen de la configuración externa; no están en el repositorio. Si falta alguno, o si la URL no es una URL `http` o `https` válida (por ejemplo, escrita entre comillas), el servicio arranca igual, registra un warning que nombra la propiedad y no intenta el login: la integración queda con error de configuración (D3, D8).
- **RF-02** · X01 paso 2; contrato §4.2.2: al arrancar, el servicio hace el login técnico (`POST /api/authenticate` con `username`, `password` y `rememberMe` en verdadero).
- **RF-03** · X01 pasos 3 a 5; contrato §4.2.2: con `200` y `provisioningStatus` `PROVISIONED`, guarda en memoria el `id_token` y los datos de la integración que usa turnos (`groupId`, `kafkaBootstrapServers`, `kafkaConsumerGroupId`, `kafkaAppointmentActionsTopic`, `kafkaAppointmentPhoneTopic`) y queda con la integración habilitada. Ignora los campos `redis*` y `kafkaCatalogTopic`.
- **RF-04** · X01 alternativo 4.1: con `200` y otro estado (`PENDING`, `FAILED`, `REVOKED` o desconocido), no habilita la integración y reintenta con límite.
- **RF-05** · X01 alternativo 2.1; contrato §4.2.2: con `401` en el login, o `403`/`404` `STUDENT_INTEGRATION_NOT_FOUND`, registra un error de configuración, no reintenta y queda sin integración habilitada.
- **RF-06** · X01 alternativo 2.2; contrato §4.2.2; anexo §18.4: ante timeout, corte de conexión o `503` (y `500`, contrato §4.2.1), reintenta un número limitado de veces con espera creciente; cada intento queda registrado; mientras tanto el servicio queda degradado.
- **RF-07** · X01 postcondición y alternativo 2.2: mientras la integración no está habilitada, el servicio sigue atendiendo lo que no depende de la cátedra (T01 y T02) y ofrece a los demás casos de uso el estado de la integración para que no la usen. El servicio arranca aunque la cátedra no responda o la cuenta esté mal configurada, y el login técnico no demora el arranque (D2).
- **RF-08** · X01 descripción; contrato §4.2.1 y §4.2.2: otro caso de uso puede pedir el login técnico en dos situaciones: cuando la cátedra rechaza el JWT guardado con un `401` en una operación, o cuando necesita la integración y está degradada (D4). Con la integración habilitada, los casos de uso usan el JWT guardado y no hay logins extra (D1).
- **RF-09** · contrato común §1.1, contrato §4.2.1, anexo §2 y §5.1: el JWT técnico y la `password` nunca aparecen en logs, en respuestas a la app ni en el repositorio; el JWT técnico solo vive en memoria.
- **RF-10** · X01 alternativo 2.1, D1: con error de configuración (RF-05 o configuración faltante, RF-01), no se envía ningún login más hasta reiniciar el servicio; los pedidos de RF-08 se responden al instante como integración no habilitada, sin llamar a la cátedra.
- **RF-11** · X01 alternativo 2.2 y 4.1, D1, D4, P7: todo pedido de RF-08, con la integración habilitada o degradada, dispara un intento de login solo si pasó el intervalo mínimo configurable (60 segundos por defecto) desde el último; antes de eso se responde al instante con la sesión vigente, o como integración no habilitada si no la hay, sin llamar a la cátedra.
- **RF-12** · D1, P7; guía de backend (concurrencia): nunca hay dos logins técnicos a la vez; los pedidos que llegan mientras hay uno en curso esperan su resultado. Si el JWT que rechazó la cátedra ya no es el vigente, porque otro pedido lo renovó, se devuelve el vigente sin un login nuevo.
- **RF-13** · X01 alternativo 2.2, D8: un error inesperado durante un intento de login cuenta como un intento fallido por cátedra no disponible; la sesión nunca queda detenida en el estado de conexión inicial.

## Fuera de alcance

<!-- Exclusiones acordadas, no deducidas por el agente. Incluye las partes de los
casos de uso que se dejan para otra funcionalidad. Si no hay exclusiones
adicionales, indícalo tras revisarlo con la persona. -->

- Repetir una operación contra la cátedra después de renovar el login (contrato §4.2.1, "repite la operación una sola vez"): corresponde a cada caso de uso que llama a la cátedra, desde T03 (D1).
- La respuesta `503` `SERVICE_DEGRADED` que da cada operación cuando la integración no está habilitada: la define cada caso de uso (T03 en adelante).
- Conectarse a Kafka con la configuración obtenida: corresponde a T06, T07 y T08. Lo mismo el re-login ante una falla de credenciales de Kafka (T06 5.2).
- Consultar la integración con `GET /api/student/integration` (anexo §5.3): el login ya devuelve la integración.
- Registrar la cuenta técnica (anexo §5.1): ya se hizo, a mano, una sola vez.
- X01 en el servicio de catálogo: lo hace catálogo por su cuenta.

## Flujo de la operación

<!-- Cómo se inicia (request, evento o tarea programada), qué verifica y qué
resultado produce, incluidas las alternativas relevantes. Si el caso de uso ya
lo describe, referencia sus pasos en lugar de repetirlos y describe solo lo que
cubre esta funcionalidad. -->

1. El servicio arranca y lee la configuración de la cátedra (RF-01).
2. Ejecuta los pasos 2 a 5 de X01 y el diagrama de secuencia §1 (RF-02 y RF-03).
3. Según la respuesta, aplica 2.1, 2.2 o 4.1 de X01 (RF-04 a RF-06). Hasta completar X01, el servicio está degradado (RF-07).
4. Más tarde, otro caso de uso puede pedir renovar el login tras un `401` de la cátedra, y se repite este flujo desde el paso 2 (RF-08, según D1).

## Datos y reglas de negocio

<!-- Información que necesita la operación, campos obligatorios, validaciones,
límites y reglas como duplicados u orden de presentación. Describe significado
y comportamiento, sin diseñar tablas, DTO, DAO ni almacenamiento. -->

- Request del login técnico: `username` (1 a 254 caracteres), `password` (4 a 100) y `rememberMe` en verdadero (contrato §4.2.2). Las credenciales configuradas se envían tal cual.
- Datos que guarda turnos de la respuesta: `id_token` y los cinco campos de la integración de RF-03. Todo en memoria: al reiniciar el servicio, se vuelve a hacer el login.
- La integración está habilitada solo si el último login respondió `200` con `PROVISIONED`. Cualquier otro resultado la deja no habilitada.
- Un login nuevo exitoso reemplaza el JWT y la integración anteriores.
- Reintentos (RF-04 y RF-06): número máximo de intentos y espera creciente configurables, con valores por defecto definidos en el PLAN. Al agotarlos, el servicio queda degradado y vuelve a intentar el login recién cuando un caso de uso lo necesita, sin reintentos periódicos en segundo plano (D4).
- Los logs registran el resultado de cada intento (éxito con `groupId` y estado, o el motivo del fallo) sin el JWT ni la `password` (RF-09; anexo §18.4).

## Casos alternativos y de error

<!-- Adapta la tabla usando BACKEND_GUIDELINES.md. Cuando el caso de uso ya define
el flujo alternativo, referencia su ID en lugar de repetirlo. Añade escenarios
relevantes. Marca No aplica con su motivo cuando corresponda. Expresa resultados
observables (estado HTTP, código de error, estado final del recurso, evento
publicado), no mecanismos técnicos. -->

| Situación | Comportamiento esperado |
| --- | --- |
| Entrada inválida | No aplica como request: X01 no tiene endpoint. Configuración faltante: arranca, warning con las propiedades faltantes, sin login (RF-01) |
| Sin sesión o sin permiso | Credenciales rechazadas (`401`) o integración inexistente (`403`/`404` `STUDENT_INTEGRATION_NOT_FOUND`): X01 2.1, error de configuración registrado, sin reintentos, integración no habilitada (RF-05) |
| Recurso inexistente o ajeno | Ver la fila anterior (`404` `STUDENT_INTEGRATION_NOT_FOUND`) |
| Dependencia no disponible (cátedra, catálogo, Redis, Kafka) | Cátedra con `503`, `500` o sin conexión: X01 2.2, reintentos limitados con espera creciente; servicio degradado (RF-06, RF-07) |
| Timeout o espera excesiva | Igual que la cátedra no disponible: cuenta como intento fallido y se reintenta con límite (RF-06) |
| Operación o mensaje repetido | Pedidos simultáneos de login producen uno solo (RF-12). Con error de configuración no se repite el login (RF-10); degradada, como máximo un intento por intervalo (RF-11) |
| Evento tardío o fuera de orden | No aplica: no hay eventos |
| Falla de persistencia | No aplica: X01 no persiste nada (todo en memoria) |
| Estado de provisión distinto de `PROVISIONED` | X01 4.1: integración no habilitada, reintento con límite (RF-04) |
| Solicitudes simultáneas | Ver "Operación o mensaje repetido" |
| Reinicio con operaciones pendientes | Al reiniciar, el JWT y la configuración se pierden y X01 se ejecuta de nuevo |
| Reintentos agotados | Degradado; nuevo intento solo cuando un caso de uso lo necesita (D4) |

**Puntos de la guía no aplicables y motivo:** idempotencia de mensajes, estados de procesos, transacciones, persistencia y paginación: X01 no persiste datos ni consume mensajes.

## Restricciones del pedido

<!-- Condiciones ya impuestas: compatibilidad, límites de alcance, requisitos
de seguridad o rendimiento medibles, o una tecnología expresamente exigida.
Ejemplo: Respetar el contrato de Documentacion y el anexo de la cátedra puede ser
una restricción; el diseño de los adaptadores va en PLAN.md.
No conviertas una preferencia del agente en una restricción. -->

- Respetar el contrato §4.2.2 y el anexo §5.2: ruta, request, `rememberMe` en verdadero y lectura de `id_token` e `integration`.
- La URL de la cátedra, el `login` y la `password` se externalizan (anexo §3); nada de eso se versiona.
- El JWT técnico solo vive en memoria y nunca llega a la app ni a catálogo (contrato común §1.1, contrato §4.2.1).
- Los tests automáticos no llaman a la cátedra real ni usan las credenciales reales: simulan la cátedra según el anexo. La integración real se comprueba con una prueba manual, con las credenciales del `.env` local (acordado con el usuario).
- Aplicar la skill `hexagonal-vertical-slicing` con las excepciones ya autorizadas.
- No cambiar el comportamiento de T01 y T02.

## Criterios de aceptación

<!-- Resultados observables que permitan decidir si se cumple cada requisito.
Incluye los casos alternativos acordados: cada flujo alternativo cubierto debería
tener un criterio. No uses Funciona correctamente.
Repite el formato según sea necesario. -->

- **CA-01 · RF-02, RF-03:** Dada una cátedra que responde `200` con `PROVISIONED`, cuando el servicio arranca, entonces envía `POST /api/authenticate` con las credenciales configuradas y `rememberMe` en verdadero, y queda con la integración habilitada, con el `id_token` y los cinco datos de la integración disponibles en memoria.
- **CA-02 · RF-04:** Dada una cátedra que responde `200` con `PENDING`, `FAILED` o `REVOKED`, cuando el servicio arranca, entonces la integración no queda habilitada y el login se reintenta hasta el límite configurado.
- **CA-03 · RF-05:** Dada una cátedra que responde `401` al login, o `403`/`404` con `STUDENT_INTEGRATION_NOT_FOUND`, cuando el servicio arranca, entonces registra un error de configuración, hace un solo intento y la integración no queda habilitada.
- **CA-04 · RF-06:** Dada una cátedra que responde `503`, `500`, demora más que el timeout o rechaza la conexión, cuando el servicio arranca, entonces reintenta hasta el límite con esperas crecientes, registra cada intento, y si en un intento posterior responde `200` `PROVISIONED`, la integración queda habilitada.
- **CA-05 · RF-07:** Mientras la integración no está habilitada, T01 y T02 siguen respondiendo normalmente.
- **CA-06 · RF-08, RF-12:** Dada una integración habilitada, cuando no hay `401`, entonces los casos de uso obtienen el JWT guardado sin ningún login nuevo; y cuando varios piden el login a la vez por un `401`, entonces se envía un solo login y todos obtienen el JWT nuevo.
- **CA-07 · RF-09:** En todos los escenarios de este documento, los logs no contienen el JWT técnico ni la `password`.
- **CA-08 · RF-01:** Dado que falta la URL, el `login` o la `password`, o que la URL no es `http`/`https` válida (entre comillas, sin protocolo o con otro protocolo), cuando el servicio arranca, entonces arranca, registra un warning que nombra la propiedad, no envía ningún login y la integración queda con error de configuración.
- **CA-09 · RF-02, RF-03:** Con la cátedra real y las credenciales del `.env` local, cuando el servicio arranca, entonces el log muestra el login exitoso con el `groupId` y `PROVISIONED`, sin el JWT.
- **CA-10 · RF-11, D4:** Dada una integración degradada tras agotar los reintentos, cuando un caso de uso pide el login antes del intervalo mínimo, entonces la cátedra no recibe ningún request; y cuando lo pide después del intervalo, entonces se envía un único login y, si responde `PROVISIONED`, la integración queda habilitada.
- **CA-11 · RF-10:** Dada una integración con error de configuración, cuando los casos de uso piden el login varias veces, entonces la cátedra no recibe ningún request y cada pedido responde al instante que la integración no está habilitada.
- **CA-12 · RF-12:** Dado que un pedido ya renovó el JWT, cuando otro pedido informa el rechazo del JWT anterior, entonces recibe el JWT vigente y la cátedra no recibe otro login.
- **CA-13 · RF-11:** Dada una integración habilitada cuyo JWT se renovó hace menos del intervalo mínimo, cuando llega otro pedido por un `401` con el JWT vigente, entonces no se envía un login y se responde al instante.
- **CA-14 · RF-13:** Dado un intento de login que falla con un error inesperado, cuando corre el login inicial o una renovación, entonces cuenta como intento fallido y la sesión termina habilitada o degradada, nunca en el estado de conexión inicial.

## Cómo se comprueba el comportamiento

<!-- Una fila por criterio: escenario y resultado que debemos comprobar.
La selección de tests, herramientas, comandos y evidencias se desarrolla en PLAN.md.
No marques los criterios como superados durante la especificación. -->

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | Cátedra simulada que responde `200` `PROVISIONED`; arrancar | Request con las credenciales de test y `rememberMe: true`; integración habilitada con los datos de la respuesta |
| CA-02 | Cátedra simulada con cada estado distinto de `PROVISIONED` | Integración no habilitada; cantidad de intentos igual al límite |
| CA-03 | Cátedra simulada con `401`, con `403` y con `404` `STUDENT_INTEGRATION_NOT_FOUND` | Un solo intento; error de configuración en el log; integración no habilitada |
| CA-04 | Cátedra simulada con `503`, `500`, demora y conexión rechazada; luego `200` `PROVISIONED` | Intentos con espera creciente hasta el límite; habilitada si un intento posterior tiene éxito |
| CA-05 | Cátedra simulada caída; registrar e iniciar sesión | `201` y `200` como en T01 y T02 |
| CA-06 | Integración habilitada; pedir el JWT varias veces; luego pedidos simultáneos de login por `401` | Sin requests a la cátedra en lo primero; un único login en lo segundo, y todos reciben el JWT nuevo |
| CA-07 | Revisar la salida de todos los escenarios automáticos | Sin el `id_token` ni la `password` |
| CA-08 | Arrancar sin cada una de las tres propiedades, y con URLs inválidas | Arranca; warning con la propiedad; cátedra simulada sin requests; T01 y T02 funcionan |
| CA-09 | Prueba manual con la cátedra real | Log con `groupId` y `PROVISIONED`, sin el JWT |
| CA-10 | Integración degradada; pedidos antes y después del intervalo, con un reloj controlado en el test | Ningún request antes; un único login después |
| CA-11 | Integración con error de configuración; varios pedidos de login | La cátedra simulada no recibe requests; respuestas inmediatas |
| CA-12 | Renovar con el JWT A (pasa a B); luego pedir con el JWT A | Devuelve B; un solo login en total |
| CA-13 | Renovar; antes del intervalo, pedir con el JWT nuevo, con un reloj controlado | Ningún login nuevo; respuesta inmediata |
| CA-14 | Cliente que lanza un error inesperado, en el login inicial y en una renovación | Intentos contados como fallidos; estado final `DEGRADED` o `ENABLED` |

## Decisiones pendientes

<!-- Al resolverlas, actualiza las secciones afectadas. Escribe Ninguna cuando
no queden pendientes funcionales ni restricciones por decidir. -->

Ninguna.

Decisiones tomadas (2026-10-21):

- **D1 · Cuándo se pide el login y cómo se limita:** con la integración habilitada se usa el JWT guardado; el login se repite solo ante un `401` en una operación o cuando la integración está degradada y un caso de uso la necesita. Un `401` en el propio login, o `STUDENT_INTEGRATION_NOT_FOUND`, o la configuración faltante, es un error de configuración: no se reintenta hasta reiniciar. Degradada, como máximo un intento cada 60 segundos (configurable). Nunca dos logins simultáneos. Repetir la operación que falló queda en cada caso de uso, desde T03.
- **D2 · Arranque con la cátedra caída o mal configurada:** el servicio arranca igual y atiende T01 y T02; el login técnico no demora el arranque.
- **D3 · Configuración faltante:** el servicio arranca igual y registra obligatoriamente un warning que nombra las propiedades faltantes; no intenta el login y la integración queda con error de configuración.
- **D4 · Reintentos agotados:** queda degradado y vuelve a intentar el login recién cuando un caso de uso lo necesita, sin reintentos periódicos en segundo plano.
- **X01 2.1, "se informa al profesor":** el servicio registra un error de configuración claro; avisar al profesor es una acción manual del alumno.

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el alcance está acordado, las referencias apuntan a pasos y flujos
que existen, los flujos son coherentes, los puntos de backend relevantes están
cubiertos y cada requisito tiene criterios comprobables.
Resuelve las dudas y los marcadores pendientes. Mantén el diseño técnico en PLAN.md.
-->
- **Exclusiones:** confirmadas por el usuario. El control de los logins (RF-08, RF-10 a RF-12) es de X01; cada caso de uso solo repite su propia operación una vez después de un login nuevo.
- **P7 (desde el PLAN) · Intervalo mínimo también con la integración habilitada:** todo login a pedido respeta el intervalo mínimo, y si el JWT rechazado ya no es el vigente se devuelve el vigente sin login. Evita un login por request si la cátedra rechazara cada JWT nuevo. Aprobado por el usuario el 2026-10-21; la SPEC queda aprobada con este cambio.
- **D8 (desde la prueba manual, TASK-06) · URL inválida y errores inesperados:** una URL de la cátedra que no es `http`/`https` válida se trata como configuración faltante (warning, sin login); cualquier error inesperado en un intento cuenta como cátedra no disponible, para que la sesión nunca quede detenida en el estado de conexión. Surgió al probar con valores entre comillas en el `.env`: el login lanzaba una excepción no prevista y la sesión quedaba trabada. Aprobado por el usuario el 2026-10-21.
