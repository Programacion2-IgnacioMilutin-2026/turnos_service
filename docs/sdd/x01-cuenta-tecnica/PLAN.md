# PLAN: X01 — Autenticar cuenta técnica y obtener configuración

> **Nota (2026-10-24):** en este documento, el slice `integration` (paquete `turnos.integration`, carpeta `integration/`) es el que desde T03 se llama `catedra` (decisión 009). El contenido se conserva como se escribió, porque registra lo que se hizo en su momento. Cuando aparece como campo JSON de la respuesta de la cátedra (`integration`), no es el paquete y no cambió.

**SPEC de referencia:** `docs/sdd/x01-cuenta-tecnica/SPEC.md`
**Versión de la spec revisada:** aprobada el 2026-10-21 (todavía sin commit)
**Estado:** Aprobado <!-- Borrador | En revisión | Aprobado -->

<!-- PARA LA PERSONA
Copia esta plantilla como PLAN.md junto a la SPEC.md aprobada.
Este documento define la solución técnica. Una vez revisado, el agente puede
derivar TASKS.md con tareas, dependencias y comprobaciones.
-->

<!-- PARA EL AGENTE
- Lee la SPEC.md aprobada, las instrucciones del proyecto, BACKEND_GUIDELINES.md,
  los documentos citados en Referencias de la SPEC y la skill
  hexagonal-vertical-slicing. Si falta un documento necesario o la spec no está
  aprobada, indícalo antes de avanzar.
- Inspecciona el repositorio. Referencia rutas verificadas y distingue las nuevas propuestas.
- Propón una solución proporcional al alcance y coherente con el proyecto.
  Reutiliza lo existente y justifica nuevas dependencias o cambios de arquitectura.
- Aplica la skill hexagonal-vertical-slicing tal como está: no la reinterpretes.
  Si la funcionalidad necesita algo que la skill no contempla, regístralo como
  decisión pendiente en lugar de improvisar una estructura.
- Distingue hechos, decisiones confirmadas y propuestas. Consulta las decisiones
  no resueltas; haz pocas preguntas por vez y actualiza el plan con las respuestas.
- Referencia los requisitos y criterios por su ID, y los contratos y casos de uso
  por archivo e ID, sin copiar su contenido.
- Si una decisión cambia el comportamiento o alcance, vuelve a la spec y solicita
  confirmación. No resuelvas una duda de producto mediante una suposición técnica.
- Conserva estos comentarios. No implementes durante la planificación.
- Solicita aprobación antes de marcar el plan como Aprobado. La autorización
  para implementar debe ser explícita; no se deduce del estado de los documentos.
-->

## Contexto técnico verificado

<!-- Qué existe hoy y cómo participa en la funcionalidad: código, slices,
migraciones y configuración ya implementados. Si todavía no hay código para
esto, indícalo. -->
Rutas relativas a `src/main/java/turnos/`.

| Componente o archivo existente | Ruta verificada | Responsabilidad y uso previsto |
| --- | --- | --- |
| Slice `user` | `user/` | No cambia. T01 y T02 deben seguir funcionando con cualquier estado de la integración (CA-05) |
| `config/` | `config/` | Paquete transversal autorizado. Recibe un bean `Clock` en UTC, para que las esperas y el intervalo se puedan controlar en los tests |
| `application.yaml`, `.env.example` | `src/main/resources/`, raíz | Suman la sección `turnos.catedra` y sus variables |
| `TestcontainersConfiguration` | `src/test/java/turnos/` | Los tests que levantan la aplicación arrancan sin configuración de la cátedra (warning y error de configuración, D3), salvo los que la simulan |
| Cliente HTTP | No existe todavía | Se usa `RestClient` con `JdkClientHttpRequestFactory` (`spring-web` 7.0.9, ya presente) para fijar los timeouts |
| Arranque | — | `ApplicationReadyEvent` (`spring-boot` 4.1.1) dispara el login sin demorar el arranque |
| Servidor para simular la cátedra en los tests | JDK 25, módulo `jdk.httpserver` | Servidor HTTP real que responde según el anexo, sin dependencias nuevas (P5) |

**Convenciones y slice de referencia:** el slice `user` y las excepciones ya autorizadas (`config`, `infrastructure/security`, `ApiExceptionHandler`). X01 no tiene endpoint: no hay capa `web`.

## Solución propuesta

<!-- Explica el enfoque y sus motivos. Describe las responsabilidades y el
recorrido de la solicitud, el evento o la tarea desde que entra hasta la
respuesta, la persistencia o el mensaje publicado. Referencia el diagrama de
secuencia del proyecto si ya lo describe; usa un diagrama nuevo solo si aporta
claridad. -->
Se crea el slice `integration`, dueño de la sesión técnica con la cátedra. Guarda en memoria el estado de la integración (decisión 005) y ofrece tres casos de uso:

| Caso de uso | Lo usa | Hace |
| --- | --- | --- |
| `StartTechnicalSessionUseCase` | El arranque | Login inicial con reintentos y espera creciente (RF-02 a RF-06) |
| `GetTechnicalSessionUseCase` | T03 en adelante | Devuelve el JWT y la integración si está habilitada; no llama a la cátedra (RF-07, CA-06) |
| `RenewTechnicalSessionUseCase` | T03 en adelante, tras un `401` o con la integración degradada | Un login a pedido, con las reglas de la decisión 005 (RF-08, RF-10 a RF-12) |

```
ApplicationReadyEvent
└─ TechnicalSessionStartup (hilo aparte: no demora el arranque, D2)
   └─ TechnicalSessionService.startTechnicalSession()
      └─ StartTechnicalSessionUseCaseImpl
         ├─ TechnicalLoginClient.login()  → Success | ConfigurationError | Unavailable
         │    = CatedraLoginClientAdapter: POST {base-url}/api/authenticate con RestClient
         ├─ Success con PROVISIONED       → sesión habilitada                       (RF-03)
         ├─ Success con otro estado       → reintento                               (RF-04)
         ├─ ConfigurationError            → error de configuración, sin reintentos   (RF-05)
         ├─ Unavailable                   → reintento con espera creciente           (RF-06)
         └─ TechnicalSessionRepository.save(...)  = adaptador en memoria
```

- **Qué decide cada capa.** El adaptador traduce la respuesta HTTP a uno de tres resultados del dominio: `200` es `Success`; `401`, `403`, `404` y cualquier otro `4xx` son `ConfigurationError`; `5xx`, timeout y conexión rechazada son `Unavailable`. El caso de uso decide qué hacer con cada resultado, sin conocer HTTP.
- **URL inválida y errores inesperados (D8, agregado en TASK-06).** Una `base-url` que no es `http`/`https` válida se trata como configuración faltante. Cualquier excepción no prevista es `Unavailable`, en el adaptador y en los dos casos de uso, para que la sesión nunca quede en `CONNECTING`.
- **Configuración faltante (D3).** Si falta `base-url`, `username` o `password`, el adaptador registra un warning al crearse, con las propiedades faltantes, y `login()` devuelve `ConfigurationError` sin enviar ningún request.
- **Un login a la vez (RF-12).** `RenewTechnicalSessionUseCaseImpl` serializa los logins con un candado. Quien llega mientras hay uno en curso espera, y al entrar ve que hubo un intento posterior a su pedido, así que devuelve ese resultado sin otro login.
- **Una sola sesión vigente, siempre leída en el momento (P3).** La sesión vive solo en memoria, en `TechnicalSessionRepository`, y cada login exitoso la reemplaza entera. Los casos de uso de T03 en adelante la piden con `GetTechnicalSessionUseCase` en cada operación y **nunca se guardan una copia en un campo propio**: así siempre usan la última integración obtenida.
- **Reglas en el dominio.** `TechnicalSession` sabe si está habilitada y si se puede intentar un login en un instante dado (`canAttemptLogin`). `TechnicalLoginPolicy` calcula la espera de cada reintento. Así las reglas de la decisión 005 se prueban sin Spring ni HTTP.

## Slices y componentes afectados

<!-- Aplica la skill hexagonal-vertical-slicing: un paquete por slice, con sus
capas domain, application e infrastructure. Identifica los slices afectados, sus
responsabilidades y la dirección de sus dependencias. Una funcionalidad puede
afectar más de un slice. Justifica cualquier slice nuevo o dependencia nueva
entre slices. -->

| Slice | Existe / nuevo | Responsabilidad y cambios | Dependencias con otros slices |
| --- | --- | --- | --- |
| `integration` (`turnos/integration/`) | Nuevo (P1) | Sesión técnica con la cátedra: login, estado y renovación | Ninguna. Desde T03, otros slices usarán sus puertos de entrada; esa dependencia se decide en T03 (skill §9) |
| `config` | Existente | Suma el bean `Clock` | No importa nada de los slices |

<!-- Distingue lo que se reutiliza, modifica o crea. Las rutas nuevas son
propuestas. Señala impacto sobre modelos, contratos o componentes compartidos. -->

| Slice y capa | Componente o ruta | Acción | Cambio y responsabilidad | Requisito relacionado |
| --- | --- | --- | --- | --- |
| integration / domain | `model/TechnicalIntegration` | Crear | `record` inmutable (P4): `groupId`, `kafkaBootstrapServers`, `kafkaConsumerGroupId`, `kafkaAppointmentActionsTopic`, `kafkaAppointmentPhoneTopic`, `provisioningStatus` | RF-03 |
| integration / domain | `model/ProvisioningStatus` | Crear | `PENDING`, `PROVISIONED`, `FAILED`, `REVOKED` y `UNKNOWN` para valores no previstos | RF-03, RF-04 |
| integration / domain | `model/IntegrationState` | Crear | `CONNECTING`, `ENABLED`, `DEGRADED`, `CONFIGURATION_ERROR` | RF-05, RF-07, RF-10, RF-11 |
| integration / domain | `model/TechnicalSession` | Crear | `record` inmutable (P4): estado, JWT, integración e instante del último intento; `isEnabled()` y `canAttemptLogin(now, minInterval)`; cada cambio crea una sesión nueva | RF-07, RF-10, RF-11 |
| integration / domain | `model/TechnicalLoginResult` | Crear | Interfaz sellada con `Success(token, integration)`, `ConfigurationError(reason)` y `Unavailable(reason)` (P4) | RF-03 a RF-06 |
| integration / domain | `model/TechnicalLoginPolicy` | Crear | Máximo de intentos, espera inicial, multiplicador, espera máxima e intervalo mínimo; `delayBeforeAttempt(n)` | RF-04, RF-06, RF-11 |
| integration / domain | `ports/in/StartTechnicalSessionUseCase`, `GetTechnicalSessionUseCase`, `RenewTechnicalSessionUseCase` | Crear | Ver Solución propuesta | RF-02 a RF-12 |
| integration / domain | `ports/out/TechnicalLoginClient` | Crear | `TechnicalLoginResult login()` | RF-02 |
| integration / domain | `ports/out/TechnicalSessionRepository` | Crear | `TechnicalSession get()` y `save(TechnicalSession)` | RF-03 |
| integration / application | `usecases/*UseCaseImpl` (3) | Crear | Reintentos, estados, intervalo y candado | RF-02 a RF-12 |
| integration / application | `service/TechnicalSessionService` | Crear | Expone los tres casos de uso | — |
| integration / infrastructure | `client/CatedraLoginClientAdapter` | Crear | `RestClient` con timeouts; traduce la respuesta a `TechnicalLoginResult`; warning por configuración faltante; nunca registra el JWT ni la `password` | RF-01, RF-02, RF-05, RF-06, RF-09 |
| integration / infrastructure | `client/dto/` (request, respuesta, integración) y `client/mapper/` | Crear | Formato del anexo §5.2; ignora `redis*` y `kafkaCatalogTopic` | RF-03 |
| integration / infrastructure | `client/CatedraProperties` | Crear | `turnos.catedra.*` (ver Dependencias y configuración) | RF-01 |
| integration / infrastructure | `persistence/adapter/InMemoryTechnicalSessionRepositoryAdapter` | Crear | Guarda la sesión en memoria (P3) | RF-03 |
| integration / infrastructure | `startup/TechnicalSessionStartup` | Crear | Escucha `ApplicationReadyEvent` y corre el login inicial en un hilo virtual (P2) | RF-02, RF-07 |
| config | `ClockConfig` | Crear | Bean `Clock.systemUTC()` | RF-11 |

## Datos y contratos

<!-- Completa solo lo aplicable. Si un punto no aplica, indica el motivo. -->
- **Operaciones, eventos y DTO de entrada y salida:** login técnico según el contrato §4.2.2 y el anexo §5.2. El contrato no cambia. X01 no expone operaciones a la app.
- **Identificadores, relaciones y restricciones:** ninguno persistido. `provisioningStatus` desconocido se trata como `UNKNOWN`, que equivale a "distinto de `PROVISIONED`" (RF-04).
- **Origen de los datos devueltos o publicados y transformaciones:** el JWT y la integración salen de la respuesta del login; el mapper conserva solo los cinco campos de RF-03.
- **Persistencia, consultas y migraciones:** no aplica. La sesión vive en memoria (RF-03); no hay migración.
- **Convivencia entre datos locales y remotos:** cada login exitoso reemplaza el JWT y la integración anteriores.
- **Compatibilidad y migraciones de datos existentes:** no aplica.

## Transacciones, concurrencia y errores

<!-- Cómo se implementan los comportamientos aprobados en la spec.
Referencia RF/CA y aplica las consideraciones relevantes de BACKEND_GUIDELINES.md. -->
- **Estados y transiciones:** `CONNECTING` al arrancar. Con `Success` y `PROVISIONED` pasa a `ENABLED`. Con `ConfigurationError` pasa a `CONFIGURATION_ERROR`, que es final hasta reiniciar. Con reintentos agotados pasa a `DEGRADED`. Desde `ENABLED` o `DEGRADED`, una renovación puede llevar a cualquiera de los tres (decisión 005).
- **Transacciones y consistencia:** no aplica. Se reemplaza la sesión completa de una vez, nunca un campo suelto.
- **Idempotencia y mensajes duplicados:** varios pedidos de renovación simultáneos producen un login (RF-12). Si el JWT rechazado ya no es el vigente, porque otro pedido lo renovó, se devuelve el vigente sin login (P7).
- **Concurrencia y tareas programadas:** sin tareas periódicas (D4). El login inicial corre en un hilo propio y comparte el candado con las renovaciones, así nunca coinciden dos logins.
- **Timeouts, reintentos y errores de dependencias:** timeouts de conexión y lectura configurables. Reintentos con espera creciente, con tope de espera y de intentos (P6). Cada intento se registra con su número y su motivo (anexo §18.4).
- **Seguridad y autorización:** no hay endpoints nuevos. El JWT técnico solo existe en memoria y solo sale por `GetTechnicalSessionUseCase`, hacia otros casos de uso del servicio.
- **Otras consideraciones de backend aplicables y su solución:**
  - Privacidad (RF-09): los logs dicen el `groupId`, el estado y el motivo, nunca el JWT ni la `password`. Los DTO no tienen `toString` con secretos.
  - El log de Spring de los requests HTTP en nivel `DEBUG` podría mostrar el cuerpo con la `password`; se deja en su nivel por defecto (ver Riesgos).
  - Ni el JWT técnico ni la integración se persisten (contrato §4.2.1, RF-09): al reiniciar se vuelve a hacer el login.

## Dependencias y configuración

<!-- Librerías, servicios externos, propiedades de configuración y secretos
afectados. Verifica compatibilidad con el proyecto y justifica las incorporaciones.
No agregues dependencias por defecto. Los valores sensibles y los parámetros
ajustables se externalizan. -->
- **Dependencias:** ninguna nueva. `RestClient` y `JdkClientHttpRequestFactory` (`spring-web` 7.0.9); servidor de test con `com.sun.net.httpserver` del JDK (P5).
- **Propiedades nuevas en `application.yaml`**, con vacío por defecto en las tres primeras para que la falta dé el warning de D3:
  - `turnos.catedra.base-url: ${TURNOS_CATEDRA_BASE_URL:}`
  - `turnos.catedra.username: ${TURNOS_CATEDRA_USERNAME:}`
  - `turnos.catedra.password: ${TURNOS_CATEDRA_PASSWORD:}`
  - `turnos.catedra.connect-timeout` (5 s) y `turnos.catedra.read-timeout` (10 s)
  - `turnos.catedra.login.max-attempts` (5), `initial-delay` (1 s), `multiplier` (2), `max-delay` (30 s) y `min-interval` (60 s) (P6)
- **Entorno de desarrollo:** `.env.example` documenta las tres variables sin valores. El usuario completa su `.env`; las credenciales nunca pasan por el chat ni por el repositorio.
- **Tests:** usan una cátedra simulada en un puerto aleatorio y credenciales de test (`test-login`, `test-password`); los reintentos usan esperas de milisegundos y el intervalo se controla con un `Clock` de test.

## Estrategia de validación

<!-- Una fila por criterio de la spec. Selecciona el método capaz de demostrarlo:
test unitario, de integración (base de datos, mensajería), de API o prueba manual.
No todos requieren todos los métodos. Identifica tests existentes y separa los
nuevos propuestos. Incluye regresiones relevantes. Una prueba con mocks no
demuestra persistencia, transacciones ni idempotencia. -->
| Criterio | Método y test existente o propuesto | Entorno y datos necesarios | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01 | Adaptador contra la cátedra simulada (`CatedraLoginClientAdapterTest`); integración `TechnicalSessionIntegrationTest` | Servidor JDK con la respuesta del anexo | Request con `username`, `password` y `rememberMe: true`; sesión `ENABLED` con los cinco datos |
| CA-02 | Unitario `StartTechnicalSessionUseCaseImplTest` con un cliente falso que devuelve cada estado | — | Intentos igual al máximo; sesión `DEGRADED` |
| CA-03 | Adaptador con `401`, `403` y `404`; unitario del caso de uso con `ConfigurationError` | Servidor JDK | `ConfigurationError`; un solo intento; `CONFIGURATION_ERROR` |
| CA-04 | Adaptador con `503`, `500`, demora mayor al timeout y puerto cerrado; unitario del caso de uso; unitario de `TechnicalLoginPolicy` | Servidor JDK; esperas de milisegundos | `Unavailable`; esperas 1, 2, 4, 8 s con tope de 30 s; éxito en un intento posterior → `ENABLED` |
| CA-05 | Integración: cátedra simulada caída; registrar e iniciar sesión por HTTP | App completa, Postgres de Testcontainers | `201` y `200` |
| CA-06 | Unitario de los casos de uso Get y Renew: sin pedidos no hay logins; 20 hilos piden renovar a la vez | Cliente falso que cuenta llamadas y demora la respuesta | 0 logins en lo primero; 1 login en lo segundo, todos reciben el JWT nuevo |
| CA-07 | Integración y adaptador con `OutputCaptureExtension` | — | La salida no contiene el JWT de test ni `test-password` |
| CA-08 | Integración sin `base-url`, sin `username` y sin `password` | App completa | Arranca; warning con la propiedad; la cátedra simulada no recibe requests |
| CA-09 | Manual con la cátedra real y el `.env` local | Credenciales reales, solo locales | Log con `groupId` y `PROVISIONED`, sin el JWT |
| CA-10 | Unitario de Renew en `DEGRADED` con un `Clock` controlado | — | 0 logins antes de 60 s; 1 login después |
| CA-11 | Unitario de Renew en `CONFIGURATION_ERROR` | — | 0 logins con varios pedidos |

**Comprobaciones de regresión:** los 105 tests actuales (T01 y T02 sin cambios) y los cinco chequeos de la skill.

**Comandos verificados para compilar y ejecutar tests:** `./mvnw test`, `./mvnw test -Dtest=NombreDeLaClase` y `./mvnw verify` (genera el reporte de JaCoCo), documentados en `CLAUDE.md`.

**Pruebas con infraestructura real o simulada:** cátedra simulada con el servidor HTTP del JDK en todos los tests automáticos; Postgres real con Testcontainers en los de integración; cátedra real solo en la prueba manual de CA-09.

**Limitaciones del entorno:** CA-09 depende de que la cátedra esté disponible y de las credenciales del usuario; si no se puede ejecutar, queda como no verificado.

<!-- Esta sección planifica la validación. Durante la implementación, registra
en TASKS.md o en el informe de validación acordado los resultados y evidencias
reales. Distingue pruebas ejecutadas, fallidas, no ejecutadas y bloqueadas.
Compilar o tener tests en verde no sustituye revisar los criterios de la spec. -->

## Orden de implementación

<!-- Etapas y dependencias principales. El desglose ejecutable se escribe en TASKS.md.
Incluye puntos de comprobación para avanzar con cambios pequeños. -->
1. **Dominio:** modelos, `TechnicalLoginPolicy` y puertos. Se comprueba con unitarios de `TechnicalSession` y `TechnicalLoginPolicy`.
2. **Login inicial:** `StartTechnicalSessionUseCaseImpl`, el repositorio en memoria y el service. Se comprueba con CA-02, CA-03 y CA-04 en unitarios.
3. **Renovación:** `GetTechnicalSessionUseCaseImpl` y `RenewTechnicalSessionUseCaseImpl` con el candado. Se comprueba con CA-06, CA-10 y CA-11.
4. **Cliente de la cátedra:** adaptador, DTO, mapper, propiedades y `Clock`. Se comprueba con los tests del adaptador contra el servidor JDK (CA-01, CA-03, CA-04, CA-07).
5. **Arranque y configuración:** `TechnicalSessionStartup`, `application.yaml` y `.env.example`. Se comprueba con la integración (CA-01, CA-05, CA-07, CA-08).
6. **Cierre:** prueba manual con la cátedra real (CA-09), regresión, chequeos de la skill y revisión de SonarQube en el PR.

## Riesgos y decisiones pendientes

<!-- Riesgos concretos de esta solución y cómo se resolverán, sin listas genéricas.
Escribe Ninguna en las decisiones pendientes cuando estén resueltas. -->
- **Riesgos y medidas acordadas:**
  - Que un log `DEBUG` de los requests HTTP exponga la `password` del cuerpo. Se mantiene el nivel por defecto y CA-07 revisa la salida.
  - Que un test de concurrencia pase por casualidad. El cliente falso demora la respuesta hasta que todos los hilos pidieron, así el solapamiento está garantizado.
  - Que los tests que levantan la aplicación sin cátedra ensucien la salida con warnings. Es esperado (D3) y no afecta los resultados.
  - Limitación conocida: si la cátedra cambia la integración por su lado, turnos la toma recién en el próximo login (un `401`, el intervalo en estado degradado o un reinicio). `GET /api/student/integration` quedó fuera de alcance.
- **Decisiones pendientes:** Ninguna.

- **Decisiones tomadas (2026-10-21):**
  - **P1 · Nombre del slice:** `integration`, con `TechnicalSession` y `TechnicalIntegration` como modelos.
  - **P2 · Paquetes de infraestructura nuevos:** autorizados `infrastructure/client` (cliente HTTP hacia la cátedra) e `infrastructure/startup` (disparador del arranque), como excepción a la skill, con su comentario en el código.
  - **P3 · Sesión en memoria como repositorio:** puerto `TechnicalSessionRepository` con un adaptador en memoria en `infrastructure/persistence/adapter`. Es el único lugar con la sesión vigente; no se persiste en la base.
  - **P4 · Tipos inmutables:** `TechnicalLoginResult` (interfaz sellada con tres `record`), `TechnicalSession` y `TechnicalIntegration` son `record`, en lugar de clases Lombok con setters: la sesión se lee en paralelo mientras un login la reemplaza, y así nunca se ve a medio actualizar. Excepción a la skill, comentada en el código.
  - **P5 · Cátedra simulada en los tests:** el servidor HTTP del JDK (`com.sun.net.httpserver`), sin dependencias nuevas.
  - **P6 · Valores por defecto:** 5 intentos con esperas de 1, 2, 4 y 8 s (tope de 30 s), intervalo mínimo de 60 s, timeouts de 5 s para conectar y 10 s para leer.
  - **P7 · Intervalo mínimo también con la integración habilitada:** aplicado en la SPEC (RF-11, RF-12, CA-12, CA-13), que el usuario volvió a aprobar.

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el plan cubre los requisitos, respeta las exclusiones, reutiliza
componentes verificados, respeta la skill hexagonal-vertical-slicing y permite
demostrar todos los criterios de aceptación.
Resuelve dudas y marcadores pendientes. Si la spec cambió, revisa su impacto.
Tras aprobar el plan, deriva TASKS.md con IDs, dependencias, referencias a RF/CA
y comprobaciones. No marques una tarea terminada sin realizar su validación;
si está bloqueada, registra el motivo.
-->
