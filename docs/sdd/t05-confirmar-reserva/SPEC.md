# SPEC: T05 — Confirmar reserva (confirmación inicial)

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
| Caso de uso T05 (`docs/casos_de_uso/turnos_y_reservas_usecases.md`) | Precondición, pasos 1 y 3 a 7, alternativos 2.2, 3.1, 3.2, 4.1 a 4.9 y 6.1, y postcondición. El paso 2 (validar el JWT) y el alternativo 2.1 ya los resuelve la validación del JWT (`docs/sdd/validacion-jwt/`). Los pasos 5 y 6 y la postcondición se corrigieron según D2: confirmar no extiende el vencimiento. Los alternativos 4.3 y 4.4 se corrigieron según D8 |
| Diagrama de secuencia (`docs/secuencias/turnos_y_reservas_secuencia.md`) | §3, bloque "T05 — Confirm reservation (initial confirmation)" (corregido según D2 y D8) |
| Contrato de turnos (`docs/contratos/turnos_y_reservas_contrato.md`) | §1 (fila T05), §2 (proceso de reserva), §3 (reglas comunes), §3.5 completo, §4.2.1 (reglas comunes de la cátedra), §4.2.5 (confirmar un hold) y §4.2.7 (cancelar; solo como contexto de D6). §3.5 y §4.2.5 se corrigieron según D2 y D8 |
| Contrato común (`docs/contratos/contratos_comunes.md`) | §1.4 (identidad desde `sub`), §2 (formato de errores) y §3 (fechas, horas e instantes) |
| Modelo de dominio de turnos (`docs/turnos_y_reservas_dominio.md`) | `User` (`firstName`, `lastName`, `externalPatientId()`), `ReservationProcess` (incluido `needsReconciliation`) y `ReservationStatus`; §2 (transiciones `HELD → WAITING_FOR_PHONE` y cierre por vencimiento en T05); reglas 2, 6, 7, 8, 9, 14 y 15. El atributo `expiresAt` se corrigió según D2 |
| SPEC de T04 (`docs/sdd/t04-bloquear-turno/`) | D1 (reintentos de una operación que crea algo), D4 (margen de seguridad), D5 (usuario del `sub` inexistente) y D9 (la cátedra no cancela un proceso en `HELD`), como antecedentes |
| X01, su SPEC (`docs/sdd/x01-cuenta-tecnica/`) y decisiones 005 y 011 | Sesión técnica, login nuevo tras un `401` y reglas comunes de cada llamada a la cátedra |
| Anexo de la cátedra (`enunciado/INTEGRATION_REFERENCE-v2.md`) | §10 (confirmar inicialmente un hold), §13 y §13.1 (errores), §15.4 (`AdditionalInformationRequested`, solo como contexto) y §18.3 y §18.4 (hold vencido; un timeout no prueba que una operación haya fallado) |
| Enunciado (`enunciado/PROJECT_STATEMENT-v1.md`) | §1 (la confirmación combina REST y Kafka) y §7 (hold de duración limitada) |

## Qué construimos y para quién

<!-- Qué necesidad cubre, qué actor la inicia (la app KMP, otro servicio, el
tiempo o la cátedra) y qué podrá hacer o recibir. Describe el objetivo en
lenguaje de negocio. Resume y referencia: no copies la descripción del caso de uso. -->

Un usuario que bloqueó un turno en T04 confirma que lo quiere. Turnos confirma el hold ante la cátedra con el nombre y apellido del perfil del usuario como datos del paciente, y el proceso pasa a esperar el teléfono (`WAITING_FOR_PHONE`), que el usuario informará en T06 antes de que venza. Es el primer paso de la confirmación: la reserva queda confirmada recién cuando termina el intercambio por Kafka (T07 y T08).

## Situación actual

<!-- Qué existe hoy y es relevante (código, datos, otras funcionalidades ya
implementadas), la limitación que queremos resolver y los comportamientos
existentes que deben conservarse. Si todavía no hay código para esto, indícalo.
No describas la arquitectura. -->

- Están implementados T01, T02, X01, la validación del JWT, T03 y T04. `POST /api/reservation-processes/{reservationProcessId}/confirm` todavía no existe.
- T04 guarda el proceso en `HELD` con su `holdId`, `reservationProcessId`, dueño y vencimiento, y cierra como `EXPIRED` los procesos vencidos del usuario al bloquear otro turno. Todavía no se guarda la marca de reconciliación.
- Ya existen la sesión técnica y las reglas comunes de cada llamada a la cátedra (login a pedido, `401` con una repetición, reintentos según si el pedido pudo haberse procesado), que T05 reutiliza.
- La cátedra real no cancela un proceso en `HELD` (`404` `RESERVATION_NOT_FOUND`, comprobado en T04) ni en `WAITING_FOR_PHONE` (`409` `RESERVATION_NOT_CANCELLABLE`, "Reservation is not confirmed", comprobado el 2026-10-28 al verificar D2), contra la nota del contrato §4.2.7; el profesor todavía no respondió la consulta. Importa para limpiar lo que creen las pruebas contra la cátedra real y para T11.
- Confirmar no reinicia el vencimiento: la cátedra devuelve el mismo `expiresAt` del hold (verificado para D2). El usuario tiene, para confirmar e informar el teléfono, lo que le quede de los 10 minutos del hold.
- Debe conservarse el comportamiento de T01, T02, X01, la validación del JWT, T03 y T04.

## Dentro del alcance

<!-- Requisitos concretos, con identificadores estables para vincularlos a
criterios, decisiones del plan y tareas. Cada requisito indica el paso o flujo
alternativo del que proviene. No agregues requisitos que los documentos de
referencia no tengan sin consultarlo. -->

**Entrada y proceso**

- **RF-01** · T05 paso 1, contrato §1 y §3.5: `POST /api/reservation-processes/{reservationProcessId}/confirm`, protegido (cualquier rol), sin body. El dueño es el `sub` del JWT; la identidad nunca sale de la ruta ni de un body.
- **RF-02** · T05 paso 2 y alternativo 2.2, contrato §3.5: si el proceso no existe o es de otro usuario, responde `404` `RESERVATION_PROCESS_NOT_FOUND`, con la misma respuesta en los dos casos y sin llamar a la cátedra. Un token cuyo `sub` ya no corresponde a ningún usuario recibe la misma respuesta: ese usuario no tiene procesos (D7).
- **RF-03** · T05 alternativo 3.1, contrato §3.5: si el proceso no está en `HELD`, responde `409` `INVALID_PROCESS_STATE` con `currentStatus`, sin llamar a la cátedra ni cambiar el proceso.
- **RF-04** · T05 alternativo 3.2, dominio reglas 7 y 8, D4: si el proceso está en `HELD` y el instante actual supera su `expiresAt` más el margen de seguridad, queda `EXPIRED` y responde `409` `HOLD_EXPIRED`, sin llamar a la cátedra. El margen es el mismo de T04: 30 segundos por defecto, configurable con la misma variable de entorno (D4 de T04). Dentro del margen se llama a la cátedra, que decide (RF-10).
- **RF-05** · T05 paso 3, contrato §3.5 y §4.2.5: `patientFirstName` y `patientLastName` son el `firstName` y el `lastName` actuales del perfil del usuario.

**Cátedra (confirmar el hold)**

- **RF-06** · T05 paso 4, contrato §4.2.1 y §4.2.5, anexo §10: pide la confirmación del `holdId` del proceso con `externalPatientId` igual al `sub` del usuario como texto (dominio regla 2) y el nombre y apellido de RF-05, con el JWT técnico vigente de la sesión de X01.
- **RF-07** · T05 alternativo 4.1: si la cátedra responde `401`, pide a X01 un login nuevo y repite el pedido una sola vez; si no se consigue sesión o vuelve a recibir `401`, responde `503` `CENTRAL_UNAVAILABLE` (como RF-14 de T04).
- **RF-08** · T05 alternativo 4.2: `403` `STUDENT_INTEGRATION_NOT_FOUND`, o la integración en error de configuración al momento del request, se registra y responde `503` `SERVICE_DEGRADED` sin detalles. Degradada y sin sesión posible, `503` `CENTRAL_UNAVAILABLE` (como RF-15 de T04).
- **RF-09** · T05 alternativo 4.3, D8: `404` `HOLD_NOT_FOUND` con el `expiresAt` local todavía sin pasar se registra y responde `404` `HOLD_NOT_FOUND`; el proceso no cambia. Si el `expiresAt` local ya pasó, es un hold vencido y se sigue RF-10.
- **RF-10** · T05 alternativo 4.4, anexo §18.3, D8: `404` `HOLD_EXPIRED`, o `404` `HOLD_NOT_FOUND` con el `expiresAt` local ya pasado, deja el proceso `EXPIRED` y responde `409` `HOLD_EXPIRED`.
- **RF-11** · T05 alternativo 4.5: `403` `HOLD_OWNER_MISMATCH` se registra como error grave y responde `500` `INTERNAL_ERROR`; el proceso no cambia.
- **RF-12** · T05 alternativo 4.6: `400` `EXTERNAL_PATIENT_MISMATCH` se registra y responde `409` `PATIENT_MISMATCH`; el proceso no cambia.
- **RF-13** · T05 alternativo 4.7, D2: `409` `RESERVATION_PROCESS_ALREADY_CONFIRMED` no es un error: el proceso local pasa de `HELD` a `WAITING_FOR_PHONE` conservando su `expiresAt` y responde `200` con el proceso.
- **RF-14** · T05 alternativo 4.8: `404` `PROFESSIONAL_NOT_FOUND`, `400` `INVALID_SLOT` y `409` `SLOT_ALREADY_RESERVED` se registran y responden `404` `PROFESSIONAL_NOT_FOUND`, `400` `INVALID_SLOT` y `409` `SLOT_NOT_AVAILABLE`; el proceso no cambia.
- **RF-15** · T05 alternativo 4.9, contrato §4.2.5, anexo §18.4, D1: ante conexión rechazada, timeout de conexión o de respuesta, corte de la conexión, `503` o `500`, se reintenta con espera creciente y un máximo de intentos, con los valores ya configurables de la cátedra. Repetir es seguro aunque el intento anterior se haya procesado: la cátedra responde `409` `RESERVATION_PROCESS_ALREADY_CONFIRMED` y se trata como éxito (RF-13). Agotados los intentos, responde `503` `CENTRAL_UNAVAILABLE` y el proceso sigue en `HELD`, marcado para reconciliar (D3).
- **RF-16** · Contrato §3: cualquier otra respuesta de la cátedra se registra y responde `500` `INTERNAL_ERROR`; el proceso no cambia.

**Proceso confirmado**

- **RF-17** · T05 pasos 5 a 7, contrato §2 y §3.5: con `202`, el proceso pasa a `WAITING_FOR_PHONE` con el `expiresAt` que informa la cátedra (el mismo del hold, D2), y responde `200` con el proceso de reserva (`startTime` en `HH:mm`; `rejectionReason`, `reservationId`, `confirmedAt`, `cancelledAt` y `cancellationReason` en `null`). Nunca expone `holdId` ni la marca de reconciliación.
- **RF-18** · T05 alternativo 6.1, contrato §3.5: si la cátedra confirmó pero falla la persistencia local, registra un error crítico que permita identificar el proceso y responde `500` `LOCAL_STATE_OUT_OF_SYNC`. Intenta además marcar el proceso para reconciliar, aparte del cambio que falló; si tampoco se puede, el error crítico del log es lo único que queda (D3).
- **RF-19** · Contrato §3: si falla la persistencia al cerrar un proceso vencido (RF-04, RF-10), responde `500` `INTERNAL_ERROR`.
- **RF-22** · Dominio `needsReconciliation` y reglas 8 y 9, contrato §2, D3: cada proceso guarda la marca de reconciliación, falsa por defecto (también en los procesos que ya existen). T05 solo la enciende (RF-15, RF-18); nunca la limpia, aunque un reintento posterior avance el proceso: eso lo hará T13. Nunca se expone a la app.

**Transversales**

- **RF-20** · Anexo §18.4, guía de backend (privacidad): cada reintento queda registrado con su número y su motivo; ni el JWT del usuario ni el técnico aparecen en logs ni en respuestas.
- **RF-21** · Guía de backend (concurrencia), dominio reglas 5 y 6, D5: las operaciones de un mismo usuario sobre sus procesos (bloquear con T04 y confirmar con T05) se atienden de a una. Un segundo pedido de confirmación simultáneo espera, encuentra el proceso ya en `WAITING_FOR_PHONE` y recibe `409` `INVALID_PROCESS_STATE` (RF-03), sin llamar a la cátedra. Un bloqueo simultáneo no puede cerrar el proceso mientras se confirma. Los pedidos de usuarios distintos no se esperan entre sí.

## Fuera de alcance

<!-- Exclusiones acordadas, no deducidas por el agente. Incluye las partes de los
casos de uso que se dejan para otra funcionalidad. Si no hay exclusiones
adicionales, indícalo tras revisarlo con la persona. -->

Confirmadas por el usuario (2026-10-28):

- La validación del JWT del usuario (paso 2 y alternativo 2.1): ya está hecha.
- El pedido de teléfono por Kafka (T07) y todo lo que sigue (T06, T08 a T11). Si T07 llega antes, el proceso avanza solo; eso se implementa en T07.
- La reconciliación (T13), que es la que lee y limpia la marca, y el barrido periódico de vencidos (T12).
- Cancelar desde la app (T11). La suite E2E intenta cancelar lo que crea solo como limpieza del test (D6).

## Flujo de la operación

<!-- Cómo se inicia (request, evento o tarea programada), qué verifica y qué
resultado produce, incluidas las alternativas relevantes. Si el caso de uso ya
lo describe, referencia sus pasos en lugar de repetirlos y describe solo lo que
cubre esta funcionalidad. -->

1. La app pide confirmar el proceso con el JWT del usuario (T05 paso 1). La cadena de seguridad valida el token antes de todo.
2. El servicio sigue los pasos 2 a 7 de T05 y el diagrama de secuencia §3 (bloque T05), con sus alternativos.
3. Lo que agrega esta spec: se reintenta también cuando la respuesta pudo perderse, porque un intento ya procesado vuelve como "ya confirmado" (RF-15, D1); "ya confirmado" pasa el proceso a `WAITING_FOR_PHONE` con su mismo vencimiento (RF-13, D2); el proceso queda marcado para reconciliar en 4.9 y 6.1 (RF-15, RF-18, RF-22, D3); el vencimiento local usa el margen de T04 (RF-04, D4); las operaciones de un mismo usuario se atienden de a una (RF-21, D5), y el token de un usuario que ya no existe recibe `404` (RF-02, D7).

## Datos y reglas de negocio

<!-- Información que necesita la operación, campos obligatorios, validaciones,
límites y reglas como duplicados u orden de presentación. Describe significado
y comportamiento, sin diseñar tablas, DTO, DAO ni almacenamiento. -->

- `reservationProcessId` es un texto opaco que asigna la cátedra (dominio regla 4); la ruta lo recibe tal cual, sin formato asumido.
- Del perfil del usuario se usan `firstName` y `lastName` (de 2 a 50 caracteres por T01, dentro de lo que acepta la cátedra: 2 a 100).
- `expiresAt` es un instante en UTC que fija la cátedra; guardarlo no extiende la vigencia (anexo §9 y §10).
- Un proceso cerrado (`EXPIRED`) nunca se reabre (dominio regla 6, anexo §18.3).
- La marca de reconciliación nunca se expone a la app (contrato §2).

## Casos alternativos y de error

<!-- Adapta la tabla usando BACKEND_GUIDELINES.md. Cuando el caso de uso ya define
el flujo alternativo, referencia su ID en lugar de repetirlo. Añade escenarios
relevantes. Marca No aplica con su motivo cuando corresponda. Expresa resultados
observables (estado HTTP, código de error, estado final del recurso, evento
publicado), no mecanismos técnicos. -->

| Situación | Comportamiento esperado |
| --- | --- |
| Entrada inválida | No hay body ni parámetros que validar: un `reservationProcessId` que no corresponde a ningún proceso del usuario es RF-02 |
| Sin sesión o sin permiso | Resuelto por la validación del JWT: `401` o `403` antes de llegar a T05 |
| Recurso inexistente o ajeno | T05 2.2: `404` `RESERVATION_PROCESS_NOT_FOUND`, sin distinguir, también para el token de un usuario que ya no existe (RF-02, D7). Hold inexistente según la cátedra: `404` `HOLD_NOT_FOUND` (RF-09) |
| Proceso en otro estado | T05 3.1: `409` `INVALID_PROCESS_STATE` con `currentStatus` (RF-03) |
| Hold vencido | Localmente, T05 3.2; según la cátedra, T05 4.4, que en la práctica responde `HOLD_NOT_FOUND` (4.3): `EXPIRED` y `409` `HOLD_EXPIRED` (RF-04, RF-10, D8) |
| Cátedra rechaza el JWT técnico | T05 4.1: login nuevo y una sola repetición (RF-07) |
| Integración en error de configuración o degradada | T05 4.2: `503` `SERVICE_DEGRADED`; degradada sin sesión, `503` `CENTRAL_UNAVAILABLE` (RF-08) |
| Dependencia no disponible | T05 4.9: reintentos y `503` `CENTRAL_UNAVAILABLE`; el proceso sigue en `HELD`, marcado para reconciliar (RF-15, D1, D3) |
| Timeout o espera excesiva | Se reintenta también si la respuesta se perdió: un intento ya procesado vuelve como "ya confirmado" (RF-15, D1) |
| Operación o mensaje repetido | Un reintento propio o de la app sobre un proceso ya confirmado en la cátedra recibe `409` `RESERVATION_PROCESS_ALREADY_CONFIRMED`: el proceso pasa a `WAITING_FOR_PHONE` y responde `200` (RF-13, D2). Un reintento de la app sobre un proceso que turnos ya registró en `WAITING_FOR_PHONE` recibe `409` `INVALID_PROCESS_STATE` (RF-03) |
| Evento tardío o fuera de orden | T05 no consume eventos. Si T07 avanzó el proceso antes, T05 responde `409` `INVALID_PROCESS_STATE` (RF-03) |
| Falla de persistencia | T05 6.1: `500` `LOCAL_STATE_OUT_OF_SYNC`, error crítico en el log e intento de marcar el proceso para reconciliar (RF-18, D3). Al cerrar un vencido, `500` `INTERNAL_ERROR` (RF-19) |
| Solicitudes simultáneas | Del mismo usuario (doble toque, o confirmar mientras bloquea otro turno): se atienden de a una; el segundo pedido de confirmación recibe `409` `INVALID_PROCESS_STATE` sin llamar a la cátedra, y un proceso nunca se cierra mientras se confirma (RF-21, D5). De usuarios distintos: no se esperan |
| Reinicio con operaciones pendientes | El proceso guardado se conserva. Un request cortado por el reinicio puede dejar el proceso en `HELD` aunque la cátedra lo haya confirmado: lo avanza T07 o lo corrige T13 |

**Puntos de la guía no aplicables y motivo:** mensajería y tareas programadas: T05 no consume ni publica mensajes (el pedido de teléfono es T07) y el barrido periódico es T12. Paginación: T05 actúa sobre un solo proceso.

## Restricciones del pedido

<!-- Condiciones ya impuestas: compatibilidad, límites de alcance, requisitos
de seguridad o rendimiento medibles, o una tecnología expresamente exigida.
Ejemplo: Respetar el contrato de Documentacion y el anexo de la cátedra puede ser
una restricción; el diseño de los adaptadores va en PLAN.md.
No conviertas una preferencia del agente en una restricción. -->

- Respetar el contrato de turnos §2, §3.5 y §4.2.5 y el contrato común §2 y §3, con los cambios que se aprueben en esta spec.
- El JWT técnico nunca sale hacia la app.
- La suite normal de tests no llama a la cátedra real: la simula, con respuestas reales grabadas y limpiadas. Antes de cualquier llamada a la cátedra real se confirma con el usuario qué requests se harán, porque confirmar un hold modifica datos (decisión 010).
- Los controllers obtienen la identidad con la convención de la decisión 008.
- Aplicar la skill `hexagonal-vertical-slicing` con las excepciones autorizadas. Lo existente se amplía en lugar de duplicarse, y las llamadas a la cátedra usan la pieza común de la decisión 011.
- No cambiar el comportamiento de T01, T02, X01, la validación del JWT, T03 ni T04, salvo que un bloqueo (T04) espera a que termine una confirmación en curso del mismo usuario (D5).

## Criterios de aceptación

<!-- Resultados observables que permitan decidir si se cumple cada requisito.
Incluye los casos alternativos acordados: cada flujo alternativo cubierto debería
tener un criterio. No uses Funciona correctamente.
Repite el formato según sea necesario. -->

- **CA-01 · RF-01, RF-02:** Cuando el usuario confirma un `reservationProcessId` que no existe, uno de otro usuario, o con un token válido cuyo `sub` ya no corresponde a ningún usuario, entonces responde `404` `RESERVATION_PROCESS_NOT_FOUND` con la misma respuesta en los tres casos, y la cátedra no recibe requests. Sin token, responde `401`.
- **CA-02 · RF-03:** Dado un proceso del usuario en `WAITING_FOR_PHONE`, `PHONE_SUBMITTED` o `EXPIRED`, cuando lo confirma, entonces responde `409` `INVALID_PROCESS_STATE` con `currentStatus` igual a ese estado, el proceso no cambia y la cátedra no recibe requests.
- **CA-03 · RF-04:** Dado un proceso en `HELD` con el margen de 30 segundos, cuando se confirma 31 segundos después de su `expiresAt`, entonces queda `EXPIRED`, responde `409` `HOLD_EXPIRED` y la cátedra no recibe requests; 29 segundos después, sí se llama a la cátedra. Con otro margen configurado, se usa ese.
- **CA-04 · RF-05, RF-06, RF-17:** Dado un proceso en `HELD` vigente, cuando el usuario lo confirma, entonces la cátedra recibe `POST /api/appointment-holds/{holdId}/confirm` con `externalPatientId` igual al `sub`, `patientFirstName` y `patientLastName` iguales al nombre y apellido actuales del perfil y `Authorization: Bearer <JWT técnico>`; con el `202` grabado, el proceso queda `WAITING_FOR_PHONE` con el `expiresAt` recibido y responde `200` con exactamente los campos del proceso de reserva (`startTime` `HH:mm`, los de reserva, confirmación y cancelación en `null`), sin `holdId` ni la marca de reconciliación.
- **CA-05 · RF-07:** Cuando la cátedra responde `401` y X01 consigue un JWT nuevo, entonces el pedido se repite una sola vez con el JWT nuevo; si vuelve a responder `401`, no se repite de nuevo y responde `503` `CENTRAL_UNAVAILABLE`.
- **CA-06 · RF-08:** Cuando la cátedra responde `403` `STUDENT_INTEGRATION_NOT_FOUND`, o la integración está en error de configuración, entonces responde `503` `SERVICE_DEGRADED` sin detalles; degradada y sin sesión posible, `503` `CENTRAL_UNAVAILABLE`.
- **CA-07 · RF-09, RF-11, RF-12, RF-14, RF-16:** Cuando la cátedra responde `404` `HOLD_NOT_FOUND`, `403` `HOLD_OWNER_MISMATCH`, `400` `EXTERNAL_PATIENT_MISMATCH`, `404` `PROFESSIONAL_NOT_FOUND`, `400` `INVALID_SLOT`, `409` `SLOT_ALREADY_RESERVED` o una respuesta no prevista, entonces turnos responde `404` `HOLD_NOT_FOUND`, `500` `INTERNAL_ERROR` (con un error grave en el log), `409` `PATIENT_MISMATCH`, `404` `PROFESSIONAL_NOT_FOUND`, `400` `INVALID_SLOT`, `409` `SLOT_NOT_AVAILABLE` o `500` `INTERNAL_ERROR`, respectivamente; el proceso sigue en `HELD` sin marcar y el error queda registrado. El `HOLD_NOT_FOUND` vale para un proceso cuyo `expiresAt` todavía no pasó.
- **CA-08 · RF-09, RF-10:** Cuando la cátedra responde `404` `HOLD_EXPIRED`, o `404` `HOLD_NOT_FOUND` para un proceso cuyo `expiresAt` ya pasó (dentro del margen), entonces el proceso queda `EXPIRED` y responde `409` `HOLD_EXPIRED`.
- **CA-09 · RF-13:** Dado un proceso en `HELD`, cuando la cátedra responde `409` `RESERVATION_PROCESS_ALREADY_CONFIRMED`, entonces el proceso queda `WAITING_FOR_PHONE` con el mismo `expiresAt` que tenía y responde `200` con el proceso.
- **CA-10 · RF-15, RF-20:** Cuando la cátedra rechaza la conexión, demora más que el timeout de conexión o de respuesta, corta la conexión o responde `503` o `500` en todos los intentos, entonces hay tantos intentos como el máximo configurado, con esperas crecientes, cada uno registrado con su número y su motivo; responde `503` `CENTRAL_UNAVAILABLE` y el proceso queda en `HELD` y marcado para reconciliar. Cuando un intento falla así y el siguiente recibe `202` o `409` `RESERVATION_PROCESS_ALREADY_CONFIRMED`, entonces responde `200` con el proceso en `WAITING_FOR_PHONE`.
- **CA-11 · RF-18:** Dado que la cátedra respondió `202`, cuando falla guardar el proceso en `WAITING_FOR_PHONE`, entonces responde `500` `LOCAL_STATE_OUT_OF_SYNC`, el log tiene un error crítico con el `reservationProcessId`, y el proceso queda marcado para reconciliar si la base lo permite; si tampoco lo permite, responde lo mismo y solo queda el log.
- **CA-12 · RF-19:** Cuando falla guardar el cierre de un proceso vencido (RF-04 o RF-10), entonces responde `500` `INTERNAL_ERROR`.
- **CA-13 · RF-20:** En todos los escenarios, la salida no contiene el JWT del usuario ni el técnico.
- **CA-14 · RF-21:** Dados dos pedidos simultáneos de confirmación del mismo proceso, con la cátedra simulada demorando la respuesta, entonces uno responde `200` y el otro `409` `INVALID_PROCESS_STATE` con `currentStatus` `WAITING_FOR_PHONE`, y la cátedra recibe una sola confirmación. Dado un bloqueo (T04) del mismo usuario mientras se confirma un proceso que vence durante la espera, entonces el proceso nunca pasa de `EXPIRED` a otro estado. Los pedidos de usuarios distintos no se esperan entre sí.
- **CA-15 · RF-22:** Los procesos que ya existían quedan sin marcar. Un proceso marcado que después se confirma con éxito queda `WAITING_FOR_PHONE` y sigue marcado. La marca no aparece en ninguna respuesta.
- **CA-16 · RF-01 a RF-19:** Con la aplicación completa, base real y la cátedra simulada, un usuario que inició sesión bloquea un turno (T04) y lo confirma: recibe `200` con `WAITING_FOR_PHONE`; un segundo pedido de confirmación recibe `409` `INVALID_PROCESS_STATE`.
- **CA-17 · RF-05, RF-06, RF-17:** Con la cátedra real (suite E2E) y catálogo simulado, un usuario registrado bloquea un turno libre y lo confirma: recibe `200` con `WAITING_FOR_PHONE` y el mismo `expiresAt` del bloqueo; al terminar el test intenta cancelar y, si la cátedra no lo permite, lo informa sin fallar. Sin credenciales, el test se saltea.

## Cómo se comprueba el comportamiento

<!-- Una fila por criterio: escenario y resultado que debemos comprobar.
La selección de tests, herramientas, comandos y evidencias se desarrolla en PLAN.md.
No marques los criterios como superados durante la especificación. -->

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | Proceso inexistente, proceso de otro usuario, token de un `sub` sin usuario y sin token | `404` idéntico en los tres primeros; `401` sin token; cátedra sin requests |
| CA-02 | Procesos en `WAITING_FOR_PHONE`, `PHONE_SUBMITTED` y `EXPIRED` | `409` con `currentStatus`; proceso igual; cátedra sin requests |
| CA-03 | Proceso `HELD` vencido hace 29 s y hace 31 s, con un reloj controlado; margen por defecto y configurado | 31 s: `EXPIRED` y `409` sin cátedra; 29 s: llama a la cátedra |
| CA-04 | Cátedra simulada con el `202` grabado, que guarda los requests; perfil con nombre y apellido conocidos | Request exacto; proceso `WAITING_FOR_PHONE` en la base; respuesta `200` con los campos exactos |
| CA-05 | Cátedra con `401` y después `202`; con `401` dos veces | Un login y una repetición; nunca una segunda |
| CA-06 | Cátedra con `403` `STUDENT_INTEGRATION_NOT_FOUND`; sesión en error de configuración; degradada sin login posible | `503` `SERVICE_DEGRADED` o `CENTRAL_UNAVAILABLE` |
| CA-07 | Cátedra con cada error, con las respuestas grabadas donde existan | Código de turnos correspondiente; proceso en `HELD` sin marcar; error en el log |
| CA-08 | Cátedra con `404` `HOLD_EXPIRED` (armado a mano) y con el `404` `HOLD_NOT_FOUND` grabado de un hold vencido, para un proceso vencido hace 10 s; el mismo `HOLD_NOT_FOUND` para uno vigente | Vencido: proceso `EXPIRED` y `409` `HOLD_EXPIRED`; vigente: `404` `HOLD_NOT_FOUND` y proceso igual |
| CA-09 | Cátedra con el `409` grabado de "ya confirmado" | Proceso `WAITING_FOR_PHONE` con el mismo `expiresAt`; `200` |
| CA-10 | Cátedra apagada, con demora de conexión y de respuesta, con `503` y con `500`; luego un fallo y después `202` o "ya confirmado" | Intentos según la configuración, registrados; `503` y proceso `HELD` marcado; o `200` |
| CA-11 | Persistencia que falla al guardar `WAITING_FOR_PHONE` después de un `202`; y que falla también al marcar | `500` `LOCAL_STATE_OUT_OF_SYNC`; error crítico en el log; proceso marcado en el primer caso |
| CA-12 | Persistencia que falla al cerrar un vencido | `500` `INTERNAL_ERROR` |
| CA-13 | Revisar la salida de los escenarios con reintentos y errores | Sin tokens |
| CA-14 | Dos confirmaciones a la vez con la cátedra demorando; un bloqueo del mismo usuario durante una confirmación que vence; dos usuarios a la vez | Un `200` y un `409`, una sola confirmación en la cátedra; ningún estado final reabierto; usuarios distintos sin esperas |
| CA-15 | Migración sobre una base con procesos; un proceso marcado confirmado después; las respuestas | Marca falsa en los existentes; el marcado sigue marcado; la marca nunca se expone |
| CA-16 | Registrar, iniciar sesión, bloquear y confirmar dos veces con la aplicación completa | `200` y después `409` `INVALID_PROCESS_STATE` |
| CA-17 | Suite E2E: `./mvnw test -Pe2e` con y sin credenciales | `200` real con `WAITING_FOR_PHONE` y el mismo vencimiento; aviso si no se puede cancelar; sin credenciales, salteado |

## Decisiones pendientes

<!-- Al resolverlas, actualiza las secciones afectadas. Escribe Ninguna cuando
no queden pendientes funcionales ni restricciones por decidir. -->

Ninguna.

Decisiones tomadas (2026-10-28):

- **D1 · Reintentos ante una respuesta incierta (RF-15, opción a):** se reintenta como dice el contrato (§4.2.5, T05 4.9), también cuando la respuesta pudo perderse (timeout de respuesta, corte, `500`). A diferencia del hold de T04 (D1 de T04), repetir una confirmación ya procesada no le hace perder nada al usuario: la cátedra la detecta y responde "ya confirmado", que T05 trata como éxito (RF-13). La repetición cumple el pedido del anexo §18.4 de consultar el estado antes de repetir, porque la propia cátedra informa que ya estaba hecho. No cambia el contrato. Alternativas descartadas: (b) reintentar solo lo que seguro no llegó, como T04: el usuario vería un error que se resuelve repitiendo, y había que cambiar el contrato; (c) consultar la lista de reservas antes de repetir: suma una operación nueva con la cátedra y esa lista no trae el vencimiento.
- **D2 · Respuesta "ya confirmado" (RF-13, opción a, verificada):** el proceso local pasa a `WAITING_FOR_PHONE` conservando su `expiresAt`, y responde `200`. Motivo: el vencimiento local debe ser el mismo que el de la cátedra; en los ejemplos del anexo (§9, §10 y §15.4) el hold, la confirmación y el pedido de teléfono traen el mismo `expiresAt`, lo que indica que confirmar no lo reinicia, y el evento de T07 trae el vencimiento real. **Verificado con la cátedra real (2026-10-28, script corrido por el usuario con los 6 pedidos que confirmó):** el hold del profesional 201 del lunes 2026-11-02 a las 12:00 respondió `201` con `expiresAt` `2026-10-28T05:04:54.812004682Z` (10 minutos); la confirmación respondió `202` `WAITING_FOR_PHONE` con **exactamente el mismo `expiresAt`**: confirmar no reinicia el vencimiento. La confirmación repetida respondió `409` `RESERVATION_PROCESS_ALREADY_CONFIRMED` sin estado ni vencimiento (solo los campos de error). El usuario prefirió verificarlo antes de seguir, en lugar de suponerlo y corregir los documentos después. **Documentos corregidos con su aprobación (2026-10-28)**, en `documentacion` y en las copias de turnos: caso de uso T05 (pasos 5 y 6 y postcondición), secuencia §3 (dos mensajes del bloque T05), dominio (atributo `expiresAt`) y contrato de turnos (§3.5, respuesta `200`, y §4.2.5, fila `202`) ya no dicen que confirmar trae un vencimiento nuevo. Además, el intento de cancelar el proceso en `WAITING_FOR_PHONE` respondió `409` `RESERVATION_NOT_CANCELLABLE`: igual que con `HELD` en T04, el usuario lo suma a su consulta al profesor y el contrato §4.2.7 no se cambia por ahora. Alternativas descartadas: (b) además marcarlo para reconciliar (depende de D3); (c) no tocar el proceso local y responder `HELD`: contradice el contrato y la app mostraría un estado viejo.
- **D3 · Marca de reconciliación (RF-15, RF-18, RF-22, opción a):** se guarda desde T05, aunque T13 todavía no exista: en 4.9 el proceso queda en `HELD` y marcado; en 6.1 se intenta marcarlo aparte del cambio que falló, y si la base tampoco lo permite queda el error crítico del log, que identifica el proceso. T05 nunca limpia la marca (dominio regla 9). Motivo: es lo que piden el caso de uso, el contrato y el dominio, y T13 encuentra los procesos marcados sin buscarlos por otro criterio. Alternativas descartadas: (b) dejar la marca para T13 y solo registrar en el log: había que cambiar el caso de uso y el contrato; (c) marcar solo en 4.9: en 6.1 se perdía la marca justo cuando la falla es pasajera.
- **D4 · Margen de seguridad (RF-04, opción a):** el mismo de T04 (30 s por defecto, misma variable de entorno), con la misma regla: vencido si el instante actual supera `expiresAt` más el margen. Motivo: una sola regla de vencimiento para todo el proceso de reserva; con dos, un proceso podría estar vencido para T04 y vigente para T05 a la vez. Alternativas descartadas: (b) una variable propia para T05: dos reglas que pueden contradecirse; (c) sin margen en T05: podría rechazar una confirmación que la cátedra aceptaría si los relojes difieren.
- **D5 · Pedidos simultáneos (RF-21, opción a):** las operaciones de un mismo usuario (bloquear y confirmar) se atienden de a una, con el mismo mecanismo que T04 (D3 de T04). Motivo: sin eso, un T04 del mismo usuario podría cerrar el proceso por vencido mientras T05 espera a la cátedra, y T05 reabriría un estado final (dominio regla 6) que además choca con el índice de un proceso abierto por usuario. En un doble toque, el segundo pedido recibe `409` `INVALID_PROCESS_STATE` sin llamar a la cátedra. Como en T04, no cubre más de una instancia del servicio. Alternativas descartadas: (b) sin esperas, comprobando al guardar que el proceso siga en `HELD`: dejaba la cátedra confirmada y el proceso local cerrado, con un error nuevo; (c) no proteger nada y aceptar el caso, aunque es improbable.
- **D6 · Respuestas reales que se graban y prueba E2E (opción a en las dos):** **Grabaciones:** se versionan, limpias como indica `src/test/resources/catedra/README.md`, el `202` de la confirmación y el `409` `RESERVATION_PROCESS_ALREADY_CONFIRMED` de la verificación de D2, y se graban con un script, previa confirmación de cada request: confirmar un `holdId` inventado (`404` `HOLD_NOT_FOUND`) y con un token inválido (`401`), sin efectos; y con un hold nuevo del profesional 201, confirmarlo con otro `externalPatientId` (`400` `EXTERNAL_PATIENT_MISMATCH`, no lo confirma) y, pasados sus 10 minutos, confirmarlo bien (`404` `HOLD_EXPIRED`). Ocupa un turno 10 minutos y no deja nada en la cátedra. `HOLD_OWNER_MISMATCH`, `500`, `503` y los timeouts no se pueden provocar y se arman a mano. **E2E:** se amplía el E2E de T04 con la confirmación, en un solo flujo (registro, login, bloqueo y confirmación); al terminar intenta cancelar y, si la cátedra no lo permite, avisa sin fallar (D9 de T04). Cada corrida deja una reserva `PHONE_PENDING` que vence sola a los 10 minutos. Alternativas descartadas: grabar solo lo que no tiene efectos y armar el resto a mano; un E2E aparte para T05 (dos holds por corrida); sin E2E.
- **D7 · Usuario del `sub` inexistente (RF-02, opción a):** `404` `RESERVATION_PROCESS_NOT_FOUND`, como un proceso ajeno. Motivo: por la clave foránea (decisión 012) ese usuario no tiene procesos, así que la respuesta sale sola, sin una consulta extra, y no da ninguna información. Es lo mismo que pasará naturalmente en T09 (`404`) y T10 (lista vacía). Alternativa descartada: (b) `401` como en T04: suma una consulta en cada confirmación, y el motivo de T04 (no crear un hold a nombre de nadie) acá no existe.
- **D8 · Un hold vencido responde `HOLD_NOT_FOUND` (RF-09, RF-10, opción a, 2026-10-29, cambio a la SPEC aprobada con OK del usuario):** en TASK-01, confirmar un hold 21 segundos después de su `expiresAt` respondió `404` `HOLD_NOT_FOUND`, no `HOLD_EXPIRED`. Si la cátedra responde `HOLD_NOT_FOUND` y el `expiresAt` local ya pasó, se trata como vencido: proceso `EXPIRED` y `409` `HOLD_EXPIRED`; si todavía no pasó, sigue siendo `404` `HOLD_NOT_FOUND`. `HOLD_EXPIRED` se sigue manejando por si llega. Se compara con `expiresAt` sin el margen: el margen sirve para no cerrar antes de tiempo un proceso que la cátedra todavía acepta, y acá la cátedra ya dijo que el hold no existe. Motivo: sin esto, quien confirma unos segundos después del vencimiento recibe "hold no encontrado" y el proceso sigue en `HELD`, así que un bloqueo nuevo dentro del margen recibe `OPEN_PROCESS_EXISTS`. Se corrigieron con su aprobación el caso de uso T05 (4.3 y 4.4), el contrato (§3.5, filas `HOLD_NOT_FOUND` y `HOLD_EXPIRED` de T05; §4.2.5, fila `HOLD_NOT_FOUND`) y la secuencia §3, en `documentacion` y en las copias de turnos. Alternativas descartadas: (b) seguir los documentos tal cual: mensaje equivocado y bloqueo rechazado durante unos segundos; (c) tratar todo `HOLD_NOT_FOUND` como vencido: escondería un hold que desaparece antes de vencer.
