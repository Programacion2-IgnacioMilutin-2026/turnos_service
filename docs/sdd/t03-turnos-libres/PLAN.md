# PLAN: T03 — Consultar turnos libres de un profesional

**SPEC de referencia:** `docs/sdd/t03-turnos-libres/SPEC.md`
**Versión de la spec revisada:** aprobada el 2026-10-24 (todavía sin commit)
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
| Slice `integration` (X01) | `integration/` (24 archivos en `src/main`, 14 en `src/test`; ningún otro archivo lo importa) | Sesión técnica con la cátedra. Se renombra a `catedra` (P1) y suma la operación "consultar ocupaciones" |
| `TechnicalSessionRepository`, `RenewTechnicalSessionUseCase` | `integration/domain/ports/` | La operación nueva lee el estado de la sesión (para D1) y pide un login nuevo con las reglas de la decisión 005 |
| `CatedraProperties`, `CatedraLoginClientAdapter` | `integration/infrastructure/client/` | Ya tienen la URL base, su validación (`http`/`https`, comillas) y los timeouts del login. La operación nueva usa la misma URL con sus propios timeouts y reintentos (D2) |
| `TechnicalLoginPolicy` | `integration/domain/model/` | Modelo de reintentos de X01; la política de las operaciones de usuario sigue la misma forma con otros valores |
| `SecurityConfig`, convención del controller | `config/`, decisión 008 | `/api/free-slots` ya queda protegido; el controller recibe el `Jwt` con `@AuthenticationPrincipal` y pasa el token como `String` |
| `ApiExceptionHandler` | `config/ApiExceptionHandler.java` | Hoy arma `VALIDATION_ERROR` con `fieldErrors` solo para cuerpos JSON. Los errores de parámetros de la URL (faltante, tipo, restricción) salen sin `code` (observado el 2026-10-24 con `415` y `405`); se extiende (P3) |
| `ClockConfig` | `config/ClockConfig.java` | Reloj en UTC para "hoy", los turnos ya comenzados, `dataAsOf` y la antigüedad del caché |
| `FakeCatedraServer` | `src/test/java/turnos/integration/` | Responde lo mismo a cualquier ruta y no guarda la consulta ni el `Authorization`. Se generaliza (ver Estrategia de validación); su comentario de la línea 68 cita el anexo y se corrige |
| `TestSecurityConfiguration`, `TestTokens` | `src/test/java/turnos/` | Seguridad real y tokens de prueba para los tests web e integración |
| Dependencias | `pom.xml` | Nada nuevo: `RestClient` y `JdkClientHttpRequestFactory` (`spring-web` 7.0.9), Bean Validation, `@EnableScheduling` y `@Scheduled` (`spring-context` 7.0.9) |
| APIs verificadas con `javap` | `spring-webmvc` 7.0.9 | `ResponseEntityExceptionHandler` tiene `handleMissingServletRequestParameter`, `handleTypeMismatch` y `handleHandlerMethodValidationException` para sobrescribir |

**Convenciones y slice de referencia:** el slice `integration` (cliente HTTP en `infrastructure/client`, resultados como interfaz sellada de `record`, política de reintentos como `record` del dominio, repositorio en memoria en `persistence/adapter`) y el slice `user` (controller, DTO y mapper). Comentarios y nombres de tests sin citar documentos (`CLAUDE.md`).

## Solución propuesta

<!-- Explica el enfoque y sus motivos. Describe las responsabilidades y el
recorrido de la solicitud, el evento o la tarea desde que entra hasta la
respuesta, la persistencia o el mensaje publicado. Referencia el diagrama de
secuencia del proyecto si ya lo describe; usa un diagrama nuevo solo si aporta
claridad. -->

Slices "pasarela" hacia cada sistema externo (decisión A) y un slice propio para la disponibilidad (decisión B). El recorrido sigue el diagrama de secuencia §2, ya actualizado por D9:

```
GET /api/free-slots?professionalId&date  (JWT ya validado por la cadena de seguridad)
└─ availability · AvailabilityController: FreeSlotsRequest con @Valid @ModelAttribute valida tipos y rangos (RF-02, D7; P3)
   └─ AvailabilityService → GetFreeSlotsUseCase = GetFreeSlotsUseCaseImpl
      ├─ fecha anterior a hoy en la zona de atención → 400 VALIDATION_ERROR (RF-02, D4)
      ├─ ProfessionalAgendaProvider (puerto propio)
      │  = adaptador → catalog · GetProfessionalUseCase (ports.in)
      │                 └─ CatalogClient → C06 con el token del usuario, reintentos (RF-03 a RF-07, D2, D8)
      ├─ profesional deshabilitado → 409; sin horarios ese día → 200 PROFESSIONAL_NOT_WORKING (RF-08, RF-18, D5, D9)
      ├─ OccupancyProvider (puerto propio)
      │  = adaptador → catedra · GetOccupanciesUseCase (ports.in)
      │                 ├─ sesión en error de configuración → ConfigurationError (D1)
      │                 ├─ sesión degradada → login a pedido; sin sesión → Unavailable (D1)
      │                 └─ CatedraOccupancyClient → ocupaciones con el JWT técnico; 401 → login y 1 repetición;
      │                    403 STUDENT_INTEGRATION_NOT_FOUND → ConfigurationError; 5xx o timeout → reintentos (RF-09 a RF-12)
      ├─ Found → OccupancyCache.save (RF-13); ConfigurationError → 503 SERVICE_DEGRADED (RF-11)
      ├─ Unavailable → OccupancyCache: vigente → stale; vencida → se borra y 503; sin entrada → 503 (RF-14, D3)
      └─ ProfessionalAgenda (dominio): genera los turnos, une repetidos, descarta comenzados y ocupados (RF-15, RF-16, D6)
   └─ 200 FreeSlotsResponse (RF-17, RF-19)
```

- **Por qué pasarelas:** las reglas comunes de la cátedra (JWT técnico, `401` con un login y una repetición, `403` de configuración, reintentos) quedan en `catedra`, y las de catálogo en `catalog`. T04, T05, T11 y T13 solo agregan su operación. D1 se resuelve dentro de `catedra`, que conoce el estado de la sesión, sin exponerlo.
- **Cómo se cruzan los slices (skill §9, opción "puerto de salida hacia el otro slice"):** `availability` declara sus propios puertos de salida con sus propios tipos. Sus adaptadores llaman a los puertos de entrada de `catalog` y `catedra` y traducen sus resultados. Ni el dominio ni la aplicación de `availability` importan otro slice, y nunca se usan la `application` ni la `infrastructure` de los otros. Cada punto de cruce lleva su comentario.
- **Las reglas de cálculo viven en el dominio de `availability`:** generar turnos, unir repetidos (D6), descartar comenzados y ocupados. Se prueban sin Spring.
- **El caché es de `availability`:** solo T03 lo usa. Puerto `OccupancyCache` con un adaptador en memoria (como `InMemoryTechnicalSessionRepositoryAdapter`), seguro para requests simultáneos y con un barrido periódico que descarta las entradas vencidas (RF-14, P4).
- **La lógica del `401` y los reintentos de la cátedra** se escribe en esta primera operación. Cuando T04 agregue la segunda, se extrae a una pieza común de `catedra` (anotado en Riesgos).

## Slices y componentes afectados

<!-- Aplica la skill hexagonal-vertical-slicing: un paquete por slice, con sus
capas domain, application e infrastructure. Identifica los slices afectados, sus
responsabilidades y la dirección de sus dependencias. Una funcionalidad puede
afectar más de un slice. Justifica cualquier slice nuevo o dependencia nueva
entre slices. -->

| Slice | Existe / nuevo | Responsabilidad y cambios | Dependencias con otros slices |
| --- | --- | --- | --- |
| `catedra` (`turnos/catedra/`) | Renombrado desde `integration` (P1) | Pasarela hacia la cátedra: sesión técnica (X01, sin cambios) y la operación nueva "consultar ocupaciones" | Ninguna. `availability` usa su puerto de entrada |
| `catalog` (`turnos/catalog/`) | Nuevo | Pasarela hacia el servicio de catálogo: C06 con reintentos y traducción de errores | Ninguna. `availability` usa su puerto de entrada (y T04 lo hará) |
| `availability` (`turnos/availability/`) | Nuevo | T03: endpoint, cálculo de turnos libres y caché de ocupaciones | Usa los puertos de entrada de `catalog` y `catedra` desde adaptadores propios (P2) |
| `config` | Existente | `ApiExceptionHandler` suma los errores de parámetros (P3); `@EnableScheduling` para el barrido del caché (P4) | No importa nada de los slices |

| Slice y capa | Componente o ruta | Acción | Cambio y responsabilidad | Requisito relacionado |
| --- | --- | --- | --- | --- |
| catedra (todas) | `integration/` → `catedra/`, en `src/main` y `src/test` | Renombrar | Mover paquetes y actualizar imports. Sin cambios de comportamiento | P1 |
| catedra / domain | `model/Occupancy`, `model/OccupancyStatus`, `model/OccupanciesResult` | Crear | La ocupación (`date`, `startTime`, `status`, `expiresAt`) y el resultado sellado: `Found(occupancies)`, `ConfigurationError`, `Unavailable(reason)`, `Failed(reason)` | RF-09 a RF-12 |
| catedra / domain | `model/CatedraCallPolicy` | Crear | Intentos, espera inicial y multiplicador para las operaciones de usuario | RF-12, D2 |
| catedra / domain | `ports/in/GetOccupanciesUseCase` | Crear | `OccupanciesResult getOccupancies(Long professionalId, LocalDate date)` | RF-09 |
| catedra / domain | `ports/out/CatedraOccupancyClient` | Crear | Una llamada con un token dado; resultado sellado: éxito, `401`, integración inexistente, no disponible, respuesta inesperada | RF-09 a RF-12 |
| catedra / application | `usecases/GetOccupanciesUseCaseImpl` | Crear | Sesión (D1), login ante `401` y una repetición, reintentos con espera creciente, cada intento en el log | RF-10 a RF-12, RF-21 |
| catedra / application | `service/TechnicalSessionService` | Modificar | Suma `getOccupancies` | RF-09 |
| catedra / infrastructure | `client/CatedraOccupancyClientAdapter`, `client/dto/…`, `client/mapper/…` | Crear | `GET /api/appointment-occupancies` con `Bearer`, timeouts de D2; acepta `HH:mm` y `HH:mm:ss` | RF-09, RF-16 |
| catedra / infrastructure | `client/CatedraProperties`, `client/CatedraClientConfiguration` | Modificar | Suman `operations.*` (timeouts y reintentos de D2) y el bean de `CatedraCallPolicy` | RF-20, D2 |
| catalog / domain | `model/Professional`, `model/ProfessionalCategory`, `model/WeeklySchedule`, `model/ProfessionalResult`, `model/CatalogCallPolicy` | Crear | Lo que devuelve C06 y el resultado sellado: `Found`, `NotFound`, `Rejected` (`401`/`403`), `Unavailable`, `Failed` (otra respuesta) | RF-03 a RF-07 |
| catalog / domain | `ports/in/GetProfessionalUseCase`, `ports/out/CatalogClient` | Crear | `ProfessionalResult getProfessional(Long professionalId, String userToken)` | RF-03 |
| catalog / application | `usecases/GetProfessionalUseCaseImpl`, `service/CatalogService` | Crear | Reintentos con espera creciente ante no disponible, cada intento en el log | RF-06, RF-21 |
| catalog / infrastructure | `client/CatalogClientAdapter`, `client/CatalogProperties`, `client/CatalogClientConfiguration`, `client/dto/…`, `client/mapper/…` | Crear | `GET /api/professionals/{id}` reenviando el `Authorization`; URL validada como en X01 (D8) | RF-03, RF-20 |
| availability / domain | `model/ProfessionalAgenda`, `model/AgendaSchedule`, `model/OccupiedSlot`, `model/OccupancySnapshot`, `model/FreeSlot`, `model/FreeSlots`, `model/EmptyReason`, `model/AvailabilityPolicy`, `model/AgendaLookup`, `model/OccupancyLookup` | Crear | Copia propia de lo que necesita de catálogo y de la cátedra (skill §9), las reglas de cálculo, la zona de atención y la antigüedad máxima | RF-13 a RF-19, D3 a D6 |
| availability / domain | `ports/in/GetFreeSlotsUseCase`, `ports/out/ProfessionalAgendaProvider`, `ports/out/OccupancyProvider`, `ports/out/OccupancyCache` | Crear | `FreeSlots getFreeSlots(Long professionalId, LocalDate date, String userToken)` y los tres puertos de salida | RF-03, RF-09, RF-13 |
| availability / application | `usecases/GetFreeSlotsUseCaseImpl`, `service/AvailabilityService`, `exception/AvailabilityException` y subclases | Crear | El recorrido de la Solución propuesta; una excepción por cada error del contrato §3.3 | RF-02 a RF-19 |
| availability / infrastructure | `web/controller/AvailabilityController`, `web/dto/FreeSlotsRequest`, `web/dto/FreeSlotsResponse`, `web/dto/FreeSlotResponse`, `web/mapper/AvailabilityDtoMapper` | Crear | `GET /api/free-slots` con `@Valid @ModelAttribute FreeSlotsRequest` (`Long professionalId` con `@NotNull` y `@Positive`, `LocalDate date` con `@NotNull` y formato ISO); traduce las excepciones a los estados y `code` del contrato | RF-01, RF-02, RF-17 |
| availability / infrastructure | `persistence/adapter/InMemoryOccupancyCacheAdapter` | Crear | Mapa concurrente por (`professionalId`, `date`) y un método `@Scheduled` que cada minuto borra las entradas que superan la antigüedad máxima (P4) | RF-13, RF-14 |
| availability / infrastructure | `gateway/CatalogAgendaAdapter`, `gateway/CatedraOccupancyAdapter` | Crear | Implementan `ProfessionalAgendaProvider` y `OccupancyProvider` llamando a los puertos de entrada de `catalog` y `catedra`, y traducen sus resultados a los tipos de `availability`. Paquete nuevo (P2), con su comentario | RF-03, RF-09 |
| availability / infrastructure | `AvailabilityProperties` y su `@Configuration` | Crear | Zona de atención y antigüedad máxima del caché (D3, D4) | RF-20 |
| config | `ApiExceptionHandler` | Modificar | En `fieldErrors`, un campo que falla por conversión (`abc`, número fuera de rango, fecha imposible) recibe un mensaje neutro en lugar del texto de Spring con tipos de Java (P3) | RF-02 |
| config | `SchedulingConfig` | Crear | `@EnableScheduling`, para el barrido del caché (P4) | RF-14 |
| — | `application.yaml`, `.env.example`, `CLAUDE.md` | Modificar | Variables nuevas (ver Dependencias y configuración), el renombre, los slices nuevos y la regla de las dos suites de tests | RF-20 |
| — | `pom.xml` | Modificar | Surefire excluye la etiqueta `e2e`; perfil `e2e` (P6) | CA-26 |
| — (tests) | `src/test/resources/catedra/` | Crear | Respuestas reales grabadas y limpiadas | CA-08 a CA-10, CA-25 |
| — (tests) | `src/test/java/e2e/FreeSlotsE2ETest` | Crear | Primer test de la suite E2E | CA-26 |
| — | `docs/decisiones/010-tests-simulados-y-e2e.md` | Creado (P6) | Las dos suites y las grabaciones limpias | CA-26 |
| — | `docs/decisiones/009-slices-pasarela.md` | Creado (P5) | Slices pasarela, cruce entre slices y renombre | — |

## Datos y contratos

<!-- Completa solo lo aplicable. Si un punto no aplica, indica el motivo. -->

- **Operaciones, eventos y DTO de entrada y salida:** `GET /api/free-slots` según el contrato de turnos §3.3; C06 según el contrato de catálogo §3.1; ocupaciones según el contrato §4.2.3. Ningún contrato cambia.
- **Identificadores, relaciones y restricciones:** `professionalId` es `Long` positivo (D7). La clave del caché es (`professionalId`, `date`).
- **Origen de los datos devueltos o publicados y transformaciones:** los horarios salen de C06 y las ocupaciones de la cátedra (o del caché). `startTime` se emite en `HH:mm`; `dataAsOf` en ISO-8601 UTC.
- **Persistencia, consultas y migraciones:** no aplica. T03 no usa la base (RF-22); no hay migración.
- **Convivencia entre datos locales y remotos:** el caché es la única copia local y nunca reemplaza a la cátedra cuando responde: una consulta en vivo exitosa siempre lo reemplaza.
- **Compatibilidad y migraciones de datos existentes:** no aplica. El renombre de `integration` no cambia propiedades ni comportamiento.

## Transacciones, concurrencia y errores

<!-- Cómo se implementan los comportamientos aprobados en la spec.
Referencia RF/CA y aplica las consideraciones relevantes de BACKEND_GUIDELINES.md. -->

- **Estados y transiciones:** no aplica en `availability`. En `catedra` se lee el estado de la sesión técnica (D1) y no se agregan estados.
- **Transacciones y consistencia:** no aplica.
- **Idempotencia y mensajes duplicados:** no aplica: la consulta no modifica nada.
- **Concurrencia y tareas programadas:** el caché usa un mapa concurrente y entradas inmutables (`record`, como `TechnicalSession`): un request nunca ve una entrada a medio escribir. El barrido periódico corre cada minuto y borra las entradas que superan la antigüedad máxima (P4). Los logins a pedido siguen protegidos por el candado de X01.
- **Timeouts, reintentos y errores de dependencias:** valores de D2 por variable de entorno, por separado para catálogo y para las operaciones de la cátedra. Esperas de 0,5 s y después 1 s. Cada intento fallido se registra con su número y su motivo. Traducción de errores:

| Origen | Resultado | Respuesta de T03 |
| --- | --- | --- |
| Catálogo `401` o `403` | `Rejected` | `500` `INTERNAL_ERROR`, error registrado (RF-04) |
| Catálogo `404` | `NotFound` | `404` `PROFESSIONAL_NOT_FOUND` (RF-05) |
| Catálogo `503`, timeout o conexión, agotados; o URL faltante o inválida | `Unavailable` | `503` `CATALOG_UNAVAILABLE` (RF-06, D8) |
| Catálogo otra respuesta | `Failed` | `500` `INTERNAL_ERROR` (RF-07) |
| Cátedra en error de configuración, o `403` `STUDENT_INTEGRATION_NOT_FOUND` | `ConfigurationError` | `503` `SERVICE_DEGRADED` (RF-11) |
| Cátedra `5xx`, timeout o conexión agotados; degradada sin sesión; `401` repetido | `Unavailable` | Caché o `503` `CENTRAL_UNAVAILABLE` (RF-12, RF-14) |
| Cátedra otra respuesta no prevista (por ejemplo `400`) | `Failed` | `500` `INTERNAL_ERROR`, error registrado, sin caché (D10 de la SPEC) |

- **Seguridad y autorización:** el endpoint lo protege la cadena de seguridad. El token del usuario solo viaja a catálogo; el técnico, solo a la cátedra. Ninguno se registra: los mensajes de log nombran el estado HTTP y el motivo, nunca encabezados.
- **Otras consideraciones de backend aplicables y su solución:**
  - Orden de validación: los parámetros (presencia, tipo y rango) los valida el DTO en la capa web; "no anterior a hoy" depende del reloj y de la zona de atención, así que la valida el caso de uso y el controller lo responde con el mismo formato `VALIDATION_ERROR`, con `date` en `fieldErrors` (P3).
  - La URL de catálogo inválida o faltante se avisa una vez, al arrancar, como en X01 (D8).

## Dependencias y configuración

<!-- Librerías, servicios externos, propiedades de configuración y secretos
afectados. Verifica compatibilidad con el proyecto y justifica las incorporaciones.
No agregues dependencias por defecto. Los valores sensibles y los parámetros
ajustables se externalizan. -->

- **Dependencias:** ninguna nueva.
- **Propiedades nuevas** (en `application.yaml`, con su variable y su valor por defecto; documentadas en `.env.example`):

| Propiedad | Variable | Por defecto |
| --- | --- | --- |
| `turnos.catalog.base-url` | `TURNOS_CATALOG_BASE_URL` | vacía (D8: warning y `503`) |
| `turnos.catalog.connect-timeout`, `read-timeout` | `TURNOS_CATALOG_CONNECT_TIMEOUT`, `TURNOS_CATALOG_READ_TIMEOUT` | `2s`, `3s` |
| `turnos.catalog.max-attempts`, `initial-delay`, `multiplier` | `TURNOS_CATALOG_MAX_ATTEMPTS`, `TURNOS_CATALOG_INITIAL_DELAY`, `TURNOS_CATALOG_DELAY_MULTIPLIER` | `3`, `500ms`, `2` |
| `turnos.catedra.operations.connect-timeout`, `read-timeout` | `TURNOS_CATEDRA_OPERATIONS_CONNECT_TIMEOUT`, `TURNOS_CATEDRA_OPERATIONS_READ_TIMEOUT` | `2s`, `3s` |
| `turnos.catedra.operations.max-attempts`, `initial-delay`, `multiplier` | `TURNOS_CATEDRA_OPERATIONS_MAX_ATTEMPTS`, `TURNOS_CATEDRA_OPERATIONS_INITIAL_DELAY`, `TURNOS_CATEDRA_OPERATIONS_DELAY_MULTIPLIER` | `3`, `500ms`, `2` |
| `turnos.availability.zone-id` | `TURNOS_ATTENTION_ZONE` | `America/Argentina/Mendoza` |
| `turnos.availability.occupancy-cache-max-age` | `TURNOS_OCCUPANCY_CACHE_MAX_AGE` | `60m` |

- **Ningún valor es secreto.** La URL de la cátedra y las credenciales siguen como en X01.
- **Tests:** catálogo y cátedra simulados en puertos aleatorios; esperas de milisegundos; reloj controlado (`MutableClock`) para "hoy", los turnos comenzados y la antigüedad del caché.
- **Suite E2E (`pom.xml`):** Surefire excluye la etiqueta `e2e` por defecto. Un perfil `e2e` corre solo esa etiqueta y apunta `turnos.env-file` al `.env` real; el resto de la configuración de Surefire no cambia.

## Estrategia de validación

<!-- Una fila por criterio de la spec. Selecciona el método capaz de demostrarlo:
test unitario, de integración (base de datos, mensajería), de API o prueba manual.
No todos requieren todos los métodos. Identifica tests existentes y separa los
nuevos propuestos. Incluye regresiones relevantes. Una prueba con mocks no
demuestra persistencia, transacciones ni idempotencia. -->

**Respuestas grabadas (decisión 010):** en la etapa del servidor simulado se graban, con `curl` y las credenciales del `.env` local sin mostrarlas, las respuestas reales de la cátedra para el login, las ocupaciones de un profesional existente y los errores que se pueden provocar (`401` con un token inválido, `400` con `from` posterior a `to`). Se limpian (JWT, contraseña de Redis y cualquier valor sensible reemplazados por valores falsos), se verifica con `grep` que no quede ninguno real, y se guardan en `src/test/resources/catedra/`. `503`, timeouts y conexiones rechazadas se arman a mano.

**Servidor HTTP simulado:** `FakeCatedraServer` se generaliza en un servidor de test que responde según la ruta, encadena respuestas (por ejemplo `401` y después `200`) y guarda método, ruta, consulta, encabezados y cuerpo. Lo usan la cátedra y catálogo simulados; los tests de X01 conservan sus ayudantes y siguen sin cambios de comportamiento.

| Criterio | Método y test existente o propuesto | Entorno y datos necesarios | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01 | Web `AvailabilityControllerTest` (faltantes, `abc`, `0`, `-1`, fuera de rango, `20-10-2026`, `2026-02-30`; mensajes sin tipos de Java) y unitario del caso de uso (fecha anterior a hoy con reloj controlado) | Service simulado; `MutableClock` | `400` `VALIDATION_ERROR` con el campo; sin llamadas a los puertos |
| CA-02 | Adaptador `CatalogClientAdapterTest` contra el servidor simulado | Servidor simulado | Ruta y `Authorization` recibidos |
| CA-03, CA-04, CA-06 | Adaptador (traducción) y unitario del caso de uso de `availability` | Servidor simulado; puertos falsos | Resultados y respuestas de la tabla de errores; cátedra sin llamadas |
| CA-05 | Unitario `GetProfessionalUseCaseImplTest` con un cliente falso de secuencias, y adaptador con demora y puerto cerrado | Esperas de milisegundos | Intentos igual al máximo; éxito en el segundo |
| CA-07 | Unitario del caso de uso | Agendas de prueba | `409`; puerto de ocupaciones sin llamadas; categoría deshabilitada sin efecto |
| CA-08 | Adaptador `CatedraOccupancyClientAdapterTest`; unitario del caso de uso (caché guardado) | Servidor simulado | Consulta y `Bearer` correctos; entrada en el caché |
| CA-25 | Unitario `GetOccupanciesUseCaseImplTest` (`Failed`) y del caso de uso de `availability` con caché vigente | Cliente falso | `500`; caché sin usar |
| CA-09, CA-10 | Unitario `GetOccupanciesUseCaseImplTest` con cliente y sesión falsos | `ScriptedLoginClient`, repositorio en memoria | Un login y una repetición; `ConfigurationError` |
| CA-11, CA-12 | Unitario del caso de uso con caché real en memoria y `MutableClock` | Reloj controlado | `stale: true` con el `dataAsOf`; `503` y entrada borrada |
| CA-13, CA-14, CA-15, CA-22 | Unitarios del dominio (`ProfessionalAgendaTest`) | Sin Spring | Exactamente los turnos esperados |
| CA-16 | Web y mapper (`AvailabilityDtoMapperTest`) | — | Formato del contrato §3.3 |
| CA-17, CA-18 | Unitario del caso de uso | Puertos falsos | `emptyReason`; ocupaciones sin llamadas en CA-17 |
| CA-19 | Binding de propiedades (`ApplicationContextRunner`) | — | Valores por defecto y configurados |
| CA-20 | Unitarios con `OutputCaptureExtension` e integración | — | Intentos registrados; sin tokens |
| CA-26 | E2E `src/test/java/e2e/FreeSlotsE2ETest` (etiqueta `e2e`, `./mvnw test -Pe2e`): registro, login y turnos libres con la cátedra real y catálogo simulado | `.env` local; Testcontainers | `200` con ocupaciones reales; salteado sin credenciales |
| CA-21, CA-24 | Integración `FreeSlotsIntegrationTest`: app completa, Postgres, catálogo y cátedra simulados | Testcontainers y servidores simulados | Respuesta esperada; contenido de la base sin cambios |
| CA-23 | Integración sin URL de catálogo y con URL entre comillas | App completa | Warning; `503`; T01 y T02 normales |

**Comprobaciones de regresión:** los 248 tests actuales, en especial los de X01 después del renombre, y los cinco chequeos de la skill (el quinto, con los cruces autorizados de `availability`).

**Comandos verificados para compilar y ejecutar tests:** `./mvnw test`, `./mvnw test -Dtest=NombreDeLaClase` y `./mvnw clean verify` (`CLAUDE.md`). Conteo con los reportes XML de Surefire.

**Pruebas con infraestructura real o simulada:** la suite normal simula cátedra y catálogo, con respuestas grabadas de la cátedra real; Postgres de Testcontainers en la integración. La suite E2E usa la cátedra real y catálogo simulado (P6, decisión 010). Además, una prueba manual corta con la aplicación levantada y `curl`.

**Limitaciones del entorno:** el servicio de catálogo real no existe; su integración real queda sin verificar hasta que exista.

<!-- Esta sección planifica la validación. Durante la implementación, registra
en TASKS.md o en el informe de validación acordado los resultados y evidencias
reales. Distingue pruebas ejecutadas, fallidas, no ejecutadas y bloqueadas.
Compilar o tener tests en verde no sustituye revisar los criterios de la spec. -->

## Orden de implementación

<!-- Etapas y dependencias principales. El desglose ejecutable se escribe en TASKS.md.
Incluye puntos de comprobación para avanzar con cambios pequeños. -->

1. **Renombre de `integration` a `catedra`** y corrección del comentario de `FakeCatedraServer`. En la documentación: `CLAUDE.md`, decisión 009, SPEC y PLAN de T03 y PLAN de la validación del JWT pasan a `catedra`; SPEC, PLAN y TASKS de X01 suman una nota al principio sin reescribir su evidencia; el campo JSON `integration` de la cátedra (contrato y SPEC de X01) no se toca. Se comprueba con los 248 tests en verde, los chequeos de la skill y `grep`.
2. **Servidor HTTP simulado generalizado y respuestas grabadas** de la cátedra real, limpias. Se comprueba con los tests de X01 sin cambios de comportamiento y con `grep` sobre las grabaciones.
3. **Pasarela `catalog`:** dominio, caso de uso con reintentos, cliente y propiedades. Se comprueba con CA-02 a CA-06 en unitarios y del adaptador.
4. **Operación de ocupaciones en `catedra`:** dominio, caso de uso con sesión, `401` y reintentos, cliente y propiedades. Se comprueba con CA-08 a CA-10.
5. **Dominio de `availability`:** agenda, cálculo de turnos y reglas. Se comprueba con CA-13 a CA-15 y CA-22.
6. **Caso de uso, caché y adaptadores de `availability`.** Se comprueba con CA-01 (fecha), CA-07, CA-11, CA-12, CA-17 y CA-18.
7. **Web y errores de parámetros:** controller, DTO, mapper y `ApiExceptionHandler`. Se comprueba con CA-01 y CA-16.
8. **Configuración e integración:** propiedades, `.env.example`, integración completa. Se comprueba con CA-19 a CA-21, CA-23 a CA-25.
9. **Suite E2E:** perfil en `pom.xml` y `FreeSlotsE2ETest`. Se comprueba con CA-26, con y sin credenciales, y con la suite normal sin cambios.
10. **Prueba manual, `CLAUDE.md` (slices nuevos, renombre y las dos suites), regresión y SonarQube.**

## Riesgos y decisiones pendientes

<!-- Riesgos concretos de esta solución y cómo se resolverán, sin listas genéricas.
Escribe Ninguna en las decisiones pendientes cuando estén resueltas. -->

- **Riesgos y medidas acordadas:**
  - El renombre puede dejar alguna referencia a `turnos.integration` (por ejemplo, en un comentario o en `CLAUDE.md`). Se comprueba con `grep` y con la suite completa antes de seguir.
  - La lógica del `401` y los reintentos de la cátedra queda, por ahora, dentro de la primera operación. Cuando T04 agregue la segunda, se extrae a una pieza común de `catedra`; si no, quedaría duplicada.
  - Tipos parecidos en varios slices (por ejemplo, el horario semanal en `catalog` y en `availability`). Es buscado: cada slice tiene su copia (skill §9) y los adaptadores traducen. Lo cubren los tests de los mappers y adaptadores.
  - Dormir el hilo del request durante las esperas de los reintentos ocupa un hilo de Tomcat hasta unos 11 s por servicio en el peor caso. Aceptable para el volumen del proyecto; se revisa si se ajustan los valores de D2.
- **Decisiones pendientes:**
  - Ninguna.
- **Decisiones tomadas (2026-10-24):**
  - **Decisión A · Slices pasarela:** `catedra` es la única puerta hacia la cátedra y `catalog` la única hacia el servicio de catálogo; `availability` las usa por puertos de salida propios cuyos adaptadores llaman a sus puertos de entrada (skill §9). Las reglas comunes de cada sistema se escriben una vez y T04, T05, T11 y T13 solo agregan su operación. Alternativa descartada: cada slice con sus propios clientes, que repetiría C06 en T04 y la lógica del `401` y los reintentos en cada caso de uso.
  - **Decisión B · Nombre del slice de T03:** `availability`, el término del enunciado (§4.2). Alternativa descartada: `freeslot`.
  - **P2 · Paquete de los adaptadores que llaman a otro slice:** `infrastructure/gateway`, solo para adaptadores de salida que implementan un puerto propio llamando al puerto de entrada de otro slice. Los cruces quedan juntos y visibles, y el chequeo 5 de la skill solo debe encontrar imports de otros slices en ese paquete. Excepción a la skill autorizada por el usuario, con su comentario en el código. Alternativa descartada: `infrastructure/client`, que mezclaría llamadas HTTP con llamadas dentro del mismo programa.
  - **P3 · Validación de los parámetros de la URL:** un DTO `FreeSlotsRequest` con `@Valid @ModelAttribute`, siguiendo el patrón de DTO de la skill. Se comprobó con un test temporal (2026-10-24) que faltantes, `@Positive` y errores de conversión llegan al manejador actual como `VALIDATION_ERROR` con `fieldErrors`; el único ajuste es neutralizar el mensaje de los errores de conversión, que expone tipos de Java. Alternativas descartadas: parámetros sueltos con tres manejadores nuevos en `ApiExceptionHandler`, y un DTO con textos y expresiones regulares.
  - **P4 · Descarte de las entradas vencidas del caché:** el adaptador en memoria se limpia solo con un método `@Scheduled` cada minuto (`@EnableScheduling` en `config`). Recorrer las entradas de la última hora tarda microsegundos y no consulta ni la base ni la red. Una entrada vencida también se borra si se la encuentra al consultar (RF-14). Alternativa descartada: un caso de uso y un disparador periódico en un paquete nuevo, más estructura para una limpieza interna.
  - **P5 · Texto de decisión:** `docs/decisiones/009-slices-pasarela.md`, redactado el 2026-10-24 (pasarelas, cruce con puertos propios y paquete `gateway`, renombre).
  - **P6 · Pruebas con la cátedra real:** dos suites (decisión 010). La normal simula la cátedra con respuestas reales grabadas y limpiadas. La E2E vive en `src/test/java/e2e/` (fuera de `turnos`, se le indica la aplicación con `classes = TurnosServiceApplication.class`), lleva la etiqueta `e2e`, se corre con `./mvnw test -Pe2e`, lee las credenciales del `.env` local (opción a) y queda salteada sin ellas. Necesita solo `TestcontainersConfiguration` (base limpia y clave de test) y el catálogo simulado mientras no exista el real; el token sale del login real, así que no usa `TestTokens` ni `TestSecurityConfiguration`. Alternativas descartadas: credenciales solo por variables de entorno (opción b), E2E dentro de la suite normal, en una carpeta de fuentes o un módulo aparte, o dentro del paquete `turnos`.
  - **P1 · Nombres de las pasarelas:** `catedra` (renombrando `integration`) y `catalog`. Java no admite guiones en los paquetes; los nombres cortos y simétricos dicen con qué sistema habla cada uno, y `catedra` ya es el nombre de las propiedades (`turnos.catedra.*`) y de las clases (`Catedra…`). El renombre es la primera tarea, sin cambios de comportamiento; los documentos SDD de X01 quedan como registro histórico y `CLAUDE.md` lo aclara. Alternativas descartadas: `catedraintegration` y `catalogintegration` (largos), y conservar `integration` (no dice con qué se integra ahora que hay dos).
