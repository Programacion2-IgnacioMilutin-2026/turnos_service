# SPEC: T04 — Bloquear turno temporalmente (crear hold)

**Estado:** Aprobada <!-- Borrador | En revisión | Aprobada -->

<!-- PARA LA PERSONA
Copia esta plantilla como SPEC.md en una carpeta de la funcionalidad.
Pide al agente que la complete contigo usando BACKEND_GUIDELINES.md.
SPEC.md define qué debe cumplirse; PLAN.md desarrolla cómo implementarlo;
TASKS.md organiza los pasos de ejecución.
Los casos de uso, diagramas, contratos y el anexo de la cátedra se referencian
por ID; esta SPEC no los reescribe.
-->

<!-- PARA EL AGENTE
- Lee las instrucciones del proyecto, BACKEND_GUIDELINES.md y los documentos
  citados en Referencias (casos de uso, diagramas, contratos, modelo de dominio
  y anexo de la cátedra). Inspecciona el repositorio para comprobar el
  comportamiento actual. Si falta un documento, pide su ubicación.
- Los casos de uso y los contratos son la fuente de verdad. No los reescribas ni
  los contradigas: referencia sus pasos y flujos alternativos por ID. Si esta
  spec y un documento de referencia difieren, o este es ambiguo, detente y consulta.
- Completa esta spec con la persona: investiga lo comprobable y consulta las
  decisiones pendientes. Haz pocas preguntas por vez y actualiza las respuestas.
- No inventes requisitos ni exclusiones. Distingue propuestas de decisiones
  confirmadas y marca como PENDIENTE lo que aún no esté resuelto.
- Aplica las consideraciones de backend relevantes sin ampliar el alcance automáticamente.
- No incluyas diseño de clases, tablas, componentes, archivos o algoritmos:
  esos detalles pertenecen a PLAN.md. Sí registra restricciones explícitas del pedido.
- Mantén el documento breve y proporcional a la funcionalidad. Conserva los comentarios.
- Un documento completo no está aprobado automáticamente. Solicita aprobación
  antes de marcarlo como Aprobada. No implementes durante esta etapa.
-->

## Referencias

<!-- Documentos de Documentacion en los que se apoya esta funcionalidad. Indica
qué partes cubre esta spec, con IDs exactos de archivo, caso de uso, paso y flujo
alternativo. Una funcionalidad puede cubrir solo una parte de un caso de uso: lo
que no se nombra aquí no forma parte de ella. -->

| Documento | Partes cubiertas |
| --- | --- |
| Caso de uso T04 (`docs/casos_de_uso/turnos_y_reservas_usecases.md`) | Precondición, pasos 1 y 3 a 8, alternativos 3.1, 3.2, 4.1 a 4.4, 5.1 a 5.8 y 7.1, y postcondición. El paso 2 y el alternativo 2.1 ya los resuelve la validación del JWT (`docs/sdd/validacion-jwt/`). El 5.8 se cambió según D1. El alternativo 4.5 (profesional deshabilitado según catálogo) se agregó según D2 |
| Diagrama de secuencia (`docs/secuencias/turnos_y_reservas_secuencia.md`) | §3, bloque "T04 — Block the slot (create hold)" |
| Contrato de turnos (`docs/contratos/turnos_y_reservas_contrato.md`) | §1 (fila T04), §2 (proceso de reserva), §3 (reglas comunes), §3.4 completo, §4.1 (uso de C06), §4.2.1 (reglas comunes de la cátedra) y §4.2.4 (crear un hold) |
| Contrato común (`docs/contratos/contratos_comunes.md`) | §1.4 y §1.5 (identidad desde `sub`; el JWT del usuario se reenvía a catálogo), §2 (formato de errores) y §3 (fechas, horas, instantes y zona horaria de atención; la fila de las horas se aclaró según D6) |
| Contrato de catálogo (`../catalog_service/docs/contratos/catalogo_y_sincronizacion_contrato.md`) | §3.1 (C06), como lo que turnos espera recibir |
| Modelo de dominio de turnos (`docs/turnos_y_reservas_dominio.md`) | `ReservationProcess` y `ReservationStatus`; §2 (estados: `[*] → HELD` y cierre por vencimiento en T04); reglas 2, 4, 5, 7, 8, 13, 14 y 16 |
| Modelo de dominio de catálogo (`../catalog_service/docs/catalogo_y_sincronizacion_dominio.md`) | Reglas 4 a 6 (`enabled` independientes; solo los horarios habilitados generan turnos; cómo se generan), igual que en T03 |
| X01, su SPEC (`docs/sdd/x01-cuenta-tecnica/`) y decisión 005 | Cómo se pide la sesión técnica y un login nuevo tras un `401` o con la integración degradada |
| SPEC de T03 (`docs/sdd/t03-turnos-libres/`) | D1 (integración no habilitada al momento del request), D2 (tiempos y reintentos), D4 (zona horaria de atención), D7 (rango de `professionalId`) y D8 (URL de catálogo faltante), que T04 reutiliza |
| SPEC de la validación del JWT (`docs/sdd/validacion-jwt/`) | DC: qué hace T04 si el usuario del `sub` ya no existe (D5) |
| Anexo de la cátedra (`enunciado/INTEGRATION_REFERENCE-v2.md`) | §4 (horas), §9 (crear un hold), §13 y §13.1 (errores) y §18.4 (un timeout no prueba que una operación haya fallado; reintentos limitados y observables) |
| Enunciado (`enunciado/PROJECT_STATEMENT-v1.md`) | §4.2 (datos históricos del profesional; procesos asociados al usuario), §7 (hold temporal), §8 (sin catálogo no se inician operaciones que validan información vigente; repetición por timeout) |
| Caso de uso de la app A06 (`documentacion/uml/casos_de_uso/app_kmp_usecases.md`) | Solo como contexto: cómo usa la app cada respuesta, y que un reintento de la app tras un timeout recibe `OPEN_PROCESS_EXISTS` si el proceso ya se creó (A06 2.1) |

## Qué construimos y para quién

<!-- Qué necesidad cubre, qué actor la inicia (la app KMP, otro servicio, el
tiempo o la cátedra) y qué podrá hacer o recibir. Describe el objetivo en
lenguaje de negocio. Resume y referencia: no copies la descripción del caso de uso. -->

Un usuario con sesión iniciada elige uno de los turnos libres que vio en T03 y pide bloquearlo, para que nadie más lo tome mientras confirma la reserva (T05). Turnos revalida el turno contra los horarios vigentes de catálogo (C06), pide a la cátedra un bloqueo temporal (hold) y guarda el proceso de reserva, asociado al usuario y en estado `HELD`, con su vencimiento. Un usuario tiene a lo sumo un proceso abierto a la vez. La inicia la app KMP (A06).

## Situación actual

<!-- Qué existe hoy y es relevante (código, datos, otras funcionalidades ya
implementadas), la limitación que queremos resolver y los comportamientos
existentes que deben conservarse. Si todavía no hay código para esto, indícalo.
No describas la arquitectura. -->

- Están implementados T01, T02, X01, la validación del JWT y T03. `POST /api/reservation-processes` todavía no existe: con un token válido responde `404` y sin token `401`.
- Ya existen la consulta a catálogo (C06, con reintentos) y la sesión técnica con la cátedra (login a pedido, `401` con una repetición, reintentos), usadas por T03. Catálogo hoy solo informa a turnos el `enabled` y los horarios del profesional: no su nombre ni su categoría.
- No hay ninguna llamada a la cátedra que cree datos, ni procesos de reserva guardados: la base solo tiene usuarios.
- El servicio de catálogo todavía no existe: se simula según su contrato.
- Debe conservarse el comportamiento de T01, T02, X01, la validación del JWT y T03.

## Dentro del alcance

<!-- Requisitos concretos, con identificadores estables para vincularlos a
criterios, decisiones del plan y tareas. Cada requisito indica el paso o flujo
alternativo del que proviene. No agregues requisitos que los documentos de
referencia no tengan sin consultarlo. -->

**Entrada**

- **RF-01** · T04 paso 1, contrato §1 y §3.4: `POST /api/reservation-processes`, protegido (cualquier rol), con `professionalId`, `date` y `startTime` en el cuerpo. El dueño del proceso es el `sub` del JWT; el token se reenvía a catálogo. La identidad nunca sale del cuerpo.
- **RF-02** · T04 paso 3 y alternativo 3.1, contrato §3.4: los tres campos son obligatorios; `professionalId` es un entero positivo dentro del rango de un `Long` (como D7 de T03); `date` es `yyyy-MM-dd`; `startTime` es una hora `HH:mm` o `HH:mm:ss` (D6); una hora con segundos distintos de cero no es el inicio de ningún turno y responde `400` `INVALID_SLOT` (RF-12); el turno no es anterior al momento actual en la zona horaria de atención (D4 de T03). Si no, `400` `VALIDATION_ERROR` con `fieldErrors`, sin llamar a catálogo ni a la cátedra. Un campo que no se puede convertir (texto en lugar de número, fecha u hora imposibles) también se informa en `fieldErrors`, con el mensaje `invalid value`, como en T03; un cuerpo que no es JSON válido responde `400` `VALIDATION_ERROR` sin `fieldErrors` (D7). Un turno que empieza justo en el momento actual cuenta como anterior (D10).

**Proceso abierto**

- **RF-03** · T04 paso 3, dominio §2 y reglas 7 y 8: antes de consultar a catálogo, los procesos del usuario en `HELD` o `WAITING_FOR_PHONE` cuyo `expiresAt` más el margen de seguridad ya pasó quedan `EXPIRED`. El margen es de 30 segundos por defecto y se configura por una variable de entorno documentada en `.env.example` (D4).
- **RF-04** · T04 paso 3 y alternativo 3.2, dominio regla 5, contrato §3.4: si al usuario le queda un proceso abierto (`HELD`, `WAITING_FOR_PHONE` o `PHONE_SUBMITTED`), responde `409` `OPEN_PROCESS_EXISTS` con `reservationProcessId`, `currentStatus` y `expiresAt` de ese proceso, sin llamar a catálogo ni a la cátedra.
- **RF-05** · Dominio regla 5, guía de backend (concurrencia), D3: dos pedidos simultáneos del mismo usuario nunca dejan dos procesos abiertos. Los pedidos de bloqueo de un mismo usuario se atienden de a uno: el segundo espera a que termine el primero, encuentra su proceso abierto y recibe `409` `OPEN_PROCESS_EXISTS` (RF-04), sin pedir un hold. Además, la base rechaza un segundo proceso abierto del mismo usuario; si eso ocurre (por ejemplo, con más de una instancia del servicio), responde `409` `OPEN_PROCESS_EXISTS` con los datos del proceso que ya existía y registra un error que identifica el hold que quedó sin proceso, que vence solo. Los pedidos de usuarios distintos no se esperan entre sí.

**Catálogo (C06)**

- **RF-06** · T04 paso 4, contrato §4.1, contrato común §1.5: pide a catálogo el profesional y sus horarios, reenviando sin modificar el JWT que mandó la app.
- **RF-07** · T04 alternativo 4.1, contrato §3.4: si catálogo responde `404`, responde `404` `PROFESSIONAL_NOT_FOUND`, sin llamar a la cátedra.
- **RF-08** · T04 alternativo 4.3, contrato §4.1: ante timeout, corte de conexión o `503`, reintenta con espera creciente y un máximo de intentos, con los mismos valores que T03 (D2 de T03); agotados, responde `503` `CATALOG_UNAVAILABLE`, sin llamar a la cátedra. Sin URL de catálogo válida, responde lo mismo sin intentar la llamada (D8 de T03).
- **RF-09** · T04 alternativo 4.4, contrato §4.1: si catálogo responde `401` o `403`, registra un error de integración interna y responde `500` `INTERNAL_ERROR`, sin llamar a la cátedra.
- **RF-10** · Contrato §4.1 (última fila): cualquier otra respuesta de catálogo (por ejemplo `400` o `500`) se registra y responde `500` `INTERNAL_ERROR`, sin llamar a la cátedra.
- **RF-11** · T04 alternativo 4.5 (nuevo, D2), igual que T03 4.4: si catálogo informa el profesional con `enabled` en falso, responde `409` `PROFESSIONAL_DISABLED`, sin llamar a la cátedra. Se evalúa antes que el turno (RF-12). El `enabled` de la categoría no se tiene en cuenta (D5 de T03).
- **RF-12** · T04 paso 4 y alternativo 4.2, contrato §3.4, dominio de catálogo reglas 5 y 6: el turno es válido si al menos un horario semanal habilitado del día de la semana de `date` lo genera: `startTime` es el inicio del horario más un múltiplo de `slotDurationMinutes`, y el turno termina en o antes de `endTime`. Si ninguno lo genera, responde `400` `INVALID_SLOT`, sin llamar a la cátedra.

**Cátedra (crear el hold)**

- **RF-13** · T04 paso 5, contrato §4.2.1 y §4.2.4, anexo §9: pide el hold con `professionalId`, `date`, `startTime` (`HH:mm`) y `externalPatientId` igual al `sub` del usuario como texto (dominio regla 2), con el JWT técnico vigente de la sesión de X01.
- **RF-14** · T04 alternativo 5.1, contrato §4.2.1: si la cátedra responde `401`, pide a X01 un login nuevo, informando el JWT rechazado, y repite el pedido una sola vez con la sesión nueva. Repetir es seguro: un `401` significa que la cátedra rechazó el pedido sin procesarlo. Si X01 no consigue una sesión, o el segundo intento vuelve a recibir `401`, responde `503` `CENTRAL_UNAVAILABLE`.
- **RF-15** · T04 alternativo 5.2, contrato §4.2.1, D1 de T03: si la cátedra responde `403` `STUDENT_INTEGRATION_NOT_FOUND`, o la integración está en error de configuración al momento del request, registra un error de configuración y responde `503` `SERVICE_DEGRADED` sin exponer detalles. Si está degradada, primero pide a X01 un login nuevo; si no se consigue sesión, responde `503` `CENTRAL_UNAVAILABLE`.
- **RF-16** · T04 alternativo 5.3: `404` `PROFESSIONAL_NOT_FOUND` de la cátedra se registra y responde `404` `PROFESSIONAL_NOT_FOUND`.
- **RF-17** · T04 alternativo 5.4: `400` `PROFESSIONAL_DISABLED` de la cátedra responde `409` `PROFESSIONAL_DISABLED`.
- **RF-18** · T04 alternativo 5.5: `400` `INVALID_SLOT` de la cátedra responde `400` `INVALID_SLOT`.
- **RF-19** · T04 alternativos 5.6 y 5.7: `409` `SLOT_ALREADY_RESERVED` o `SLOT_ALREADY_HELD` de la cátedra responden `409` `SLOT_NOT_AVAILABLE`, el mismo código para los dos.
- **RF-20** · T04 alternativo 5.8 (según D1), anexo §18.4: se reintenta con espera creciente y un máximo de intentos solo cuando es seguro que la cátedra no procesó el pedido: no se pudo conectar, venció el tiempo de conexión o respondió `503`. Cuando pudo haberlo procesado (venció el tiempo de espera de la respuesta, la conexión se cortó durante la respuesta o respondió `500`), no se reintenta, porque el hold podría existir: se registra un warning que lo indica. En los dos casos, al no conseguir el hold, responde `503` `CENTRAL_UNAVAILABLE` y no crea ningún proceso local. Cualquier otra respuesta no prevista de la cátedra se registra y responde `500` `INTERNAL_ERROR`, como en T03 (D10 de T03).

**Proceso creado**

- **RF-21** · T04 pasos 6 y 7, dominio `ReservationProcess` y reglas 4, 13 y 14: con `201`, guarda un proceso nuevo en estado `HELD`, asociado al usuario, con `holdId`, `reservationProcessId` y `expiresAt` tal como los asigna la cátedra, `professionalId`, `date`, `startTime`, y el nombre, apellido y nombre de la categoría del profesional que informó catálogo en el paso 4, como dato histórico.
- **RF-22** · T04 paso 8, contrato §2 y §3.4: responde `201` con el proceso de reserva en estado `HELD`: `reservationProcessId`, `status`, datos del profesional, `date`, `startTime` (`HH:mm`), `expiresAt`, y `rejectionReason`, `reservationId`, `confirmedAt`, `cancelledAt` y `cancellationReason` en `null`. Nunca expone `holdId`.
- **RF-23** · T04 alternativo 7.1, contrato §3.4: si el hold se creó pero falla la persistencia local, registra un error crítico que permita identificar el hold huérfano y responde `500` `HOLD_NOT_SAVED` con el `expiresAt` recibido y el encabezado `Retry-After` con los segundos que faltan hasta ese instante. No hay recuperación automática (T13 lo excluye a propósito).

**Transversales**

- **RF-24** · Guía de backend (configuración), D4: el margen de seguridad se lee de una variable de entorno, con 30 segundos por defecto, y se puede cambiar sin tocar código; una variable vacía conserva el valor por defecto, como las de T03. Los tiempos y reintentos hacia catálogo y la cátedra son los ya configurables de T03.
- **RF-25** · Anexo §18.4, guía de backend (privacidad): cada reintento queda registrado con su número y su motivo. Ni el JWT del usuario ni el técnico aparecen en logs ni en respuestas.
- **RF-26** · SPEC de la validación del JWT (DC), D5: después de validar la entrada (RF-02) y antes de mirar sus procesos (RF-03), se verifica que el usuario del `sub` exista. Si no existe, responde `401` con el mismo cuerpo y el mismo `WWW-Authenticate` que un token inválido, sin llamar a catálogo ni a la cátedra, y no se crea ningún hold. Además, la base exige que todo proceso pertenezca a un usuario existente, y un usuario con procesos no se puede borrar.

## Fuera de alcance

<!-- Exclusiones acordadas, no deducidas por el agente. Incluye las partes de los
casos de uso que se dejan para otra funcionalidad. Si no hay exclusiones
adicionales, indícalo tras revisarlo con la persona. -->

Confirmadas por el usuario (2026-10-26):

- La validación del JWT del usuario (paso 2 y alternativo 2.1): ya está hecha.
- Confirmar el hold (T05) y todo lo que sigue (T06 a T11).
- El barrido periódico de vencidos (T12) y la reconciliación (T13). En particular, recuperar un hold huérfano (7.1 o un timeout de RF-20): T13 lo excluye a propósito y el hold vence solo.
- Cancelar un hold desde la app (T11). La suite E2E cancela el hold que crea con una llamada directa a la cátedra, solo como limpieza del test (D9).
- El servicio de catálogo real: se simula según su contrato.

## Flujo de la operación

<!-- Cómo se inicia (request, evento o tarea programada), qué verifica y qué
resultado produce, incluidas las alternativas relevantes. Si el caso de uso ya
lo describe, referencia sus pasos en lugar de repetirlos y describe solo lo que
cubre esta funcionalidad. -->

1. La app pide bloquear el turno con el JWT del usuario (T04 paso 1). La cadena de seguridad valida el token antes de todo.
2. El servicio sigue los pasos 3 a 8 de T04 y el diagrama de secuencia §3 (bloque T04), con sus alternativos.
3. Lo que agrega esta spec: después de validar la entrada, se verifica que el usuario del `sub` exista, antes de mirar sus procesos y de consultar a catálogo (RF-26, D5); el profesional deshabilitado según catálogo corta antes de llamar a la cátedra (RF-11, D2); solo se reintentan los pedidos de hold que la cátedra seguro no procesó (RF-20, D1); qué pasa si la integración no está habilitada al momento del request (RF-15, como D1 de T03); los pedidos simultáneos del mismo usuario (RF-05, D3); el formato de las horas y de los errores de la entrada (RF-02, D6, D7) y el margen configurable (RF-24, D4).

## Datos y reglas de negocio

<!-- Información que necesita la operación, campos obligatorios, validaciones,
límites y reglas como duplicados u orden de presentación. Describe significado
y comportamiento, sin diseñar tablas, DTO, DAO ni almacenamiento. -->

- De catálogo se usan el `enabled` del profesional (D2), su `firstName` y `lastName`, el `name` de su categoría y, de cada horario, `dayOfWeek`, `startTime`, `endTime`, `slotDurationMinutes` y `enabled`. El `enabled` de la categoría no se tiene en cuenta, como en T03 (D5 de T03).
- `reservationProcessId` y `holdId` son textos opacos que asigna la cátedra; turnos no genera identificadores propios para los procesos (dominio regla 4).
- `expiresAt` es un instante en UTC que fija la cátedra; guardarlo no extiende la vigencia del hold (anexo §9). `date` y `startTime` son locales de atención, sin zona (dominio regla 16).
- Los datos del profesional se guardan como copia del momento del bloqueo y nunca se usan como catálogo vigente (dominio regla 13, enunciado §4.2).
- El proceso se guarda en la base de turnos y no se borra al cerrarse (dominio regla 14).
- Un usuario tiene como máximo un proceso abierto; las reservas confirmadas no cuentan (dominio regla 5).

## Casos alternativos y de error

<!-- Adapta la tabla usando BACKEND_GUIDELINES.md. Cuando el caso de uso ya define
el flujo alternativo, referencia su ID en lugar de repetirlo. Añade escenarios
relevantes. Marca No aplica con su motivo cuando corresponda. Expresa resultados
observables (estado HTTP, código de error, estado final del recurso, evento
publicado), no mecanismos técnicos. -->

| Situación | Comportamiento esperado |
| --- | --- |
| Entrada inválida | T04 3.1: `400` `VALIDATION_ERROR` con `fieldErrors`, sin llamar a nadie (RF-02) |
| Sin sesión o sin permiso | Resuelto por la validación del JWT: `401` o `403` antes de llegar a T04 |
| Recurso inexistente o ajeno | Profesional inexistente: T04 4.1 y 5.3, `404` `PROFESSIONAL_NOT_FOUND` (RF-07, RF-16). Procesos ajenos: no aplica, T04 solo mira los procesos del usuario del `sub`. Usuario del `sub` inexistente: `401` como un token inválido, sin hold (RF-26, D5) |
| Proceso abierto del usuario | T04 3.2: `409` `OPEN_PROCESS_EXISTS` con los datos del proceso (RF-04); los vencidos se cierran antes (RF-03) |
| Turno fuera de agenda | T04 4.2 y 5.5: `400` `INVALID_SLOT` (RF-12, RF-18) |
| Profesional deshabilitado | Según catálogo, T04 4.5: `409` `PROFESSIONAL_DISABLED` sin llamar a la cátedra (RF-11, D2). Según la cátedra, T04 5.4: la misma respuesta (RF-17) |
| Turno ocupado | T04 5.6 y 5.7: `409` `SLOT_NOT_AVAILABLE` (RF-19) |
| Catálogo rechaza el token reenviado | T04 4.4: `500` `INTERNAL_ERROR`, error registrado (RF-09) |
| Catálogo no disponible | T04 4.3: reintentos y `503` `CATALOG_UNAVAILABLE` (RF-08) |
| Cátedra rechaza el JWT técnico | T04 5.1: login nuevo con X01 y una sola repetición (RF-14) |
| Integración en error de configuración o degradada | T04 5.2: `503` `SERVICE_DEGRADED`; degradada sin sesión, `503` `CENTRAL_UNAVAILABLE` (RF-15) |
| Cátedra no disponible | T04 5.8 según D1: reintentos solo si la cátedra seguro no lo procesó; si no, `503` `CENTRAL_UNAVAILABLE` sin reintentar; sin proceso local (RF-20) |
| Timeout o espera excesiva | Con catálogo, cuenta como no disponible (RF-08). Con la cátedra, un timeout de conexión se reintenta y un timeout de respuesta no, porque el hold pudo crearse (RF-20) |
| Operación o mensaje repetido | Un reintento de la app después de un bloqueo exitoso recibe `OPEN_PROCESS_EXISTS` (RF-04, A06 2.1). Si el bloqueo anterior terminó sin respuesta y el hold existe en la cátedra, el reintento puede recibir `SLOT_NOT_AVAILABLE` hasta que vence (RF-20) |
| Evento tardío o fuera de orden | No aplica: T04 no consume eventos |
| Falla de persistencia | T04 7.1: `500` `HOLD_NOT_SAVED` con `expiresAt` y `Retry-After` (RF-23). Si falla al cerrar los vencidos (RF-03), `500` `INTERNAL_ERROR` sin llamar a nadie (contrato §3) |
| Solicitudes simultáneas | Del mismo usuario: se atienden de a uno y el segundo recibe `409` `OPEN_PROCESS_EXISTS` sin pedir un hold; la base impide dos procesos abiertos aunque haya más de una instancia (RF-05, D3). De usuarios distintos sobre el mismo turno: la cátedra concede uno y el otro recibe `SLOT_NOT_AVAILABLE` (RF-19) |
| Reinicio con operaciones pendientes | El proceso ya guardado se conserva. Un request en curso durante el reinicio puede dejar un hold sin proceso local, que vence solo |

**Puntos de la guía no aplicables y motivo:** mensajería, eventos y tareas programadas: T04 no consume ni publica mensajes y el barrido periódico es T12. Paginación: T04 crea un solo proceso.

## Restricciones del pedido

<!-- Condiciones ya impuestas: compatibilidad, límites de alcance, requisitos
de seguridad o rendimiento medibles, o una tecnología expresamente exigida.
Ejemplo: Respetar el contrato de Documentacion y el anexo de la cátedra puede ser
una restricción; el diseño de los adaptadores va en PLAN.md.
No conviertas una preferencia del agente en una restricción. -->

- Respetar el contrato de turnos §2, §3.4, §4.1 y §4.2.4, el contrato común §2 y §3, y el C06 del contrato de catálogo, con los cambios aprobados en esta spec (D1, D2, D6).
- Turnos no guarda copia del catálogo: lo consulta en cada operación (enunciado §3, contrato §4.1). Los datos del profesional del proceso son solo históricos.
- El JWT técnico nunca sale hacia la app ni hacia catálogo; el del usuario se reenvía a catálogo sin modificarlo.
- La suite normal de tests no llama a la cátedra real ni al catálogo: los simula, con respuestas reales grabadas y limpiadas. La suite E2E usa la cátedra real y deshace lo que crea (decisión 010). Antes de cualquier llamada a la cátedra real se confirma con el usuario qué requests se harán, porque crear un hold modifica datos.
- Los controllers obtienen la identidad y el token con la convención de la decisión 008.
- Aplicar la skill `hexagonal-vertical-slicing` con las excepciones autorizadas. Lo que ya existe se amplía en lugar de duplicarse; la lógica de sesión, `401` y reintentos de la cátedra se extrae a una pieza común en lugar de copiarse (se decide en el PLAN).
- No cambiar el comportamiento de T01, T02, X01, la validación del JWT ni T03, salvo que los cuerpos de T01 y T02 con un campo que no se puede convertir pasan a informar ese campo en `fieldErrors` (D7).

## Criterios de aceptación

<!-- Resultados observables que permitan decidir si se cumple cada requisito.
Incluye los casos alternativos acordados: cada flujo alternativo cubierto debería
tener un criterio. No uses Funciona correctamente.
Repite el formato según sea necesario. -->

- **CA-01 · RF-02:** Cuando falta cualquiera de los tres campos, `professionalId` no es un entero positivo dentro del rango de un `Long` (`0`, `-1`, `"abc"`, `99999999999999999999`), `date` no es `yyyy-MM-dd` o `startTime` no es una hora válida, o el turno es anterior al momento actual en la zona de atención, entonces responde `400` `VALIDATION_ERROR` con el campo en `fieldErrors` (`invalid value` para los que no se pueden convertir, como `"abc"`, `2026-02-30` o `25:00`), y ni catálogo ni la cátedra reciben requests. `startTime` `09:30` y `09:30:00` se aceptan igual. Un cuerpo que no es JSON válido responde `400` `VALIDATION_ERROR` sin `fieldErrors`. Los cuerpos de T01 y T02 con un campo que no se puede convertir pasan a informar el campo, y el resto de sus respuestas no cambia. Un turno de hoy que empieza justo en el momento actual también responde `400`.
- **CA-02 · RF-03:** Dado un proceso del usuario en `HELD` cuyo `expiresAt` más el margen ya pasó, cuando pide bloquear otro turno, entonces ese proceso queda `EXPIRED` y el bloqueo sigue normalmente.
- **CA-03 · RF-04:** Dado un proceso del usuario en `HELD`, `WAITING_FOR_PHONE` o `PHONE_SUBMITTED` todavía vigente, cuando pide bloquear otro turno, entonces responde `409` `OPEN_PROCESS_EXISTS` con su `reservationProcessId`, `currentStatus` y `expiresAt`, y ni catálogo ni la cátedra reciben requests. Un proceso abierto de otro usuario no lo impide.
- **CA-04 · RF-05:** Dados dos pedidos simultáneos del mismo usuario para turnos distintos, entonces uno responde `201` y el otro `409` `OPEN_PROCESS_EXISTS` con los datos del primero, la cátedra recibe un solo pedido de hold y la base tiene un solo proceso abierto del usuario. Dado que la base ya tiene un proceso abierto del usuario cuando se intenta guardar otro (simulando una segunda instancia), entonces responde `409` `OPEN_PROCESS_EXISTS`, el log tiene un error que identifica el hold sin proceso y la base sigue con un solo proceso abierto. Dos pedidos simultáneos de usuarios distintos se atienden en paralelo.
- **CA-05 · RF-06:** Dado un request válido, cuando turnos consulta a catálogo, entonces catálogo recibe `GET /api/professionals/{professionalId}` con el mismo `Authorization` que mandó la app.
- **CA-06 · RF-07, RF-09, RF-10:** Cuando catálogo responde `404`, entonces turnos responde `404` `PROFESSIONAL_NOT_FOUND`; cuando responde `401`, `403`, `400` o `500`, entonces responde `500` `INTERNAL_ERROR` y registra el error. En todos los casos la cátedra no recibe requests.
- **CA-07 · RF-08:** Cuando catálogo responde `503`, demora más que el timeout o rechaza la conexión en todos los intentos, o no hay URL de catálogo válida, entonces turnos responde `503` `CATALOG_UNAVAILABLE` y la cátedra no recibe requests.
- **CA-08 · RF-11:** Dado un profesional con `enabled` en falso, entonces turnos responde `409` `PROFESSIONAL_DISABLED` y la cátedra no recibe requests, aunque el turno pertenezca a un horario habilitado. Dado un profesional habilitado de una categoría deshabilitada, entonces el bloqueo sigue normalmente.
- **CA-09 · RF-12:** Dado un horario habilitado de 09:00 a 12:00 cada 30 minutos para el día de la fecha, entonces `09:00`, `10:30` y `11:30` son válidos, y `10:15`, `12:00` y `08:30` responden `400` `INVALID_SLOT` sin llamar a la cátedra; un horario deshabilitado o de otro día no hace válido ningún turno; con dos horarios ese día, alcanza con que uno genere el turno.
- **CA-10 · RF-13, RF-21, RF-22:** Dado un turno válido, cuando la cátedra responde `201`, entonces recibió `POST /api/appointment-holds` con `professionalId`, `date`, `startTime` `HH:mm`, `externalPatientId` igual al `sub` y `Authorization: Bearer <JWT técnico vigente>`; turnos responde `201` con el proceso en `HELD`, el `reservationProcessId` y el `expiresAt` de la cátedra, los datos del profesional de catálogo y los demás campos en `null`, sin `holdId`; y la base tiene ese proceso asociado al usuario, con el `holdId`.
- **CA-11 · RF-14:** Cuando la cátedra responde `401` y X01 consigue un JWT nuevo, entonces el pedido se repite una sola vez con el JWT nuevo; si vuelve a responder `401`, no se repite de nuevo y turnos responde `503` `CENTRAL_UNAVAILABLE`.
- **CA-12 · RF-15:** Cuando la cátedra responde `403` `STUDENT_INTEGRATION_NOT_FOUND`, o la integración está en error de configuración, entonces turnos responde `503` `SERVICE_DEGRADED` sin detalles; con la integración degradada y sin sesión posible, `503` `CENTRAL_UNAVAILABLE` sin pedir el hold.
- **CA-13 · RF-16 a RF-19:** Cuando la cátedra responde `404` `PROFESSIONAL_NOT_FOUND`, `400` `PROFESSIONAL_DISABLED`, `400` `INVALID_SLOT`, `409` `SLOT_ALREADY_RESERVED` o `409` `SLOT_ALREADY_HELD`, entonces turnos responde `404` `PROFESSIONAL_NOT_FOUND`, `409` `PROFESSIONAL_DISABLED`, `400` `INVALID_SLOT`, `409` `SLOT_NOT_AVAILABLE` y `409` `SLOT_NOT_AVAILABLE`, respectivamente, y no se guarda ningún proceso.
- **CA-14 · RF-20:** Cuando la cátedra rechaza la conexión, demora más que el timeout de conexión o responde `503` en todos los intentos, entonces hay tantos intentos como el máximo configurado, con esperas crecientes, y turnos responde `503` `CENTRAL_UNAVAILABLE`; si un intento posterior responde `201`, el proceso se crea normalmente. Cuando la cátedra demora más que el timeout de respuesta o responde `500`, entonces hay un solo intento, un warning en el log y `503` `CENTRAL_UNAVAILABLE`. En ningún caso se guarda un proceso sin `201`. Una respuesta no prevista (por ejemplo `502`) responde `500` `INTERNAL_ERROR` en un solo intento.
- **CA-15 · RF-23:** Dado que la cátedra respondió `201` y la persistencia del proceso falla, entonces turnos responde `500` `HOLD_NOT_SAVED` con el `expiresAt` de la cátedra y `Retry-After` igual a los segundos hasta ese instante, el log tiene un error crítico que identifica el hold, y la base no tiene el proceso.
- **CA-16 · RF-24:** Cuando se cambia por configuración el margen de seguridad, entonces el servicio usa el valor nuevo sin cambios de código; sin configurarlo, o con la variable vacía, usa 30 segundos.
- **CA-17 · RF-25:** En todos los escenarios, cada reintento queda en el log con su número y su motivo, y la salida no contiene el JWT del usuario ni el técnico.
- **CA-18 · RF-26:** Dado un token válido cuyo `sub` no corresponde a ningún usuario, cuando pide bloquear un turno, entonces responde `401` con el mismo cuerpo y encabezado que un token inválido, y ni catálogo ni la cátedra reciben requests. Dado un usuario con un proceso, cuando se intenta borrar el usuario de la base, entonces el borrado se rechaza.
- **CA-19 · RF-01 a RF-23:** Con la aplicación completa, catálogo y cátedra simulados, un usuario que inició sesión bloquea un turno y recibe la respuesta esperada; un segundo pedido recibe `OPEN_PROCESS_EXISTS`.
- **CA-20 · RF-13, RF-21, RF-22:** Con la cátedra real (suite E2E) y catálogo simulado, un usuario registrado inicia sesión y bloquea un turno libre: recibe `201` con un proceso `HELD` y un `expiresAt` futuro, y al terminar el test cancela el hold en la cátedra (D9). El turno se elige entre los libres que informa T03, para no chocar con un hold anterior. Sin credenciales en el `.env`, el test queda salteado.

## Cómo se comprueba el comportamiento

<!-- Una fila por criterio: escenario y resultado que debemos comprobar.
La selección de tests, herramientas, comandos y evidencias se desarrolla en PLAN.md.
No marques los criterios como superados durante la especificación. -->

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | Requests sin cada campo, con valores inválidos y que no se pueden convertir de cada uno, con `startTime` en las dos formas, con un JSON mal escrito y con un turno de hoy ya pasado, con un reloj controlado; regresión de los tests de T01 y T02 | `400` con el campo (o sin `fieldErrors` en el JSON mal escrito); las dos formas de hora aceptadas; catálogo y cátedra simulados sin requests |
| CA-02 | Proceso `HELD` vencido más el margen, con un reloj controlado; pedir otro turno | Proceso viejo `EXPIRED`; proceso nuevo `HELD` |
| CA-03 | Procesos vigentes en cada estado abierto; y uno de otro usuario | `409` con los datos; sin requests externos; el otro usuario no influye |
| CA-04 | Dos pedidos a la vez del mismo usuario, con la cátedra simulada demorando la respuesta; un proceso abierto insertado en la base justo antes de guardar; dos usuarios a la vez | Un `201` y un `409`; un solo hold pedido; un solo proceso abierto; error registrado en el segundo caso; los dos usuarios avanzan sin esperarse |
| CA-05 | Catálogo simulado que guarda los requests | Ruta y `Authorization` iguales al del usuario |
| CA-06 | Catálogo simulado con `404`, `401`, `403`, `400` y `500` | `404` o `500` según el caso; cátedra sin requests |
| CA-07 | Catálogo simulado con `503`, con demora, apagado y sin URL | `503` `CATALOG_UNAVAILABLE`; cátedra sin requests |
| CA-08 | Profesional con `enabled: false`; profesional habilitado con categoría deshabilitada | `409` y cátedra sin requests; bloqueo normal |
| CA-09 | Horarios de ejemplo y turnos válidos e inválidos | Exactamente los válidos indicados; el resto `INVALID_SLOT` sin cátedra |
| CA-10 | Cátedra simulada con `201` grabado, que guarda los requests | Request y respuesta según el criterio; proceso en la base |
| CA-11 | Cátedra con `401` y después `201`; con `401` dos veces | Un login y una repetición; nunca una segunda |
| CA-12 | Cátedra con `403` `STUDENT_INTEGRATION_NOT_FOUND`; sesión en error de configuración; sesión degradada sin login posible | `503` `SERVICE_DEGRADED` o `CENTRAL_UNAVAILABLE` |
| CA-13 | Cátedra con cada error, con respuestas grabadas donde se pueda | Código de turnos correspondiente; base sin procesos |
| CA-14 | Cátedra apagada, con demora de conexión y con `503`; luego `503` y después `201`; con demora de respuesta, con `500` y con `502` | Intentos según el caso; `503` o `500`; base sin procesos salvo con `201` |
| CA-15 | Persistencia que falla después de un `201` | `500` `HOLD_NOT_SAVED`, `expiresAt`, `Retry-After`; error crítico en el log |
| CA-16 | Arrancar sin configurar el margen, con la variable vacía y con otro valor; un proceso vencido hace 29 s y otro hace 31 s, con un reloj controlado | 30 s por defecto o el configurado; con 30 s, solo el de 31 s queda `EXPIRED` |
| CA-17 | Revisar la salida de los escenarios de reintento | Intentos registrados; sin tokens |
| CA-18 | Token firmado con la clave de test para un `sub` que no está en la base; comparar con el `401` de un token alterado. Borrar con SQL un usuario que tiene un proceso | `401` idéntico; sin requests externos. Borrado rechazado por la base |
| CA-19 | Registrar, iniciar sesión, bloquear y volver a bloquear con la aplicación completa | `201` y después `409` `OPEN_PROCESS_EXISTS` |
| CA-20 | Suite E2E: `./mvnw test -Pe2e` con y sin credenciales | `201` real con un turno libre de T03 y hold cancelado al terminar; sin credenciales, salteado |

## Decisiones pendientes

<!-- Al resolverlas, actualiza las secciones afectadas. Escribe Ninguna cuando
no queden pendientes funcionales ni restricciones por decidir. -->

Ninguna.

Decisiones tomadas (2026-10-26):

- **D1 · Reintentos al crear el hold (RF-20, opción b):** se reintenta solo cuando es seguro que la cátedra no procesó el pedido (conexión rechazada, timeout de conexión o `503`). Ante un timeout de respuesta, un corte durante la respuesta o un `500`, no se reintenta y se responde `503` `CENTRAL_UNAVAILABLE`, porque el hold podría existir. Motivo: el anexo §18.4 dice que un timeout no prueba que una operación haya fallado y que, antes de repetir una acción con efectos, hay que reconciliar con identificadores persistidos, y antes de recibir la respuesta del hold no hay ninguno. Reintentar a ciegas no duplica reservas (la cátedra no admite dos holds sobre el mismo turno), pero puede responder `SLOT_NOT_AVAILABLE` por el propio hold del usuario. Alternativas descartadas: (a) reintentar todo, como decía el caso de uso; (c) no reintentar nunca. Cambia el caso de uso T04 5.8, el contrato §4.2.1 y §4.2.4 y el diagrama de secuencia §3. Con el OK del usuario (2026-10-26) se aplicó este texto en `documentacion` y en la copia de `turnos_service/docs`, que quedaron idénticas:
  - T04 5.8: "**5.8, Timeout, corte de conexión, `503` o `500` de la cátedra:** si es seguro que la cátedra no procesó el pedido (no se pudo conectar, venció el tiempo de conexión o respondió `503`), se reintenta con espera creciente y un máximo de intentos. Si pudo haberlo procesado (venció el tiempo de espera de la respuesta, la conexión se cortó durante la respuesta o respondió `500`), no se reintenta, porque el hold podría haberse creado, y se registra. En los dos casos, sin hold, se responde `503` (el bloqueo no pudo crearse) y termina, sin crear ningún proceso local; si el hold se creó, vence solo, como en 7.1."
  - Contrato §4.2.1, regla de reintentos: agregar al final "La creación de un hold es la excepción: ver 4.2.4."
  - Contrato §4.2.4, la fila "Timeout, corte, `503` o `500`" se reemplaza por dos: "Conexión rechazada, timeout de conexión o `503` → Reintenta. Agotados, responde `503` `CENTRAL_UNAVAILABLE` y no crea ningún proceso (T04 5.8)" y "Timeout de respuesta, corte durante la respuesta o `500` → No reintenta, porque el hold pudo crearse. Registra y responde `503` `CENTRAL_UNAVAILABLE`, sin crear ningún proceso (T04 5.8)".
  - Diagrama §3 (T04): el `break` "Central API unavailable after retries (timeout, connection lost, 503, 500, growing wait, max attempts)" pasa a "Central API unavailable after retries (connection refused, connect timeout, 503, growing wait, max attempts), or answer lost (read timeout, 500), not retried".
- **D2 · Profesional deshabilitado según catálogo (RF-11, opción a):** se corta en el paso 4 con `409` `PROFESSIONAL_DISABLED`, sin llamar a la cátedra, antes de validar el turno. Motivo: es lo que hace T03 (4.4), el enunciado §8 pide no iniciar operaciones sin validar la información vigente, y evita una llamada a la cátedra que modifica datos. Para la app la respuesta es la misma que en 5.4 (A06 3.6). Alternativa descartada: (b) no mirar `enabled` y dejar que decida la cátedra. Cambia el caso de uso T04 (alternativo nuevo), el contrato §3.4 y §4.1 y el diagrama de secuencia §3. Con el OK del usuario (2026-10-26) se aplicó este texto en `documentacion` y en la copia de `turnos_service/docs`, que quedaron idénticas:
  - T04, flujo alternativo, después del 4.4: "**4.5, El profesional está deshabilitado (`enabled` en falso):** responde `409` con un código funcional (`PROFESSIONAL_DISABLED`), igual que 5.4 y que T03 (4.4), sin llamar a la cátedra; termina."
  - Contrato §3.4, fila `409` `PROFESSIONAL_DISABLED`: "(T04 5.4)" pasa a "(T04 4.5 y 5.4)".
  - Contrato §4.1, fila `200`: "En T03, si `enabled` es falso, responde `409` `PROFESSIONAL_DISABLED` (T03 4.4)." pasa a "En T03 y T04, si `enabled` es falso, responde `409` `PROFESSIONAL_DISABLED` sin llamar a la cátedra (T03 4.4, T04 4.5)."
  - Diagrama §3 (T04): después del `break` de catálogo no disponible, un `break` nuevo "Professional disabled" con `Service-->>App: 409 (PROFESSIONAL_DISABLED, Central API not called)`, igual que en el diagrama §2 de T03.
- **D3 · Pedidos simultáneos del mismo usuario (RF-05, opción c):** los pedidos de un mismo usuario se atienden de a uno, y además la base impide dos procesos abiertos del mismo usuario. Motivo: el candado evita, en el caso normal (doble toque, dos dispositivos), que se cree un hold de más que le ocupe el turno a otro usuario hasta vencer; la regla de la base la garantiza aunque algún día haya más de una instancia del servicio, que el candado en memoria no cubre. Alternativas descartadas: (a) solo la base, que deja un hold huérfano en cada doble toque; (b) solo el candado, que depende de que haya una sola instancia.
- **D4 · Margen de seguridad (RF-03, RF-24, opción b):** 30 segundos por defecto, configurable por una variable de entorno documentada en `.env.example` (pedido del usuario). Un proceso vence cuando el instante actual supera `expiresAt` más el margen (dominio regla 7): esperar un poco después del vencimiento de la cátedra evita cerrar antes de tiempo un proceso que la cátedra todavía acepta si el reloj propio está adelantado, y la espera es casi imperceptible para el usuario. Se revisa si la duración real de un hold (D8) lo hace desproporcionado. Alternativas descartadas: (a) 0 segundos; (c) 60 segundos o más.
- **D5 · Usuario del `sub` inexistente (RF-26, opción b):** se verifica que el usuario exista antes de pedir el hold, y la base exige que cada proceso pertenezca a un usuario existente (clave foránea, que impide borrar un usuario con procesos). Si no existe, `401` igual al de un token inválido, para que la app mande a iniciar sesión (A06 3.2) sin distinguir los casos. Motivo: sin la verificación, el proceso no se podría guardar después de crear el hold (hold huérfano y `500` `HOLD_NOT_SAVED`); sin la clave foránea, un token viejo después de reiniciar la base podría dejar un proceso que heredaría el próximo usuario con ese id, y borrar un usuario dejaría procesos sin dueño. Costo aceptado por el usuario: una consulta por clave primaria en cada T04. Alternativas descartadas: (a) clave foránea sin verificar antes; (c) solo el id, sin clave foránea; (c') solo el id con la verificación. **Para T05 (indicación del usuario):** no hace falta volver a verificar que el usuario exista: el proceso se busca por su dueño y la clave foránea garantiza que ese usuario existe; T05 lee su nombre y apellido igual, porque los necesita.
- **D6 · Formato de `startTime` (RF-02, RF-22, opción b):** se emite `HH:mm`, como el contrato de turnos (§2 y §3.4) y T03, y se acepta `HH:mm` o `HH:mm:ss`, como pide el contrato común §3. Una hora con segundos distintos de cero responde `400` `INVALID_SLOT`. Motivo: el contrato común §3 dice que las horas se emiten `HH:mm:ss`, pero el contrato de turnos, más específico, fija `HH:mm` para los turnos, y T03 ya responde así. Alternativas descartadas: (a) aceptar solo `HH:mm`; (c) emitir `HH:mm:ss`, que cambiaría T03. Con el OK del usuario (2026-10-26), la fila "Hora local de atención" del contrato común §3 pasó de "Se emite `HH:mm:ss` y se acepta `HH:mm` o `HH:mm:ss`" a "Catálogo emite `HH:mm:ss`; turnos emite `HH:mm`. Los dos aceptan `HH:mm` o `HH:mm:ss`" (ejemplo: `08:30:00` en catálogo, `08:30` en turnos), en las tres copias (`documentacion`, `turnos_service/docs` y `catalog_service/docs`), que quedaron idénticas.
- **D7 · Errores de formato en el cuerpo JSON (RF-02, opción b):** si el JSON es válido pero un campo no se puede convertir, `400` `VALIDATION_ERROR` con ese campo en `fieldErrors` y el mensaje `invalid value`, como los parámetros de T03; un JSON mal escrito sigue sin `fieldErrors`. El cambio está en el manejo global de errores, así que vale también para T01 y T02, sin cambiar sus otras respuestas. Motivo: coherencia con T03 y respuestas más fáciles de diagnosticar; para la app no cambia nada (A06 3.4). Alternativas descartadas: (a) dejarlo como está; (c) recibir los campos como texto y validarlos con expresiones regulares (descartado en T03, P3).
- **D8 · Respuestas reales que se graban (respuestas de la cátedra para la suite simulada):** se graban, limpias como indica `src/test/resources/catedra/README.md`: (1) crear un hold del profesional 201 el próximo lunes a las 12:00, con un `externalPatientId` de prueba (`201`, y la duración real del hold, para revisar D4); (2) el mismo hold otra vez (`409` `SLOT_ALREADY_HELD`); (3) un hold a las 12:15 (`400` `INVALID_SLOT`); (4) un hold al profesional 999999 (`404` `PROFESSIONAL_NOT_FOUND`); (5) un hold con un token inválido (`401`); (6) el snapshot, solo para buscar un profesional deshabilitado, y un hold a él si existe (`400` `PROFESSIONAL_DISABLED`); (8) la cancelación del hold de (1), para dejar la cátedra como estaba, sin versionarla. Solo (1) y (8) modifican datos. Las llamadas se hacen en una tarea de TASKS y cada request se confirma con el usuario antes de enviarlo; si una llamada de más ocurre por error, se informa. Además (7), mientras el hold de (1) está activo, las ocupaciones de ese lunes (`GET`, no modifica datos): es la primera lista real con un `HELD` y su `expiresAt`, que T03 no pudo grabar. Se versiona y el test de T03 que hoy usa una lista armada a mano pasa a usarla; si el formato real difiere del que supone T03, se informa como defecto de T03 antes de corregir nada. Lo que no se puede provocar se arma a mano: `SLOT_ALREADY_RESERVED`, `403` `STUDENT_INTEGRATION_NOT_FOUND`, `500`, `503` y los timeouts.
- **D9 · El E2E deshace el hold que crea (CA-20, opción a):** al terminar, el test cancela el hold con una llamada directa a la cátedra (`POST /api/appointments/{reservationProcessId}/cancel`), con el JWT técnico de la sesión; es código solo de test. El turno se elige entre los libres de T03. Si la cancelación falla, se registra y el hold vence solo. **Cuando exista T11, el E2E pasa a cancelar con T11** (indicación del usuario), y así prueba también la cancelación de punta a punta. Alternativas descartadas: (b) dejar que venza solo, que hace fallar una segunda corrida antes del vencimiento; (c) postergar el E2E hasta T11.
- **D10 · Un turno que empieza justo en el momento actual (RF-02, opción a):** cuenta como anterior y responde `400` `VALIDATION_ERROR`, la misma regla que T03, que ya no lo ofrece como libre (P9 del PLAN de T03). Alternativa descartada: (b) aceptarlo hasta que pase su hora de inicio.
- **Para el PLAN (2026-10-26):** la regla que dice si un horario genera un turno (inicio del horario más un múltiplo de la duración, terminando en o antes del cierre) es una copia propia del slice del proceso de reserva, como el resto de los datos que cada slice toma de catálogo (decisión 009). No se comparte con `availability` ni se crea un paquete `shared`. Motivo: T04 no puede reutilizar el resultado de T03 (el backend no guarda qué turnos vio cada usuario, el catálogo puede cambiar entre las dos consultas y el enunciado §3 y §4.2 piden consultar la información vigente sin guardar una réplica), y la pregunta de T04 (¿este turno pertenece a un horario habilitado?) es más chica que la de T03. El costo, la fórmula escrita en dos slices, lo cubren los tests de cada uno.
- **Limitación conocida (fuera de T04):** el token es sin estado (contrato común §1.6). Si se reinicia la base sin cambiar el par de claves, un token emitido antes con `sub=5` vale para el nuevo usuario 5 hasta su `exp`. Se evita regenerando las claves al reiniciar la base.

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el alcance está acordado, las referencias apuntan a pasos y flujos
que existen, los flujos son coherentes, los puntos de backend relevantes están
cubiertos y cada requisito tiene criterios comprobables.
Resuelve las dudas y los marcadores pendientes. Mantén el diseño técnico en PLAN.md.
-->
