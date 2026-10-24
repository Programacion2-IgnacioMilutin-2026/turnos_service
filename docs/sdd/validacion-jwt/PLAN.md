# PLAN: Validación del JWT en endpoints protegidos

**SPEC de referencia:** `docs/sdd/validacion-jwt/SPEC.md`
**Versión de la spec revisada:** aprobada el 2026-10-23 (todavía sin commit)
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

Rutas relativas a `src/main/java/turnos/`, salvo indicación.

| Componente o archivo existente | Ruta verificada | Responsabilidad y uso previsto |
| --- | --- | --- |
| `SecurityConfig` | `config/SecurityConfig.java` | Una sola cadena de seguridad: sin sesión, CSRF desactivado, `permitAll` para `POST /api/register` y `POST /api/authenticate`, el resto `authenticated()`. No tiene forma de autenticar, por eso hoy responde `403`. Se le agregan la validación del JWT, el formato de los rechazos y CORS |
| `JwtTokenIssuerAdapter` | `user/infrastructure/security/` | Carga la clave privada en su constructor (con los errores de arranque de T02, D3), obtiene la pública y firma. La carga de la clave se separa para que la use también la validación (P1) |
| `JwtProperties` | `user/infrastructure/security/` | `turnos.jwt.private-key-location` y `token-validity`. No cambia: la validación no necesita propiedades nuevas (DA) |
| `ClockConfig` | `config/ClockConfig.java` | Bean `Clock` en UTC. Lo usa también el validador de vencimiento, para controlar el reloj en los tests |
| `ApiExceptionHandler` | `config/ApiExceptionHandler.java` | Da formato a los errores de los controllers. No interviene en los rechazos de seguridad, que ocurren antes de llegar al controller |
| `TestRsaKeys`, `TestcontainersConfiguration` | `src/test/java/turnos/` | Par RSA de test por corrida y registro de su ubicación. Se reutilizan para firmar tokens de prueba |
| Tests web de `user` | `src/test/java/turnos/user/infrastructure/web/controller/` | `UserControllerTest` y `UserControllerAuthenticateTest` importan `SecurityConfig`: van a necesitar también la configuración de la validación (ver Riesgos) |
| Dependencias | `pom.xml` | Ya están `spring-boot-starter-security-oauth2-resource-server` y su starter de test. Versiones verificadas: Spring Security 7.1.1, Spring Framework 7.0.9, Nimbus JOSE 10.9.1, Jackson 3.1.5 (`tools.jackson`) |
| APIs verificadas con `javap` | `spring-security-oauth2-jose` y `-resource-server` 7.1.1 | `NimbusJwtDecoder.withPublicKey(...).signatureAlgorithm(...)`, `setJwtValidator`, `JwtTimestampValidator(Duration)` con `setClock`, `JwtClaimValidator`, `JwtTypeValidator`, `JwtGrantedAuthoritiesConverter` con `setAuthoritiesClaimName`, `setAuthorityPrefix` y `setAuthoritiesClaimDelimiter`, `DefaultBearerTokenResolver`. `JwtValidators.createDefault()` incluye una tolerancia de 60 s, así que no se usa (DD) |

**Convenciones y slice de referencia:** el slice `user` y las excepciones autorizadas: paquete `config`, `infrastructure/security` (decisiones 001 y 004), `@Configuration` dentro de un slice para armar beans técnicos (precedente: `catedra/infrastructure/client/CatedraClientConfiguration`). Comentarios del código sin citar documentos.

## Solución propuesta

<!-- Explica el enfoque y sus motivos. Describe las responsabilidades y el
recorrido de la solicitud, el evento o la tarea desde que entra hasta la
respuesta, la persistencia o el mensaje publicado. Referencia el diagrama de
secuencia del proyecto si ya lo describe; usa un diagrama nuevo solo si aporta
claridad. -->

Se usa el soporte de *resource server* de Spring Security, que ya está en el proyecto. La validación no es un caso de uso: ocurre en la cadena de filtros, antes de que el request llegue a un controller, y no toca `domain` ni `application` de ningún slice.

```
Request a un endpoint protegido
└─ Cadena de seguridad (config/SecurityConfig)
   ├─ CORS: preflight de un origen no permitido → rechazado; de uno permitido → respondido sin token  (RF-13)
   ├─ Lector del token: en las rutas públicas no busca token, así que nunca rechaza ahí              (RF-08, P2)
   ├─ Sin token, otro esquema o token mal formado → 401                                                (RF-02)
   ├─ JwtDecoder (bean de user/infrastructure/security, P1)
   │  ├─ firma RS256 con la clave pública obtenida de la privada                                       (RF-03, RF-10)
   │  ├─ exp sin tolerancia, con el Clock del servicio                                                 (RF-04)
   │  └─ sub presente y numérico                                                                       (RF-05)
   │     → cualquier falla: 401
   ├─ Conversor de roles (bean de user): el claim auth se convierte en autoridades, separadas por espacio, sin prefijo extra
   ├─ Regla de autorización (bean de user): al menos un rol de Authority → si no tiene ninguno: 403       (RF-06)
   └─ 401 y 403 con cuerpo problem+json fijo (config), sin code ni datos                               (RF-07, RF-12)
└─ Controller del slice: recibe el Jwt validado y saca sub y el texto del token                        (RF-09, P3)
```

- **Sin estado (RF-11):** el decodificador solo usa la clave pública; no consulta la base ni guarda nada.
- **`WWW-Authenticate`:** el `401` lleva `WWW-Authenticate: Bearer`, sin el motivo. El de Spring incluye `error_description` (por ejemplo "Jwt expired at …"), lo que revelaría el motivo que DB decidió ocultar.
- **Orden 401 antes que 400:** al estar en la cadena de filtros, el token se valida antes de que el controller lea los parámetros.
- **El tipo del token (`typ`):** el encabezado de los tokens de T02 no trae `typ` (observado en T02, TASK-09). Se acepta ausente o `JWT`, como hace por defecto `JwtTypeValidator.jwt()`; se confirma con un test.

## Slices y componentes afectados

<!-- Aplica la skill hexagonal-vertical-slicing: un paquete por slice, con sus
capas domain, application e infrastructure. Identifica los slices afectados, sus
responsabilidades y la dirección de sus dependencias. Una funcionalidad puede
afectar más de un slice. Justifica cualquier slice nuevo o dependencia nueva
entre slices. -->

| Slice | Existe / nuevo | Responsabilidad y cambios | Dependencias con otros slices |
| --- | --- | --- | --- |
| `user` (`turnos/user/`) | Existente | Dueño de la identidad: además de firmar, ofrece lo que depende de sus claves, su formato de token y sus roles: el `JwtDecoder`, el conversor de `auth` a autoridades y la regla "al menos un rol de `Authority`" (P1). Solo cambia `infrastructure/security` | Ninguna. `config` usa esos tres beans por interfaces de Spring, sin importar clases del slice |
| `config` (`turnos/config/`) | Existente (excepción autorizada) | Cadena de seguridad: conecta los beans de `user`, define las rutas públicas, el formato de los rechazos y CORS | No importa nada de los slices |

| Slice y capa | Componente o ruta | Acción | Cambio y responsabilidad | Requisito relacionado |
| --- | --- | --- | --- | --- |
| user / infrastructure | `security/JwtSigningKeys` | Crear | Carga la clave privada una sola vez (la lógica y los mensajes que hoy están en el adaptador) y expone la privada y la pública | RF-10 |
| user / infrastructure | `security/JwtTokenIssuerAdapter` | Modificar | Toma las claves de `JwtSigningKeys` en lugar de cargarlas; firma igual que hoy | RF-10 (sin cambios para T02) |
| user / infrastructure | `security/JwtValidationConfiguration` | Crear | Tres beans: `JwtDecoder` (clave pública, solo `RS256`, validadores de tipo, vencimiento sin tolerancia con el `Clock` y `sub` numérico); conversor de `auth` a autoridades; `AuthorizationManager` que exige al menos uno de los roles de `Authority` | RF-03 a RF-06, RF-10, RF-11 |
| config | `SecurityConfig` | Modificar | Resource server con el `JwtDecoder` y el conversor; lector de token que ignora las rutas públicas (P2); el resto de las rutas con el `AuthorizationManager`; manejadores de `401` y `403`; CORS. Recibe los beans por sus interfaces de Spring | RF-01, RF-02, RF-06, RF-08, RF-13 |
| config | `SecurityProblemHandler` | Crear | Escribe el `401` y el `403` en `problem+json` (`type`, `title`, `status`, `detail`, `instance`, igual que los demás errores, sin `code`) y `WWW-Authenticate: Bearer`. Nunca escribe el token | RF-07, RF-12 |
| config | `CorsProperties` | Crear | `turnos.cors.allowed-origins`, lista vacía por defecto | RF-13 |
| — | `src/main/resources/application.yaml`, `.env.example` | Modificar | `turnos.cors.allowed-origins: ${TURNOS_CORS_ALLOWED_ORIGINS:}` y la variable comentada, vacía | RF-13 |
| — | `CLAUDE.md` | Modificar | Sección Seguridad (cómo se valida y cómo obtiene la identidad un controller, P3) | — |
| — | `docs/decisiones/007-cors.md` y `008-validacion-del-jwt.md` | Crear | Textos de decisión (P4) | RF-13; P1 a P3 |

## Datos y contratos

<!-- Completa solo lo aplicable. Si un punto no aplica, indica el motivo. -->

- **Operaciones, eventos y DTO de entrada y salida:** no hay operaciones nuevas. Respuestas `401` y `403` según el contrato común §1.4 y §2, sin `code` (DB). El contrato no cambia.
- **Identificadores, relaciones y restricciones:** `sub` es el id del usuario como texto (dígitos). `auth` lista los roles separados por un espacio; las autoridades resultantes son exactamente esos textos (`ROLE_USER`, `ROLE_ADMIN`), sin prefijo agregado.
- **Origen de los datos devueltos o publicados y transformaciones:** el controller obtiene del `Jwt` validado `getSubject()` (identidad) y `getTokenValue()` (el token tal como llegó, para reenviarlo a catálogo en T03 y T04).
- **Persistencia, consultas y migraciones:** no aplica: la validación no lee ni escribe la base (RF-11).
- **Convivencia entre datos locales y remotos:** no aplica.
- **Compatibilidad y migraciones de datos existentes:** los tokens que ya emite T02 se validan sin cambios (restricción de la SPEC).

## Transacciones, concurrencia y errores

<!-- Cómo se implementan los comportamientos aprobados en la spec.
Referencia RF/CA y aplica las consideraciones relevantes de BACKEND_GUIDELINES.md. -->

- **Estados y transiciones:** no aplica.
- **Transacciones y consistencia:** no aplica.
- **Idempotencia y mensajes duplicados:** no aplica.
- **Concurrencia y tareas programadas:** el `JwtDecoder` y los manejadores no tienen estado mutable; se comparten entre requests sin bloqueos.
- **Timeouts, reintentos y errores de dependencias:** no hay dependencias externas.
- **Seguridad y autorización:**
  - Solo `RS256` con la clave pública de turnos: un token `HS256` o sin firma se rechaza (RF-03).
  - Rechazos: un token presente pero inválido lo rechaza el filtro del token; la ausencia de token o un esquema distinto llegan como anónimos y los rechaza la autorización. Los dos caminos usan el mismo manejador, así el cuerpo es idéntico (RF-07).
  - Rutas públicas: el lector del token comparte la definición de las rutas públicas con el `permitAll`, así no pueden quedar desparejas (P2).
  - CORS: orígenes exactos de la propiedad; métodos `GET`, `POST` y `OPTIONS`; encabezados `Authorization` y `Content-Type`; se expone `Retry-After` (lo usa T04 en `HOLD_NOT_SAVED`); sin credenciales de navegador (cookies); el resto con los valores por defecto de Spring. Con la lista vacía se registra igual una configuración sin orígenes, así un preflight siempre recibe la misma respuesta de rechazo de Spring (`403`).
- **Otras consideraciones de backend aplicables y su solución:**
  - Privacidad (RF-12): el manejador nunca incluye el token ni el motivo; no se registran los rechazos en `INFO` ni en `WARN`, para no llenar el log con cada request sin token. Los logs de Spring Security quedan en su nivel por defecto.

## Dependencias y configuración

<!-- Librerías, servicios externos, propiedades de configuración y secretos
afectados. Verifica compatibilidad con el proyecto y justifica las incorporaciones.
No agregues dependencias por defecto. Los valores sensibles y los parámetros
ajustables se externalizan. -->

- **Dependencias:** ninguna nueva.
- **Propiedad nueva:** `turnos.cors.allowed-origins: ${TURNOS_CORS_ALLOWED_ORIGINS:}`, lista separada por comas, vacía por defecto (ningún navegador permitido). No es un secreto. `.env.example` la documenta vacía.
- **Clave:** ninguna propiedad nueva; la pública se obtiene de `turnos.jwt.private-key-location` (DA).
- **Tests:** firman tokens con `TestRsaKeys` (y con otro par generado en el test para CA-03 y CA-11); los tests web registran la clave de test con un `DynamicPropertyRegistrar`, como `TestcontainersConfiguration`.

## Estrategia de validación

<!-- Una fila por criterio de la spec. Selecciona el método capaz de demostrarlo:
test unitario, de integración (base de datos, mensajería), de API o prueba manual.
No todos requieren todos los métodos. Identifica tests existentes y separa los
nuevos propuestos. Incluye regresiones relevantes. Una prueba con mocks no
demuestra persistencia, transacciones ni idempotencia. -->

Todavía no hay endpoints protegidos: se usa un controller que existe solo en los tests (`GET /api/probe`, devuelve el `sub` y el token que recibió), importado únicamente por los tests que lo necesitan. Los nombres de los tests describen el comportamiento, sin citar documentos.

| Criterio | Método y test existente o propuesto | Entorno y datos necesarios | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01, CA-02 | Web `ProtectedEndpointSecurityTest` (`@WebMvcTest` del controller de prueba con `SecurityConfig` y la validación) | Clave de test | `401` `problem+json`; el controller no se ejecuta |
| CA-03 | Unitario `JwtValidationConfigurationTest`; web para el `401` | Otro par RSA, un secreto HMAC, un token sin firma y uno alterado | `JwtException` en el decodificador; `401` en la web |
| CA-04 | Unitario con un `Clock` fijo | Tokens que vencen 1 s antes y después del reloj | Rechazado y aceptado |
| CA-05 | Unitario y web | Tokens sin `sub` y con `sub` `abc` | `401` |
| CA-06 | Unitario de la regla de autorización (roles tomados de `Authority`) y web | Tokens con `ROLE_USER`, `ROLE_ADMIN`, sin `auth`, vacío y `ROLE_OTRO` | Los dos primeros llegan; los otros, `403` |
| CA-07 | Web: comparar los cuerpos | Los casos de CA-01 a CA-06 | Cuerpos idénticos por estado; sin `code`; `WWW-Authenticate: Bearer` sin descripción |
| CA-08 | Integración `ProtectedEndpointIntegrationTest` (app completa) y regresión de `UserControllerTest` y `UserControllerAuthenticateTest` | Tokens válido, inválido y vencido | Mismas respuestas que sin encabezado en `/api/register` y `/api/authenticate` |
| CA-09 | Web | Token con `sub` `42` y `?userId=99` | El controller recibe `42` y el token exacto |
| CA-10 | Integración: la app arranca solo con la clave privada de test | — | Un token de T02 llega al endpoint de prueba |
| CA-11 | Unitario: dos decodificadores, con el mismo par y con uno nuevo | Dos pares | Aceptado y rechazado |
| CA-12 | Integración con `OutputCaptureExtension`, y revisión de los cuerpos en la web | Tokens de los casos anteriores | El token no aparece en la salida ni en los cuerpos |
| CA-13 | Integración: registrar, iniciar sesión y llamar al endpoint de prueba | Postgres de Testcontainers, puerto aleatorio | Llega con su `id`; `401` sin token |
| CA-14 | Web: token con un `sub` que no está en la base (sin base en `@WebMvcTest`) | — | Llega con ese `sub` |
| CA-15 | Web sin orígenes configurados: preflight a `/api/register` y a `/api/probe` | — | Rechazado, sin `Access-Control-Allow-Origin` |
| CA-16 | Web con `turnos.cors.allowed-origins=http://localhost:3000` | — | Preflight aceptado con los encabezados; `401` con encabezados CORS; llega con token; otro origen rechazado |
| CA-17 | Web: requests sin `Origin` con y sin orígenes configurados | — | Mismas respuestas |

**Comprobaciones de regresión:** los 178 tests actuales, en especial los de T01 y T02 con `Authorization` y la integración de X01; los cinco chequeos de la skill.

**Comandos verificados para compilar y ejecutar tests:** `./mvnw test`, `./mvnw test -Dtest=NombreDeLaClase` y `./mvnw clean verify`, documentados en `CLAUDE.md`.

**Pruebas con infraestructura real o simulada:** Postgres de Testcontainers en la integración; claves RSA reales generadas en cada corrida. Prueba manual con la aplicación levantada (`./mvnw spring-boot:run` y el compose) y `curl`: request sin token, con un token de T02, con un token alterado y un preflight con `Origin`.

**Limitaciones del entorno:** ninguna conocida; no hace falta la cátedra.

<!-- Esta sección planifica la validación. Durante la implementación, registra
en TASKS.md o en el informe de validación acordado los resultados y evidencias
reales. Distingue pruebas ejecutadas, fallidas, no ejecutadas y bloqueadas.
Compilar o tener tests en verde no sustituye revisar los criterios de la spec. -->

## Orden de implementación

<!-- Etapas y dependencias principales. El desglose ejecutable se escribe en TASKS.md.
Incluye puntos de comprobación para avanzar con cambios pequeños. -->

1. **Claves compartidas:** `JwtSigningKeys` y el adaptador de firma usándolas. Se comprueba con los tests de T02 sin cambios de comportamiento.
2. **Validación en el slice `user`:** `JwtValidationConfiguration` con el decodificador, el conversor y la regla de roles. Se comprueba con los unitarios de CA-03 a CA-06 y CA-11.
3. **Cadena de seguridad:** `SecurityConfig`, `SecurityProblemHandler`, el controller de prueba y la configuración de test compartida; ajuste de los tests web de `user`. Se comprueba con los tests web de CA-01, CA-02, CA-05 a CA-09 y CA-14, y la regresión.
4. **CORS:** `CorsProperties`, la configuración en `SecurityConfig`, `application.yaml` y `.env.example`. Se comprueba con CA-15 a CA-17.
5. **Integración y prueba manual:** CA-08, CA-10, CA-12 y CA-13 con la app completa, y `curl` con la app levantada.
6. **Cierre:** textos de decisión, `CLAUDE.md`, regresión completa, chequeos de la skill y SonarQube en el PR.

## Riesgos y decisiones pendientes

<!-- Riesgos concretos de esta solución y cómo se resolverán, sin listas genéricas.
Escribe Ninguna en las decisiones pendientes cuando estén resueltas. -->

- **Riesgos y medidas acordadas:**
  - Los `@WebMvcTest` de `user` importan `SecurityConfig`, que pasa a necesitar el `JwtDecoder` y la clave: sin ajuste, no arrancan. Se resuelve con una configuración de test compartida que importa la validación real y registra la clave de test; se comprueba en la etapa 3 con la regresión.
  - Que el filtro del token rechace un `Authorization` inválido en las rutas públicas (riesgo ya anotado en T02). Lo evita P2 y lo fijan los tests de CA-08.
  - Que el controller de prueba quede registrado en todos los contextos de test. Se declara de forma que solo lo carguen los tests que lo importan; se comprueba que `contextLoads` no lo expone.
  - SonarQube puede volver a marcar el CSRF desactivado si cambia la línea. Ya está justificado (decisión 006); se marca otra vez como falso positivo si aparece.
- **Decisiones pendientes:**
  - Ninguna.
- **Decisiones tomadas (2026-10-23; el usuario aprobó el PLAN ese mismo día):**
  - **P1 · Quién arma las piezas que dependen del usuario:** `user/infrastructure/security` ofrece tres beans con tipos de Spring: el `JwtDecoder`, el conversor de `auth` a autoridades y la regla "al menos un rol de `Authority`" (`AuthorizationManager`). `SecurityConfig` solo los conecta por esas interfaces, sin importar nada del slice. Así la emisión y la validación comparten clave, formato y roles en un solo lugar, el enum sigue siendo la única lista de roles y un rol nuevo queda aceptado sin tocar `config`, coherente con "cualquier rol". Un endpoint futuro que exija un rol puntual necesitará su propia regla. Alternativas descartadas: que `config` importe `Authority` (rompe la regla de que `config` no importa los slices); escribir los roles como texto en `config` (dos listas de roles); un paquete `shared` con `Authority` (el dominio de `user` importaría un tipo ajeno, para un solo enum que usa un solo slice; se puede reconsiderar si otro slice necesita los roles).
  - **P2 · Cómo se ignora el token en las rutas públicas:** una sola cadena de seguridad, con un lector del token que no busca nada en las rutas públicas, usando la misma lista de rutas que el `permitAll`. Alternativa descartada: dos cadenas, que repiten la configuración común y la línea del CSRF.
  - **P3 · Cómo obtiene la identidad un controller (desde T03):** el controller recibe el `Jwt` validado (`@AuthenticationPrincipal Jwt`) y pasa al service el `sub` como `Long` y el texto del token como `String`. El `Jwt` queda en la capa web, igual que los DTO, y el service y el caso de uso reciben solo valores simples, sin conocer Spring Security. No agrega tipos ni dependencias entre slices ni hacia `config`. Alternativas descartadas: un tipo propio con la identidad (`AuthenticatedUser`) y una anotación para recibirlo, en `config` o en `user` (todos los slices protegidos pasarían a importar ese paquete, y dentro de `user` no hay una capa que otro slice pueda usar para algo técnico como esto); leer la identidad desde el caso de uso (la capa `application` dependería de Spring Security). **Puede cambiar:** si al implementar T03 a T11 la extracción se repite demasiado, o la identidad suma datos, se revisa con un paquete `shared` (shared kernel, skill §9) que contenga el tipo de la identidad y su armado; es la alternativa más consistente después de esta.
  - **P4 · Textos de decisión:** dos textos, `007-cors.md` y `008-validacion-del-jwt.md` (dónde vive la validación y cómo obtiene la identidad cada controller, P1 a P3), para que T03 a T11 tengan un solo lugar al cual remitir. Se redactan en la etapa de cierre.
