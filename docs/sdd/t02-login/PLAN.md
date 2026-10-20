# PLAN: T02 — Iniciar sesión

**SPEC de referencia:** `docs/sdd/t02-login/SPEC.md`
**Versión de la spec revisada:** aprobada el 2026-10-20 (todavía sin commit)
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
| Modelo `User` y enum `Authority` | `user/domain/model/` | Se reutilizan: `id`, `login`, `passwordHash`, `activated`, `authorities` |
| Puerto `UserRepository` | `user/domain/ports/out/UserRepository.java` | Se reutiliza `findByLogin`; no cambia |
| Puerto `PasswordHasher` | `user/domain/ports/out/PasswordHasher.java` | Hoy solo `hash`; se agrega la comparación (decisión 001) |
| `Pbkdf2PasswordHasherAdapter` | `user/infrastructure/security/` | Implementa la comparación con el mismo `Pbkdf2PasswordEncoder` |
| `UserException` | `user/application/exception/` | Base de la nueva excepción de credenciales inválidas |
| `UserService` | `user/application/service/UserService.java` | Suma el método de inicio de sesión |
| `UserController`, `UserDtoMapper` | `user/infrastructure/web/` | Suman el endpoint y la conversión de la respuesta |
| `SecurityConfig` | `config/SecurityConfig.java` | Suma `POST /api/authenticate` como ruta pública |
| `ApiExceptionHandler` | `config/ApiExceptionHandler.java` | Se reutiliza tal cual: `VALIDATION_ERROR`, `problem+json` de los controllers e `INTERNAL_ERROR` |
| `TestcontainersConfiguration` | `src/test/java/turnos/` | Se le agrega la clave de test (ver Dependencias y configuración) |
| Migración `V1__create_users.sql` | `src/main/resources/db/migration/` | Sin cambios: T02 no escribe datos |
| Dependencias | `pom.xml` | Ya están `spring-security-oauth2-jose` 7.1.1 (`NimbusJwtEncoder`, `JwtClaimsSet`) y Nimbus JOSE 10.9.1. No hace falta agregar nada |

**Convenciones y slice de referencia:** el slice `user` de T01, con las excepciones a la skill ya autorizadas: paquete `config` (configuración transversal), `infrastructure/security` (decisión 001) y `ApiExceptionHandler` (decisión 003). Hash con PBKDF2 (decisión 002).

## Solución propuesta

<!-- Explica el enfoque y sus motivos. Describe las responsabilidades y el
recorrido de la solicitud, el evento o la tarea desde que entra hasta la
respuesta, la persistencia o el mensaje publicado. Referencia el diagrama de
secuencia del proyecto si ya lo describe; usa un diagrama nuevo solo si aporta
claridad. -->

Se sigue el mismo recorrido que T01 y el bloque "T02 — Log in" del diagrama de secuencia §1:

```
POST /api/authenticate {username, password}
└─ SecurityConfig: ruta pública, sin validar Authorization            (RF-01)
└─ UserController.authenticateUser + @Valid AuthenticateRequest        (RF-02)
   └─ UserService.authenticateUser(login, password)
      └─ AuthenticateUserUseCase = AuthenticateUserUseCaseImpl
         ├─ login a minúscula (Locale.ROOT)                              (RF-03)
         ├─ UserRepository.findByLogin               → vacío: Optional.empty
         ├─ user.isActivated()                       → falso: Optional.empty   (RF-05)
         ├─ PasswordHasher.matches(password, hash)   → falso: Optional.empty   (RF-04)
         └─ TokenIssuer.issue(user)                  → JWT firmado             (RF-06)
      └─ Optional vacío → UserInvalidCredentialsException
   └─ catch → ResponseStatusException 401 + code INVALID_CREDENTIALS      (RF-03, RF-04, RF-05)
   └─ 200 TokenResponse {id_token}                                        (RF-07)
```

- **Por qué `Optional`:** la skill define que un resultado que puede no existir es `Optional` en el puerto y en el caso de uso, y que el service lo convierte en excepción. "No hay token para estas credenciales" es exactamente eso. Los tres motivos (login inexistente, cuenta inactiva, contraseña incorrecta) terminan en el mismo `Optional.empty()`, así que no pueden producir respuestas distintas.
- **La firma del token es otro puerto de salida, `TokenIssuer`,** con el mismo patrón que `PasswordHasher` (decisión 001): el caso de uso no conoce JWT ni RSA. El adaptador usa `NimbusJwtEncoder` de Spring Security, que es lo que también usará la validación de T03.
- **Solo se configura la clave privada (D7).** El adaptador obtiene la clave pública de la privada (una clave privada RSA en PKCS#8 incluye el módulo y el exponente público), que `NimbusJwtEncoder` necesita para armar la clave de firma.
- **Arranque (D3):** el adaptador carga la clave al crearse. Si falta o no es RSA válida, lanza una excepción con un mensaje que nombra la propiedad y no la clave, y la aplicación no arranca.

## Slices y componentes afectados

<!-- Aplica la skill hexagonal-vertical-slicing: un paquete por slice, con sus
capas domain, application e infrastructure. Identifica los slices afectados, sus
responsabilidades y la dirección de sus dependencias. Una funcionalidad puede
afectar más de un slice. Justifica cualquier slice nuevo o dependencia nueva
entre slices. -->

| Slice | Existe / nuevo | Responsabilidad y cambios | Dependencias con otros slices |
| --- | --- | --- | --- |
| `user` (`turnos/user/`) | Existente | Suma el inicio de sesión: un caso de uso, un puerto de salida, un adaptador de firma y el endpoint | Ninguna |
| `config` (`turnos/config/`) | Existente (excepción autorizada) | `SecurityConfig` abre `/api/authenticate` | No importa nada de los slices |

<!-- Distingue lo que se reutiliza, modifica o crea. Las rutas nuevas son
propuestas. Señala impacto sobre modelos, contratos o componentes compartidos. -->

| Slice y capa | Componente o ruta | Acción | Cambio y responsabilidad | Requisito relacionado |
| --- | --- | --- | --- | --- |
| user / domain | `ports/in/AuthenticateUserUseCase` | Crear | `Optional<String> authenticateUser(String login, String password)` | RF-03 a RF-06 |
| user / domain | `ports/out/PasswordHasher` | Modificar | Agrega `boolean matches(String password, String passwordHash)` | RF-04 |
| user / domain | `ports/out/TokenIssuer` | Crear | `String issue(User user)`: el token firmado del usuario | RF-06 |
| user / application | `usecases/AuthenticateUserUseCaseImpl` | Crear | El recorrido de la Solución propuesta | RF-03 a RF-06 |
| user / application | `exception/UserInvalidCredentialsException` | Crear | Extiende `UserException`, con un mensaje fijo que no incluye el login | RF-03 a RF-05, RF-11 |
| user / application | `service/UserService` | Modificar | `String authenticateUser(String login, String password)`: `orElseThrow` con la excepción anterior | RF-03 a RF-05 |
| user / infrastructure | `security/Pbkdf2PasswordHasherAdapter` | Modificar | Implementa `matches` | RF-04 |
| user / infrastructure | `security/JwtTokenIssuerAdapter` | Crear | Carga la clave (con falla de arranque), arma los claims `sub`, `auth`, `iat`, `exp` y firma con `RS256` | RF-06, RF-08, RF-10 |
| user / infrastructure | `security/JwtProperties` | Crear | Propiedades `turnos.jwt.private-key-location` y `turnos.jwt.token-validity` (24 horas por defecto) | RF-10 (P1, P4) |
| user / infrastructure | `web/dto/AuthenticateRequest` | Crear | `username` y `password` con `@NotEmpty` (D5) | RF-01, RF-02 (P3) |
| user / infrastructure | `web/dto/TokenResponse` | Crear | Campo `id_token` | RF-07 (P3) |
| user / infrastructure | `web/mapper/UserDtoMapper` | Modificar | Agrega `toTokenResponse(String token)` | RF-07 |
| user / infrastructure | `web/controller/UserController` | Modificar | `@PostMapping("/authenticate")`; traduce la excepción a `401` con `code` `INVALID_CREDENTIALS` | RF-01 a RF-07 |
| config | `SecurityConfig` | Modificar | `POST /api/authenticate` con `permitAll` | RF-01 |
| — | `TurnosServiceApplication` | Modificar | `@ConfigurationPropertiesScan`, para registrar `JwtProperties` | RF-10 |

## Datos y contratos

<!-- Completa solo lo aplicable. Si un punto no aplica, indica el motivo. -->

- **Operaciones, eventos y DTO de entrada y salida:** contrato de turnos §3.2 (`POST /api/authenticate`, `username`, `password`, `id_token`, `400` `VALIDATION_ERROR`, `401` `INVALID_CREDENTIALS`) y contrato común §1.2 (formato del token). El contrato no cambia.
- **Identificadores, relaciones y restricciones:** `sub` es `user.getId()` como texto, igual que `externalPatientId()`. `auth` son los nombres de `authorities`, ordenados y separados por un espacio. `iat` es el instante de emisión y `exp` es `iat` más `token-validity`, en segundos.
- **Origen de los datos devueltos o publicados y transformaciones:** el usuario sale de la base con `findByLogin`. El token se arma en el adaptador; no se guarda.
- **Persistencia, consultas y migraciones:** no aplica. T02 no escribe datos ni necesita migraciones; la búsqueda por login ya existe.
- **Convivencia entre datos locales y remotos:** no aplica: no hay datos remotos.
- **Compatibilidad y migraciones de datos existentes:** no aplica. Los usuarios de T01 ya tienen el hash PBKDF2 que compara `matches`.

## Transacciones, concurrencia y errores

<!-- Cómo se implementan los comportamientos aprobados en la spec.
Referencia RF/CA y aplica las consideraciones relevantes de BACKEND_GUIDELINES.md. -->

- **Estados y transiciones:** no aplica.
- **Transacciones y consistencia:** no aplica: solo hay una lectura.
- **Idempotencia y mensajes duplicados:** no aplica: cada inicio de sesión correcto emite un token nuevo e independiente.
- **Concurrencia y tareas programadas:** no aplica.
- **Timeouts, reintentos y errores de dependencias:** sin dependencias externas. Una falla de la base (RF-09) o de la firma (`JwtEncodingException`, RF-08) no se atrapa en el slice, así que llega a `ApiExceptionHandler`, que registra el error y responde `500` `INTERNAL_ERROR` con un detalle genérico.
- **Seguridad y autorización:**
  - El endpoint es público.
  - Como no hay validación de JWT configurada (D1), el encabezado `Authorization` se ignora sin hacer nada (RF-01). CA-08 lo fija con un test, para que no cambie cuando T03 agregue la validación.
  - El `401` lo produce el controller: es un error de negocio con `code`, no un rechazo de la capa de seguridad.
- **Otras consideraciones de backend aplicables y su solución:**
  - Privacidad (RF-11): la excepción usa un mensaje fijo, sin el login. Los DTO no exponen la contraseña. El token no se registra. El mensaje de error de la clave nombra la propiedad, no el contenido.
  - Diferencia de tiempo: un login inexistente responde sin calcular un hash, más rápido que una contraseña incorrecta. Ver P2.

## Dependencias y configuración

<!-- Librerías, servicios externos, propiedades de configuración y secretos
afectados. Verifica compatibilidad con el proyecto y justifica las incorporaciones.
No agregues dependencias por defecto. Los valores sensibles y los parámetros
ajustables se externalizan. -->

- **Dependencias:** ninguna nueva. Se usan `NimbusJwtEncoder`, `JwtClaimsSet` y `JwsHeader` (`spring-security-oauth2-jose` 7.1.1) y `RsaKeyConverters.pkcs8()` (`spring-security-core` 7.1.1), ya presentes en el proyecto.
- **Propiedades nuevas en `application.yaml`:**
  - `turnos.jwt.private-key-location: ${TURNOS_JWT_PRIVATE_KEY_LOCATION}`, sin valor por defecto: si falta, el servicio no arranca (D3).
  - `turnos.jwt.token-validity: ${TURNOS_JWT_TOKEN_VALIDITY:24h}` (D4).
- **Entorno de desarrollo:**
  - El par se genera con los comandos del contrato común §1.3 dentro de `secrets/`. El `.gitignore` suma `secrets/` y `*.pem`, para que ninguna clave se suba aunque quede en otra carpeta.
  - No se versiona una clave de ejemplo: cualquier clave subida al repositorio sería una clave real utilizable. El ejemplo es la línea de `.env.example` más los comandos de `CLAUDE.md`.
  - `.env.example` documenta `TURNOS_JWT_PRIVATE_KEY_LOCATION=file:./secrets/jwt-private.pem`, y el `.env` local la define.
  - `CLAUDE.md` suma el comando para generar las claves.
- **Tests:** `TestcontainersConfiguration` genera un par RSA en memoria al iniciar, lo escribe en un archivo temporal y registra `turnos.jwt.private-key-location` con un `DynamicPropertyRegistrar`. Así no se versiona ninguna clave privada, ni siquiera de test, y todos los tests que levantan la aplicación tienen clave. La clave pública queda disponible para que los tests verifiquen la firma. (P5)

## Estrategia de validación

<!-- Una fila por criterio de la spec. Selecciona el método capaz de demostrarlo:
test unitario, de integración (base de datos, mensajería), de API o prueba manual.
No todos requieren todos los métodos. Identifica tests existentes y separa los
nuevos propuestos. Incluye regresiones relevantes. Una prueba con mocks no
demuestra persistencia, transacciones ni idempotencia. -->

Todos los tests son nuevos, salvo la regresión.

| Criterio | Método y test existente o propuesto | Entorno y datos necesarios | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01 | Unitario `JwtTokenIssuerAdapterTest`: emitir para un usuario y verificar con la clave pública. Integración: CA-13 | Par RSA generado en el test | `alg` `RS256`; firma válida; claims exactamente `{sub, auth, iat, exp}`; `exp - iat` = vigencia |
| CA-02 | Unitario `AuthenticateUserUseCaseImplTest` (repositorio en memoria, como en T01). Integración `AuthenticateUserIntegrationTest` | Usuario `maria.gomez` | Token emitido para `Maria.Gomez` |
| CA-03 | API `UserControllerTest`, parametrizado: sin campo, `null` y `""` en cada campo | Service simulado | `400` `VALIDATION_ERROR`, campo en `fieldErrors`, `verifyNoInteractions` |
| CA-04 | Unitario del caso de uso (`Optional.empty`) y API (`401`) | — | `401` `INVALID_CREDENTIALS`, `problem+json` |
| CA-05 | Unitario del caso de uso. Integración: comparar el cuerpo con el de CA-04 | Usuario real con hash PBKDF2 | Cuerpos idénticos |
| CA-06 | Unitario del caso de uso con un usuario inactivo. Integración: desactivar con SQL y comparar con CA-04 | Usuario con `activated = false` | `401` con el mismo cuerpo |
| CA-07 | Integración con `rememberMe: true` | Aplicación completa | `exp - iat` = 86 400 |
| CA-08 | API e integración con `Authorization: Bearer xxx` | — | `200` |
| CA-09 | API: el service lanza `JwtEncodingException` | Service simulado | `500` `INTERNAL_ERROR`, `detail` genérico |
| CA-10 | API: el service lanza un error de acceso a datos. Manual: base detenida, como en T01 | Service simulado; app con Postgres temporal | `500` `INTERNAL_ERROR`; el log muestra el error real |
| CA-11 | Integración con `OutputCaptureExtension`: recorrer los casos de error y buscar en la salida | Aplicación completa | La salida no contiene la contraseña, el hash, el token ni `BEGIN PRIVATE KEY` |
| CA-12 | `ApplicationContextRunner` con el adaptador: sin propiedad, con un archivo inexistente y con un archivo que no es una clave. Binding de `JwtProperties` sin vigencia | Sin base | El contexto falla con un mensaje que nombra la propiedad; vigencia por defecto `PT24H` |
| CA-13 | Integración `AuthenticateUserIntegrationTest`: registrar con T01 e iniciar sesión por HTTP | Tomcat en puerto aleatorio y Postgres de Testcontainers | `200` con un token que cumple CA-01 |

**Comprobaciones de regresión:** los 65 tests actuales, en especial `RegisterUserIntegrationTest` y `UserControllerTest` (T01 sigue público y con el mismo comportamiento), y los cinco chequeos de la skill.

**Comandos verificados para compilar y ejecutar tests:** `./mvnw test` y `./mvnw test -Dtest=NombreDeLaClase`, documentados en `CLAUDE.md`.

**Pruebas con infraestructura real o simulada:** Postgres real con Testcontainers en los tests de integración. Clave RSA real, generada en el momento. Prueba manual con la aplicación levantada con `spring-boot:test-run` y `curl`, y con la base detenida para CA-10.

**Limitaciones del entorno:** ninguna conocida. Docker ya está disponible.

<!-- Esta sección planifica la validación. Durante la implementación, registra
en TASKS.md o en el informe de validación acordado los resultados y evidencias
reales. Distingue pruebas ejecutadas, fallidas, no ejecutadas y bloqueadas.
Compilar o tener tests en verde no sustituye revisar los criterios de la spec. -->

## Orden de implementación

<!-- Etapas y dependencias principales. El desglose ejecutable se escribe en TASKS.md.
Incluye puntos de comprobación para avanzar con cambios pequeños. -->

1. **Comparación de contraseñas:** `matches` en el puerto y en el adaptador PBKDF2. Se comprueba con el test del adaptador.
2. **Firma del token:** `TokenIssuer`, `JwtProperties`, `JwtTokenIssuerAdapter` y la clave de test en `TestcontainersConfiguration`. Se comprueba con CA-01 y CA-12 en unitarios, y con `contextLoads` en verde.
3. **Configuración de desarrollo:** `application.yaml`, `.env.example`, `.gitignore` y `CLAUDE.md`. Se comprueba arrancando la aplicación con una clave generada con `openssl`.
4. **Caso de uso y service:** puerto de entrada, implementación, excepción y método del service. Se comprueba con los unitarios de CA-02, CA-04, CA-05 y CA-06.
5. **Web y seguridad:** DTOs, mapper, endpoint y `permitAll`. Se comprueba con los tests de API de CA-03, CA-04, CA-08, CA-09 y CA-10.
6. **Integración, privacidad y prueba manual:** CA-05 a CA-08, CA-11 y CA-13 con la aplicación completa, y CA-10 a mano. Se cierra con la regresión completa y los chequeos de la skill.

## Riesgos y decisiones pendientes

<!-- Riesgos concretos de esta solución y cómo se resolverán, sin listas genéricas.
Escribe Ninguna en las decisiones pendientes cuando estén resueltas. -->

- **Riesgos y medidas acordadas:**
  - Que `DynamicPropertyRegistrar` registre la propiedad después de crear el adaptador, y los tests no arranquen. Se comprueba en la etapa 2 con `contextLoads`. Si falla, la alternativa es `@DynamicPropertySource` en cada test que levanta la aplicación.
  - Que T03 cambie el comportamiento de `/api/authenticate` al agregar la validación del JWT. Por ejemplo, el filtro de token de Spring rechaza un `Authorization` inválido aun en rutas públicas. Lo frena el test de CA-08.
- **Decisiones pendientes:** Ninguna.
- **Decisiones tomadas (2026-10-20):**
  - **P1 · Configuración del JWT:** `JwtProperties` en `user/infrastructure/security`, junto al único adaptador que la usa. Si en T03 la validación necesita la misma configuración, se mueve a `config`.
  - **P2 · Diferencia de tiempo:** no se mitiga en T02. Queda como limitación conocida: midiendo el tiempo de respuesta se podría deducir si un login existe.
  - **P3 · Nombres de los DTO:** los nombres de la skill salen del ejemplo `product`. Cada slice puede tener los request y response que necesite, siempre que respeten las capas y su función. Se usan `AuthenticateRequest` y `TokenResponse`.
  - **P5 · Clave de los tests:** se genera en cada corrida; no se versiona ninguna clave privada.
  - **P4 · Entrega de la clave privada a la aplicación:** por la ubicación de un archivo (`TURNOS_JWT_PRIVATE_KEY_LOCATION=file:./secrets/jwt-private.pem`), no por el contenido PEM en una variable. `secrets/` y `*.pem` van al `.gitignore`; no se versiona una clave de ejemplo.
