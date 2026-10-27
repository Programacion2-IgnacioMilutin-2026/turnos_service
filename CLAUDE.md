# turnos_service

Los comandos se ejecutan desde la raíz de este repositorio.

## Requisitos

- Docker en ejecución: lo usan el compose y los tests.
- Un archivo `.env` creado a partir de `.env.example`, con `TURNOS_DB_PASSWORD` y `TURNOS_JWT_PRIVATE_KEY_LOCATION` completos, y las tres variables `TURNOS_CATEDRA_*` de la cuenta técnica. No se versiona. Sin las de la cátedra, el servicio arranca con un warning y sin integración. `TURNOS_CORS_ALLOWED_ORIGINS` es opcional: vacía, ningún navegador puede usar la API (decisión 007). `TURNOS_CATALOG_BASE_URL` apunta al servicio de catálogo; vacía, el servicio arranca con un warning y `GET /api/free-slots` responde `503` `CATALOG_UNAVAILABLE`. Los tiempos, reintentos, zona de atención (`TURNOS_ATTENTION_ZONE`, la comparten `availability` y `reservation`), antigüedad del caché y margen de seguridad de los holds (`TURNOS_RESERVATION_SAFETY_MARGIN`, 30 s) son opcionales y están comentados en `.env.example`; una variable vacía conserva el valor por defecto. La suite normal de tests nunca lee el `.env` (simula catálogo y cátedra); solo la suite E2E (`-Pe2e`) lo lee.
- El par de claves RSA del JWT de los usuarios en `secrets/`, que no se versiona. Sin la clave privada, la aplicación no arranca (los tests generan su propio par). Se crea una sola vez:

```bash
mkdir -p secrets
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/jwt-private.pem
openssl rsa -pubout -in secrets/jwt-private.pem -out secrets/jwt-public.pem
```

## Comandos

| Para | Comando |
| --- | --- |
| Levantar Postgres de desarrollo y esperar a que esté listo | `docker compose up -d --wait` |
| Ver el estado de Postgres | `docker compose ps` |
| Correr la aplicación (necesita Postgres levantado) | `./mvnw spring-boot:run` |
| Correr todos los tests (Testcontainers crea su propio Postgres; no usan el compose) | `./mvnw test` |
| Correr solo la suite E2E, contra la cátedra real con las credenciales del `.env` (se saltea sin ellas) | `./mvnw test -Pe2e` |
| Correr una sola clase de test | `./mvnw test -Dtest=NombreDeLaClase` |
| Compilar desde cero y correr los tests | `./mvnw clean test` |
| Tests más reporte de cobertura de JaCoCo (`target/site/jacoco/jacoco.xml`), lo mismo que corre GitHub Actions | `./mvnw clean verify` |
| Apagar Postgres conservando los datos | `docker compose down` |
| Apagar Postgres y borrar los datos | `docker compose down -v` |

## Datos del proyecto

- `{basePackage}` de la skill `hexagonal-vertical-slicing`: `turnos`.
- La aplicación escucha en el puerto 8080; Postgres en `TURNOS_DB_PORT` (5432 por defecto).

## Arquitectura

Se aplica la skill `hexagonal-vertical-slicing` (`documentacion/.claude/skills/hexagonal-vertical-slicing/SKILL.md`). Slices actuales:

| Paquete | Qué contiene |
| --- | --- |
| `turnos.user` | Usuarios finales: T01 (`POST /api/register`) y T02 (`POST /api/authenticate`, emite el JWT RS256). Además, las piezas que validan ese JWT (`infrastructure/security`, decisión 008) y `ExistsUserByIdUseCase`, que responde solo sí o no (lo usa `reservation`) |
| `turnos.catedra` | Pasarela hacia la cátedra (única puerta). Sesión técnica (X01): login técnico al arrancar, estado en memoria, renovación a pedido. Consulta de ocupaciones (`GetOccupanciesUseCase`, T03) y creación de holds (`CreateHoldUseCase`, T04). Las reglas comunes de cada llamada (sesión, `401`, reintentos) viven en `application/support/CatedraOperationRunner` (decisión 011). Hasta T03 se llamaba `integration` (decisión 009) |
| `turnos.catalog` | Pasarela hacia el servicio de catálogo (única puerta): `GetProfessionalUseCase` pide un profesional reenviando el JWT del usuario. Los reintentos viven en el adaptador HTTP; sin service, porque no tiene controller (decisión 009, P7 del PLAN de T03) |
| `turnos.availability` | T03 (`GET /api/free-slots`): cálculo de turnos libres, caché en memoria de ocupaciones y adaptadores `infrastructure/gateway` hacia `catalog` y `catedra` |
| `turnos.reservation` | Proceso de reserva. T04 (`POST /api/reservation-processes`): bloquea un turno con un hold de la cátedra y guarda el proceso en `HELD` (tabla `reservation_processes`, migración `V2`). Adaptadores `infrastructure/gateway` hacia `user`, `catalog` y `catedra`. Un proceso abierto por usuario: candado por usuario en memoria más índice único parcial (decisión 012). Lo amplían T05 a T13 |
| `turnos.config` | Configuración transversal, sin lógica de negocio: `SecurityConfig`, `SecurityProblemHandler`, `CorsProperties`, `ApiExceptionHandler`, `JpaAuditingConfig`, `ClockConfig`, `SchedulingConfig` |

### Criterios de diseño acordados con el usuario

- Cada slice tiene solo las piezas que alguien usa; no se copian piezas de otro slice para que se parezcan (por ejemplo, un service sin controller). El modelo empieza con los datos que usa el caso de uso actual.
- En los casos de uso siguientes se amplían los archivos existentes (sumar `date` o `expiresAt` a `Occupancy`, nombre y categoría a `Professional`) en lugar de crear piezas paralelas, y se actualizan los tests afectados.
- La lógica de sesión técnica, renovación ante `401` y reintentos ya está extraída en `catedra/application/support/CatedraOperationRunner` (T04, decisión 011). Toda operación nueva con la cátedra (T05, T11, T13) la usa; no se copia. Reintentar un `POST` que crea algo no es seguro: solo se repite lo que seguro no llegó (ver "Cómo usar la integración con la cátedra").
- Un `update(id, modelo)` de un adaptador de persistencia exige el modelo al entrar con `Objects.requireNonNull`: el mapper de la skill devuelve `null` si recibe `null`, y SonarQube marca el acceso a la entidad que sigue (como en `JpaReservationProcessRepositoryAdapter.update`).
- Las restricciones de Bean Validation llevan siempre su mensaje propio en inglés: el mensaje por defecto cambia con el idioma del servidor o el `Accept-Language` del cliente (MockMvc usa inglés por defecto y no lo detecta).

### Excepciones a la skill autorizadas por el usuario

Cada una lleva un comentario corto en el código. Una excepción nueva se consulta antes y, si es compleja, se documenta en `docs/decisiones/`.

- Paquete `turnos.config` fuera de los slices, para configuración transversal.
- `@RestControllerAdvice` global (`ApiExceptionHandler`): da formato `problem+json`, `VALIDATION_ERROR` con `fieldErrors` e `INTERNAL_ERROR`. Decisión 003.
- Rutas bajo `/api` (las del contrato), en lugar de `/{entities}`.
- Paquetes de infraestructura además de `persistence` y `web`: `infrastructure/security` (adaptadores de seguridad, decisiones 001 y 004), `infrastructure/client` (clientes HTTP hacia sistemas externos), `infrastructure/startup` (disparadores al arrancar), `infrastructure/gateway` (adaptadores de salida que implementan un puerto propio llamando al puerto de entrada de otro slice; el único lugar donde un slice importa a otro, decisión 009) e `infrastructure/config` (propiedades y `@Configuration` propias de un slice: `availability` y `reservation`).
- Paquete `catedra/application/support/` con `CatedraOperationRunner`: no es un caso de uso ni se ofrece a otros slices, porque maneja el JWT técnico (decisión 011).
- La clave primaria de `reservation_processes` es el `reservationProcessId` de la cátedra (`VARCHAR(255)`), no un `Long id` autogenerado. `ReservationProcessEntity` implementa `Persistable` para que Spring Data no haga un `SELECT` antes de cada `INSERT` (decisión 012). Tiene clave foránea a `users` (`fk_reservation_processes_user`) e índice único parcial `ux_reservation_processes_one_open_per_user`; el adaptador distingue cuál rechazó un `INSERT` por el nombre.
- Puertos de salida para capacidades técnicas, no solo `{Entity}Repository`: `PasswordHasher`, `TokenIssuer`, `TechnicalLoginClient`. El caso de uso nunca conoce la librería (decisión 001).
- Repositorios en memoria en `persistence/adapter`, sin base de datos: `InMemoryTechnicalSessionRepositoryAdapter` y el caché de ocupaciones `InMemoryOccupancyCacheAdapter` (se limpia solo con un `@Scheduled` cada minuto).
- `record` inmutables en lugar de clases Lombok cuando el objeto se lee en paralelo mientras se reemplaza: `TechnicalSession`, `TechnicalIntegration`, `TechnicalLoginResult` (interfaz sellada). Sus `toString` ocultan el JWT.
- Resultados sellados de `record` para lo que devuelve un sistema externo, en lugar de excepciones: `ProfessionalResult` (`catalog`), `CatedraResponse<T>` (lo que devuelve cada cliente HTTP de la cátedra en un intento, decisión 011), `OccupanciesResult` y `HoldResult` (`catedra`). Políticas como `record` del dominio: `TechnicalLoginPolicy`, `CatedraOperationPolicy`, `AvailabilityPolicy`, `ReservationPolicy`.
- Varios formatos de request o response en un slice, con nombres propios (`AuthenticateRequest`, `TokenResponse`): los nombres de la skill salen del ejemplo `product`.
- `@Configuration` dentro de la infraestructura de un slice para armar beans técnicos que usa otra parte del servicio por una interfaz de Spring o el propio slice: `CatedraClientConfiguration`, `JwtValidationConfiguration` (decisión 008), `AvailabilityConfiguration` y `ReservationConfiguration`.
- `ReservationProcessController` lanza `ErrorResponseException` (con `ProblemDetail` y `code`) en lugar de `ResponseStatusException`, porque algunos errores llevan encabezados: `WWW-Authenticate: Bearer` en el `401` de un usuario inexistente y `Retry-After` en `HOLD_NOT_SAVED` (P7 del PLAN de T04).
- `ReservationProcessRequest.professionalId` es `BigDecimal` (acepta `1.0` o `"15"` como enteros y da un solo error en `fieldErrors` para un número enorme), con constructor de todos los campos escrito a mano y setter que quitan los ceros decimales: Jackson 3 construye el DTO con ese constructor, no con el setter (P8 del PLAN de T04).

### Comentarios y nombres en el código

- El código, sus comentarios y los nombres de los tests no citan documentos: ni el contrato, ni el anexo de la cátedra, ni el enunciado, ni secciones, reglas o IDs de requisitos (`§`, `RF-`, `CA-`). Un comentario explica el porqué o avisa algo, con palabras propias. Un test se nombra por el comportamiento que comprueba (no "según el contrato").
- Las referencias a documentos van en `docs/sdd/` (SPEC, PLAN y TASKS) y en `docs/decisiones/`.

### Errores HTTP

- El controller atrapa las excepciones de su slice y relanza `ResponseStatusException` con el estado y la propiedad `code` del contrato (por ejemplo `USERNAME_ALREADY_EXISTS`, `INVALID_CREDENTIALS`). Excepción: `reservation` usa `ErrorResponseException` (ver arriba).
- Si el JSON está bien escrito pero un campo no se puede convertir (`"abc"` en un número, una fecha imposible), `ApiExceptionHandler` responde `VALIDATION_ERROR` con ese campo en `fieldErrors` e `invalid value`; un JSON mal escrito responde sin `fieldErrors`.
- `ApiExceptionHandler` solo da formato; no importa nada de los slices.

### Seguridad

- `SecurityConfig`: sin sesión (`STATELESS`), CSRF desactivado (decisión 006, marcado como falso positivo en SonarQube), CORS (decisión 007) y validación del JWT (decisión 008). Una sola lista de rutas públicas, `POST /api/register` y `POST /api/authenticate`, que usan el `permitAll` y el lector del token: en esas rutas el encabezado `Authorization` se ignora aunque sea inválido. El resto exige un JWT válido con al menos un rol de `Authority`.
- JWT del usuario: RS256 con la clave privada de `secrets/` (`turnos.jwt.private-key-location`); `JwtSigningKeys` la carga una vez y obtiene la pública para la firma y la validación. Claims exactos: `sub`, `auth`, `iat`, `exp` (decisión 004).
- Validación (`user/infrastructure/security/JwtValidationConfiguration`): solo RS256 con la clave propia, `exp` obligatorio y sin tolerancia de reloj, `sub` solo dígitos dentro del rango de un `Long`. Sin estado: no consulta la base. `config` recibe el decodificador, el conversor de roles y la regla de roles por interfaces de Spring, sin importar el slice.
- Rechazos (`SecurityProblemHandler`): `401` y `403` en `problem+json` con `title`, `status`, `detail` e `instance`, sin `code`, con un único cuerpo por estado; el `401` lleva `WWW-Authenticate: Bearer` sin el motivo. Spring Security 7 agrega la autoridad `FACTOR_BEARER` a toda autenticación por token: no es un rol.
- CORS: orígenes exactos en `TURNOS_CORS_ALLOWED_ORIGINS`, separados por comas, vacía por defecto. Nunca `*`. Un origen no permitido recibe `403` `Invalid CORS request`.

### Cómo obtiene la identidad un controller (desde T03)

- El controller recibe el `Jwt` validado (`@AuthenticationPrincipal Jwt jwt`) y pasa al service valores simples: `Long.valueOf(jwt.getSubject())` como id del usuario y, si hay que reenviar el token a catálogo (C06), `jwt.getTokenValue()`. El service y el caso de uso no conocen Spring Security.
- La identidad nunca sale de un parámetro, del cuerpo ni de otro encabezado.
- Puede cambiar: si la extracción se repite demasiado o la identidad suma datos, se revisa con un paquete `shared` con un tipo para la identidad (decisión 008).

### Cómo usar la integración con la cátedra (desde T03)

- En cada operación, pedir la sesión con `GetTechnicalSessionUseCase` (JWT técnico e integración). Nunca guardar una copia en un campo: así siempre se usa la última.
- Si la cátedra responde `401`, llamar a `RenewTechnicalSessionUseCase.renewTechnicalSession(jwtRechazado)` y repetir la operación una sola vez con la sesión que devuelve; si devuelve vacío, la integración no está disponible (según el contrato, `503` `SERVICE_DEGRADED`).
- Desde T04 eso lo hace `CatedraOperationRunner.run(nombreDeLaOperacion, jwt -> cliente.llamar(..., jwt))`; un caso de uso de `catedra` no pide la sesión a mano. Para una operación nueva: un cliente HTTP en `infrastructure/client` que hace **un solo intento** y devuelve `CatedraResponse<T>`; el caso de uso llama al runner y traduce la respuesta a su propio resultado sellado (como `CreateHoldUseCaseImpl` → `HoldResult`).
- Cómo clasifica el cliente: `Answered` (respuesta definitiva, éxito o rechazo de negocio), `TokenRejected` (`401`), `ConfigurationError` (`403` `STUDENT_INTEGRATION_NOT_FOUND`), `Unavailable` (seguro que no llegó: conexión rechazada, timeout de conexión, host desconocido, `503`; el runner reintenta), `Uncertain` (pudo haber llegado: timeout de respuesta, corte durante la respuesta, `500`; no se reintenta) y `Failed` (no previsto). Una consulta trata todo como `Unavailable`; una operación que crea algo (hold, confirmación, cancelación) distingue `Uncertain` (ver `CatedraHoldClientAdapter.wasNotSent`).
- El control de los logins (uno a la vez, intervalo mínimo de 60 s, error de configuración sin reintentos) vive en `catedra` (decisión 005). Los demás slices nunca llaman al login de la cátedra.
- Usar `catedra` desde otro slice es una dependencia entre slices: se decide con el usuario (skill §9). El otro slice declara su propio puerto de salida y lo implementa un adaptador en su `infrastructure/gateway`, que llama al puerto de entrada de `catedra` (como `availability/infrastructure/gateway/CatedraOccupancyAdapter`).
- Reglas de la cátedra comprobadas con respuestas reales (grabadas en `src/test/resources/catedra/`, ver su `README.md`): el `401` llega sin cuerpo ni `Content-Type`; un profesional inexistente da `200` con lista vacía; los errores `problem+json` traen además `type`, `message` y `path`; un hold dura 10 minutos y sus ids (`process-…`, `hold-…`) tienen 41 y 44 caracteres; una ocupación `HELD` llega con `startTime` `HH:mm:ss` y `expiresAt`. **Cancelar un proceso en `HELD` responde `404` `RESERVATION_NOT_FOUND`**, aunque el contrato dice lo contrario: el usuario lo consulta con el profesor (afecta a T11). Toda grabación nueva se limpia (JWT, contraseña de Redis, hosts, `groupId`) antes de versionarla.

### Cómo usar catálogo (desde T03)

- Pedir el profesional con `GetProfessionalUseCase.getProfessional(professionalId, jwt.getTokenValue())`. Devuelve `Found`, `NotFound`, `Rejected` (`401`/`403` al token reenviado: error interno), `Unavailable` (después de los reintentos, o sin URL válida) o `Failed` (respuesta no prevista).
- Desde otro slice, por un puerto propio y un adaptador en `infrastructure/gateway` (como `CatalogAgendaAdapter` en `availability` y `CatalogProfessionalAdapter` en `reservation`). `Professional` trae nombre, apellido y nombre de la categoría; el `enabled` de la categoría no se lee, porque solo cuenta el del profesional.

## Tests

- La suite normal de Maven nunca lee el `.env` local: el `pom.xml` apunta `turnos.env-file` a un archivo inexistente (propiedad `tests.env-file`). Nunca usa las credenciales reales ni llama a la cátedra real. Solo la suite E2E (`-Pe2e`) lee el `.env` y habla con la cátedra real.
- `TestcontainersConfiguration`: Postgres 18 y la clave del JWT de test (`TestRsaKeys`, generada en cada corrida). Lo importan todos los tests que levantan la aplicación.
- `TestSecurityConfiguration`: la seguridad real (cadena, validación, claves y CORS) con la clave de test, para los `@WebMvcTest`. Los tests web la importan en lugar de `SecurityConfig`.
- `TestTokens`: arma tokens de prueba (válidos, vencidos, sin `exp`, de otra clave, `HS256`, sin firma o alterados), firmados con `TestRsaKeys`.
- `ProbeController` (`src/test/java/turnos/config/`): `GET /api/probe`, un endpoint protegido que existe solo en los tests que lo importan (`@TestComponent`). Sirve para probar la seguridad mientras no haya endpoints protegidos reales.
- `FakeHttpServer` (`src/test/java/turnos/`): servidor HTTP del JDK en un puerto aleatorio para simular sistemas externos. Cola de respuestas por ruta (la última se repite), demoras y registro de los requests. Lo usan los tests de catálogo y de la cátedra.
- `FakeCatedraServer` (`src/test/java/turnos/catedra/`): cátedra simulada sobre `FakeHttpServer` (`http()` da acceso a las respuestas por ruta). Por defecto responde un login técnico exitoso.
- Respuestas reales de la cátedra en `src/test/resources/catedra/` (limpias), leídas del classpath por los tests.
- Dobles de test escritos a mano: `ScriptedLoginClient`, `MutableClock` (`src/test/java/turnos/`, público), `FakePasswordHasher`, `InMemoryUserRepository`.
- Dos suites (decisión 010). La normal (`./mvnw test`) simula todo y nunca lee el `.env`. La E2E vive en `src/test/java/e2e/` (fuera de `turnos`, con `classes = TurnosServiceApplication.class`), lleva `@Tag("e2e")`, la excluye Surefire por defecto y la corre el perfil `-Pe2e`, que lee el `.env`; sin credenciales se saltea. Antes de cualquier llamada a la cátedra real se le confirma al usuario qué requests se harán.
- Integración con la app completa: clases `*IntegrationTest` con `@SpringBootTest(webEnvironment = RANDOM_PORT)` y `RestTestClient`. Las que crean procesos de reserva borran `reservation_processes` antes que `users` (la clave foránea no deja borrar un usuario con procesos), como `ReservationProcessIntegrationTest`.
- E2E: `E2ECredentials` (`src/test/java/e2e/`) decide si hay credenciales en el `.env`; la usan `FreeSlotsE2ETest` y `ReservationProcessE2ETest`. Este último crea un hold real en un turno libre e intenta cancelarlo al terminar; como la cátedra responde `404`, imprime un aviso y no falla (D9 de T04). El modo automático de Claude Code bloquea las escrituras a la cátedra y el `!` no llegó a correr `-Pe2e`: el usuario corre el E2E en su terminal y Claude lee `target/surefire-reports/`.
- Capa web: `@WebMvcTest` del controller con `@Import({TestSecurityConfiguration.class, ...})` y el service simulado con `@MockitoBean`.
- Para contar los tests de una corrida, usar los reportes XML (`target/surefire-reports/TEST-*.xml`): el resumen `.txt` de Surefire no suma los tests de las clases `@Nested`.
- Práctica acordada: después de que un test pasa, una prueba en negativo (romper el código a propósito, ver que el test falla, restaurar el archivo y comprobar el hash) para demostrar que el test detecta el error.
- SonarQube Cloud analiza los PR y `main` (plan gratuito: no analiza `develop`). Los resultados se leen con su API pública.

## Documentación del servicio

- `docs/`: copias de casos de uso, contratos, dominio y secuencias. La fuente de verdad está en el repo `documentacion`.
- `docs/sdd/<id>/`: SPEC, PLAN y TASKS de cada funcionalidad (`t02-login`, `x01-cuenta-tecnica`, `validacion-jwt`, `t03-turnos-libres`, `t04-bloquear-turno`). T01 se hizo sin SDD por indicación del usuario. `validacion-jwt` no es un caso de uso: su carpeta no lleva id, como excepción indicada por el usuario.
- `docs/decisiones/NNN-titulo.md`: textos de las decisiones complejas (001 a 012; 011 pieza común de la cátedra, 012 persistencia del proceso de reserva).
