# Respuestas reales de la cátedra

Grabadas con `curl` contra la cátedra real, con la cuenta técnica del proyecto: las de ocupaciones y login el 2026-10-24 (T03), las de holds y la lista con un `HELD` el 2026-10-27 (T04) las de confirmar un hold el 2026-10-28 y el 2026-10-29 (T05) y las del listado de reservas el 2026-11-05 (T13, con un programa en Python). Las usa el servidor simulado de la suite normal de tests, para que la simulación responda igual que la cátedra. La suite E2E no las usa: habla con la cátedra en vivo.

**Limpieza antes de versionar:** en `login-200.json`, el JWT técnico, la contraseña de Redis, los hosts de Redis y Kafka y el `groupId` (que también forma parte de los nombres de topics, namespaces y usuario de Redis) se reemplazaron por valores de prueba. Se verificó que ninguno de los valores reales, ni la URL de la cátedra ni las credenciales de la cuenta técnica, aparece en estos archivos ni en el resto del repositorio. Los encabezados de las respuestas no se guardan: el del `401` sin token incluye una dirección interna de la cátedra.

Los datos de profesionales y fechas son los ficticios de la cátedra.

| Archivo | Request | Estado y `Content-Type` | Observaciones |
| --- | --- | --- | --- |
| `login-200.json` | `POST /api/authenticate` con `username`, `password` y `rememberMe: true` | `200`, `application/json` | `id_token` e `integration` con los 13 campos, `provisioningStatus` `PROVISIONED` (valores limpiados) |
| `occupancies-200-empty.json` | `GET /api/appointment-occupancies?professionalId=201&from=2026-10-26&to=2026-10-26` | `200`, `application/json` | Sin ocupaciones. El día de la grabación no había ninguna reserva ni hold en la cátedra: diez consultas a profesionales y fechas distintas devolvieron la lista vacía |
| `occupancies-200-unknown-professional.json` | Igual, con `professionalId=999999` | `200`, `application/json` | Un profesional inexistente no da `404`: devuelve la lista vacía |
| `occupancies-400-invalid-date-range.json` | Igual, con `from=2026-10-27&to=2026-10-26` | `400`, `application/problem+json` | `code` `INVALID_DATE_RANGE`, con los campos propios del formato de errores de la cátedra (`type`, `message`, `path`) |
| `occupancies-401-invalid-token.json` | Igual, con `Authorization: Bearer token-invalido` | `401`, **sin `Content-Type`** | **Cuerpo vacío** (por eso el archivo está vacío). Trae `WWW-Authenticate: Bearer error="invalid_token"` |
| `occupancies-200-held.json` | `GET /api/appointment-occupancies?professionalId=201&from=2026-11-02&to=2026-11-02`, con el hold de `hold-201-created.json` activo | `200`, `application/json` | Una ocupación `HELD`: `startTime` `12:00:00` (con segundos), `date` y `expiresAt` con nanosegundos, igual al del hold |
| `hold-201-created.json` | `POST /api/appointment-holds` con `professionalId` 201, `date` 2026-11-02, `startTime` `12:00` y `externalPatientId` `999999` | `201`, `application/json` | `holdId` (41 caracteres) y `reservationProcessId` (44 caracteres) con forma de UUID y prefijo (`hold-…`, `process-…`), `status` `HELD` y `expiresAt` con nanosegundos, **10 minutos** después del pedido |
| `hold-409-slot-already-held.json` | El mismo pedido, con el hold anterior activo | `409`, `application/problem+json` | `code` `SLOT_ALREADY_HELD` |
| `hold-400-invalid-slot.json` | Igual, con `startTime` `12:15` | `400`, `application/problem+json` | `code` `INVALID_SLOT` |
| `hold-404-professional-not-found.json` | Igual, con `professionalId` 999999 | `404`, `application/problem+json` | `code` `PROFESSIONAL_NOT_FOUND`. A diferencia de las ocupaciones, crear un hold sí distingue el profesional inexistente |
| `hold-400-professional-disabled.json` | `professionalId` 214 (deshabilitado), viernes 2026-11-06 a las 13:00 | `400`, `application/problem+json` | `code` `PROFESSIONAL_DISABLED`. El horario de ese profesional también está deshabilitado: la cátedra revisa el profesional antes que el horario |
| `hold-401-invalid-token.json` | El pedido de `hold-201-created.json` con `Authorization: Bearer token-invalido` | `401`, **sin `Content-Type`** | **Cuerpo vacío**, igual que en las ocupaciones |
| `confirm-202-accepted.json` | `POST /api/appointment-holds/{holdId}/confirm` con `externalPatientId` `999999` (el del hold), `patientFirstName` `Prueba` y `patientLastName` `Grabacion`, sobre un hold del profesional 201 del lunes 2026-11-02 a las 12:00 recién creado | `202`, `application/json` | `status` `WAITING_FOR_PHONE` y **exactamente el mismo `expiresAt` del hold**, con nanosegundos: confirmar no extiende el vencimiento. Reconstruido de la salida textual mostrada al grabarlo (la respuesta cruda se perdió con un reinicio) |
| `confirm-409-already-confirmed.json` | La misma confirmación, repetida enseguida | `409`, `application/problem+json` | `code` `RESERVATION_PROCESS_ALREADY_CONFIRMED`, sin estado ni vencimiento: solo los campos de error. Reconstruido igual que el anterior |
| `confirm-404-hold-not-found.json` | Confirmar `hold-00000000-0000-0000-0000-000000000000` (inventado) | `404`, `application/problem+json` | `code` `HOLD_NOT_FOUND` |
| `confirm-401-invalid-token.json` | El pedido anterior con `Authorization: Bearer token-invalido` | `401`, **sin `Content-Type`** | **Cuerpo vacío**, como en las demás rutas |
| `confirm-400-external-patient-mismatch.json` | Confirmar un hold recién creado (con `externalPatientId` `999999`) enviando `999998` | `400`, `application/problem+json` | `code` `EXTERNAL_PATIENT_MISMATCH`. No confirma el hold |
| `confirm-404-expired-hold-not-found.json` | Confirmar bien ese mismo hold 21 segundos después de su `expiresAt` | `404`, `application/problem+json` | **`code` `HOLD_NOT_FOUND`, no `HOLD_EXPIRED`**: la cátedra no distingue un hold vencido de uno inexistente, al menos pasados unos segundos del vencimiento |
| `appointments-200-failed.json` | `GET /api/appointments?externalPatientId=999999` | `200`, `application/json` | Lista con la única reserva de ese paciente: la del proceso de `confirm-202-accepted.json`, en **`FAILED`** porque venció sin teléfono, con `reservationId` 1252, `confirmedAt` y `patientPhone` nulos. Encabezados: `X-Total-Count: 1` y `Link` con la primera y la última página (con el host interno de la cátedra, por eso no se guardan) |
| `appointments-200-phone-pending.json` | `GET /api/appointments?externalPatientId=999999&page=0&size=20`, apenas después de confirmar sin teléfono un hold del profesional 203 del viernes 2026-11-06 a las 12:00 (2026-11-05, T13) | `200`, `application/json` | La reserva recién confirmada figura de inmediato en **`PHONE_PENDING`**, con `reservationId` 1664 y `confirmedAt` y `patientPhone` nulos, primera en la lista; después, la `FAILED` de `appointments-200-failed.json`. `X-Total-Count: 2`. El listado no trae `expiresAt` |
| `appointments-500-date-filter.json` | `GET /api/appointments?from=2026-11-09&to=2026-11-09` | `500`, `application/problem+json` | **Todo listado con `from` y `to` responde así**, con o sin `externalPatientId`: `detail` `Failure during data access`, sin `code`. Sin fechas, el listado funciona |

Tiempo de respuesta observado: unos 0,5 s por consulta.

**Profesional de los E2E y de las pruebas manuales:** no es fijo. Hasta el 2026-11-05 fue el `201` (lunes de 12:00 a 15:00 y miércoles de 17:00 a 20:00, cada 30 minutos); ese día la cátedra lo deshabilitó y un hold responde `400` `PROFESSIONAL_DISABLED`. Desde entonces se elige en cada corrida, desde `GET /api/synchronization/snapshot`, uno habilitado de una categoría habilitada (`E2EProfessional`, TASK-11 de T13); la prueba de T13 usó el 203. El 2026-11-06 el snapshot volvió a dar habilitado al 201, ahora de la categoría Cardiologia, y el E2E lo eligió.

No se pudo grabar: un `403` `STUDENT_INTEGRATION_NOT_FOUND`, un `503` ni un timeout (no se pueden provocar; el único `500` es el del listado con fechas), ni un `409` `SLOT_ALREADY_RESERVED` (necesita una reserva confirmada, T05 y Kafka). Al confirmar tampoco: `403` `HOLD_OWNER_MISMATCH` (los dos backends comparten la cuenta técnica) ni `404` `HOLD_EXPIRED` (un hold vencido respondió `HOLD_NOT_FOUND`). Esos casos se arman a mano en los tests.

## Mensajes de Kafka (`kafka/`): reales y armados

**Los archivos `*-real.json` son mensajes reales** del topic de acciones, leídos sin consumer group el 2026-11-06 (T07 TASK-02): el `groupId` se reemplazó por `proyecto-de-prueba`; no traen datos personales. La cátedra publica desde el 2026-11-04: ese día publicó juntos los mensajes atrasados de octubre, con su `occurredAt` original. Formato igual al anexo §15, con dos diferencias: los instantes llegan con nanosegundos, y una cancelación sin motivo trae `reason` en `null`. La clave de cada mensaje es su `reservationProcessId`, y no hay headers.

| Archivo real | Evento | Origen |
| --- | --- | --- |
| `kafka/additional-information-requested-real.json` | `AdditionalInformationRequested` | Pedido de teléfono del E2E del 2026-11-06 (profesional 201, lunes 2026-11-09 a las 13:30) |
| `kafka/appointment-confirmed-real.json` | `AppointmentConfirmed` | La misma reserva, 1665, confirmada después de publicar el teléfono |
| `kafka/appointment-cancelled-real.json` | `AppointmentCancelled` | La misma reserva, cancelada con T11 sin motivo (`reason` en `null`) |
| `kafka/appointment-process-expired-real.json` | `AppointmentProcessExpired` | El proceso de `confirm-202-accepted.json`, vencido el 2026-10-28 y publicado el 2026-11-04 |

**Los demás archivos de `kafka/` son armados**, copiando los ejemplos del anexo (§15.4 y §15.6 a §15.10): se armaron cuando la cátedra todavía no publicaba (ver TASK-02 de `docs/sdd/t07-solicitud-informacion/TASKS.md` y la decisión 014). Siguen en uso porque cubren los tipos que la cátedra no publicó (`AdditionalInformationRejected` y `AppointmentProcessInvalid`) y porque los tests los modifican campo por campo.

| Archivo | Evento | Lo usa |
| --- | --- | --- |
| `kafka/additional-information-requested.json` | `AdditionalInformationRequested` (no trae `groupId`, como en el anexo) | T07 |
| `kafka/additional-information-submitted.json` | `AdditionalInformationSubmitted`: el único que publica turnos; los tests comparan lo publicado con este ejemplo | T06 |
| `kafka/appointment-confirmed.json` | `AppointmentConfirmed` | T08 |
| `kafka/additional-information-rejected.json` | `AdditionalInformationRejected` | T08 |
| `kafka/appointment-process-expired.json` | `AppointmentProcessExpired` | T08 |
| `kafka/appointment-process-invalid.json` | `AppointmentProcessInvalid` | T08 |
| `kafka/appointment-cancelled.json` | `AppointmentCancelled` | T08 |

**Cancelar un hold no funciona (2026-10-27):** `POST /api/appointments/{reservationProcessId}/cancel` con el `reservationProcessId` de `hold-201-created.json`, todavía en `HELD`, respondió `404` `RESERVATION_NOT_FOUND`. No se versiona, porque no lo usa ningún test. El hold no se pudo deshacer y venció solo a los 10 minutos. Los profesionales deshabilitados en la cátedra el 2026-10-27 eran el 206, el 214 y el 222 (snapshot versión 1, no versionado).

**Cancelar un proceso en `WAITING_FOR_PHONE` tampoco funciona (2026-10-28):** `POST /api/appointments/{reservationProcessId}/cancel` con el proceso de `confirm-202-accepted.json`, ya confirmado, respondió `409` `RESERVATION_NOT_CANCELLABLE` (`detail` "Reservation is not confirmed"). No se versiona, porque no lo usa ningún test. La reserva `PHONE_PENDING` venció sola a los 10 minutos.

**El listado de reservas (2026-11-05, T13):** con fechas responde siempre `500` (ver arriba); sin fechas funciona por `externalPatientId` (coincidencia exacta), por `status` y paginado, con `X-Total-Count` y `Link`. Con 0 resultados, el `Link` de la última página dice `page=-1`. Los holds que nunca se confirmaron no aparecen. Una reserva que vence sin teléfono figura `FAILED`. No se llegó a ver una reserva vigente en `PHONE_PENDING`: mientras estuvo vigente, solo se la consultó con fechas.
