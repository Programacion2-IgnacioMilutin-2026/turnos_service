# TASKS: T03 — Consultar turnos libres de un profesional

**SPEC:** `docs/sdd/t03-turnos-libres/SPEC.md` (aprobada el 2026-10-24)
**PLAN:** `docs/sdd/t03-turnos-libres/PLAN.md` (aprobado el 2026-10-24)

Las tareas siguen el "Orden de implementación" del PLAN. Rutas relativas a `src/main/java/turnos/` y `src/test/java/turnos/`, salvo indicación. Los nombres de los tests describen el comportamiento, sin citar documentos. Los tests se cuentan con los reportes XML de Surefire.

Tandas propuestas, para que la suite quede en verde al cerrar cada una: TASK-01; TASK-02 y TASK-03; TASK-04 y TASK-05; TASK-06 y TASK-07; TASK-08; TASK-09; TASK-10 y TASK-11; TASK-12 y TASK-13; TASK-14.

- [ ] **TASK-01 · Renombrar el slice `integration` a `catedra`**
  - Alcance: mueve `integration/` a `catedra/` en `src/main` y `src/test` (`git mv`) y actualiza paquetes e imports, sin cambios de comportamiento. Corrige el comentario de `FakeCatedraServer` que cita el anexo. En la documentación: `CLAUDE.md`, la decisión 009, la SPEC y el PLAN de T03 y el PLAN de la validación del JWT pasan a nombrar `catedra`; la SPEC, el PLAN y las TASKS de X01 suman una nota al principio, sin reescribir su evidencia. No se toca el campo JSON `integration` de la cátedra (contrato de turnos y SPEC de X01).
  - Depende de: ninguna
  - Resuelve: P1 del PLAN (base de todas las tareas siguientes)
  - Validación: `grep` sin resultados de `turnos.integration` en `src/` ni de `integration/` en los documentos vigentes; `grep` del campo JSON `integration` igual que antes en el contrato; `./mvnw clean test` con los 248 tests en verde; los cinco chequeos de la skill sin resultados
  - Resultado: pendiente

- [ ] **TASK-02 · Servidor HTTP simulado generalizado**
  - Alcance: crea en `src/test/java/turnos/` un servidor HTTP de test que responde según la ruta, encadena respuestas por ruta (por ejemplo `401` y después `200`), puede demorar una respuesta y guarda método, ruta, consulta, encabezados y cuerpo de cada request. `FakeCatedraServer` pasa a usarlo y conserva sus ayudantes (`TOKEN`, `provisionedLoginResponse`, `problem`), así los tests de X01 no cambian.
  - Depende de: TASK-01
  - Resuelve: base de CA-02, CA-05, CA-08 y CA-09
  - Validación: `./mvnw test -Dtest=FakeHttpServerTest` (rutas distintas, cola de respuestas, demora, registro de consulta y `Authorization`); los tests de X01 en verde sin cambios
  - Resultado: pendiente

- [ ] **TASK-03 · Grabar las respuestas reales de la cátedra**
  - Alcance: con `curl` y las credenciales del `.env` local, sin mostrarlas, graba las respuestas reales de: el login técnico; el snapshot, solo para elegir un profesional que exista; las ocupaciones de ese profesional para una fecha; un `401` con un token inválido, y un `400` con `from` posterior a `to`. Las limpia (JWT técnico, contraseña de Redis, `groupId` y cualquier otro valor del proyecto reemplazados por valores de prueba) y las guarda en `src/test/resources/catedra/`, con estado y `Content-Type` observados. Se versionan: las usa el servidor simulado de la suite normal (TASK-07 y TASK-11) y documentan cómo responde la cátedra real; la suite E2E no las usa, porque habla con la cátedra en vivo. Si el E2E empieza a fallar mientras la suite simulada sigue en verde, es la señal de que la cátedra cambió y hay que volver a grabar. No se versiona el snapshot ni nada que no se use.
  - Depende de: TASK-02
  - Resuelve: base de CA-08 a CA-10 y CA-25 (respuestas realistas)
  - Validación: comparar cada archivo con lo grabado (mismos campos, tipos y formato de horas); `grep` sin coincidencias con el JWT real, la contraseña de Redis real ni el `groupId` real (comprobado sin imprimirlos); `git status` muestra solo los archivos limpios. Si la cátedra no está disponible, la tarea queda bloqueada y se anota
  - Resultado: pendiente

- [ ] **TASK-04 · Pasarela `catalog`: dominio y caso de uso**
  - Alcance: crea el slice `catalog`: en `domain/model` el profesional, su categoría, el horario semanal, el resultado sellado (`Found`, `NotFound`, `Rejected`, `Unavailable`, `Failed`) y la política de reintentos; en `domain/ports` `GetProfessionalUseCase` y `CatalogClient`; en `application` `GetProfessionalUseCaseImpl` (reintentos con espera creciente solo ante `Unavailable`, cada intento en el log) y `CatalogService`. Sin cliente HTTP todavía.
  - Depende de: TASK-01
  - Resuelve: CA-05 (lógica); base de CA-03, CA-04 y CA-06
  - Validación: `./mvnw test -Dtest=GetProfessionalUseCaseImplTest` con un cliente falso de secuencias y esperas de milisegundos: `Unavailable` siempre → tantos intentos como el máximo y `Unavailable`; `Unavailable` y después `Found` → dos intentos; `NotFound`, `Rejected` y `Failed` → un solo intento; cada intento en el log, sin el token. Prueba en negativo: reintentar también ante `Rejected` hace fallar un test. Chequeos 1 y 2 de la skill
  - Resultado: pendiente

- [ ] **TASK-05 · Cliente HTTP de catálogo**
  - Alcance: crea en `catalog/infrastructure/client/` el adaptador de `CatalogClient` (`GET /api/professionals/{id}` reenviando el `Authorization`), `CatalogProperties` (URL, timeouts y reintentos de D2), la configuración que arma la política, los DTO y el mapper (acepta `HH:mm` y `HH:mm:ss`). URL faltante o que no es `http`/`https` válida: warning al arrancar que nombra la propiedad y `Unavailable` sin request (D8).
  - Depende de: TASK-02, TASK-04
  - Resuelve: CA-02; CA-03, CA-04, CA-06 y CA-23 (adaptador); parte de CA-20
  - Validación: `./mvnw test -Dtest='CatalogClientAdapterTest,CatalogMapperTest'` contra el servidor simulado, con respuestas armadas según el contrato de catálogo §3.1: ruta y `Authorization` recibidos iguales al token dado; `200` → `Found` con los horarios; `401`/`403` → `Rejected`; `404` → `NotFound`; `503`, demora mayor al timeout y puerto cerrado → `Unavailable`; `400` y `500` → `Failed`; sin URL y con URL entre comillas → warning y ningún request; la salida no contiene el token. Prueba en negativo: sin reenviar el `Authorization` falla el test de la ruta
  - Resultado: pendiente

- [ ] **TASK-06 · Operación "consultar ocupaciones" en `catedra`**
  - Alcance: en `catedra/domain` la ocupación, su estado, el resultado sellado (`Found`, `ConfigurationError`, `Unavailable`, `Failed`), la política de reintentos de las operaciones, `GetOccupanciesUseCase` y `CatedraOccupancyClient`; en `application` `GetOccupanciesUseCaseImpl` (sesión según D1, login ante `401` y una sola repetición, `403` de configuración, reintentos con espera creciente, respuesta no prevista → `Failed`) y el método en `TechnicalSessionService`; en `CatedraProperties` los valores `operations.*` de D2 y su bean de política.
  - Depende de: TASK-01
  - Resuelve: CA-09, CA-10 y CA-25 (pasarela); parte de CA-11, CA-12 y CA-20
  - Validación: `./mvnw test -Dtest=GetOccupanciesUseCaseImplTest` con cliente falso de secuencias, `ScriptedLoginClient` y repositorio de sesión en memoria: sesión habilitada → usa el JWT vigente; error de configuración → `ConfigurationError` sin llamar; degradada → pide login y usa el JWT nuevo; degradada sin login posible → `Unavailable`; `401` → un login y una repetición con el JWT nuevo; `401` dos veces → no repite de nuevo y da `Unavailable`; `403` `STUDENT_INTEGRATION_NOT_FOUND` → `ConfigurationError`; `5xx`/timeout → intentos hasta el máximo; respuesta no prevista → `Failed`; cada intento en el log sin el JWT. Pruebas en negativo: repetir más de una vez tras un `401` y no pedir login con la sesión degradada hacen fallar tests
  - Resultado: pendiente

- [ ] **TASK-07 · Cliente HTTP de ocupaciones**
  - Alcance: crea en `catedra/infrastructure/client/` el adaptador de `CatedraOccupancyClient` (`GET /api/appointment-occupancies` con `professionalId`, `from` y `to` iguales a la fecha y `Bearer`, timeouts de D2), sus DTO y su mapper (`HH:mm` y `HH:mm:ss`, `expiresAt` nulo en `CONFIRMED`).
  - Depende de: TASK-02, TASK-03, TASK-06
  - Resuelve: CA-08 (request y traducción); CA-09, CA-10 y CA-25 (traducción)
  - Validación: `./mvnw test -Dtest='CatedraOccupancyClientAdapterTest,OccupancyMapperTest'` con las respuestas grabadas: consulta y `Bearer` recibidos; `200` grabado → ocupaciones con su estado y hora; `401` grabado → resultado de rechazo; `403` `STUDENT_INTEGRATION_NOT_FOUND` → integración inexistente; `400` grabado → no prevista; `503`, demora y puerto cerrado → no disponible; la salida no contiene el JWT
  - Resultado: pendiente

- [ ] **TASK-08 · Dominio de `availability`**
  - Alcance: crea en `availability/domain/model/` la agenda del profesional y sus horarios, la ocupación, el snapshot del caché, el turno libre, el resultado (`slots`, `emptyReason`, `stale`, `dataAsOf`), `EmptyReason`, la política (zona y antigüedad máxima) y los resultados de los proveedores. Las reglas son métodos del dominio: generar los turnos de un día (solo horarios habilitados, terminan en o antes de `endTime`), unir repetidos (D6), descartar comenzados según la zona (D4) y descartar ocupados.
  - Depende de: ninguna
  - Resuelve: CA-13, CA-14, CA-15, CA-22; lógica de CA-17 y CA-18
  - Validación: `./mvnw test -Dtest='ProfessionalAgendaTest,FreeSlotsTest'` sin Spring: 09:00–12:00 cada 30 → seis turnos; 09:00–10:15 cada 30 → dos; horarios deshabilitados y de otro día no cuentan; superpuestos 09:00–12:00/30 y 11:00–13:00/60 → `11:00` una vez con 30 y `12:00` con 60; hoy a las 10:10 en Mendoza → sin los turnos de 09:00 a 10:00; ocupaciones `09:00:00` y `09:30` → esos dos ausentes; sin horarios ese día → `PROFESSIONAL_NOT_WORKING`; todos ocupados → `NO_FREE_SLOTS`. Prueba en negativo: comparar las horas sin normalizar los segundos hace fallar el test de ocupaciones. Chequeo 1 de la skill
  - Resultado: pendiente

- [ ] **TASK-09 · Caso de uso, caché y adaptadores de `availability`**
  - Alcance: crea los puertos (`GetFreeSlotsUseCase`, `ProfessionalAgendaProvider`, `OccupancyProvider`, `OccupancyCache`), `GetFreeSlotsUseCaseImpl`, `AvailabilityService`, `AvailabilityException` y sus subclases; `infrastructure/persistence/adapter/InMemoryOccupancyCacheAdapter` (mapa concurrente y `@Scheduled` cada minuto) y `config/SchedulingConfig`; `infrastructure/gateway/` con los adaptadores hacia `catalog` y `catedra` (paquete nuevo, con su comentario); las propiedades de la zona y la antigüedad (D3, D4) y su configuración.
  - Depende de: TASK-04, TASK-06, TASK-08
  - Resuelve: CA-01 (fecha anterior a hoy), CA-03, CA-04, CA-06, CA-07, CA-11, CA-12, CA-17, CA-18 y CA-25 (respuesta de T03)
  - Validación: `./mvnw test -Dtest='GetFreeSlotsUseCaseImplTest,InMemoryOccupancyCacheAdapterTest,CatalogAgendaAdapterTest,CatedraOccupancyAdapterTest'` con proveedores falsos, caché real y `MutableClock`: ayer en Mendoza → error de fecha sin llamar a los proveedores; cada resultado de catálogo → su excepción y ocupaciones sin llamadas; profesional deshabilitado → `409` y categoría deshabilitada sin efecto; sin horarios ese día → `PROFESSIONAL_NOT_WORKING` sin llamar a ocupaciones; ocupaciones `Found` → caché guardado con `dataAsOf`; `Unavailable` con entrada vigente → `stale` con su `dataAsOf`; entrada vencida → borrada y `503`; sin entrada → `503`; `ConfigurationError` → `SERVICE_DEGRADED`; `Failed` con caché vigente → `500` sin usar el caché; el barrido borra las vencidas y conserva las vigentes; 20 hilos leyendo y guardando a la vez sin errores; los adaptadores traducen cada resultado. Pruebas en negativo: no borrar la entrada vencida y usar el caché ante `Failed` hacen fallar tests. Chequeos 1, 2 y 5 de la skill (los cruces solo en `infrastructure/gateway`)
  - Resultado: pendiente

- [ ] **TASK-10 · Endpoint `GET /api/free-slots`**
  - Alcance: crea `AvailabilityController`, `FreeSlotsRequest` (`@Valid @ModelAttribute`, `Long` con `@NotNull` y `@Positive`, `LocalDate` con `@NotNull` y formato ISO), `FreeSlotsResponse`, `FreeSlotResponse` y `AvailabilityDtoMapper`; el controller obtiene el token con `@AuthenticationPrincipal Jwt` (decisión 008) y traduce las excepciones a los estados y `code` del contrato §3.3. En `ApiExceptionHandler`, un campo que falla por conversión recibe un mensaje neutro.
  - Depende de: TASK-09
  - Resuelve: CA-01 y CA-16; respuesta HTTP de CA-03 a CA-07, CA-10 a CA-12, CA-17, CA-18 y CA-25
  - Validación: `./mvnw test -Dtest='AvailabilityControllerTest,AvailabilityDtoMapperTest,UserControllerTest,UserControllerAuthenticateTest'`: faltantes, `abc`, `0`, `-1`, `99999999999999999999`, `20-10-2026` y `2026-02-30` → `400` `VALIDATION_ERROR` con el campo y sin tipos de Java en el mensaje, sin llamar al service; fecha anterior a hoy → `400` con `date`; cada excepción → su estado y `code`; respuesta exitosa con el formato de §3.3 (`HH:mm`, orden, `emptyReason`, `stale`, `dataAsOf`); el service recibe el token exacto; los tests de T01 y T02 siguen en verde. Prueba en negativo: sin el mensaje neutro, falla el test de `abc`
  - Resultado: pendiente

- [ ] **TASK-11 · Configuración e integración con la aplicación completa**
  - Alcance: suma a `application.yaml` las propiedades de catálogo, de las operaciones de la cátedra y de `availability`, con sus variables, y las documenta en `.env.example`. Crea `FreeSlotsIntegrationTest` con Tomcat en un puerto aleatorio, Postgres de Testcontainers y catálogo y cátedra simulados (respuestas grabadas).
  - Depende de: TASK-03, TASK-05, TASK-07, TASK-10
  - Resuelve: CA-19, CA-21, CA-23, CA-24; parte de CA-20
  - Validación: `./mvnw test -Dtest='AvailabilityPropertiesTest,CatalogPropertiesTest,CatedraPropertiesTest,FreeSlotsIntegrationTest'`: valores por defecto de D2 a D4 sin configurar nada y valores configurados; registro, login y `GET /api/free-slots` por HTTP con la respuesta esperada; sin URL de catálogo y con una URL entre comillas → arranca, warning, `503` `CATALOG_UNAVAILABLE` sin requests, T01 y T02 normales; la base sin cambios después de consultas exitosas, desde el caché y con error; la salida registra los intentos y no contiene ningún token. `./mvnw test` completo en verde
  - Resultado: pendiente

- [ ] **TASK-12 · Suite E2E**
  - Alcance: en `pom.xml`, Surefire excluye la etiqueta `e2e` por defecto y un perfil `e2e` corre solo esa etiqueta apuntando `turnos.env-file` al `.env` real. Crea `src/test/java/e2e/FreeSlotsE2ETest` (`@SpringBootTest(classes = TurnosServiceApplication.class)`, `TestcontainersConfiguration` para la base Postgres y la clave de test, y catálogo simulado con el profesional elegido en TASK-03; lo único real es la cátedra, y lo que se necesite en contenedores sigue en Testcontainers): registro, login y turnos libres con la cátedra real. Sin credenciales, salteado con un mensaje claro.
  - Depende de: TASK-11
  - Resuelve: CA-26
  - Validación: `./mvnw test -Pe2e` con el `.env` local → `200` con ocupaciones reales de la cátedra; con el `.env` apuntado a un archivo sin credenciales → test salteado, no fallido; `./mvnw test` sin el perfil no corre el E2E y `testsDoNotReadTheLocalEnvFile` sigue en verde. Si la cátedra no está disponible, se anota como bloqueado
  - Resultado: pendiente

- [ ] **TASK-13 · Prueba manual con la aplicación levantada**
  - Alcance: `docker compose up -d --wait`, un catálogo "de mentira" en el scratchpad (no se versiona) que responde C06 para el profesional de TASK-03, y `./mvnw spring-boot:run` con `TURNOS_CATALOG_BASE_URL` apuntando a él solo por variable de entorno. Probar con `curl` y dejar todo como estaba.
  - Depende de: TASK-11
  - Resuelve: evidencia real de CA-01, CA-08, CA-11, CA-16 y CA-17
  - Validación, registrando cada request y la respuesta observada: registro, login y turnos libres con ocupaciones reales; parámetros inválidos; un día en que el profesional no atiende; con la URL de la cátedra apuntando a un puerto cerrado (solo por variable de entorno), respuesta desde el caché con `stale: true` y después `503` al vencer, con una antigüedad corta configurada; los tokens no aparecen en el log
  - Resultado: pendiente

- [ ] **TASK-14 · `CLAUDE.md`, regresión y SonarQube**
  - Alcance: actualiza `CLAUDE.md` (slices `catedra`, `catalog` y `availability`; la excepción del paquete `infrastructure/gateway`; las variables nuevas; las dos suites de tests y el comando `-Pe2e`; las decisiones 001 a 010). Corrida completa, chequeos de la skill y revisión del análisis del PR. Sin cambios de código, salvo correcciones que surjan, que se anotan aquí.
  - Depende de: TASK-01 a TASK-13
  - Resuelve: regresión de T01, T02, X01 y la validación del JWT; comprobación de todos los CA
  - Validación: `./mvnw clean verify` en verde con el reporte de JaCoCo; los cinco chequeos de la sección 12 de la skill, con el quinto mostrando solo los cruces de `infrastructure/gateway`; cuando el usuario abra el PR, issues, hotspots y cobertura del código nuevo leídos con la API de SonarQube Cloud; revisión de que cada CA tiene su evidencia registrada en estas tareas
  - Resultado: pendiente
