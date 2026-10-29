# PLAN: T04 — Bloquear turno temporalmente (crear hold)

**SPEC de referencia:** `docs/sdd/t04-bloquear-turno/SPEC.md`
**Versión de la spec revisada:** aprobada el 2026-10-26 (todavía sin commit)
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

Rutas relativas a `src/main/java/turnos/`, salvo indicación. Verificado el 2026-10-26 en la rama `bloquear-turno` (`a02dfa2`), con `./mvnw clean test` en verde (367 tests).

| Componente o archivo existente | Ruta verificada | Responsabilidad y uso previsto |
| --- | --- | --- |
| `GetProfessionalUseCase`, `Professional`, `ProfessionalResponse`, `ProfessionalMapper` | `catalog/` | C06 con reintentos (T03). El modelo solo tiene `id`, `enabled` y horarios; T04 lo amplía con nombre, apellido y nombre de la categoría (RF-21) |
| `GetOccupanciesUseCaseImpl` | `catedra/application/usecases/` | Contiene la lógica de sesión (error de configuración, degradada → login), `401` → login y una repetición, y reintentos con `CatedraOperationPolicy`. Se extrae a una pieza común (P2) y la reutiliza la operación nueva de crear hold |
| `CatedraOccupancyClientAdapter`, `CatedraErrorResponse`, `CatedraProperties.Operations` | `catedra/infrastructure/client/` | Patrón del cliente: un intento, traducción a resultado sellado, lectura del `code` del error. El hold usa los mismos tiempos `operations.*` (RF-24) |
| `TechnicalSessionRepository`, `RenewTechnicalSessionUseCase` | `catedra/domain/ports/` | Sin cambios; los usa la pieza común |
| `User`, `UserRepository`, `JpaUserRepository` | `user/` | El puerto de salida no tiene `findById` ni `existsById`; `JpaRepository` sí tiene `existsById`. T04 necesita saber si el usuario existe (RF-26, P4) |
| `CatalogAgendaAdapter`, `CatedraOccupancyAdapter` | `availability/infrastructure/gateway/` | Patrón de cruce entre slices (decisión 009) que repite el slice nuevo |
| `ProfessionalAgenda.slotsOf` | `availability/domain/model/` | Regla de generación de turnos de T03. Cuenta en minutos e ignora los segundos; la copia propia de T04 debe rechazar segundos distintos de cero (D6) |
| `ApiExceptionHandler` | `config/` | `handleHttpMessageNotReadable` responde `VALIDATION_ERROR` sin `fieldErrors`; D7 lo amplía. Hereda `handleErrorResponseException`, que copia los encabezados de la excepción a la respuesta |
| `ResponseStatusException` | `spring-web` 7.0.9 | Verificado con `javap`: `getHeaders()` devuelve siempre `HttpHeaders.EMPTY`, así que no puede llevar `Retry-After` ni `WWW-Authenticate`. `ErrorResponseException` sí devuelve sus encabezados (mutables) (P7) |
| `MismatchedInputException` | `jackson-databind` 3.1.5 (`tools.jackson`) | Verificado con `javap`: hereda `getPath()` de `JacksonException`, con el nombre de cada campo; permite saber qué campo no se pudo convertir (D7) |
| `JdkClientHttpRequestFactory` | `spring-web` 7.0.9 | `setReadTimeout` y el `connectTimeout` del `HttpClient` del JDK. Distinguir conexión rechazada, timeout de conexión y timeout de respuesta (D1) depende de la causa de `ResourceAccessException`; se comprueba con un test al empezar (ver Riesgos) |
| `SecurityProblemHandler` | `config/` | Cuerpo fijo del `401` (`title`, `status`, `detail` "A valid token is required", `instance`) y `WWW-Authenticate: Bearer`. El `401` de RF-26 debe ser idéntico |
| Migraciones | `src/main/resources/db/migration/V1__create_users.sql` | Solo `users` y `user_authorities`. T04 agrega `V2` |
| Pruebas | `src/test/java/turnos/` | `FakeHttpServer`, `FakeCatedraServer`, `MutableClock`, `TestTokens`, `TestcontainersConfiguration`, `TestSecurityConfiguration`; grabaciones en `src/test/resources/catedra/`; E2E en `src/test/java/e2e/` |
| Tests de T03 que cambian | `src/test/java/turnos/` | `CatalogClientAdapterTest` (hoy comprueba que se ignoran nombre y categoría), `ProfessionalMapperTest`, `GetOccupanciesUseCaseImplTest` (regresión de la extracción), `CatedraOccupancyClientAdapterTest` (pasa a usar la grabación con un `HELD` real, D8 fila 7), `FreeSlotsE2ETest` (el catálogo simulado devuelve nombre y categoría) |

**Convenciones y slice de referencia:** `availability` (cruces por `infrastructure/gateway`, configuración en `infrastructure/config`, excepciones por error del contrato, controller con textos fijos) y `catedra` (cliente de un intento con resultado sellado, reintentos en el caso de uso). `user` para la persistencia JPA y la migración. Comentarios y nombres de tests sin citar documentos (`CLAUDE.md`).

## Solución propuesta

<!-- Explica el enfoque y sus motivos. Describe las responsabilidades y el
recorrido de la solicitud, el evento o la tarea desde que entra hasta la
respuesta, la persistencia o el mensaje publicado. Referencia el diagrama de
secuencia del proyecto si ya lo describe; usa un diagrama nuevo solo si aporta
claridad. -->

Un slice nuevo, `reservation` (P1), para el proceso de reserva, que van a usar T04 a T13. Llega a `user`, `catalog` y `catedra` por puertos de salida propios con adaptadores en `infrastructure/gateway`, como `availability` (decisión 009). El recorrido sigue el diagrama de secuencia §3 (bloque T04):

```
POST /api/reservation-processes  (JWT ya validado por la cadena de seguridad)
└─ Controller: @Valid @RequestBody (presencia, rango; HH:mm o HH:mm:ss, D6); errores de conversión → fieldErrors (D7, P8)
   └─ Service → CreateReservationProcessUseCase = Impl
      ├─ turno anterior o igual al momento actual en la zona de atención → 400 VALIDATION_ERROR (RF-02, D10)
      ├─ puerto "usuario existe" = gateway → user (P4) → no existe → 401 como token inválido (RF-26, D5)
      ├─ candado del usuario (P5): todo lo que sigue, de a un pedido por usuario (RF-05, D3)
      │  ├─ repositorio: procesos abiertos del usuario; los vencidos más el margen → EXPIRED (RF-03, D4)
      │  ├─ queda uno abierto → 409 OPEN_PROCESS_EXISTS con sus datos (RF-04)
      │  ├─ puerto "profesional" = gateway → catalog · GetProfessionalUseCase (RF-06 a RF-10)
      │  ├─ deshabilitado → 409 (RF-11, D2); el turno no lo genera ningún horario → 400 INVALID_SLOT (RF-12)
      │  ├─ puerto "hold" = gateway → catedra · CreateHoldUseCase (RF-13 a RF-20)
      │  │                 └─ pieza común (P2): sesión, 401 → login y 1 repetición, reintentos solo si es seguro (D1)
      │  └─ repositorio.create(proceso HELD)
      │       ├─ la base ya tiene un proceso abierto del usuario → 409 OPEN_PROCESS_EXISTS, hold sin proceso registrado (RF-05)
      │       └─ otra falla → 500 HOLD_NOT_SAVED con expiresAt y Retry-After, error crítico (RF-23)
   └─ 201 con el proceso (RF-22)
```

- **Validación del turno, copia propia** (decidido con la SPEC): el slice nuevo tiene su propio tipo con los horarios del profesional y un método que responde si un horario habilitado del día genera el turno. No reutiliza `availability`.
- **Sin transacciones largas:** cada operación del repositorio es su propia transacción corta. Ninguna queda abierta durante las llamadas a catálogo o a la cátedra.
- **Candado:** lo toma solo el usuario que pide; usuarios distintos no se esperan. Se libera siempre al terminar, también ante un error.

## Slices y componentes afectados

<!-- Aplica la skill hexagonal-vertical-slicing: un paquete por slice, con sus
capas domain, application e infrastructure. Identifica los slices afectados, sus
responsabilidades y la dirección de sus dependencias. Una funcionalidad puede
afectar más de un slice. Justifica cualquier slice nuevo o dependencia nueva
entre slices. -->

| Slice | Existe / nuevo | Responsabilidad y cambios | Dependencias con otros slices |
| --- | --- | --- | --- |
| `reservation` (`turnos/reservation/`) | Nuevo (P1) | T04: endpoint, reglas del proceso, persistencia, candado y validación del turno | Usa los puertos de entrada de `user` (P4), `catalog` y `catedra` desde adaptadores propios |
| `catedra` | Existente | Extrae la pieza común (P2) y suma "crear hold" | Ninguna |
| `catalog` | Existente | El profesional suma nombre, apellido y nombre de la categoría | Ninguna |
| `user` | Existente | Suma un puerto de entrada para saber si un usuario existe (P4) | Ninguna |
| `config` | Existente | `ApiExceptionHandler`: errores de conversión del cuerpo con `fieldErrors` (D7, P8) | No importa nada de los slices |

| Slice y capa | Componente o ruta | Acción | Cambio y responsabilidad | Requisito relacionado |
| --- | --- | --- | --- | --- |
| catalog / domain | `model/Professional` | Modificar | Suma `firstName`, `lastName`, `categoryName` | RF-21 |
| catalog / infrastructure | `client/dto/ProfessionalResponse`, `client/dto/CategoryResponse` (nuevo), `client/mapper/ProfessionalMapper` | Modificar / crear | Leer `firstName`, `lastName` y `category.name` | RF-21 |
| catedra / application | Pieza común en `support/` (P2) y `usecases/GetOccupanciesUseCaseImpl` | Crear / modificar | La lógica de sesión, `401` y reintentos sale del caso de uso de ocupaciones a la pieza común, sin cambiar su comportamiento | RF-14, RF-15, RF-20 |
| catedra / domain | `model/Hold`, `model/HoldResult` (P3), `ports/in/CreateHoldUseCase`, `ports/out/CatedraHoldClient` | Crear | El hold concedido (`holdId`, `reservationProcessId`, `expiresAt`) y los resultados posibles | RF-13 a RF-20 |
| catedra / application | `usecases/CreateHoldUseCaseImpl` | Crear | Usa la pieza común; reintenta solo lo seguro (D1) | RF-13 a RF-20 |
| catedra / infrastructure | `client/CatedraHoldClientAdapter`, `client/dto/CreateHoldRequest`, `client/dto/HoldResponse`, `client/mapper/HoldMapper` | Crear | `POST /api/appointment-holds` en un intento; traduce cada respuesta y distingue "no llegó" de "pudo llegar" (P3) | RF-13 a RF-20 |
| user / domain + application | `ports/in/ExistsUserByIdUseCase` y `usecases/ExistsUserByIdUseCaseImpl` (P4); `ports/out/UserRepository` suma `existsById` | Crear / modificar | Saber si existe un usuario por id, sin devolver sus datos | RF-26 |
| user / infrastructure | `persistence/adapter/JpaUserRepositoryAdapter` | Modificar | Implementa la consulta con `existsById` | RF-26 |
| reservation / domain | Modelo del proceso y su estado, horarios del profesional con la regla del turno, política (zona y margen), puertos de entrada y salida | Crear | Reglas del dominio (`isOpen`, `hasExpired`, turno generado por un horario) | RF-02 a RF-05, RF-11, RF-12, RF-21 |
| reservation / application | Caso de uso (con el candado por usuario, P5), service, excepciones del slice | Crear | El recorrido de la Solución propuesta; una excepción por cada error del contrato §3.4 | RF-02 a RF-23, RF-26 |
| reservation / infrastructure | `persistence/` (entidad, repositorio JPA, mapper, adaptador) y `V2__create_reservation_processes.sql` | Crear | Tabla con clave foránea a `users` e índice único parcial de un proceso abierto por usuario (P6) | RF-03 a RF-05, RF-21, RF-26 |
| reservation / infrastructure | `web/` (controller, request, response, mapper) | Crear | `POST /api/reservation-processes`; `OPEN_PROCESS_EXISTS` con datos extra y `HOLD_NOT_SAVED` con `Retry-After` (P7) | RF-01, RF-02, RF-04, RF-22, RF-23, RF-26 |
| reservation / infrastructure | `gateway/` (tres adaptadores, hacia `user`, `catalog` y `catedra`) | Crear | Traducen los resultados de los otros slices a los tipos y excepciones propios | RF-06 a RF-20, RF-26 |
| reservation / infrastructure | `config/` (propiedades y configuración) | Crear | Margen (D4) y zona de atención | RF-03, RF-24 |
| config | `ApiExceptionHandler` | Modificar | `fieldErrors` para los campos del cuerpo que no se pueden convertir (P8); los errores con encabezados ya los formatea sin cambios (P7) | RF-02 |
| — | `application.yaml`, `.env.example`, `CLAUDE.md` | Modificar | Variable del margen; slice nuevo, excepción `application/support` y decisión 011 | RF-24 |
| — (tests) | `src/test/resources/catedra/` y su `README.md` | Crear / modificar | Grabaciones de D8 | CA-10, CA-11, CA-13 |
| — (tests) | `src/test/java/e2e/` | Crear | E2E de T04 con la cancelación del hold (D9) | CA-20 |
| — | `docs/decisiones/011-pieza-comun-de-la-catedra.md` y `012-persistencia-del-proceso-de-reserva.md` | Creados (P2, P10) | Textos de decisión | — |

## Datos y contratos

<!-- Completa solo lo aplicable. Si un punto no aplica, indica el motivo. -->

- **Operaciones, eventos y DTO de entrada y salida:** `POST /api/reservation-processes` según el contrato de turnos §2 y §3.4; C06 según el contrato de catálogo §3.1; crear hold según el contrato §4.2.4 (ya actualizado por D1). Ningún contrato cambia en el PLAN.
- **Identificadores, relaciones y restricciones:** el proceso se identifica por el `reservationProcessId` de la cátedra (dominio regla 4), que es la clave primaria (P6a), en una columna amplia (`VARCHAR(255)`, a confirmar con la grabación real). Clave foránea a `users` (D5) e índice único parcial: un solo proceso en `HELD`, `WAITING_FOR_PHONE` o `PHONE_SUBMITTED` por usuario (D3).
- **Origen de los datos devueltos o publicados y transformaciones:** `holdId`, `reservationProcessId` y `expiresAt` de la cátedra; nombre, apellido y categoría de C06; `startTime` se emite `HH:mm` y `expiresAt` en ISO-8601 UTC.
- **Persistencia, consultas y migraciones:** `V2__create_reservation_processes.sql`. Consultas: procesos abiertos de un usuario; marcar `EXPIRED`; insertar. Columnas y estados según P6b. La clave foránea y el índice único parcial tienen nombres propios, para que el adaptador distinga cuál de las dos reglas rechazó un `INSERT`.
- **Convivencia entre datos locales y remotos:** el proceso local es una copia del hold; su vigencia la decide la cátedra (`expiresAt`). Los datos del profesional son históricos.
- **Compatibilidad y migraciones de datos existentes:** no aplica; la tabla es nueva y `users` no cambia.

## Transacciones, concurrencia y errores

<!-- Cómo se implementan los comportamientos aprobados en la spec.
Referencia RF/CA y aplica las consideraciones relevantes de BACKEND_GUIDELINES.md. -->

- **Estados y transiciones:** T04 crea procesos en `HELD` y pasa a `EXPIRED` los `HELD` o `WAITING_FOR_PHONE` vencidos más el margen (dominio §2, reglas 7 y 8). `PHONE_SUBMITTED` vencido no se cierra (lo hará T09, T10 o T12).
- **Transacciones y consistencia:** transacciones cortas por operación del repositorio. Si el insert falla después de un `201` de la cátedra, el hold queda huérfano y vence solo (RF-23); no hay compensación.
- **Idempotencia y mensajes duplicados:** un reintento de la app después de un éxito encuentra el proceso abierto (RF-04). No hay mensajes.
- **Concurrencia y tareas programadas:** candado por usuario (P5) más el índice único parcial (D3). Sin tareas programadas.
- **Timeouts, reintentos y errores de dependencias:** catálogo igual que T03. Cátedra con `operations.*`: reintenta solo conexión rechazada, timeout de conexión y `503`; timeout de respuesta, corte durante la respuesta y `500` → un solo intento, warning y `503` `CENTRAL_UNAVAILABLE` (D1). Tabla completa de traducción en la SPEC (RF-07 a RF-20).
- **Seguridad y autorización:** el `sub` es el dueño; solo se consultan procesos de ese usuario. El `401` de usuario inexistente es idéntico al de la capa de seguridad (P7).
- **Otras consideraciones de backend aplicables y su solución:** el error crítico de RF-23 y el de RF-05 registran `holdId`, `reservationProcessId` y `expiresAt` (no son secretos) para poder rastrear el hold huérfano; nunca tokens.

## Dependencias y configuración

<!-- Librerías, servicios externos, propiedades de configuración y secretos
afectados. Verifica compatibilidad con el proyecto y justifica las incorporaciones.
No agregues dependencias por defecto. Los valores sensibles y los parámetros
ajustables se externalizan. -->

- **Dependencias:** ninguna nueva.
- **Propiedades nuevas** (P9), en `application.yaml` con su variable y su valor por defecto; la del margen se documenta comentada en `.env.example`, como las opcionales de T03:

| Propiedad | Variable | Por defecto |
| --- | --- | --- |
| `turnos.reservation.safety-margin` | `TURNOS_RESERVATION_SAFETY_MARGIN` | `30s` (D4) |
| `turnos.reservation.zone-id` | `TURNOS_ATTENTION_ZONE` (la misma que `availability`) | `America/Argentina/Mendoza` |

- Los tiempos y reintentos hacia la cátedra (`turnos.catedra.operations.*`) y hacia catálogo (`turnos.catalog.*`) son los de T03, sin propiedades nuevas.
- **Ningún valor es secreto.**

## Estrategia de validación

<!-- Una fila por criterio de la spec. Selecciona el método capaz de demostrarlo:
test unitario, de integración (base de datos, mensajería), de API o prueba manual.
No todos requieren todos los métodos. Identifica tests existentes y separa los
nuevos propuestos. Incluye regresiones relevantes. Una prueba con mocks no
demuestra persistencia, transacciones ni idempotencia. -->

| Criterio | Método y test existente o propuesto | Entorno y datos necesarios | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01 | Web `ReservationProcessControllerTest` (`@WebMvcTest`, service simulado): faltantes, valores inválidos, conversiones (`"abc"`, `2026-02-30`, `25:00`), `1.5`, `1.0`, número enorme, `startTime` en las dos formas, JSON mal escrito. Unitario del caso de uso: turno de hoy ya pasado y que empieza justo ahora, en la zona de atención. `ApiExceptionHandlerTest` y regresión de `UserControllerTest` y `UserControllerAuthenticateTest` (D7) | Service simulado; `MutableClock` | `400` con el campo (sin `fieldErrors` en el JSON mal escrito); el service y los puertos sin llamadas |
| CA-02 | Unitario `CreateReservationProcessUseCaseImplTest` con puertos falsos; integración del adaptador de persistencia | `MutableClock`; Postgres de Testcontainers | Proceso viejo `EXPIRED` en la base; proceso nuevo `HELD` |
| CA-03 | Unitario del caso de uso (cada estado abierto; proceso de otro usuario); integración del adaptador (consulta de abiertos por usuario) | Puertos falsos; Testcontainers | `409` con los datos; catálogo y cátedra sin llamadas |
| CA-04 | Unitario del caso de uso con dos hilos del mismo usuario y un proveedor de holds que demora; dos hilos de usuarios distintos. Integración del adaptador: insertar un segundo proceso abierto del mismo usuario | Puertos falsos que cuentan llamadas; Testcontainers | Un `201` y un `409`; un solo pedido de hold; la base rechaza el segundo abierto y el log identifica el hold; los dos usuarios llegan a la cátedra a la vez |
| CA-05 | `CatalogClientAdapterTest` (existente, ya comprueba el `Authorization`); unitario del adaptador `gateway` hacia `catalog`; integración | Servidor simulado | Ruta y `Authorization` del usuario |
| CA-06, CA-07 | Unitario del adaptador `gateway` (cada resultado de `catalog` → su excepción); unitario del caso de uso (cátedra sin llamadas); integración con catálogo en `503` | Puertos falsos; servidor simulado | `404`, `500` o `503` según el caso; cátedra sin requests |
| CA-08 | Unitario del caso de uso | Puertos falsos | `409`; cátedra sin llamadas; categoría deshabilitada sin efecto |
| CA-09 | Unitarios del dominio de `reservation` (la regla del turno, sin Spring) | — | Exactamente los turnos válidos; segundos distintos de cero rechazados |
| CA-10 | `CatedraHoldClientAdapterTest` con el `201` grabado (request: cuerpo, `HH:mm`, `externalPatientId`, `Bearer`); unitario del caso de uso; `HoldMapperTest`; mapper round-trip de persistencia y del DTO (skill §10); integración | Grabaciones; Testcontainers | Request y respuesta según el criterio; proceso en la base con su `holdId`; la respuesta sin `holdId` |
| CA-11, CA-12 | Unitario de la pieza común (`support`) con cliente falso, `ScriptedLoginClient` y sesión en memoria; regresión de `GetOccupanciesUseCaseImplTest` | Dobles existentes de X01 y T03 | Un login y una repetición; `ConfigurationError` → `SERVICE_DEGRADED`; degradada sin sesión → `CENTRAL_UNAVAILABLE` |
| CA-13 | `CatedraHoldClientAdapterTest` con las grabaciones (`404`, `400` `INVALID_SLOT`, `409` `SLOT_ALREADY_HELD`, `PROFESSIONAL_DISABLED` si se grabó) y `SLOT_ALREADY_RESERVED` armado a mano; adaptador `gateway`; web (estados y `code`) | Grabaciones | Código de turnos de cada caso; ningún proceso guardado |
| CA-14 | `CatedraHoldClientAdapterTest`: puerto cerrado, timeout de conexión y `503` → no llegó; demora de respuesta y `500` → pudo llegar; `502` → no prevista. Unitario de la pieza común: reintenta lo primero, no lo segundo | Servidor simulado; esperas de milisegundos | Intentos según el caso; warning en el log; `503` o `500` |
| CA-15 | Unitario del caso de uso con un repositorio que falla después del hold; web: encabezado `Retry-After` y `expiresAt` | Puertos falsos; `MutableClock` | `500` `HOLD_NOT_SAVED` con `Retry-After` en segundos; error crítico con el `holdId` |
| CA-16 | `ReservationPropertiesTest` (por defecto, vacía y configurada); unitario del dominio con vencidos hace 29 y 31 s | `ApplicationContextRunner`; reloj fijo | 30 s por defecto; solo el de 31 s vencido |
| CA-17 | `OutputCaptureExtension` en la pieza común, el cliente del hold y la integración | — | Intentos registrados; sin tokens |
| CA-18 | Web: comparar el `401` de usuario inexistente con el de un token alterado; integración con un token de un `sub` sin usuario; integración de persistencia: borrar un usuario con un proceso | `TestTokens`; Testcontainers | Cuerpos y `WWW-Authenticate` idénticos; sin requests externos; borrado rechazado |
| CA-19 | Integración `ReservationProcessIntegrationTest`: aplicación completa, Postgres, catálogo y cátedra simulados (grabaciones) | Testcontainers y servidores simulados | `201` y después `409` `OPEN_PROCESS_EXISTS`; proceso en la base |
| CA-20 | E2E `src/test/java/e2e/ReservationProcessE2ETest` (`-Pe2e`): registro, login, turnos libres de T03, bloqueo con la cátedra real y cancelación directa al terminar | `.env` local; Testcontainers; catálogo simulado | `201` real; hold cancelado; sin credenciales, salteado |

Además, `FreeSlotsE2ETest` y `CatedraOccupancyClientAdapterTest` pasan a usar el catálogo simulado con nombre y categoría, y la grabación de ocupaciones con un `HELD` real (D8, fila 7), respectivamente. Prueba manual con la aplicación levantada y `curl`, con catálogo simulado y la cátedra real, con las llamadas confirmadas antes.

**Comprobaciones de regresión:** los 367 tests actuales (en especial `GetOccupanciesUseCaseImplTest` después de la extracción, los de catálogo y los de T01 y T02 con el cambio de D7) y los cinco chequeos de la skill.

**Comandos verificados para compilar y ejecutar tests:** `./mvnw test`, `./mvnw test -Dtest=NombreDeLaClase`, `./mvnw clean verify` y `./mvnw test -Pe2e` (`CLAUDE.md`). Conteo con los reportes XML de Surefire.

**Pruebas con infraestructura real o simulada:** suite normal con catálogo y cátedra simulados (respuestas grabadas de D8) y Postgres de Testcontainers; E2E con la cátedra real (D9); prueba manual con la aplicación levantada.

**Limitaciones del entorno:** el servicio de catálogo real no existe. `SLOT_ALREADY_RESERVED` no se puede provocar en la cátedra real hasta tener T05 y Kafka.

<!-- Esta sección planifica la validación. Durante la implementación, registra
en TASKS.md o en el informe de validación acordado los resultados y evidencias
reales. Distingue pruebas ejecutadas, fallidas, no ejecutadas y bloqueadas.
Compilar o tener tests en verde no sustituye revisar los criterios de la spec. -->

## Orden de implementación

<!-- Etapas y dependencias principales. El desglose ejecutable se escribe en TASKS.md.
Incluye puntos de comprobación para avanzar con cambios pequeños. -->

1. **Grabaciones de la cátedra real (D8)**, confirmando cada request antes de enviarlo. Primero, porque fijan los formatos, el largo real del `reservationProcessId` (P6a) y la duración del hold (D4). Se comprueba con la limpieza verificada por `grep`.
2. **`catalog` ampliado** (nombre, apellido y categoría). Se comprueba con sus tests ampliados y la regresión de T03.
3. **Pieza común de `catedra`** (respuesta común y componente en `support/`) y **ocupaciones adaptadas** a ella, sin cambios de comportamiento. Se comprueba con `GetOccupanciesUseCaseImplTest` y la suite de T03 en verde.
4. **Operación "crear hold" en `catedra`**: dominio, caso de uso, cliente, DTO y mapper. Lo primero es el test que distingue timeout de conexión y de respuesta (ver Riesgos). Se comprueba con CA-11 a CA-14 a nivel de `catedra`.
5. **Puerto `ExistsUserByIdUseCase` en `user`.** Se comprueba con su test y la regresión de T01 y T02.
6. **Dominio de `reservation`**: proceso, estados, regla del turno y política. Se comprueba con CA-09 y CA-16 (dominio).
7. **Persistencia de `reservation`**: migración `V2`, entidad con `Persistable`, repositorio, mapper y adaptador. Se comprueba con tests contra Postgres de Testcontainers (CA-02, CA-04, CA-18).
8. **Caso de uso, candado, excepciones, service y adaptadores `gateway`.** Se comprueba con CA-02 a CA-08, CA-15 y CA-18 en unitarios.
9. **Web y `ApiExceptionHandler`** (P7, P8). Se comprueba con CA-01, CA-13, CA-15 y CA-18 a nivel web, y la regresión de T01 y T02.
10. **Configuración e integración**: propiedades, `application.yaml`, `.env.example` y la integración completa. Se comprueba con CA-16, CA-17 y CA-19.
11. **E2E** (D9) **y test de T03 con la grabación del `HELD` real.** Se comprueba con CA-20, con y sin credenciales.
12. **Prueba manual, `CLAUDE.md`** (slice `reservation`, excepciones nuevas, decisiones 011 y 012) **y regresión completa** con los chequeos de la skill.

## Riesgos y decisiones pendientes

<!-- Riesgos concretos de esta solución y cómo se resolverán, sin listas genéricas.
Escribe Ninguna en las decisiones pendientes cuando estén resueltas. -->

- **Riesgos y medidas acordadas:**
  - Distinguir timeout de conexión y timeout de respuesta depende de cómo el cliente HTTP del JDK y Spring envuelven la causa (`HttpConnectTimeoutException` es una subclase de `HttpTimeoutException`). Se comprueba con un test contra el servidor simulado al empezar la operación del hold; si no se pueden distinguir, se vuelve a la SPEC.
  - El candado retiene al usuario durante las llamadas a catálogo y a la cátedra (hasta unos 22 s en el peor caso con los valores de T03). Solo afecta a pedidos del mismo usuario. Los candados se descartan al soltarse, para que el mapa no crezca con cada usuario que alguna vez bloqueó un turno; lo cubre un test.
  - Simular un timeout de conexión en un test puede ser frágil (depende de la red). Si no se logra de forma confiable, la clasificación se prueba pasándole al traductor del cliente las excepciones del JDK, y se anota en TASKS.
  - Que el adaptador confunda el rechazo por la clave foránea (usuario inexistente) con el del índice único (proceso abierto). Las dos restricciones tienen nombre propio y un test de persistencia comprueba cada una.
  - Cambiar `CatedraOccupancyClientAdapterTest` a la grabación con un `HELD` real puede mostrar que el formato real difiere del que supone T03. Si pasa, se informa como defecto de T03 antes de corregir.
  - Extraer la pieza común puede cambiar el comportamiento de T03 sin querer. Lo cubren sus tests actuales, que no deben cambiar.
- **Decisiones pendientes:**
  - Ninguna.
- **Decisiones tomadas:**
  - **P1 · Nombre del slice nuevo (opción b, 2026-10-26):** `reservation` (`turnos.reservation`), con clases `ReservationProcess…`, tabla `reservation_processes` y ruta `/api/reservation-processes`. Nombra el área del negocio, que cubre todo el ciclo (del bloqueo a la reserva confirmada o cancelada), como `availability` en T03 (decisión B de su PLAN). Alternativa descartada: (a) `reservationprocess`, la regla literal de la skill para conceptos de dos palabras, larga y difícil de leer.
  - **P2 y P3 · Forma de la pieza común y caso "pudo haber llegado" (opción a, 2026-10-26):** todos los clientes de la cátedra devuelven un mismo tipo de respuesta: `Answered` (respuesta definitiva, con el contenido propio de la operación), `TokenRejected` (`401`), `ConfigurationError`, `Unavailable` (seguro no llegó: se reintenta), `Uncertain` (pudo haber llegado: no se reintenta, D1) y `Failed` (respuesta no prevista). Un componente común aplica sobre esa respuesta las reglas de sesión (error de configuración, degradada → login), el `401` con un login y una sola repetición, y los reintentos con espera creciente; cada caso de uso solo indica qué llamada hacer y traduce la respuesta a su propio resultado. `GetOccupanciesUseCase` sigue devolviendo `OccupanciesResult`; `OccupanciesResult.TokenRejected` deja de usarse y se borra, junto con su rama en `CatedraOccupancyAdapter` de `availability`. P3 queda resuelta con `Uncertain`. Alternativa descartada: (b) una clase base abstracta de la que heredan los casos de uso, que obliga a escribir constructores a mano para pasarle las dependencias (choca con `@RequiredArgsConstructor`) y es más difícil de seguir. **Lugar (2026-10-26, opción ii):** un paquete nuevo, `catedra/application/support/`, como excepción a la skill autorizada por el usuario, con su comentario en el código. No es un caso de uso: no es una operación que el slice ofrece hacia afuera, y publicarlo como puerto de entrada dejaría el JWT técnico al alcance de otros slices. Alternativas descartadas: (i) `application/usecases/`, que tendría algo que no es un caso de uso; el dominio, porque usa puertos y esperas; `infrastructure/client`, que escondería reglas de la integración y contradice P7 de T03. Texto de decisión: `docs/decisiones/011-pieza-comun-de-la-catedra.md`.
  - **P4 · Qué ofrece `user` para saber si un usuario existe (opción a, 2026-10-26):** un puerto de entrada nuevo, `ExistsUserByIdUseCase` (`boolean existsUserById(Long id)`), que `UserRepository` resuelve con una consulta por clave primaria. `reservation` lo usa con un puerto propio y un adaptador en su `gateway` (decisión 009). Es lo mínimo que necesita T04 y no saca de `user` ningún dato del usuario, en particular el hash de la contraseña, que viaja dentro del modelo `User`. T05 agregará su propio puerto para el nombre y el apellido. Alternativa descartada: (b) `GetUserByIdUseCase`, que devuelve el `User` completo.
  - **P5 · Dónde vive el candado por usuario (opción a, 2026-10-26):** como campo de `CreateReservationProcessUseCaseImpl`: un candado en memoria por usuario, creado al usarlo y descartado al soltarlo, igual que el candado del login técnico en `RenewTechnicalSessionUseCaseImpl` (X01). Si otra operación necesita lo mismo, se extrae. Alternativa descartada: (b) un puerto de salida con un adaptador en memoria, más piezas para un solo uso. La garantía final, aun con varias instancias, sigue siendo el índice único de la base (D3).
  - **P6a · Clave primaria (opción a, 2026-10-26):** la clave primaria de `reservation_processes` es el `reservationProcessId` que asigna la cátedra, sin un `Long id` propio. Es una excepción a la skill (que pide un `Long` autogenerado), con su comentario en el código. Motivo: respeta la regla 4 del dominio (turnos no genera identificadores propios para los procesos), deja un solo identificador del proceso en logs, API y base, y las búsquedas de T05 a T11 van directo a la clave primaria. Consecuencias aceptadas: la entidad implementa `Persistable` para que Spring Data no haga un `SELECT` antes de cada `INSERT`; la columna es amplia porque el anexo no fija el largo del id, y se confirma con la grabación real (D8); si la cátedra repitiera un id, el `INSERT` falla por clave duplicada (se trata como RF-23). Alternativa descartada: (b) un `Long id` interno más el `reservationProcessId` único, que deja dos identificadores para lo mismo y contradice la regla 4 del dominio; solo ganaría si hubiera que guardar procesos antes de que la cátedra los conozca, que hoy no pasa.
  - **P6b · Columnas y estados (opción a, 2026-10-26):** la tabla tiene solo lo que usa T04: `reservationProcessId`, `holdId`, el usuario (clave foránea, D5), `professionalId`, nombre, apellido y categoría del profesional, `date`, `startTime`, `status` y `expiresAt`. Cada caso de uso siguiente suma sus columnas con su propia migración. El estado tiene solo los valores que usa T04: `HELD`, `WAITING_FOR_PHONE` y `PHONE_SUBMITTED` (abiertos) y `EXPIRED`. La respuesta igual incluye todos los campos del contrato §2, con `null` en los que T04 no tiene. Alternativa descartada: (b) todas las columnas del dominio desde ahora.
  - **P7 · Errores del controller (opción b, 2026-10-26):** todos los errores del controller de `reservation` se lanzan como `ErrorResponseException` de Spring, armados por un único método auxiliar; los que lo necesitan suman encabezados (`WWW-Authenticate: Bearer` en el `401` de D5, `Retry-After` en `HOLD_NOT_SAVED`) y campos (`reservationProcessId`, `currentStatus` y `expiresAt` en `OPEN_PROCESS_EXISTS`; `expiresAt` en `HOLD_NOT_SAVED`). `ApiExceptionHandler` les da el mismo formato que a todos los errores del servicio (`title`, `status`, `detail`, `instance`, `code` y los campos extra que correspondan; un campo que no corresponde no aparece) y copia los encabezados, así que `config` no cambia por esto. Motivo: `ResponseStatusException`, la que usan los otros controllers, no puede llevar encabezados (verificado: `getHeaders()` devuelve siempre `HttpHeaders.EMPTY`). El `401` de D5 no lleva `code` y su `detail` es el mismo que el de `SecurityProblemHandler`, para que el cuerpo sea idéntico (CA-18). Los otros controllers no cambian. Alternativas descartadas: (a) `ErrorResponseException` solo en los tres errores especiales; (c) armar la respuesta a mano en el controller, fuera del manejador.
  - **P8 · Campos que no se pueden convertir y números con decimales (2026-10-26):** (1) `ApiExceptionHandler`: cuando el cuerpo es JSON válido pero Jackson no puede convertir un campo (su error trae la ruta del campo, verificado con `getPath()`), responde `400` `VALIDATION_ERROR` con ese campo en `fieldErrors` y el mensaje `invalid value`; un JSON mal escrito sigue sin `fieldErrors` (D7). (2) Opción b: Jackson convierte `1.5` en `1` sin avisar en un campo `Long` (verificado: `ACCEPT_FLOAT_AS_INT` viene activada en Jackson 3.1.5, y `@JsonFormat(lenient = false)` no lo evita). Por eso, solo en el DTO de T04, `professionalId` se recibe como `BigDecimal`, con un setter propio que quita los ceros finales (indicación del usuario: `1.0` y `15.000` se aceptan como `1` y `15`), y se valida con Bean Validation y mensajes propios: obligatorio, positivo, sin decimales y no mayor que el máximo de un `Long` (un número demasiado grande da un solo mensaje). El mapper del DTO lo pasa a `Long`. Probado con las versiones del proyecto: `15`, `"15"`, `1.0`, `15.000` y `1e3` se aceptan; `1.5`, `1.50`, `0`, `-1` y `99999999999999999999` dan `400` con el campo. Alternativas descartadas: (a) desactivar `ACCEPT_FLOAT_AS_INT` para todo el servicio, que alcanzaría a cualquier JSON futuro (por ejemplo, los mensajes de Kafka); (c) aceptar la conversión silenciosa.
  - **P9 · Nombres de las propiedades (2026-10-26):** `turnos.reservation.safety-margin` (`TURNOS_RESERVATION_SAFETY_MARGIN`, `30s`) y `turnos.reservation.zone-id`, que lee la misma variable que `availability` (`TURNOS_ATTENTION_ZONE`): la zona se configura una sola vez y T03 y T04 nunca discrepan sobre qué es "hoy", sin compartir clases entre los slices.
  - **P10 · Textos de decisión (2026-10-26):** además del 011 (P2 y P3), `docs/decisiones/012-persistencia-del-proceso-de-reserva.md`, sobre cómo se guarda el proceso: la clave primaria de la cátedra (P6a), la clave foránea a `users` con la verificación previa (D5) y un solo proceso abierto por usuario con el candado y el índice de la base (D3, P5). D1 ya está explicada en la SPEC, el caso de uso, el contrato y el 011; P8 es un detalle de validación de un campo.
  - **Validación del turno (desde la SPEC, 2026-10-26):** copia propia en el slice nuevo, sin compartir con `availability` ni crear un paquete `shared`.
  - **Cruces con `catalog` y `catedra`:** puertos de salida propios con adaptadores en `infrastructure/gateway`, según la decisión 009 (skill §9, paso 0: ya está documentado).
<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el plan cubre los requisitos, respeta las exclusiones, reutiliza
componentes verificados, respeta la skill hexagonal-vertical-slicing y permite
demostrar todos los criterios de aceptación.
Resuelve dudas y marcadores pendientes. Si la spec cambió, revisa su impacto.
Tras aprobar el plan, deriva TASKS.md con IDs, dependencias, referencias a RF/CA
y comprobaciones. No marques una tarea terminada sin realizar su validación;
si está bloqueada, registra el motivo.
-->
