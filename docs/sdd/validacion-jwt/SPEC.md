# SPEC: Validación del JWT en endpoints protegidos

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

Esta funcionalidad no tiene un caso de uso propio: cubre la parte común de seguridad de varios. Por indicación del usuario (2026-10-23) se hace fuera de T03, antes que él, con SDD, en esta carpeta sin id de caso de uso.

| Documento | Partes cubiertas |
| --- | --- |
| Contrato común (`docs/contratos/contratos_comunes.md`) | §1.1 (turnos valida el token en sus endpoints protegidos), §1.2 (`RS256`, `Authorization: Bearer`, claims), §1.3 (claves), §1.4 completo, §1.6 (token sin estado, sin revocación) y §2 (formato de errores) |
| Contrato de turnos (`docs/contratos/turnos_y_reservas_contrato.md`) | §1 (columna "Acceso"), §3 (regla común del `401` y el `403`), §4.1 (turnos reenvía a catálogo el JWT recibido, sin modificarlo: aquí solo se deja disponible) y §4.2.1 (`externalPatientId` es el `sub`) |
| Casos de uso (`docs/casos_de_uso/turnos_y_reservas_usecases.md`) | Paso 2 y alternativo 2.1 de T03, T04, T05, T06, T09, T10 y T11 (solo la validación del JWT; el resto de cada caso queda para su funcionalidad). Precondición de T01 y T02 (endpoints públicos) |
| Diagrama de secuencia (`docs/secuencias/turnos_y_reservas_secuencia.md`) | En §2 a §6, el paso "Validate user JWT" y su `break` con `401` o `403` |
| Modelo de dominio (`docs/turnos_y_reservas_dominio.md`) | `Authority` y regla 2 (`externalPatientId()` es el mismo valor que el `sub`) |
| Enunciado (`enunciado/PROJECT_STATEMENT-v1.md`) | §9: exigir y validar el JWT en las APIs protegidas (firma, vigencia y permisos), proteger lo que no es expresamente público, no confiar en identificadores de usuario enviados por el cliente y configurar CORS de acuerdo con los clientes permitidos |
| SPEC de T02 (`docs/sdd/t02-login/SPEC.md`) | D1 (la validación se hace con el primer endpoint protegido) y D7 (configuración de la clave pública) |
| Casos de uso de la app (`documentacion/uml/casos_de_uso/app_kmp_usecases.md`) | Solo como contexto: ante un `401` la app descarta el JWT y pide iniciar sesión, y ante un `403` informa y no cierra la sesión (por ejemplo, A05 5.2 y 5.3). No cambia nada de la app |

## Qué construimos y para quién

<!-- Qué necesidad cubre, qué actor la inicia (la app KMP, otro servicio, el
tiempo o la cátedra) y qué podrá hacer o recibir. Describe el objetivo en
lenguaje de negocio. Resume y referencia: no copies la descripción del caso de uso. -->

Los endpoints protegidos de turnos (T03 a T06 y T09 a T11) solo deben atender a quien presenta un JWT emitido por T02 y vigente. Esta funcionalidad agrega esa validación como pieza común, antes de que exista el primero de esos endpoints, y deja disponible para cada caso de uso la identidad del usuario (`sub`) y el token recibido, que T03 y T04 reenvían a catálogo (C06). La inicia la app KMP en cada request a un endpoint protegido. Además, deja definido qué navegadores pueden usar la API (CORS): ninguno, salvo que se configure, porque el único cliente es la app nativa.

## Situación actual

<!-- Qué existe hoy y es relevante (código, datos, otras funcionalidades ya
implementadas), la limitación que queremos resolver y los comportamientos
existentes que deben conservarse. Si todavía no hay código para esto, indícalo.
No describas la arquitectura. -->

- Están implementados T01, T02 y X01. La suite tiene 178 tests en verde (corrida del 2026-10-23).
- Todavía no existe ningún endpoint protegido. Cualquier ruta distinta de `POST /api/register` y `POST /api/authenticate` exige autenticación, pero no hay forma de autenticarse: hoy responde `403` sin cuerpo (observado en T02, TASK-05). El contrato pide `401` en ese caso.
- El servicio tiene la clave privada del JWT y obtiene de ella la pública para firmar (decisión 004). No hay una clave pública configurada aparte.
- Los errores de los controllers salen en `application/problem+json` con `code`; los rechazos de la capa de seguridad todavía no tienen formato.
- Los endpoints públicos ya ignoran el encabezado `Authorization`. Un test lo fija para `POST /api/authenticate` (T02, CA-08); no hay uno equivalente para `POST /api/register`.
- Debe conservarse el comportamiento de T01, T02 y X01.

## Dentro del alcance

<!-- Requisitos concretos, con identificadores estables para vincularlos a
criterios, decisiones del plan y tareas. Cada requisito indica el paso o flujo
alternativo del que proviene. No agregues requisitos que los documentos de
referencia no tengan sin consultarlo. -->

- **RF-01** · Contrato común §1.4 (tabla de endpoints), contrato de turnos §1, enunciado §9: toda ruta distinta de `POST /api/register` y `POST /api/authenticate` exige un JWT válido en `Authorization: Bearer <jwt>`, incluidas las rutas que todavía no existen.
- **RF-02** · Contrato común §1.4 (fila 1), T03 a T11 alternativo 2.1: sin el encabezado, con otro esquema distinto de `Bearer`, o con un token que no es un JWT bien formado, responde `401`.
- **RF-03** · Contrato común §1.2 y §1.4 (fila 2): si la firma no se verifica con la clave pública de turnos, o el token no usa `RS256`, responde `401`.
- **RF-04** · Contrato común §1.4 (fila 3): si el `exp` venció, responde `401`, sin tolerancia de reloj: un token vencido hace un segundo ya se rechaza (DD). Un token sin `exp` también responde `401`, porque valdría para siempre (DH).
- **RF-05** · Contrato común §1.2 y §1.4 (identidad desde `sub`): un token bien firmado sin `sub`, o con un `sub` que no es un número, responde `401`: sin `sub` válido no hay identidad (DF).
- **RF-06** · Contrato común §1.4 (tabla de endpoints: "token válido, cualquier rol") y fila 4: un token con `ROLE_USER` o con `ROLE_ADMIN` pasa la validación. Un token bien firmado cuyo `auth` falta, está vacío o no incluye ninguno de esos dos roles responde `403`, porque "cualquier rol" exige tener al menos uno (DE).
- **RF-07** · Contrato común §1.4 (último punto) y §2, contrato de turnos §3, T03 a T11 alternativo 2.1: el `401` y el `403` usan `application/problem+json` con `status`, `title` y `detail`, con la misma forma que los demás errores del servicio, sin `code` y sin datos del recurso ni del token. Todos los motivos de `401` dan el mismo cuerpo, así no se revela si el token venció, fue alterado o falta (DB).
- **RF-08** · Contrato común §1.4 (primer punto), T01 y T02: los endpoints públicos ignoran el encabezado `Authorization`, sea válido, inválido o vencido, y responden lo mismo que sin él.
- **RF-09** · Contrato común §1.4 (segundo punto) y §1.5, contrato de turnos §4.1 y §4.2.1: con un token válido, el caso de uso obtiene la identidad del usuario del `sub` y el token tal como llegó (para reenviarlo a catálogo). Nunca la toma de un parámetro, del cuerpo ni de otro encabezado.
- **RF-10** · Contrato común §1.3, SPEC de T02 D7, decisión 004: los tokens se validan con la clave pública que se obtiene de la clave privada ya configurada para firmar. No se agrega configuración: si la clave privada falta o no es válida, el servicio no arranca, como ya define T02 (D3) (DA).
- **RF-11** · Contrato común §1.6: la validación no guarda estado ni consulta la base: no registra tokens, no verifica si fueron revocados ni si el usuario del `sub` existe. Un token vale hasta su `exp`, también después de reiniciar el servicio, mientras no cambie el par de claves (DC).
- **RF-12** · Guía de backend (privacidad): el token recibido no aparece en logs ni en respuestas de error.
- **RF-13** · Enunciado §9 ("configurar CORS de acuerdo con los clientes permitidos"): los orígenes de navegador permitidos se leen de la configuración externa y, si no se configuran, no se permite ninguno, porque el único cliente es la app nativa (DG). Un preflight (`OPTIONS`) desde un origen no permitido se rechaza, sin encabezados que lo habiliten. Desde un origen permitido, el preflight se responde sin pedir el JWT y habilita los métodos de la API y el encabezado `Authorization`; los requests reales siguen las reglas RF-01 a RF-08, y sus respuestas, incluido el `401`, llevan los encabezados CORS para que el navegador pueda leerlas. Los requests sin encabezado `Origin`, como los de la app, no cambian.

## Fuera de alcance

<!-- Exclusiones acordadas, no deducidas por el agente. Incluye las partes de los
casos de uso que se dejan para otra funcionalidad. Si no hay exclusiones
adicionales, indícalo tras revisarlo con la persona. -->

Confirmadas por el usuario (2026-10-23):

- Los endpoints de T03 a T11 y el resto de sus pasos, incluido que cada usuario vea y modifique solo lo suyo: cada caso de uso lo aplica con el `sub` que deja disponible esta funcionalidad.
- Reenviar el token a catálogo y tratar su `401` o `403` (contrato de turnos §4.1): corresponde a T03 y T04.
- La validación del token en catálogo (C06 y C07): la hace `catalog_service`, cuando se desarrolle.
- Endpoints que exijan `ROLE_ADMIN`: turnos no tiene ninguno (contrato común §1.4).
- Revocación, renovación del token y cierre de sesión en el backend (contrato común §1.6; T02, D6).
- Rotación de las claves: es manual (contrato común §1.6).
- Qué hace cada caso de uso si el usuario del `sub` ya no existe (DC): lo define la SPEC del caso que necesite al usuario, por ejemplo T04 (asocia el proceso al usuario) o T05 (lee su nombre).
- CORS del servicio de catálogo: lo resuelve `catalog_service`, con el mismo criterio si corresponde. El CORS de turnos sí está dentro del alcance (RF-13).

## Flujo de la operación

<!-- Cómo se inicia (request, evento o tarea programada), qué verifica y qué
resultado produce, incluidas las alternativas relevantes. Si el caso de uso ya
lo describe, referencia sus pasos en lugar de repetirlos y describe solo lo que
cubre esta funcionalidad. -->

1. La app envía un request a un endpoint protegido con `Authorization: Bearer <jwt>` (paso 1 de T03 a T11).
2. Antes de que actúe el endpoint, el servicio aplica las verificaciones del contrato común §1.4, en su orden: token presente y bien formado, firma, `exp` y rol (paso 2 de T03 a T11; "Validate user JWT" en el diagrama de secuencia).
3. Si alguna falla, responde `401` o `403` sin datos y el endpoint no se ejecuta (alternativo 2.1).
4. Si todas pasan, el endpoint se ejecuta con la identidad del `sub` y el token recibido (RF-09).
5. En `POST /api/register` y `POST /api/authenticate` no se verifica nada y el encabezado se ignora (RF-08).
6. Si el request viene de un navegador (trae `Origin`), antes se aplica RF-13: el preflight de un origen no permitido se rechaza, y el de uno permitido se responde sin pedir el JWT.

## Datos y reglas de negocio

<!-- Información que necesita la operación, campos obligatorios, validaciones,
límites y reglas como duplicados u orden de presentación. Describe significado
y comportamiento, sin diseñar tablas, DTO, DAO ni almacenamiento. -->

- El token tiene el formato del contrato común §1.2: `RS256` y exactamente `sub`, `auth`, `iat` y `exp`.
- `sub` es el `id` del usuario como texto; es el mismo valor que `externalPatientId` (dominio, regla 2).
- `auth` lista los roles separados por un espacio (`ROLE_USER`, `ROLE_ADMIN`).
- La validación del JWT va antes que la de los parámetros: un request sin token válido y con parámetros inválidos responde `401`, no `400` (en el diagrama de secuencia, "Validate user JWT" está antes que "Validate …" de los parámetros).
- No se guarda nada: ni tokens ni intentos rechazados (RF-11).

## Casos alternativos y de error

<!-- Adapta la tabla usando BACKEND_GUIDELINES.md. Cuando el caso de uso ya define
el flujo alternativo, referencia su ID en lugar de repetirlo. Añade escenarios
relevantes. Marca No aplica con su motivo cuando corresponda. Expresa resultados
observables (estado HTTP, código de error, estado final del recurso, evento
publicado), no mecanismos técnicos. -->

| Situación | Comportamiento esperado |
| --- | --- |
| Entrada inválida | Sin encabezado, con otro esquema, con `Bearer` vacío o con algo que no es un JWT: `401` (RF-02). Token sin `sub` o con un `sub` no numérico: `401` (RF-05) |
| Sin sesión o sin permiso | Firma inválida, otro algoritmo o `exp` vencido, sin tolerancia: `401` (RF-03, RF-04). Sin ningún rol conocido: `403` (RF-06) |
| Recurso inexistente o ajeno | Ruta inexistente sin token válido: `401`; con token válido: `404`. Recursos ajenos: los resuelve cada caso de uso con el `sub` (fuera de alcance) |
| Token válido de un usuario que ya no existe | La validación lo deja pasar: no consulta la base (RF-11, DC). El caso de uso que necesite al usuario lo resuelve en su SPEC |
| Request desde un navegador | Origen no permitido (o ninguno configurado): preflight rechazado. Origen permitido: preflight sin JWT y respuestas con encabezados CORS (RF-13) |
| Endpoint público con `Authorization` | Se ignora, sea válido, inválido o vencido (RF-08) |
| Dependencia no disponible (cátedra, catálogo, Redis, Kafka) | No aplica: la validación usa solo la clave pública, sin llamadas externas |
| Timeout o espera excesiva | No aplica: no hay llamadas externas |
| Operación o mensaje repetido | No aplica: validar no modifica nada; el mismo token se puede usar en muchos requests hasta su `exp` |
| Evento tardío o fuera de orden | No aplica: no hay eventos |
| Falla de persistencia | No aplica: no se persiste nada |
| Solicitudes simultáneas | No aplica: cada request se valida de forma independiente |
| Reinicio con operaciones pendientes | Los tokens emitidos antes del reinicio siguen valiendo hasta su `exp` si no cambió el par de claves; con un par nuevo, `401` (RF-11; contrato común §1.6) |
| Configuración faltante | Sin clave privada válida el servicio no arranca, como hoy (T02, D3); la clave pública no requiere configuración (RF-10). Sin orígenes CORS configurados, el servicio arranca y no permite ningún navegador (RF-13) |

**Puntos de la guía no aplicables y motivo:** dependencias externas, idempotencia, transacciones, estados, tareas programadas, persistencia y paginación: la validación no llama a otros sistemas, no guarda datos ni consume mensajes.

## Restricciones del pedido

<!-- Condiciones ya impuestas: compatibilidad, límites de alcance, requisitos
de seguridad o rendimiento medibles, o una tecnología expresamente exigida.
Ejemplo: Respetar el contrato de Documentacion y el anexo de la cátedra puede ser
una restricción; el diseño de los adaptadores va en PLAN.md.
No conviertas una preferencia del agente en una restricción. -->

- Respetar el contrato común §1 y §2 sin cambiarlo.
- No cambiar el comportamiento de T01, T02 ni X01; los tokens que ya emite T02 deben validarse sin cambios en su formato.
- La clave privada sigue sin versionarse y sin salir de turnos (contrato común §1.3).
- Los tests no leen el `.env` local y generan sus propias claves en cada corrida.
- Aplicar la skill `hexagonal-vertical-slicing` con las excepciones ya autorizadas; dónde vive la validación se decide en el PLAN.

## Criterios de aceptación

<!-- Resultados observables que permitan decidir si se cumple cada requisito.
Incluye los casos alternativos acordados: cada flujo alternativo cubierto debería
tener un criterio. No uses Funciona correctamente.
Repite el formato según sea necesario. -->

- **CA-01 · RF-01, RF-02:** Dado un endpoint protegido, cuando llega un request sin `Authorization`, entonces responde `401` en `application/problem+json` y el endpoint no se ejecuta.
- **CA-02 · RF-02:** Cuando el encabezado es `Basic …`, `Bearer` sin token o `Bearer xxx`, entonces responde `401` y el endpoint no se ejecuta.
- **CA-03 · RF-03:** Cuando el token está firmado con otra clave RSA, usa otro algoritmo (por ejemplo `HS256` o sin firma) o fue alterado después de firmarse, entonces responde `401`.
- **CA-04 · RF-04:** Cuando el `exp` del token ya pasó, entonces responde `401`; cuando todavía no pasó, el request llega al endpoint. Un token vencido hace un segundo ya responde `401`, igual que un token bien firmado sin `exp`.
- **CA-05 · RF-05:** Cuando el token está bien firmado y vigente pero no tiene `sub`, o su `sub` no es un número, entonces responde `401` y el endpoint no se ejecuta.
- **CA-06 · RF-06:** Dado un token emitido por T02 para un usuario con `ROLE_USER`, y otro con `ROLE_ADMIN`, cuando llaman a un endpoint protegido, entonces ambos llegan al endpoint. Cuando el token está bien firmado y vigente pero no tiene `auth`, lo tiene vacío o solo tiene roles desconocidos, entonces responde `403` y el endpoint no se ejecuta.
- **CA-07 · RF-07:** Los `401` de CA-01 a CA-05 tienen `Content-Type` `application/problem+json`, `status` 401, `title` y `detail`, no tienen `code` ni datos del recurso o del token, y su cuerpo es idéntico en todos los casos. Los `403` de CA-06 cumplen lo mismo con `status` 403.
- **CA-08 · RF-08:** Cuando `POST /api/register` y `POST /api/authenticate` reciben `Authorization` con un token válido, uno inválido y uno vencido, entonces responden lo mismo que sin el encabezado (por ejemplo `201`, `200`, `400` o `401` `INVALID_CREDENTIALS` según el cuerpo).
- **CA-09 · RF-09:** Dado un token válido con `sub` `42`, cuando llega a un endpoint protegido, entonces el endpoint obtiene `42` como identidad y el mismo texto de token que llegó; un id distinto en un parámetro o en el cuerpo no cambia la identidad.
- **CA-10 · RF-10:** Dado el servicio arrancado solo con su clave privada, sin ninguna propiedad de clave pública, cuando llega un token emitido por T02, entonces se valida y llega al endpoint.
- **CA-11 · RF-11:** Dado un token válido, cuando el servicio se reinicia con el mismo par de claves, entonces el token sigue llegando al endpoint; con un par nuevo, responde `401`.
- **CA-12 · RF-12:** En todos los escenarios anteriores, los logs y los cuerpos de error no contienen el token recibido.
- **CA-13 · RF-01, RF-06, RF-09:** Con la aplicación completa, cuando un usuario se registra (T01), inicia sesión (T02) y llama a un endpoint protegido con ese token, entonces llega al endpoint con su `id` como identidad; sin el token, `401`.
- **CA-14 · RF-11:** Dado un token válido cuyo `sub` no corresponde a ningún usuario de la base, cuando llama a un endpoint protegido, entonces llega al endpoint con ese `sub`, sin que la validación consulte la base.
- **CA-15 · RF-13:** Sin orígenes configurados, cuando llega un preflight con cualquier `Origin`, a un endpoint protegido o a uno público, entonces se rechaza y la respuesta no tiene `Access-Control-Allow-Origin`.
- **CA-16 · RF-13:** Con un origen configurado, cuando llega un preflight desde ese origen a un endpoint protegido, sin token, entonces se acepta con `Access-Control-Allow-Origin` igual al origen y `Authorization` entre los encabezados permitidos; el request real sin token responde `401` con esos encabezados, y con un token válido llega al endpoint. Un preflight desde otro origen se rechaza.
- **CA-17 · RF-13:** Los requests sin encabezado `Origin` se comportan igual con y sin orígenes configurados (CA-01 a CA-14).

## Cómo se comprueba el comportamiento

<!-- Una fila por criterio: escenario y resultado que debemos comprobar.
La selección de tests, herramientas, comandos y evidencias se desarrolla en PLAN.md.
No marques los criterios como superados durante la especificación. -->

Todavía no hay endpoints protegidos reales: los criterios se comprueban con un endpoint protegido que existe solo en los tests y devuelve la identidad recibida.

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | Request sin `Authorization` al endpoint de prueba | `401` `problem+json`; el endpoint no se ejecuta |
| CA-02 | `Basic abc`, `Bearer` vacío y `Bearer xxx` | `401` en los tres |
| CA-03 | Token firmado con otro par RSA, token `HS256`, token sin firma y token con el payload modificado | `401` en todos |
| CA-04 | Token vencido hace un segundo, token vigente y token sin `exp`, con un reloj controlado | `401`, llega al endpoint y `401` |
| CA-05 | Tokens firmados con la clave de test: sin `sub` y con `sub` `abc` | `401` en los dos |
| CA-06 | Tokens con `ROLE_USER` y con `ROLE_ADMIN`; tokens firmados con la clave de test sin `auth`, con `auth` vacío y con `auth` `ROLE_OTRO` | Los dos primeros llegan al endpoint; los otros, `403` |
| CA-07 | Comparar los cuerpos de CA-01 a CA-05, y los `403` de CA-06 | `problem+json` con `status` 401, `title` y `detail`; sin `code` ni datos; cuerpos idénticos |
| CA-08 | Registrar e iniciar sesión con encabezados válidos, inválidos y vencidos, y sin encabezado | Mismas respuestas con y sin encabezado |
| CA-09 | Token con `sub` `42` y un parámetro con otro id | El endpoint recibe `42` y el token exacto |
| CA-10 | Aplicación con solo la clave privada de test; token emitido por T02 | Llega al endpoint |
| CA-11 | Validar un token con dos contextos de la aplicación: el mismo par y un par nuevo | Pasa con el mismo par; `401` con el nuevo |
| CA-12 | Revisar la salida y los cuerpos de los escenarios anteriores | Sin el token |
| CA-13 | Aplicación completa: registrar, iniciar sesión y llamar al endpoint de prueba con y sin el token | Llega con su `id`; `401` sin token |
| CA-14 | Token firmado con la clave de test para un `sub` que no está en la base | Llega al endpoint con ese `sub` |
| CA-15 | Sin orígenes configurados: preflight con `Origin` a `/api/register` y al endpoint de prueba | Rechazado; sin `Access-Control-Allow-Origin` |
| CA-16 | Con un origen de prueba configurado: preflight, request sin token y request con token desde ese origen; preflight desde otro origen | Preflight aceptado con los encabezados; `401` con encabezados CORS; llega con token; el otro origen, rechazado |
| CA-17 | Repetir requests sin `Origin` con y sin orígenes configurados | Mismas respuestas |

## Decisiones pendientes

<!-- Al resolverlas, actualiza las secciones afectadas. Escribe Ninguna cuando
no queden pendientes funcionales ni restricciones por decidir. -->

Ninguna.

Decisiones tomadas (2026-10-23):

- **Aprobación:** el usuario aprobó la SPEC el 2026-10-23, junto con la lista de "Fuera de alcance" y la nota en D1 y D7 de la SPEC de T02, que ya se agregó.

- La validación del JWT va fuera de T03, en esta funcionalidad propia, con SDD completo. La carpeta `docs/sdd/validacion-jwt/` no lleva id de caso de uso: es una excepción a la convención `docs/sdd/<id>-<nombre-corto>/`, por indicación del usuario, porque la funcionalidad no es un caso de uso.
- **DA · Origen de la clave pública (RF-10):** se obtiene de la clave privada, como ya hace la firma (decisión 004). No hay configuración nueva y el par nunca puede quedar desparejo. Alternativa descartada: configurar un archivo propio con la pública. Implica aclarar D7 en la SPEC de T02 (ver la nota de arriba).
- **DB · Cuerpo del `401` y el `403` (RF-07):** `problem+json` con `status`, `title` y `detail`, sin `code`, con la forma de los demás errores del servicio y el mismo cuerpo para todos los motivos. Cumple el contrato común §1.4 sin cambiarlo. Alternativas descartadas: agregar un `code`, que exigía cambiar el contrato; un `detail` por motivo, que revela por qué se rechazó el token.
- **DC · Token válido de un usuario que ya no existe (RF-11):** la validación no consulta la base. El token es sin estado (contrato común §1.6), una consulta por request no aporta seguridad y ningún caso de uso borra usuarios. Si un caso de uso necesita al usuario, lo resuelve en su SPEC. Alternativa descartada: consultar la base en cada request y responder `401`.
- **DD · Tolerancia de reloj en `exp` (RF-04):** ninguna. Turnos firma y valida con el mismo reloj, así que no hay desfase que compensar. Alternativa descartada: los 60 segundos que Spring acepta por defecto.
- **DE · Token sin ningún rol conocido (RF-06):** `403`, porque "cualquier rol" exige tener al menos uno. Es el único caso en que el `403` aplica en turnos. Alternativa descartada: aceptarlo porque la firma es válida.
- **DF · Token sin `sub` o con un `sub` no numérico (RF-05):** `401`, porque sin `sub` válido no hay identidad. Alternativa descartada: dejarlo pasar y que falle el caso de uso.
- **DH · Token sin `exp` (RF-04, desde TASK-02):** se rechaza con `401`. Spring lo acepta por defecto y un token así no vencería nunca; T02 siempre emite `exp`, así que no afecta a los usuarios reales. Surgió durante la implementación y el usuario lo aprobó el 2026-10-23.
- **DG · CORS (enunciado §9):** se resuelve en esta SPEC (RF-13), porque se configura en el mismo lugar que la validación del JWT y el preflight nunca lleva token. Orígenes permitidos por configuración externa, ninguno por defecto. Se documenta en `docs/decisiones/007`, que se redacta junto con el PLAN. Alternativas descartadas: un cambio aparte antes de T03 (tocar dos veces la misma configuración) o dejarlo para la app KMP (queda sin documentar hasta el final).

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el alcance está acordado, las referencias apuntan a pasos y flujos
que existen, los flujos son coherentes, los puntos de backend relevantes están
cubiertos y cada requisito tiene criterios comprobables.
Resuelve las dudas y los marcadores pendientes. Mantén el diseño técnico en PLAN.md.
-->
