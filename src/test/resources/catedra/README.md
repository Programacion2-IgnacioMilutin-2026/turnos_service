# Respuestas reales de la cátedra

Grabadas con `curl` contra la cátedra real, con la cuenta técnica del proyecto: las de ocupaciones y login el 2026-10-24 (T03), las de holds y la lista con un `HELD` el 2026-10-27 (T04) y las de confirmar un hold el 2026-10-28 y el 2026-10-29 (T05). Las usa el servidor simulado de la suite normal de tests, para que la simulación responda igual que la cátedra. La suite E2E no las usa: habla con la cátedra en vivo.

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

Tiempo de respuesta observado: unos 0,5 s por consulta.

**Profesional de referencia para la suite E2E y la prueba manual:** `201`, habilitado, de una categoría habilitada, con horarios los lunes de 12:00 a 15:00 y los miércoles de 17:00 a 20:00, cada 30 minutos.

No se pudo grabar: un `403` `STUDENT_INTEGRATION_NOT_FOUND`, un `503`, un `500` ni un timeout (no se pueden provocar), ni un `409` `SLOT_ALREADY_RESERVED` (necesita una reserva confirmada, T05 y Kafka). Al confirmar tampoco: `403` `HOLD_OWNER_MISMATCH` (los dos backends comparten la cuenta técnica) ni `404` `HOLD_EXPIRED` (un hold vencido respondió `HOLD_NOT_FOUND`). Esos casos se arman a mano en los tests.

## Mensajes de Kafka (`kafka/`): armados, no grabados

**Estos archivos no son respuestas reales.** Hasta el 2026-10-31 la cátedra no publicó ningún mensaje en el topic de acciones de la integración: estaba vacío después de confirmar holds y después de su vencimiento (ver TASK-02 de `docs/sdd/t07-solicitud-informacion/TASKS.md` y la decisión 014). Por eso se armaron copiando los ejemplos del anexo de la cátedra (§15.4 y §15.6 a §15.10, los mismos del contrato de turnos §4.3.2 y §4.3.4), con el `groupId` reemplazado por el valor de prueba de `login-200.json`. Cuando la cátedra publique, se reemplazan por los grabados, limpios como el resto.

| Archivo | Evento | Lo usa |
| --- | --- | --- |
| `kafka/additional-information-requested.json` | `AdditionalInformationRequested` (no trae `groupId`, como en el anexo) | T07 |
| `kafka/appointment-confirmed.json` | `AppointmentConfirmed` | Hasta T08, como evento que se descarta |
| `kafka/additional-information-rejected.json` | `AdditionalInformationRejected` | Ídem |
| `kafka/appointment-process-expired.json` | `AppointmentProcessExpired` | Ídem |
| `kafka/appointment-process-invalid.json` | `AppointmentProcessInvalid` | Ídem |
| `kafka/appointment-cancelled.json` | `AppointmentCancelled` | Ídem |

**Cancelar un hold no funciona (2026-10-27):** `POST /api/appointments/{reservationProcessId}/cancel` con el `reservationProcessId` de `hold-201-created.json`, todavía en `HELD`, respondió `404` `RESERVATION_NOT_FOUND`. No se versiona, porque no lo usa ningún test. El hold no se pudo deshacer y venció solo a los 10 minutos. Los profesionales deshabilitados en la cátedra el 2026-10-27 eran el 206, el 214 y el 222 (snapshot versión 1, no versionado).

**Cancelar un proceso en `WAITING_FOR_PHONE` tampoco funciona (2026-10-28):** `POST /api/appointments/{reservationProcessId}/cancel` con el proceso de `confirm-202-accepted.json`, ya confirmado, respondió `409` `RESERVATION_NOT_CANCELLABLE` (`detail` "Reservation is not confirmed"). No se versiona, porque no lo usa ningún test. La reserva `PHONE_PENDING` venció sola a los 10 minutos.
