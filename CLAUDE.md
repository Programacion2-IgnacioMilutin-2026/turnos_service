# turnos_service

Los comandos se ejecutan desde la raíz de este repositorio.

## Requisitos

- Docker en ejecución: lo usan el compose y los tests.
- Un archivo `.env` creado a partir de `.env.example`, con `TURNOS_DB_PASSWORD` y `TURNOS_JWT_PRIVATE_KEY_LOCATION` completos, y las tres variables `TURNOS_CATEDRA_*` de la cuenta técnica. No se versiona. Sin las de la cátedra, el servicio arranca con un warning y sin integración. `TURNOS_CORS_ALLOWED_ORIGINS` es opcional: vacía, ningún navegador puede usar la API (decisión 007). Los tests de Maven nunca leen el `.env` (simulan la cátedra).
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
| `turnos.user` | Usuarios finales: T01 (`POST /api/register`) y T02 (`POST /api/authenticate`, emite el JWT RS256). Además, las piezas que validan ese JWT (`infrastructure/security`, decisión 008) |
| `turnos.catedra` | Pasarela hacia la cátedra. Sesión técnica (X01): login técnico al arrancar, estado en memoria, renovación a pedido. Hasta T03 se llamaba `integration` (decisión 009) |
| `turnos.config` | Configuración transversal, sin lógica de negocio: `SecurityConfig`, `SecurityProblemHandler`, `CorsProperties`, `ApiExceptionHandler`, `JpaAuditingConfig`, `ClockConfig` |

### Excepciones a la skill autorizadas por el usuario

Cada una lleva un comentario corto en el código. Una excepción nueva se consulta antes y, si es compleja, se documenta en `docs/decisiones/`.

- Paquete `turnos.config` fuera de los slices, para configuración transversal.
- `@RestControllerAdvice` global (`ApiExceptionHandler`): da formato `problem+json`, `VALIDATION_ERROR` con `fieldErrors` e `INTERNAL_ERROR`. Decisión 003.
- Rutas bajo `/api` (las del contrato), en lugar de `/{entities}`.
- Paquetes de infraestructura además de `persistence` y `web`: `infrastructure/security` (adaptadores de seguridad, decisiones 001 y 004), `infrastructure/client` (clientes HTTP hacia sistemas externos) e `infrastructure/startup` (disparadores al arrancar).
- Puertos de salida para capacidades técnicas, no solo `{Entity}Repository`: `PasswordHasher`, `TokenIssuer`, `TechnicalLoginClient`. El caso de uso nunca conoce la librería (decisión 001).
- Repositorio en memoria en `persistence/adapter` (`InMemoryTechnicalSessionRepositoryAdapter`): no usa base de datos.
- `record` inmutables en lugar de clases Lombok cuando el objeto se lee en paralelo mientras se reemplaza: `TechnicalSession`, `TechnicalIntegration`, `TechnicalLoginResult` (interfaz sellada). Sus `toString` ocultan el JWT.
- Varios formatos de request o response en un slice, con nombres propios (`AuthenticateRequest`, `TokenResponse`): los nombres de la skill salen del ejemplo `product`.
- `@Configuration` dentro de la infraestructura de un slice para armar beans técnicos que usa otra parte del servicio por una interfaz de Spring: `CatedraClientConfiguration` y `JwtValidationConfiguration` (decisión 008).

### Comentarios y nombres en el código

- El código, sus comentarios y los nombres de los tests no citan documentos: ni el contrato, ni el anexo de la cátedra, ni el enunciado, ni secciones, reglas o IDs de requisitos (`§`, `RF-`, `CA-`). Un comentario explica el porqué o avisa algo, con palabras propias. Un test se nombra por el comportamiento que comprueba (no "según el contrato").
- Las referencias a documentos van en `docs/sdd/` (SPEC, PLAN y TASKS) y en `docs/decisiones/`.

### Errores HTTP

- El controller atrapa las excepciones de su slice y relanza `ResponseStatusException` con el estado y la propiedad `code` del contrato (por ejemplo `USERNAME_ALREADY_EXISTS`, `INVALID_CREDENTIALS`).
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
- El control de los logins (uno a la vez, intervalo mínimo de 60 s, error de configuración sin reintentos) vive en `catedra` (decisión 005). Los demás slices nunca llaman al login de la cátedra.
- Usar `catedra` desde otro slice es una dependencia entre slices: se decide con el usuario (skill §9).

## Tests

- Los tests de Maven nunca leen el `.env` local: surefire define `turnos.env-file` con un archivo inexistente. Nunca usan las credenciales reales ni llaman a la cátedra real; la cátedra real solo se prueba a mano.
- `TestcontainersConfiguration`: Postgres 18 y la clave del JWT de test (`TestRsaKeys`, generada en cada corrida). Lo importan todos los tests que levantan la aplicación.
- `TestSecurityConfiguration`: la seguridad real (cadena, validación, claves y CORS) con la clave de test, para los `@WebMvcTest`. Los tests web la importan en lugar de `SecurityConfig`.
- `TestTokens`: arma tokens de prueba (válidos, vencidos, sin `exp`, de otra clave, `HS256`, sin firma o alterados), firmados con `TestRsaKeys`.
- `ProbeController` (`src/test/java/turnos/config/`): `GET /api/probe`, un endpoint protegido que existe solo en los tests que lo importan (`@TestComponent`). Sirve para probar la seguridad mientras no haya endpoints protegidos reales.
- `FakeCatedraServer` (`src/test/java/turnos/catedra/`): cátedra simulada con el servidor HTTP del JDK, en un puerto aleatorio. Responde lo que indica el test y guarda los requests.
- Dobles de test escritos a mano: `ScriptedLoginClient`, `MutableClock`, `FakePasswordHasher`, `InMemoryUserRepository`.
- Integración con la app completa: clases `*IntegrationTest` con `@SpringBootTest(webEnvironment = RANDOM_PORT)` y `RestTestClient`.
- Capa web: `@WebMvcTest` del controller con `@Import({TestSecurityConfiguration.class, ...})` y el service simulado con `@MockitoBean`.
- Para contar los tests de una corrida, usar los reportes XML (`target/surefire-reports/TEST-*.xml`): el resumen `.txt` de Surefire no suma los tests de las clases `@Nested`.
- Práctica acordada: después de que un test pasa, una prueba en negativo (romper el código a propósito, ver que el test falla, restaurar el archivo y comprobar el hash) para demostrar que el test detecta el error.
- SonarQube Cloud analiza los PR y `main` (plan gratuito: no analiza `develop`). Los resultados se leen con su API pública.

## Documentación del servicio

- `docs/`: copias de casos de uso, contratos, dominio y secuencias. La fuente de verdad está en el repo `documentacion`.
- `docs/sdd/<id>/`: SPEC, PLAN y TASKS de cada funcionalidad (`t02-login`, `x01-cuenta-tecnica`, `validacion-jwt`). T01 se hizo sin SDD por indicación del usuario. `validacion-jwt` no es un caso de uso: su carpeta no lleva id, como excepción indicada por el usuario.
- `docs/decisiones/NNN-titulo.md`: textos de las decisiones complejas (001 a 008).
