# SPEC: T02 — Iniciar sesión

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
| Caso de uso T02 (`docs/casos_de_uso/turnos_y_reservas_usecases.md`) | Precondición, pasos 1 a 6 y alternativos 2.1, 3.1, 4.1 y 5.1 |
| Diagrama de secuencia (`docs/secuencias/turnos_y_reservas_secuencia.md`) | §1, bloque "T02 — Log in" |
| Contrato de turnos (`docs/contratos/turnos_y_reservas_contrato.md`) | §3 (reglas comunes de las operaciones) y §3.2 `POST /api/authenticate` |
| Contrato común (`docs/contratos/contratos_comunes.md`) | §1.1 a §1.4 (emisión, formato, claves y endpoints públicos del JWT del usuario) y §2 (formato de errores) |
| Modelo de dominio (`docs/turnos_y_reservas_dominio.md`) | `User`: `login`, `passwordHash`, `activated`, `authorities`; regla 1 (login sin distinguir mayúsculas) |
| Enunciado / anexo de la cátedra | Enunciado: usuarios compatibles con JHipster y emisión del JWT al autenticar. El anexo no aplica: T02 no usa la cátedra |

## Qué construimos y para quién

<!-- Qué necesidad cubre, qué actor la inicia (la app KMP, otro servicio, el
tiempo o la cátedra) y qué podrá hacer o recibir. Describe el objetivo en
lenguaje de negocio. Resume y referencia: no copies la descripción del caso de uso. -->

La app KMP necesita que un usuario registrado (T01) pueda iniciar sesión con su `login` y su `password` y reciba un JWT propio del servicio de turnos. La app usa ese JWT durante la sesión para las operaciones protegidas de turnos y de catálogo (T02, descripción; A02).

## Situación actual

<!-- Qué existe hoy y es relevante (código, datos, otras funcionalidades ya
implementadas), la limitación que queremos resolver y los comportamientos
existentes que deben conservarse. Si todavía no hay código para esto, indícalo.
No describas la arquitectura. -->

- T01 está implementado: los usuarios se guardan con el `login` y el `email` en minúscula, la contraseña solo como hash, activos y con `ROLE_USER`.
- No existe el inicio de sesión ni la emisión de JWT. No hay claves RSA configuradas.
- `POST /api/register` es la única ruta pública; cualquier otra responde `403` porque todavía no hay forma de autenticarse.
- Los errores de la API ya salen en `application/problem+json` con `code` (validación, errores de negocio y errores inesperados).
- Debe conservarse el comportamiento de T01.

## Dentro del alcance

<!-- Requisitos concretos, con identificadores estables para vincularlos a
criterios, decisiones del plan y tareas. Cada requisito indica el paso o flujo
alternativo del que proviene. No agregues requisitos que los documentos de
referencia no tengan sin consultarlo. -->

- **RF-01** · T02 paso 1, contrato §3.2, contrato común §1.4: `POST /api/authenticate` es público, recibe `username` y `password`, ignora `rememberMe` y cualquier otro campo, e ignora el encabezado `Authorization`, sea válido o no.
- **RF-02** · T02 paso 2 y alternativo 2.1, contrato §3.2: si falta `username` o `password`, responde `400` `VALIDATION_ERROR` con el detalle por campo, sin consultar al usuario.
- **RF-03** · T02 paso 3 y alternativo 3.1, dominio regla 1: busca al usuario por `login` sin distinguir mayúsculas; si no existe, responde `401` `INVALID_CREDENTIALS`.
- **RF-04** · T02 paso 4 y alternativo 4.1: verifica la contraseña contra el hash guardado; si no coincide, responde `401` `INVALID_CREDENTIALS` con el mismo cuerpo que RF-03.
- **RF-05** · T02 precondición, dominio §4 (`activated`): una cuenta no activa no inicia sesión; responde `401` `INVALID_CREDENTIALS` con el mismo cuerpo que RF-03, sin revelar el estado de la cuenta (D2).
- **RF-06** · T02 paso 5, contrato común §1.2 y §1.3: emite un JWT `RS256` firmado con la clave privada de turnos, con exactamente los claims `sub` (id del usuario como texto), `auth` (roles separados por un espacio), `iat` y `exp`, y una vigencia definida por configuración.
- **RF-07** · T02 paso 6, contrato §3.2: responde `200` con `{"id_token": "<jwt>"}`.
- **RF-08** · T02 alternativo 5.1: si falla la generación o la firma del JWT, registra el error y responde `500` `INTERNAL_ERROR` sin detalles internos.
- **RF-09** · contrato §3 (reglas comunes): si falla la base de datos, responde `500` `INTERNAL_ERROR`.
- **RF-10** · contrato común §1.2 y §1.3: la clave privada y la vigencia llegan por configuración externa; la clave no se versiona en el repositorio. Si la clave falta o es inválida, el servicio no arranca y registra un error de configuración claro (D3). La vigencia es configurable y vale 24 horas (86 400 segundos) si no se configura (D4).
- **RF-11** · contrato común §1.3, guía de backend (privacidad): la contraseña, el hash, el token y la clave privada no aparecen en logs ni en respuestas de error.

## Fuera de alcance

<!-- Exclusiones acordadas, no deducidas por el agente. Incluye las partes de los
casos de uso que se dejan para otra funcionalidad. Si no hay exclusiones
adicionales, indícalo tras revisarlo con la persona. -->

- Validar el JWT en los endpoints protegidos (contrato común §1.4): se hace en la primera funcionalidad con un endpoint protegido (D1).
- Cerrar sesión en el backend: el cierre es local en la app (A03, contrato común §1.6) (D6).
- Renovación de tokens (refresh token), bloqueo por intentos fallidos y recuperación de contraseña: ningún documento los define.
- Exponer la clave pública por un endpoint: no se hace; el contrato común §1.3 la distribuye por configuración externa. Configurar la clave pública en turnos y en catálogo corresponde a la primera funcionalidad que valide el token en cada servicio (D7).

## Flujo de la operación

<!-- Cómo se inicia (request, evento o tarea programada), qué verifica y qué
resultado produce, incluidas las alternativas relevantes. Si el caso de uso ya
lo describe, referencia sus pasos en lugar de repetirlos y describe solo lo que
cubre esta funcionalidad. -->

1. La app envía `POST /api/authenticate` con `username` y `password` (T02 paso 1).
2. El servicio sigue los pasos 2 a 6 de T02 y el bloque "T02 — Log in" del diagrama de secuencia §1.
3. Lo que agrega esta spec al caso de uso: el `username` se compara sin distinguir mayúsculas (RF-03), la cuenta debe estar activa (RF-05) y el token tiene el formato del contrato común §1.2 (RF-06).

## Datos y reglas de negocio

<!-- Información que necesita la operación, campos obligatorios, validaciones,
límites y reglas como duplicados u orden de presentación. Describe significado
y comportamiento, sin diseñar tablas, DTO, DAO ni almacenamiento. -->

- `username` lleva el `login` del usuario (contrato §3.2). Se compara sin distinguir mayúsculas: `Maria.Gomez` inicia sesión como `maria.gomez`.
- Un campo falta si no viene, es `null` o es un texto vacío (D5). Un `username` de solo espacios no falta: no coincide con ningún usuario y responde `401`.
- No se validan largos ni formato en el inicio de sesión: un `username` o una `password` que no cumplen las reglas de T01 simplemente no coinciden con ningún usuario y responden `401`.
- `sub` es el `id` del usuario como texto, el mismo valor que `externalPatientId` (dominio regla 2). `auth` lista los roles del usuario.
- `exp` es `iat` más la vigencia configurada. `rememberMe` no la modifica.
- El inicio de sesión no modifica datos del usuario.
- Nunca se registran en logs ni se devuelven en errores la contraseña, el hash, el token ni la clave privada (RF-11).

## Casos alternativos y de error

<!-- Adapta la tabla usando BACKEND_GUIDELINES.md. Cuando el caso de uso ya define
el flujo alternativo, referencia su ID en lugar de repetirlo. Añade escenarios
relevantes. Marca No aplica con su motivo cuando corresponda. Expresa resultados
observables (estado HTTP, código de error, estado final del recurso, evento
publicado), no mecanismos técnicos. -->

| Situación | Comportamiento esperado |
| --- | --- |
| Entrada inválida | Falta `username` o `password`: T02 2.1, `400` `VALIDATION_ERROR` con `fieldErrors`. JSON mal formado: `400` `VALIDATION_ERROR` |
| Sin sesión o sin permiso | No aplica: el endpoint es público. Un encabezado `Authorization`, válido o no, se ignora (RF-01) |
| Recurso inexistente o ajeno | `login` inexistente: T02 3.1, `401` `INVALID_CREDENTIALS`, mismo cuerpo que una contraseña incorrecta (T02 4.1) |
| Cuenta no activa | `401` `INVALID_CREDENTIALS`, mismo cuerpo que un `login` inexistente (RF-05) |
| Dependencia no disponible (cátedra, catálogo, Redis, Kafka) | No aplica: T02 solo usa la base de turnos |
| Timeout o espera excesiva | No aplica: no hay llamadas a otros servicios |
| Operación o mensaje repetido | Cada inicio de sesión correcto emite un token nuevo; los anteriores siguen valiendo hasta su `exp` (contrato común §1.6) |
| Evento tardío o fuera de orden | No aplica: no hay eventos |
| Falla de persistencia | No se escriben datos. Si la base no responde al buscar al usuario: `500` `INTERNAL_ERROR` (RF-09) |
| Falla al firmar el token | T02 5.1: `500` `INTERNAL_ERROR`, error registrado, sin detalles (RF-08) |
| Solicitudes simultáneas | No aplica: no modifica estado; los inicios de sesión simultáneos son independientes |
| Reinicio con operaciones pendientes | No aplica: no hay operaciones pendientes |
| Configuración faltante (clave privada o vigencia) | Clave faltante o inválida: el servicio no arranca y registra el error. Vigencia sin configurar: 24 horas (RF-10) |

**Puntos de la guía no aplicables y motivo:** dependencias externas, idempotencia de mensajes, estados y transiciones, tareas programadas, paginación y concurrencia: T02 no llama a otros servicios, no consume eventos y no modifica datos.

## Restricciones del pedido

<!-- Condiciones ya impuestas: compatibilidad, límites de alcance, requisitos
de seguridad o rendimiento medibles, o una tecnología expresamente exigida.
Ejemplo: Respetar el contrato de Documentacion y el anexo de la cátedra puede ser
una restricción; el diseño de los adaptadores va en PLAN.md.
No conviertas una preferencia del agente en una restricción. -->

- Respetar el contrato §3.2 y el contrato común §1.2 a §1.4 y §2: ruta, nombres `username` e `id_token`, algoritmo `RS256`, claims exactos, par RSA de 2048 bits y formato de errores.
- Compatibilidad con el usuario de JHipster que exige el enunciado.
- La clave privada no se versiona ni se entrega a catálogo (contrato común §1.3).
- Aplicar la skill `hexagonal-vertical-slicing` con las excepciones ya autorizadas (`docs/decisiones/001` a `003` y el paquete `config`).
- No cambiar el comportamiento de T01.

## Criterios de aceptación

<!-- Resultados observables que permitan decidir si se cumple cada requisito.
Incluye los casos alternativos acordados: cada flujo alternativo cubierto debería
tener un criterio. No uses Funciona correctamente.
Repite el formato según sea necesario. -->

- **CA-01 · RF-06, RF-07:** Dado un usuario activo registrado con T01, cuando envía su `login` y su `password` correctos, entonces recibe `200` con `id_token`, y ese token usa `RS256`, se verifica con la clave pública de turnos y tiene exactamente `sub` igual a su `id`, `auth` igual a `ROLE_USER`, `iat`, y `exp` igual a `iat` más la vigencia configurada.
- **CA-02 · RF-03:** Dado el usuario `maria.gomez`, cuando envía `username` `Maria.Gomez` con su contraseña, entonces recibe `200` con un token cuyo `sub` es su `id`.
- **CA-03 · RF-02:** Cuando falta `username` o `password`, entonces recibe `400` `VALIDATION_ERROR` en `application/problem+json`, con el campo en `fieldErrors`, y no se consulta al usuario.
- **CA-04 · RF-03:** Cuando el `username` no corresponde a ningún usuario, entonces recibe `401` `INVALID_CREDENTIALS` en `application/problem+json`.
- **CA-05 · RF-04:** Cuando la contraseña no coincide, entonces recibe `401` con el mismo `status`, `title`, `code` y `detail` que en CA-04.
- **CA-06 · RF-05:** Dado un usuario no activo, cuando envía credenciales correctas, entonces recibe `401` con el mismo `status`, `title`, `code` y `detail` que en CA-04.
- **CA-07 · RF-01:** Cuando el body incluye `rememberMe`, entonces la vigencia del token es la configurada, igual que sin `rememberMe`.
- **CA-08 · RF-01:** Cuando el request trae un encabezado `Authorization` con un token inválido y credenciales correctas, entonces recibe `200` con un token nuevo.
- **CA-09 · RF-08:** Cuando falla la firma del token, entonces recibe `500` `INTERNAL_ERROR` sin detalles internos y el error queda registrado.
- **CA-10 · RF-09:** Cuando la base de datos no responde, entonces recibe `500` `INTERNAL_ERROR`.
- **CA-11 · RF-11:** La contraseña, el hash, el token y la clave privada no aparecen en los logs ni en las respuestas de error de los casos anteriores.
- **CA-12 · RF-10:** Dado que falta la clave privada o no es una clave RSA válida, cuando arranca el servicio, entonces no arranca y el log indica el error de configuración sin mostrar la clave. Dado que no se configura la vigencia, entonces `exp - iat` es 86 400.
- **CA-13 · RF-01 a RF-07:** Con la aplicación completa levantada, cuando un usuario se registra con T01 e inicia sesión con T02, entonces recibe un token válido según CA-01.

## Cómo se comprueba el comportamiento

<!-- Una fila por criterio: escenario y resultado que debemos comprobar.
La selección de tests, herramientas, comandos y evidencias se desarrolla en PLAN.md.
No marques los criterios como superados durante la especificación. -->

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | Registrar un usuario, iniciar sesión, decodificar el token y verificarlo con la clave pública | `200`; `alg` `RS256`; firma válida; claims exactos; `exp - iat` igual a la vigencia configurada |
| CA-02 | Iniciar sesión con el `login` en otras mayúsculas | `200`; `sub` igual al `id` del usuario |
| CA-03 | Enviar sin `username`, sin `password`, con `null` y con texto vacío | `400` `VALIDATION_ERROR` con el campo en `fieldErrors`; el usuario no se busca |
| CA-04 | Iniciar sesión con un `login` inexistente | `401` `INVALID_CREDENTIALS` |
| CA-05 | Iniciar sesión con contraseña incorrecta y comparar con CA-04 | Mismo `status`, `title`, `code` y `detail` |
| CA-06 | Desactivar un usuario y comparar su respuesta con CA-04 | `401` con el mismo cuerpo |
| CA-07 | Iniciar sesión con `rememberMe: true` | `exp - iat` igual a la vigencia configurada |
| CA-08 | Iniciar sesión con `Authorization: Bearer xxx` y credenciales correctas | `200` |
| CA-09 | Simular una falla de firma | `500` `INTERNAL_ERROR`; detalle genérico; error en el log |
| CA-10 | Simular la base caída | `500` `INTERNAL_ERROR` |
| CA-11 | Revisar los logs y los cuerpos de error de CA-03 a CA-10 | Sin contraseña, hash, token ni clave privada |
| CA-12 | Arrancar sin clave, con una clave inválida y sin vigencia configurada | No arranca y el log explica el motivo; sin vigencia, `exp - iat` = 86 400 |
| CA-13 | Aplicación completa con base real: registrar e iniciar sesión por HTTP | `200` con un token que cumple CA-01 |

## Decisiones pendientes

<!-- Al resolverlas, actualiza las secciones afectadas. Escribe Ninguna cuando
no queden pendientes funcionales ni restricciones por decidir. -->

Ninguna.

Decisiones tomadas (2026-10-20):

- **D1 · Validación del JWT en endpoints protegidos:** fuera de T02; se hace con la primera funcionalidad que tenga un endpoint protegido. Se hizo en `docs/sdd/validacion-jwt/` (2026-10-23), como funcionalidad propia antes de T03, por indicación del usuario.
- **D2 · Cuenta no activa:** `401` `INVALID_CREDENTIALS`, mismo cuerpo que un `login` inexistente.
- **D3 · Clave privada faltante o inválida:** el servicio no arranca y registra un error de configuración claro.
- **D4 · Vigencia del token:** configurable, 24 horas (86 400 segundos) por defecto.
- **D5 · Campo faltante:** ausente, `null` o texto vacío responde `400`; un `username` de solo espacios responde `401`.
- **D6 · Cierre de sesión:** se mantiene local en la app, como definen A03 y el contrato común §1.6. El backend no participa y no se cambia documentación.
- **D7 · Clave pública:** no se expone por endpoint. T02 solo configura la clave privada; la pública se configura en turnos y en catálogo cuando cada uno empiece a validar el token. Aclaración (2026-10-23, `validacion-jwt`, DA): en turnos la clave pública no se configura; se obtiene de la privada, igual que para firmar (decisión 004). Configurarla aparte queda solo para catálogo.
