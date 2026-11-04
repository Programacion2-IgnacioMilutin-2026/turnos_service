# PLAN: T10 — Consultar mis reservas

**SPEC de referencia:** `docs/sdd/t10-mis-reservas/SPEC.md`
**Versión de la spec revisada:** aprobada el 2026-11-03 (D1 a D4, RF-01 a RF-12, CA-01 a CA-16), sin commitear
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
| Proceso de reserva | `reservation/domain/model/ReservationProcess.java` | `hasExpired` define el vencido; T10 no lo modifica |
| Política | `reservation/domain/model/ReservationPolicy.java` | Da la zona de atención y el margen de seguridad |
| Puerto de salida | `reservation/domain/ports/out/ReservationProcessRepository.java` | Suma la consulta del listado |
| Adaptador y repositorio JPA | `reservation/infrastructure/persistence/adapter/JpaReservationProcessRepositoryAdapter.java`, `persistence/repository/JpaReservationProcessRepository.java` | Suman la consulta |
| Service | `reservation/application/service/ReservationService.java` | Suma un método |
| Controller y mapper web | `reservation/infrastructure/web/controller/ReservationProcessController.java`, `web/mapper/ReservationProcessDtoMapper.java` | Suman el `GET` y la página. `toResponse` se reutiliza sin cambios |
| Parámetros por URL | `availability/infrastructure/web/dto/FreeSlotsRequest.java` | Patrón: `@ModelAttribute`, con mensajes propios y errores de conversión como `fieldErrors` |
| Manejo global de errores | `config/ApiExceptionHandler.java` | Da formato a `VALIDATION_ERROR` y al `500` de una lectura fallida |
| Migraciones | `src/main/resources/db/migration/V1` a `V6` | Según P2, se suma `V7` |
| Tests | `src/test/java/turnos/reservation/`: `ReservationProcessStatusIntegrationTest`, `DatabaseDownStatusQueryIntegrationTest`, `infrastructure/persistence/adapter/JpaReservationProcessRepositoryAdapterTest`, `infrastructure/web/controller/ReservationProcessControllerTest` | Patrón o se amplían |

**Convenciones y slice de referencia:** `reservation`, como T09.

## Solución propuesta

<!-- Explica el enfoque y sus motivos. Describe las responsabilidades y el
recorrido de la solicitud, el evento o la tarea desde que entra hasta la
respuesta, la persistencia o el mensaje publicado. Referencia el diagrama de
secuencia del proyecto si ya lo describe; usa un diagrama nuevo solo si aporta
claridad. -->

1. `ReservationProcessController` recibe el `GET` con los parámetros en un DTO validado y saca el usuario del JWT.
2. `ReservationService` llama al caso de uso `FindReservationProcessesByUserUseCase`, nombrado según la regla de la skill.
3. El caso de uso calcula el instante actual en la zona de atención y el límite del vencido (ahora menos el margen de seguridad), y pide la página con esos datos en un `ReservationListCriteria`.
4. El adaptador cuenta el total. Si la página pedida queda más allá, devuelve `items` vacío sin otra consulta. Si no, trae la página.
5. El controller arma `items`, `page`, `size` y `totalItems`, reutilizando `toResponse`.

No toma el candado ni escribe (D2).

**Decisiones técnicas:**

- **P1 · Dónde se filtra, se ordena y se pagina (opción a, decidida por el usuario el 2026-11-03):** en la base, con JPQL: una consulta filtra, ordena y trae la página, y otra cuenta el total. La regla del vencido queda también en esa consulta, y un test cuida que coincida con `hasExpired`. Descartados: en Java, porque cada pedido cargaría todo el historial del usuario; y SQL nativo, porque ata a Postgres sin ganar nada.
- **P2 · Índice por usuario (opción a, decidida por el usuario el 2026-11-03):** migración `V7` con un índice común por `user_id`. Medido en un Postgres temporal, con 200.000 filas, una corrida: de 9,8 ms a 0,16 ms.
- **P3 · Cómo se valida `status` (opción a, decidida por el usuario el 2026-11-03):** texto en el DTO con `@Pattern`, que acepta los 5 valores y el vacío. El mapper lo convierte a `ReservationStatus`, y la lista de estados sin filtro sale del enum, con un método nuevo `isListable`. El usuario prefería reutilizar el enum también en el DTO: se puede, pero aceptaría `EXPIRED` e `INVALID`, y rechazarlos informando el campo `status` en `fieldErrors` exige una validación propia. Un test comprueba que lo que acepta el `@Pattern` coincide con `isListable`.
- **P4 · Dónde vive el tipo de la página (opción a, decidida por el usuario el 2026-11-03):** un `record` `ReservationProcessPage` en el dominio de `reservation`, con los procesos y el total. La respuesta tiene su propio DTO.
- **P5 · Contar primero, consecuencia de P1 y de CA-10.** El adaptador calcula el desplazamiento como `long` y, si es mayor o igual que el total, no consulta la página. Así un número de página enorme nunca llega a la base, donde el desplazamiento se limita a un `int`. `size` y `page` no numéricos o fuera de rango de un `Integer` ya son un `400`.
- **P6 · Filtro sin valores nulos, consecuencia de P1.** La consulta recibe siempre una lista de estados: el pedido o todos los listables. Un parámetro `null` en JPQL puede fallar en Postgres por no poder determinar su tipo.

## Slices y componentes afectados

<!-- Aplica la skill hexagonal-vertical-slicing: un paquete por slice, con sus
capas domain, application e infrastructure. Identifica los slices afectados, sus
responsabilidades y la dirección de sus dependencias. Una funcionalidad puede
afectar más de un slice. Justifica cualquier slice nuevo o dependencia nueva
entre slices. -->

| Slice | Existe / nuevo | Responsabilidad y cambios | Dependencias con otros slices |
| --- | --- | --- | --- |
| `reservation` | Existente | Suma el listado | Ninguna nueva |

<!-- Distingue lo que se reutiliza, modifica o crea. Las rutas nuevas son
propuestas. Señala impacto sobre modelos, contratos o componentes compartidos. -->

| Slice y capa | Componente o ruta | Acción | Cambio y responsabilidad | Requisito relacionado |
| --- | --- | --- | --- | --- |
| `reservation`, dominio | `domain/model/ReservationStatus.java` | Modificar | `isListable`: abiertos, `CONFIRMED` y `CANCELLED` | RF-05 |
| `reservation`, dominio | `domain/model/ReservationListCriteria.java` | Crear | `record` con usuario, estado opcional, límite del vencido, fecha y hora actuales, página y tamaño | RF-03 a RF-06 |
| `reservation`, dominio | `domain/model/ReservationProcessPage.java` | Crear | `record` con los procesos y el total (P4) | RF-07 |
| `reservation`, dominio | `domain/ports/in/FindReservationProcessesByUserUseCase.java` | Crear | Puerto de entrada | RF-01 |
| `reservation`, dominio | `domain/ports/out/ReservationProcessRepository.java` | Modificar | `findListable(criteria)` | RF-03 a RF-07 |
| `reservation`, aplicación | `application/usecases/FindReservationProcessesByUserUseCaseImpl.java` | Crear | Arma el criterio con el `Clock` y `ReservationPolicy`. No usa candado ni escribe | RF-03, RF-09, RF-11 |
| `reservation`, aplicación | `application/service/ReservationService.java` | Modificar | `findReservationProcessesByUser` | RF-01 |
| `reservation`, persistencia | `infrastructure/persistence/repository/JpaReservationProcessRepository.java` | Modificar | Dos `@Query`: la página y el total | RF-03 a RF-06 |
| `reservation`, persistencia | `infrastructure/persistence/adapter/JpaReservationProcessRepositoryAdapter.java` | Modificar | `findListable`, con P5 y P6 | RF-07, RF-10 |
| `reservation`, persistencia | `src/main/resources/db/migration/V7__add_reservation_processes_user_index.sql` | Crear | Índice por `user_id` (P2) | Rendimiento |
| `reservation`, web | `infrastructure/web/dto/ReservationProcessListRequest.java` | Crear | `status`, `page` y `size`, con mensajes propios (P3) | RF-02 |
| `reservation`, web | `infrastructure/web/dto/ReservationProcessPageResponse.java` | Crear | `items`, `page`, `size` y `totalItems` | RF-07, RF-08 |
| `reservation`, web | `infrastructure/web/mapper/ReservationProcessDtoMapper.java` | Modificar | Valores por defecto, vacío como ausente y la página | RF-02, RF-07, RF-08 |
| `reservation`, web | `infrastructure/web/controller/ReservationProcessController.java` | Modificar | El `GET`, sin una traducción de errores propia | RF-01, RF-02, RF-10 |
| Tests | `application/usecases/CopyingReservationProcessRepository`, `ControllableProcessRepository` | Modificar | Implementan el método nuevo del puerto | Ninguno |
| Documentación | `CLAUDE.md`, `docs/decisiones/012-persistencia-del-proceso-de-reserva.md` | Modificar | T10, el índice y su motivo, y los criterios de la consulta | Ninguno |

## Datos y contratos

<!-- Completa solo lo aplicable. Si un punto no aplica, indica el motivo. -->

- **Operaciones, eventos y DTO de entrada y salida:** contrato §3.8 y contrato común §3.1. El contrato ya se ajustó en la SPEC.
- **Identificadores, relaciones y restricciones:** el dueño sale del token.
- **Origen de los datos devueltos o publicados y transformaciones:** todo sale de la fila local, con el mismo mapeo de T09.
- **Persistencia, consultas y migraciones:** dos consultas JPQL, la página y el total, con las mismas condiciones: el usuario; los estados de P6; los `HELD` y `WAITING_FOR_PHONE` con `expiresAt` mayor o igual que el límite; los `CANCELLED` con `confirmedAt`. La página ordena por: abierto primero; turno que no comenzó primero; esos, por fecha y hora ascendentes; los comenzados, por fecha y hora descendentes; `reservationProcessId`. Migración `V7` (P2).
- **Convivencia entre datos locales y remotos:** no aplica: T10 no consulta a la cátedra.
- **Compatibilidad y migraciones de datos existentes:** `V7` solo crea un índice. No cambia datos, y las respuestas de las demás operaciones no cambian.

## Transacciones, concurrencia y errores

<!-- Cómo se implementan los comportamientos aprobados en la spec.
Referencia RF/CA y aplica las consideraciones relevantes de BACKEND_GUIDELINES.md. -->

- **Estados y transiciones:** ninguna: T10 no escribe.
- **Transacciones y consistencia:** sin transacción propia: son dos lecturas. Si otra operación escribe entre el total y la página, pueden diferir en un proceso hasta la consulta siguiente. Es aceptable en un listado, y evitarlo no valdría el costo.
- **Idempotencia y mensajes duplicados:** no aplica: solo lectura.
- **Concurrencia y tareas programadas:** sin candado (D2).
- **Timeouts, reintentos y errores de dependencias:** una falla de lectura llega a `ApiExceptionHandler`. El límite de espera de la base es el de T09.
- **Seguridad y autorización:** la cadena de seguridad ya exige JWT y rol en toda ruta no pública. El dueño sale del token.
- **Otras consideraciones de backend aplicables y su solución:** privacidad: el mismo DTO de T09, sin los datos privados.

## Dependencias y configuración

<!-- Librerías, servicios externos, propiedades de configuración y secretos
afectados. Verifica compatibilidad con el proyecto y justifica las incorporaciones.
No agregues dependencias por defecto. Los valores sensibles y los parámetros
ajustables se externalizan. -->

- Sin dependencias ni propiedades nuevas.

## Estrategia de validación

<!-- Una fila por criterio de la spec. Selecciona el método capaz de demostrarlo:
test unitario, de integración (base de datos, mensajería), de API o prueba manual.
No todos requieren todos los métodos. Identifica tests existentes y separa los
nuevos propuestos. Incluye regresiones relevantes. Una prueba con mocks no
demuestra persistencia, transacciones ni idempotencia. -->

| Criterio | Método y test existente o propuesto | Entorno y datos necesarios | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01 | Web, en `ReservationProcessControllerTest` | `@WebMvcTest`, `TestTokens` | `401` y `403`; el service no se llama |
| CA-02 | Adaptador en `JpaReservationProcessRepositoryAdapterTest`; integración nueva `ReservationProcessListIntegrationTest` | Postgres, dos usuarios | Cada uno ve solo lo suyo |
| CA-03 | Web e integración | Sin parámetros y con cada estado | Valores por defecto y filtro |
| CA-04 | Web | Cada valor inválido | `400` con el campo; el service no se llama. Test de `@Pattern` contra `isListable` |
| CA-05 | Integración, contra el servidor real | `?status=`, `?page=` y `?size=` | Igual que sin parámetros; D3 queda comprobada |
| CA-06 | Adaptador e integración | Fuera y dentro del margen, también en el límite exacto, contra `hasExpired` | Coincide con el modelo; tampoco con filtro |
| CA-07 | Adaptador e integración | `PHONE_SUBMITTED` vencido | Aparece |
| CA-08 | Adaptador | Una fila por estado | Solo las listables |
| CA-09 | Adaptador, con el instante fijo; integración por páginas | Abierto, futuros, pasados, de hoy y empatados | Orden exacto; sin repetidos ni faltantes |
| CA-10 | Adaptador e integración | Sin filas; más allá de la última; página enorme | Vacía, con el total real; nunca `500` |
| CA-11 | Mapper e integración | Un proceso por estado listable | Campos según el estado |
| CA-12 | Integración | El test toma el candado y lista | Responde con el candado tomado |
| CA-13 | Web; `DatabaseDownStatusQueryIntegrationTest` ampliado | Falla simulada; Postgres en pausa | `500` `INTERNAL_ERROR` |
| CA-14 | Integración | Filas antes y después, también un vencido; servidores simulados | Sin cambios ni requests |
| CA-15 | Integración | Fila con teléfono; salida capturada | Sin esos datos |
| CA-16 | Suite completa | `./mvnw clean test` | Sin cambios en lo que comprueban |

**Comprobaciones de regresión:** suite completa. Pruebas en negativo previstas: sacar la exclusión de vencidos, sacar la de cancelados sin confirmar, invertir el orden de los pasados, quitar el desempate, sacar `P5`, y sacar el filtro por usuario.

**Comandos verificados para compilar y ejecutar tests:** `./mvnw test -Dtest=NombreDeLaClase`, `./mvnw clean test`. El conteo sale de `target/surefire-reports/TEST-*.xml`.

**Pruebas con infraestructura real o simulada:** Postgres real en Testcontainers, indispensable para el orden y los filtros; cátedra y catálogo simulados; Kafka apagado.

**Limitaciones del entorno:** no se levanta la app con `.env`, porque el arranque hace el login técnico contra la cátedra real, y T10 no necesita esa prueba (D4).

<!-- Esta sección planifica la validación. Durante la implementación, registra
en TASKS.md o en el informe de validación acordado los resultados y evidencias
reales. Distingue pruebas ejecutadas, fallidas, no ejecutadas y bloqueadas.
Compilar o tener tests en verde no sustituye revisar los criterios de la spec. -->

## Orden de implementación

<!-- Etapas y dependencias principales. El desglose ejecutable se escribe en TASKS.md.
Incluye puntos de comprobación para avanzar con cambios pequeños. -->

1. Dominio: `isListable`, los dos `record` y el puerto. Comprobación: compila, y test de `isListable`.
2. Persistencia: las dos consultas, el adaptador y `V7`. Comprobación: `JpaReservationProcessRepositoryAdapterTest`, sobre Postgres.
3. Caso de uso y service. Comprobación: test del caso de uso.
4. Web: DTO, mapper y controller. Comprobación: `ReservationProcessControllerTest` y `ReservationProcessDtoMapperTest`.
5. Integración, incluidos el vacío, la página enorme y el candado tomado.
6. Base caída: amplía `DatabaseDownStatusQueryIntegrationTest`.
7. Documentación, suite completa y conteo.

## Riesgos y decisiones pendientes

<!-- Riesgos concretos de esta solución y cómo se resolverán, sin listas genéricas.
Escribe Ninguna en las decisiones pendientes cuando estén resueltas. -->

- **Riesgos y medidas acordadas:**
  - **La regla del vencido queda en dos lugares (D2, P1):** si cambia en el modelo y no en la consulta, un vencido se vería. Lo detecta el test de CA-06, que compara las dos sobre los mismos casos, incluido el límite exacto.
  - **JPQL y Hibernate:** la consulta usa `CASE` con parámetros en el `ORDER BY`. No comprobé todavía que Hibernate y Postgres la acepten tal cual. Se comprueba primero, en el paso 2. Si no se puede, se avisa, porque cambiaría P1.
  - **D3 sin comprobar:** cómo convierte Spring un parámetro vacío. Se comprueba en el paso 5. Si no funciona como se espera, se ajusta el mapper o el DTO.
  - **El turno cambia de grupo con la hora:** entre dos páginas, un turno que acaba de comenzar puede repetirse o faltar. Pasa muy rara vez y se corrige al actualizar la lista.
- **Decisiones pendientes:** Ninguna.

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el plan cubre los requisitos, respeta las exclusiones, reutiliza
componentes verificados, respeta la skill hexagonal-vertical-slicing y permite
demostrar todos los criterios de aceptación.
Resuelve dudas y marcadores pendientes. Si la spec cambió, revisa su impacto.
Tras aprobar el plan, deriva TASKS.md con IDs, dependencias, referencias a RF/CA
y comprobaciones. No marques una tarea terminada sin realizar su validación;
si está bloqueada, registra el motivo.
-->
