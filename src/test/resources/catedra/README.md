# Respuestas reales de la cátedra

Grabadas el 2026-10-24 con `curl` contra la cátedra real, con la cuenta técnica del proyecto. Las usa el servidor simulado de la suite normal de tests, para que la simulación responda igual que la cátedra. La suite E2E no las usa: habla con la cátedra en vivo.

**Limpieza antes de versionar:** en `login-200.json`, el JWT técnico, la contraseña de Redis, los hosts de Redis y Kafka y el `groupId` (que también forma parte de los nombres de topics, namespaces y usuario de Redis) se reemplazaron por valores de prueba. Se verificó que ninguno de los valores reales, ni la URL de la cátedra ni las credenciales de la cuenta técnica, aparece en estos archivos ni en el resto del repositorio. Los encabezados de las respuestas no se guardan: el del `401` sin token incluye una dirección interna de la cátedra.

Los datos de profesionales y fechas son los ficticios de la cátedra.

| Archivo | Request | Estado y `Content-Type` | Observaciones |
| --- | --- | --- | --- |
| `login-200.json` | `POST /api/authenticate` con `username`, `password` y `rememberMe: true` | `200`, `application/json` | `id_token` e `integration` con los 13 campos, `provisioningStatus` `PROVISIONED` (valores limpiados) |
| `occupancies-200-empty.json` | `GET /api/appointment-occupancies?professionalId=201&from=2026-10-26&to=2026-10-26` | `200`, `application/json` | Sin ocupaciones. El día de la grabación no había ninguna reserva ni hold en la cátedra: diez consultas a profesionales y fechas distintas devolvieron la lista vacía |
| `occupancies-200-unknown-professional.json` | Igual, con `professionalId=999999` | `200`, `application/json` | Un profesional inexistente no da `404`: devuelve la lista vacía |
| `occupancies-400-invalid-date-range.json` | Igual, con `from=2026-10-27&to=2026-10-26` | `400`, `application/problem+json` | `code` `INVALID_DATE_RANGE`, con los campos propios del formato de errores de la cátedra (`type`, `message`, `path`) |
| `occupancies-400-missing-parameter.json` | Igual, sin `professionalId` | `400`, `application/problem+json` | `code` `VALIDATION_ERROR` |
| `occupancies-401-invalid-token.json` | Igual, con `Authorization: Bearer token-invalido` | `401`, **sin `Content-Type`** | **Cuerpo vacío** (por eso el archivo está vacío). Trae `WWW-Authenticate: Bearer error="invalid_token"` |

Tiempo de respuesta observado: unos 0,5 s por consulta.

**Profesional de referencia para la suite E2E y la prueba manual:** `201`, habilitado, de una categoría habilitada, con horarios los lunes de 12:00 a 15:00 y los miércoles de 17:00 a 20:00, cada 30 minutos.

No se pudo grabar: un `403` `STUDENT_INTEGRATION_NOT_FOUND`, un `503` ni un timeout (no se pueden provocar), y una lista con ocupaciones (no había ninguna). Esos casos se arman a mano en los tests.
