# TASKS: T02 — Iniciar sesión

**SPEC:** `docs/sdd/t02-login/SPEC.md` (aprobada el 2026-10-20)
**PLAN:** `docs/sdd/t02-login/PLAN.md` (aprobado el 2026-10-20)

Las tareas siguen el "Orden de implementación" del PLAN. Rutas relativas a `src/main/java/turnos/` y `src/test/java/turnos/`, salvo indicación.

- [x] **TASK-01 · Comparar una contraseña con su hash**
  - Alcance: agrega `matches(String password, String passwordHash)` a `user/domain/ports/out/PasswordHasher` y lo implementa en `user/infrastructure/security/Pbkdf2PasswordHasherAdapter`. No toca el caso de uso de T01.
  - Depende de: ninguna
  - Resuelve: base de CA-05
  - Validación: `./mvnw test -Dtest=Pbkdf2PasswordHasherAdapterTest`, con tests nuevos: la contraseña correcta coincide con su hash, una incorrecta no coincide, y siguen pasando los tests existentes
  - Resultado: 2026-10-20. `matches` agregado al puerto y al adaptador PBKDF2. `Pbkdf2PasswordHasherAdapterTest`: 6 tests en verde (los 4 anteriores y 2 nuevos: la contraseña coincide con su hash; `Secreta123` y `secreta12` no coinciden). Efecto colateral: `PasswordHasher` deja de ser una interfaz de un método, así que el hasher falso de `RegisterUserUseCaseImplTest` pasó a la clase de test `FakePasswordHasher`, compartida con TASK-06, igual que `InMemoryUserRepository`. Sin cambios de comportamiento: sus 5 tests siguen en verde.

- [x] **TASK-02 · Puerto de firma y propiedades del JWT**
  - Alcance: crea `user/domain/ports/out/TokenIssuer` (`String issue(User user)`), `user/infrastructure/security/JwtProperties` (`private-key-location`, `token-validity` con 24 horas por defecto) y agrega `@ConfigurationPropertiesScan` a `TurnosServiceApplication`. Todavía no firma nada.
  - Depende de: ninguna
  - Resuelve: CA-12 (vigencia por defecto)
  - Validación: test de binding de `JwtProperties`: sin `token-validity`, la vigencia es `PT24H`; con `token-validity: 1h`, `PT1H`. Chequeo 1 de la skill sobre `domain`
  - Resultado: 2026-10-20. `JwtPropertiesTest`: 2 tests en verde (sin propiedad, `PT24H`; con `1h`, `PT1H`). Chequeo 1 de la skill sin resultados. `@ConfigurationPropertiesScan` agregado a `TurnosServiceApplication`.

- [x] **TASK-03 · Firmar el token con la clave privada**
  - Alcance: crea `user/infrastructure/security/JwtTokenIssuerAdapter`: carga la clave PKCS#8, obtiene la pública, firma con `RS256` y arma exactamente `sub`, `auth`, `iat` y `exp`. Si la clave falta o es inválida, lanza un error que nombra la propiedad sin mostrar la clave.
  - Depende de: TASK-02
  - Resuelve: CA-01 (formato y firma), CA-12 (servicio sin clave)
  - Validación: `./mvnw test -Dtest=JwtTokenIssuerAdapterTest`:
    - Con un par generado en el test, el token se verifica con la clave pública, `alg` es `RS256`, los claims son exactamente los cuatro, `sub` es el id, `auth` es `ROLE_USER` y `exp - iat` es la vigencia.
    - Con `ApplicationContextRunner`: sin propiedad, con un archivo inexistente y con un archivo que no es una clave, el contexto falla con un mensaje que nombra la propiedad y no contiene `BEGIN`.
  - Resultado: 2026-10-20. `JwtTokenIssuerAdapterTest`: 7 tests en verde. Token verificado con la clave pública, `alg` `RS256`, claims exactamente `sub`, `auth`, `iat` y `exp`, `sub` `42`, `auth` `ROLE_USER` (`ROLE_ADMIN ROLE_USER` con dos roles), vigencia de 24 h. Con otra clave, `BadJwtException`. El contexto no arranca sin propiedad, con archivo inexistente, con un archivo que no es clave y con una clave de 1024 bits; los mensajes nombran la propiedad y no contienen `BEGIN` ni `MII`. Agregado sobre el PLAN: se rechazan claves de menos de 2048 bits (contrato común §1.3).

- [x] **TASK-04 · Clave RSA para los tests que levantan la aplicación**
  - Alcance: `TestcontainersConfiguration` genera un par RSA por corrida, escribe la privada en un archivo temporal y registra `turnos.jwt.private-key-location` con un `DynamicPropertyRegistrar`. Expone la pública para verificar firmas en los tests.
  - Depende de: TASK-03
  - Resuelve: base de CA-13 (riesgo del PLAN: orden de registro de la propiedad)
  - Validación: `./mvnw test`: `contextLoads` y `RegisterUserIntegrationTest` en verde con el adaptador activo. Si el orden de registro falla, se aplica la alternativa del PLAN (`@DynamicPropertySource`) y se anota aquí
  - Resultado: 2026-10-20. `TestRsaKeys` genera el par por corrida y `TestcontainersConfiguration` registra la ubicación con `DynamicPropertyRegistrar`: el riesgo del PLAN no se dio y no hizo falta la alternativa. `./mvnw test`: 74 tests en verde, con `contextLoads` y `RegisterUserIntegrationTest` incluidos. Prueba en negativo: sin el registro, `contextLoads` falla con `Missing configuration property turnos.jwt.private-key-location`; restaurado con hash idéntico y otra vez en verde.

- [x] **TASK-05 · Configuración de desarrollo**
  - Alcance: `src/main/resources/application.yaml` (`turnos.jwt.*` con `TURNOS_JWT_PRIVATE_KEY_LOCATION`, sin valor por defecto, y `TURNOS_JWT_TOKEN_VALIDITY:24h`); `.env.example` con la línea de ejemplo; `.gitignore` con `secrets/` y `*.pem`; `CLAUDE.md` con los comandos `openssl` del contrato común §1.3. Genera el par local en `secrets/` y agrega la variable al `.env` local. No se versiona ninguna clave.
  - Depende de: TASK-03
  - Resuelve: CA-12 (comprobación con la aplicación real)
  - Validación:
    - `./mvnw spring-boot:run` con la clave configurada: arranca.
    - Sin la variable: no arranca y el log nombra la propiedad.
    - `git status --short` y `git check-ignore secrets/jwt-private.pem` confirman que la clave queda fuera del repositorio.
  - Resultado: 2026-10-20. Par generado con los comandos del contrato en `secrets/` (`BEGIN PRIVATE KEY`, 2048 bits, privada con permisos 600). `.gitignore` suma `secrets/` y `*.pem`; `git check-ignore` confirma que las dos claves quedan fuera y `git status` no las muestra. Cambio sobre el PLAN: `application.yaml` usa `${TURNOS_JWT_PRIVATE_KEY_LOCATION:}` (vacío por defecto) en lugar de `${TURNOS_JWT_PRIVATE_KEY_LOCATION}`, para que la falta de la variable dé el mensaje del adaptador y no un error genérico de Spring. `./mvnw spring-boot:run` con el compose: arranca en 2,7 s y un GET sin token responde `403`. Con la variable vacía: no arranca (`Application run failed`) y el log dice `Missing configuration property turnos.jwt.private-key-location`. Ningún log contiene material de la clave. `./mvnw test`: 74 en verde. Observación fuera de alcance: el `.gitignore` tiene una línea `.gitignore` y nunca se versionó; en un clon nuevo estas reglas no existen.

- [x] **TASK-06 · Caso de uso y service del inicio de sesión**
  - Alcance: crea `user/domain/ports/in/AuthenticateUserUseCase`, `user/application/usecases/AuthenticateUserUseCaseImpl` (login a minúscula, búsqueda, cuenta activa, contraseña y firma; `Optional.empty()` en los tres rechazos) y `user/application/exception/UserInvalidCredentialsException` (mensaje fijo, sin el login). Agrega `authenticateUser` a `UserService`.
  - Depende de: TASK-01, TASK-02
  - Resuelve: CA-02, CA-04, CA-05, CA-06 (lógica)
  - Validación: `./mvnw test -Dtest=AuthenticateUserUseCaseImplTest` con repositorio en memoria, hasher y emisor falsos:
    - `Maria.Gomez` obtiene token.
    - Login inexistente, contraseña incorrecta y cuenta inactiva dan `Optional.empty()`, y en esos casos no se emite token.
    - Test del service: el vacío se convierte en la excepción.
    - Chequeos 1 y 2 de la skill.
  - Resultado: 2026-10-20. `AuthenticateUserUseCaseImplTest`: 6 tests en verde (credenciales correctas, `Maria.Gomez`, login inexistente, contraseña incorrecta, cuenta inactiva y login de solo espacios; en los rechazos no se emite token). `UserServiceTest`: 2 tests en verde (devuelve el token; el vacío se convierte en `UserInvalidCredentialsException` con mensaje `Invalid credentials`). Prueba en negativo: sin el filtro de cuenta activa falla el test de la cuenta inactiva; restaurado con hash idéntico. Chequeos 1 y 2 de la skill sin resultados. `./mvnw test`: 84 en verde.

- [x] **TASK-07 · Endpoint `POST /api/authenticate`**
  - Alcance: crea `user/infrastructure/web/dto/AuthenticateRequest` (`@NotEmpty` en `username` y `password`) y `TokenResponse` (`id_token`); agrega `toTokenResponse` a `UserDtoMapper` y el endpoint a `UserController`, que traduce la excepción a `401` con `code` `INVALID_CREDENTIALS`; abre la ruta en `config/SecurityConfig`.
  - Depende de: TASK-06
  - Resuelve: CA-03, CA-04, CA-08, CA-09 y CA-10 (capa web)
  - Validación: `./mvnw test -Dtest=UserControllerTest`, con tests nuevos:
    - `200` con `id_token`.
    - `400` `VALIDATION_ERROR` sin campo, con `null` y con `""` en cada campo, sin llamar al service.
    - `401` `INVALID_CREDENTIALS` en `problem+json`.
    - `200` con `Authorization: Bearer xxx`.
    - `500` `INTERNAL_ERROR` cuando el service lanza `JwtEncodingException` y cuando lanza un error de acceso a datos.
    - Los tests de T01 siguen en verde.
  - Resultado: 2026-10-20. Tests en una clase nueva, `UserControllerAuthenticateTest`, para no mezclarlos con los de T01: 14 en verde. Cubren: `200` con solo `id_token`; `400` `VALIDATION_ERROR` sin campo, con `null` y con `""` en cada campo, sin llamar al service; un `username` de espacios llega al service (`401`); `401` `INVALID_CREDENTIALS` con `detail` `Invalid credentials`; `rememberMe` e `id` ignorados; `200` con `Authorization: Bearer xxx`; `500` `INTERNAL_ERROR` con `JwtEncodingException` y con `DataAccessResourceFailureException`, sin el mensaje interno; JSON mal formado. `UserDtoMapperTest` suma `toTokenResponse` (5 en verde). En `UserController`, el método auxiliar `badRequest` pasó a `error(status, code, e)` para usarlo también con el `401`; los 35 tests de T01 siguen en verde. Prueba en negativo: sin `/api/authenticate` en `permitAll`, el login responde `403`; restaurado con hash idéntico. `./mvnw test`: 99 en verde; los cinco chequeos de la skill sin resultados.

- [x] **TASK-08 · Test de integración del inicio de sesión**
  - Alcance: crea `src/test/java/turnos/user/AuthenticateUserIntegrationTest`, con la aplicación completa, Tomcat en puerto aleatorio y Postgres de Testcontainers. Registra con T01 e inicia sesión por HTTP.
  - Depende de: TASK-04, TASK-07
  - Resuelve: CA-01, CA-02, CA-05, CA-06, CA-07, CA-08, CA-11, CA-13
  - Validación: `./mvnw test -Dtest=AuthenticateUserIntegrationTest`:
    - El token se verifica con la clave pública de test y cumple CA-01.
    - `Maria.Gomez` inicia sesión.
    - Contraseña incorrecta, login inexistente y usuario desactivado con SQL devuelven cuerpos idénticos.
    - Con `rememberMe: true`, `exp - iat` es 86 400.
    - Funciona con `Authorization: Bearer xxx`.
    - Con `OutputCaptureExtension`, la salida no contiene la contraseña, el hash, el token ni `BEGIN PRIVATE KEY`.
  - Resultado: 2026-10-20. `AuthenticateUserIntegrationTest`: 6 tests en verde, con Tomcat en un puerto aleatorio (38315 en la corrida) y Postgres de Testcontainers; los usuarios se registran por HTTP con T01. El token se verifica con la clave pública de test: `RS256`, claims exactamente `sub`, `auth`, `iat` y `exp`, `sub` igual al id de la base, `ROLE_USER` y 86 400 s. `Maria.Gomez` inicia sesión. Contraseña incorrecta, login inexistente y cuenta desactivada con SQL devuelven cuerpos idénticos con `INVALID_CREDENTIALS`. `rememberMe: true` no cambia la vigencia. `Authorization: Bearer xxx` se ignora. La salida capturada no contiene las contraseñas, el hash, el token ni `BEGIN PRIVATE KEY`. Prueba en negativo: con el controller imprimiendo la contraseña, el test de privacidad falla; restaurado con hash idéntico.

- [x] **TASK-09 · Prueba manual con la aplicación levantada**
  - Alcance: levantar la aplicación con `./mvnw spring-boot:test-run` (Postgres temporal) y probar con `curl`. Sin cambios de código.
  - Depende de: TASK-07
  - Resuelve: CA-10 (con la base real detenida), evidencia manual de CA-01 y CA-04
  - Validación: registrar e iniciar sesión (`200` y token), contraseña incorrecta (`401`), y detener el contenedor de Postgres e iniciar sesión (`500` `INTERNAL_ERROR` y error en el log). Registrar los requests y las respuestas observadas
  - Resultado: 2026-10-20. Cambio sobre lo planeado: se usó `./mvnw spring-boot:run` con el compose en lugar de `spring-boot:test-run`, para probar también la configuración real (`.env` y `secrets/`). Registro de `manual.t02`: `201`. Inicio de sesión con `Manual.T02`: `200`, `application/json`, token de 520 caracteres; encabezado `{"kid":"…","alg":"RS256"}` (el `kid` lo agrega `NimbusJwtEncoder`; el contrato no lo exige ni lo prohíbe); payload `{"sub":"1","exp":1792563444,"auth":"ROLE_USER","iat":1792477044}` (86 400 s); `openssl dgst -sha256 -verify secrets/jwt-public.pem` sobre la firma: `Verified OK`; el id en la base es 1. Contraseña incorrecta: `401`, `application/problem+json`, `INVALID_CREDENTIALS`, `detail` `Invalid credentials`; login inexistente: el mismo cuerpo. Con `turnos-db` detenido: `500` `INTERNAL_ERROR`, `detail` `Internal server error`; el log tiene `ApiExceptionHandler : Unexpected error` y `Connection to localhost:5432 refused`. El log de la app no contiene la contraseña, la clave ni el token. Limpieza: usuario de prueba borrado, app detenida y compose apagado como estaba.

- [x] **TASK-10 · Regresión y chequeos finales**
  - Alcance: corrida completa y verificación de la skill. Sin cambios de código, salvo correcciones que surjan, que se anotan aquí.
  - Depende de: TASK-01 a TASK-09
  - Resuelve: regresión de T01 y comprobación de todos los CA
  - Validación: `./mvnw test` completo en verde; los cinco chequeos de la sección 12 de la skill sin resultados; revisión de que cada CA tiene su evidencia registrada en estas tareas
  - Resultado: 2026-10-20. `./mvnw clean test`: 105 tests en verde (los 65 de T01 más 40 nuevos de T02). Los cinco chequeos de la sección 12 de la skill sin resultados. `git check-ignore`: la clave sigue fuera del repositorio. Evidencia por criterio: CA-01 TASK-03, TASK-08 y TASK-09; CA-02 TASK-06 y TASK-08; CA-03 TASK-07; CA-04 TASK-06, TASK-07 y TASK-09; CA-05 TASK-06, TASK-08 y TASK-09; CA-06 TASK-06 y TASK-08; CA-07 TASK-08; CA-08 TASK-07 y TASK-08; CA-09 TASK-07; CA-10 TASK-07 y TASK-09; CA-11 TASK-03 (mensajes de arranque), TASK-08 y TASK-09; CA-12 TASK-02, TASK-03 y TASK-05; CA-13 TASK-08.
