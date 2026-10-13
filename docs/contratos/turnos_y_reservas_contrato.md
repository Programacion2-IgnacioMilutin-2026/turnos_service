# Contrato del Servicio de Turnos y Reservas

Nota: Los valores de las requests y respuestas son ejemplos.

## 1. Operaciones

| Caso | Método y ruta | Acceso | Respuesta exitosa |
| --- | --- | --- | --- |
| T01 | `POST /api/register` | Público | `201` |
| T02 | `POST /api/authenticate` | Público | `200` |
| T03 | `GET /api/free-slots` | JWT válido (cualquier rol) | `200` |
| T04 | `POST /api/reservation-processes` | JWT válido (cualquier rol) | `201` |
| T05 | `POST /api/reservation-processes/{reservationProcessId}/confirm` | JWT válido (cualquier rol) | `200` |
| T06 | `POST /api/reservation-processes/{reservationProcessId}/phone` | JWT válido (cualquier rol) | `200` |
| T09 | `GET /api/reservation-processes/{reservationProcessId}` | JWT válido (cualquier rol) | `200` |
| T10 | `GET /api/reservation-processes` | JWT válido (cualquier rol) | `200` |
| T11 | `POST /api/reservation-processes/{reservationProcessId}/cancel` | JWT válido (cualquier rol) | `200` |

T07 y T08 se disparan por mensajes de Kafka de la cátedra, y T12 y T13 por tiempo. Ninguno expone un endpoint.

## 2. Proceso de reserva

Es el objeto que devuelven T04, T05, T06, T09 y T11, y los `items` de T10.

Proceso abierto, a la espera de un nuevo teléfono tras un rechazo:

```json
{
  "reservationProcessId": "process-a92b47d0",
  "status": "WAITING_FOR_PHONE",
  "professionalId": 15,
  "professionalFirstName": "Ana",
  "professionalLastName": "Gomez",
  "professionalCategoryName": "Cardiología",
  "date": "2026-10-20",
  "startTime": "09:30",
  "expiresAt": "2026-10-12T14:44:50Z",
  "rejectionReason": "INVALID_PHONE_NUMBER",
  "reservationId": null,
  "confirmedAt": null,
  "cancelledAt": null,
  "cancellationReason": null
}
```

Reserva confirmada:

```json
{
  "reservationProcessId": "process-a92b47d0",
  "status": "CONFIRMED",
  "professionalId": 15,
  "professionalFirstName": "Ana",
  "professionalLastName": "Gomez",
  "professionalCategoryName": "Cardiología",
  "date": "2026-10-20",
  "startTime": "09:30",
  "expiresAt": null,
  "rejectionReason": null,
  "reservationId": 8251,
  "confirmedAt": "2026-10-12T14:36:01Z",
  "cancelledAt": null,
  "cancellationReason": null
}
```

| Campo | Descripción |
| --- | --- |
| `reservationProcessId` | Identificador del proceso, asignado por la cátedra. Es el que la app usa en las rutas. |
| `status` | Estado del proceso (tabla siguiente). |
| `professionalId`, `professionalFirstName`, `professionalLastName`, `professionalCategoryName` | Profesional y su categoría tal como estaban al bloquear el turno (dato histórico). |
| `date`, `startTime` | Fecha (`yyyy-MM-dd`) y hora de inicio (`HH:mm`) del turno. |
| `expiresAt` | Instante de vencimiento. Solo tiene valor mientras el proceso está abierto; en otro caso es `null`. |
| `rejectionReason` | Código del rechazo del teléfono informado por la cátedra (por ejemplo `INVALID_PHONE_NUMBER`). Solo tiene valor mientras el último evento del proceso fue ese rechazo; en otro caso es `null`. |
| `reservationId` | Identificador numérico de la reserva en la cátedra. Lo tienen las reservas confirmadas y las canceladas después de confirmarse; en otro caso es `null`. |
| `confirmedAt` | Instante de confirmación. `null` si nunca se confirmó. |
| `cancelledAt`, `cancellationReason` | Instante y motivo de la cancelación. `cancellationReason` es `null` si no se indicó. |

| `status` | Significado |
| --- | --- |
| `HELD` | Turno bloqueado, falta confirmarlo (T05). Abierto. |
| `WAITING_FOR_PHONE` | Confirmado ante la cátedra, falta informar el teléfono (T06). Abierto. |
| `PHONE_SUBMITTED` | Teléfono enviado, a la espera del resultado. Abierto. |
| `CONFIRMED` | Reserva confirmada. |
| `CANCELLED` | Cancelada. |
| `EXPIRED` | Venció sin confirmarse. |
| `INVALID` | La cátedra lo marcó como inválido y se reconcilia por REST (T13). |

El objeto nunca expone `holdId`, `requestEventId`, el teléfono ni la marca de reconciliación.

## 3. Operaciones expuestas a la app

Reglas que valen para todas las operaciones y no se repiten en cada tabla:

- En las operaciones protegidas, un JWT faltante, inválido o vencido responde `401`, y un rol sin permisos responde `403` (`contratos_comunes.md`, sección 1.4).
- Un fallo de base de datos o cualquier error no previsto responde `500` con `INTERNAL_ERROR`.
- Todos los errores tienen el formato de `contratos_comunes.md`, sección 2 (`status`, `title`, `code`, `detail` y, si corresponde, `fieldErrors`). Algunos agregan campos extra con datos que la app necesita; por ejemplo, `OPEN_PROCESS_EXISTS` incluye los datos del proceso abierto. Esos campos se indican en cada operación.

### 3.1 T01 — Registrar usuario

`POST /api/register`, público.

```json
{
  "login": "maria.gomez",
  "password": "secreta123",
  "firstName": "María",
  "lastName": "Gómez",
  "email": "maria.gomez@example.com",
  "langKey": "es",
  "imageUrl": "https://example.com/avatar.png"
}
```

| Campo | Obligatorio | Restricción |
| --- | --- | --- |
| `login` | Sí | 3 a 50 caracteres: letras, números y los que admite el login de JHipster. |
| `password` | Sí | 4 a 100 caracteres. |
| `firstName` | Sí | 2 a 50 caracteres. |
| `lastName` | Sí | 2 a 50 caracteres. |
| `email` | Sí | Email válido, 5 a 254 caracteres. |
| `langKey` | Sí | 2 a 10 caracteres. |
| `imageUrl` | No | Libre. |

Si el body trae `id`, estado de activación, autoridades o campos de auditoría, se ignoran.

Respuesta `201`, sin la contraseña y sin JWT:

```json
{
  "id": 1042,
  "login": "maria.gomez",
  "firstName": "María",
  "lastName": "Gómez",
  "email": "maria.gomez@example.com",
  "imageUrl": "https://example.com/avatar.png",
  "langKey": "es",
  "activated": true
}
```

| Estado | `code` | Cuándo |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR` | Falta un campo obligatorio o tiene formato inválido (T01 2.1). |
| `400` | `USERNAME_ALREADY_EXISTS` | El `login` ya está registrado (T01 3.1). |
| `400` | `EMAIL_ALREADY_EXISTS` | El `email` ya está registrado (T01 4.1). |

### 3.2 T02 — Iniciar sesión

`POST /api/authenticate`, público.

```json
{
  "username": "maria.gomez",
  "password": "secreta123"
}
```
`username` lleva el `login` del usuario, como en el `authenticate` de JHipster y de la cátedra. Si el body trae `rememberMe`, se ignora: la vigencia del JWT la define la configuración del servicio.
Respuesta `200`:

```json
{
  "id_token": "<jwt>"
}
```

El JWT tiene el formato de `contratos_comunes.md`, sección 1.2. `id_token` es el nombre que usa el usuario de JHipster.

| Estado | `code` | Cuándo |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR` | Falta `username` o `password` (T02 2.1). |
| `401` | `INVALID_CREDENTIALS` | El `login` no existe o la contraseña no coincide (T02 3.1 y 4.1). Es el mismo cuerpo en ambos casos. |

### 3.3 T03 — Consultar turnos libres

`GET /api/free-slots?professionalId=15&date=2026-10-20`

| Parámetro | Obligatorio | Descripción |
| --- | --- | --- |
| `professionalId` | Sí | Numérico. |
| `date` | Sí | `yyyy-MM-dd`, no anterior a la fecha actual. |

Respuesta `200`:

```json
{
  "slots": [
    { "professionalId": 15, "date": "2026-10-20", "startTime": "09:30", "durationMinutes": 30 },
    { "professionalId": 15, "date": "2026-10-20", "startTime": "10:00", "durationMinutes": 30 }
  ],
  "emptyReason": null,
  "stale": false,
  "dataAsOf": "2026-10-12T14:30:12Z"
}
```

| Campo | Descripción |
| --- | --- |
| `slots` | Turnos libres ordenados por hora de inicio. Vacío si no hay ninguno. |
| `emptyReason` | `null` si hay turnos. Si `slots` está vacío: `PROFESSIONAL_NOT_WORKING` (el profesional no atiende ese día) o `NO_FREE_SLOTS` (no quedan turnos libres). |
| `stale` | `true` si las ocupaciones salen del caché porque la cátedra no respondió. |
| `dataAsOf` | Instante en que se obtuvieron las ocupaciones usadas, en vivo o desde el caché. |

| Estado | `code` | Cuándo |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR` | `professionalId` o `date` faltan o son inválidos (T03 3.1). |
| `404` | `PROFESSIONAL_NOT_FOUND` | El profesional no existe en catálogo (T03 4.2). |
| `409` | `PROFESSIONAL_DISABLED` | El profesional está deshabilitado (T03 4.4). |
| `503` | `CATALOG_UNAVAILABLE` | Catálogo no responde tras los reintentos (T03 4.3). |
| `503` | `SERVICE_DEGRADED` | Error de configuración con la cátedra (T03 5.2). |
| `503` | `CENTRAL_UNAVAILABLE` | La cátedra no responde tras los reintentos y no hay caché utilizable (T03 5.3). |

Un `401` o `403` de catálogo al JWT reenviado responde `500` `INTERNAL_ERROR` (T03 4.1).

### 3.4 T04 — Bloquear turno

`POST /api/reservation-processes`

```json
{
  "professionalId": 15,
  "date": "2026-10-20",
  "startTime": "09:30"
}
```

Todos los campos son obligatorios. `date` es `yyyy-MM-dd` y `startTime` es `HH:mm`; el turno no puede ser anterior al momento actual.

Respuesta `201`: proceso de reserva (sección 2) en estado `HELD`, con `expiresAt` y los datos del profesional. Los campos de reserva, confirmación y cancelación vienen en `null`.

| Estado | `code` | Cuándo |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR` | Faltan campos, tienen formato inválido o el turno es anterior al momento actual (T04 3.1). |
| `400` | `INVALID_SLOT` | El turno no cae en un horario habilitado del profesional (T04 4.2 y 5.5). |
| `404` | `PROFESSIONAL_NOT_FOUND` | El profesional no existe (T04 4.1 y 5.3). |
| `409` | `OPEN_PROCESS_EXISTS` | El usuario ya tiene un proceso abierto (T04 3.2). |
| `409` | `PROFESSIONAL_DISABLED` | El profesional ya no acepta turnos (T04 5.4). |
| `409` | `SLOT_NOT_AVAILABLE` | El turno ya está confirmado o bloqueado por otro (T04 5.6 y 5.7). |
| `503` | `CATALOG_UNAVAILABLE` | Catálogo no responde tras los reintentos (T04 4.3). |
| `503` | `SERVICE_DEGRADED` | Error de configuración con la cátedra (T04 5.2). |
| `503` | `CENTRAL_UNAVAILABLE` | La cátedra no responde tras los reintentos; no se creó ningún proceso (T04 5.8). |
| `500` | `HOLD_NOT_SAVED` | El hold se creó en la cátedra pero no se pudo guardar el proceso (T04 7.1). |

`OPEN_PROCESS_EXISTS` agrega los datos del proceso abierto, para que la app lo continúe o lo cancele (T11):

```json
{
  "status": 409,
  "title": "Ya existe un proceso de reserva abierto",
  "code": "OPEN_PROCESS_EXISTS",
  "detail": "El usuario ya tiene un proceso de reserva abierto.",
  "reservationProcessId": "process-a92b47d0",
  "currentStatus": "WAITING_FOR_PHONE",
  "expiresAt": "2026-10-12T14:44:50Z"
}
```

`HOLD_NOT_SAVED` agrega `expiresAt` y el encabezado `Retry-After`, en segundos hasta ese instante. El turno sigue retenido hasta entonces y no conviene reintentar antes:

```http
HTTP/1.1 500
Retry-After: 540
Content-Type: application/problem+json
```

```json
{
  "status": 500,
  "title": "El bloqueo no pudo registrarse",
  "code": "HOLD_NOT_SAVED",
  "detail": "El turno quedó retenido y vence en el instante indicado.",
  "expiresAt": "2026-10-12T14:44:50Z"
}
```

Un `401` o `403` de catálogo al JWT reenviado responde `500` `INTERNAL_ERROR` (T04 4.4).

### 3.5 T05 — Confirmar reserva

`POST /api/reservation-processes/{reservationProcessId}/confirm`, sin body. El nombre y apellido del paciente salen del perfil del usuario.

Respuesta `200`: proceso de reserva (sección 2) en estado `WAITING_FOR_PHONE`, con el nuevo `expiresAt`. Si la cátedra ya lo había confirmado antes (reintento de la app), también responde `200` con el estado actual (T05 4.7).

| Estado | `code` | Cuándo |
| --- | --- | --- |
| `400` | `INVALID_SLOT` | La cátedra rechaza el turno (T05 4.8). |
| `404` | `RESERVATION_PROCESS_NOT_FOUND` | El proceso no existe o no es del usuario; no se distingue (T05 2.2). |
| `404` | `HOLD_NOT_FOUND` | La cátedra no encuentra el hold (T05 4.3). |
| `404` | `PROFESSIONAL_NOT_FOUND` | La cátedra no encuentra el profesional (T05 4.8). |
| `409` | `INVALID_PROCESS_STATE` | El proceso no está en `HELD` (T05 3.1). Agrega `currentStatus`. |
| `409` | `HOLD_EXPIRED` | El bloqueo venció, localmente o según la cátedra; el proceso queda `EXPIRED` y hay que repetir T04 (T05 3.2 y 4.4). |
| `409` | `PATIENT_MISMATCH` | El paciente no coincide con el del hold (T05 4.6). |
| `409` | `SLOT_NOT_AVAILABLE` | El turno ya está reservado (T05 4.8). |
| `503` | `SERVICE_DEGRADED` | Error de configuración con la cátedra (T05 4.2). |
| `503` | `CENTRAL_UNAVAILABLE` | La cátedra no responde tras los reintentos. El proceso sigue en `HELD` y puede avanzar solo por Kafka (T05 4.9). |
| `500` | `LOCAL_STATE_OUT_OF_SYNC` | La cátedra confirmó pero turnos no pudo guardarlo; queda marcado para reconciliar. La app consulta el estado con T09 (T05 6.1). |

`INVALID_PROCESS_STATE` incluye el estado actual:

```json
{
  "status": 409,
  "title": "El proceso no admite esta operación",
  "code": "INVALID_PROCESS_STATE",
  "detail": "El proceso no está en el estado requerido.",
  "currentStatus": "PHONE_SUBMITTED"
}
```

`HOLD_OWNER_MISMATCH` de la cátedra responde `500` `INTERNAL_ERROR` (T05 4.5).

### 3.6 T06 — Informar teléfono

`POST /api/reservation-processes/{reservationProcessId}/phone`

```json
{
  "phoneNumber": "+54 9 261 555-1234"
}
```

`phoneNumber` es obligatorio. Tras eliminar espacios, guiones, paréntesis y puntos debe cumplir `^\+?[0-9]{7,15}$`, la misma regla de la cátedra. Turnos lo normaliza sin esos caracteres antes de publicarlo.

Respuesta `200`: proceso de reserva (sección 2) en estado `PHONE_SUBMITTED`. El resultado final se consulta con T09.

| Estado | `code` | Cuándo |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR` | Falta `phoneNumber`. |
| `400` | `INVALID_PHONE_NUMBER` | El teléfono no cumple el formato (T06 4.1). El usuario puede reintentar con otro número. |
| `404` | `RESERVATION_PROCESS_NOT_FOUND` | El proceso no existe o no es del usuario (T06 2.2). |
| `409` | `INVALID_PROCESS_STATE` | El proceso no está en `WAITING_FOR_PHONE` (T06 3.1). Agrega `currentStatus`. |
| `409` | `HOLD_EXPIRED` | El plazo venció; el proceso queda `EXPIRED` y hay que repetir T04 (T06 3.2). |
| `409` | `PHONE_REQUEST_NOT_READY` | Turnos todavía no procesó el pedido de teléfono de la cátedra. Reintentar en unos segundos (T06 3.3). |
| `503` | `CENTRAL_UNAVAILABLE` | Kafka no está disponible tras los reintentos. Se puede reintentar mientras no venza `expiresAt` (T06 5.1 y 5.2). |
| `500` | `LOCAL_STATE_OUT_OF_SYNC` | El evento ya se publicó pero turnos no pudo guardarlo. La app consulta el estado con T09 (T06 6.1). |

### 3.7 T09 — Consultar estado de un proceso

`GET /api/reservation-processes/{reservationProcessId}`

Respuesta `200`: proceso de reserva (sección 2). Es una lectura local: no consulta a la cátedra.

| Estado | `code` | Cuándo |
| --- | --- | --- |
| `404` | `RESERVATION_PROCESS_NOT_FOUND` | El proceso no existe o no es del usuario (T09 3.1). |

### 3.8 T10 — Consultar mis reservas

`GET /api/reservation-processes?status=CONFIRMED&page=0&size=10`

| Parámetro | Obligatorio | Descripción |
| --- | --- | --- |
| `status` | No | `HELD`, `WAITING_FOR_PHONE`, `PHONE_SUBMITTED`, `CONFIRMED` o `CANCELLED`. |
| `page` | No | Base 0. Por defecto `0`. |
| `size` | No | Por defecto `10`, máximo `100`. |

Respuesta `200`, con el formato de página de `contratos_comunes.md`, sección 3.1. Cada elemento de `items` es un proceso de reserva (sección 2):

```json
{
  "items": [
    {
      "reservationProcessId": "process-a92b47d0",
      "status": "CONFIRMED",
      "professionalId": 15,
      "...": "..."
    }
  ],
  "page": 0,
  "size": 10,
  "totalItems": 1
}
```

Solo lista las reservas del usuario autenticado: el proceso abierto, si lo hay, las confirmadas y las canceladas después de confirmarse. No lista los `EXPIRED`, los `INVALID` ni los cancelados antes de confirmarse. El orden es: primero el proceso abierto y después por fecha y hora del turno. Una página fuera de rango o un filtro sin resultados responden `200` con `items` vacío y el `totalItems` real.

| Estado | `code` | Cuándo |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR` | `status` no reconocido, o `page` o `size` fuera de rango (T10 3.1). |

### 3.9 T11 — Cancelar reserva o proceso en curso

`POST /api/reservation-processes/{reservationProcessId}/cancel`

```json
{
  "reason": "Cambio solicitado por el usuario"
}
```

El body es opcional. `reason` admite hasta 500 caracteres.

Respuesta `200`: proceso de reserva (sección 2) en estado `CANCELLED`, con `cancelledAt` y `cancellationReason`. Si ya estaba cancelado, también responde `200` con su estado actual, sin llamar a la cátedra (T11 5.1).

| Estado | `code` | Cuándo |
| --- | --- | --- |
| `400` | `VALIDATION_ERROR` | `reason` supera los 500 caracteres (T11 3.1). |
| `404` | `RESERVATION_PROCESS_NOT_FOUND` | El proceso no existe o no es del usuario (T11 4.1). |
| `409` | `INVALID_PROCESS_STATE` | El estado no es cancelable: `PHONE_SUBMITTED`, `EXPIRED` o `INVALID` (T11 5.2). Agrega `currentStatus`. |
| `409` | `HOLD_EXPIRED` | Es un proceso abierto cuyo plazo venció; queda `EXPIRED` (T11 5.3). |
| `409` | `RESERVATION_NOT_CANCELLABLE` | La cátedra no permite cancelarlo; queda marcado para reconciliar (T11 6.5). |
| `503` | `SERVICE_DEGRADED` | Error de configuración con la cátedra (T11 6.2). |
| `503` | `CENTRAL_UNAVAILABLE` | La cátedra no responde tras los reintentos. El estado local no cambia (T11 6.6). |
| `500` | `LOCAL_STATE_OUT_OF_SYNC` | La cátedra canceló pero turnos no pudo guardarlo. La app consulta el estado con T09 (T11 8.1). |

`RESERVATION_NOT_FOUND` y `RESERVATION_OWNER_MISMATCH` de la cátedra responden `500` `INTERNAL_ERROR` (T11 6.3 y 6.4).

## 4. Contratos que turnos consume

### 4.1 Servicio de catálogo

Turnos usa una sola ruta de catálogo, C06. Su contrato completo está en `contrato_catalogo.md`; aquí se indica cómo la usa turnos. Catálogo no guarda nada de turnos y turnos no guarda copia del catálogo: se consulta en cada operación (T03 y T04).

`GET /api/professionals/{professionalId}`

Turnos reenvía, sin modificarlo, el JWT del usuario que recibió de la app:

```http
Authorization: Bearer <jwt-del-usuario>
```

Respuesta `200`:

```json
{
  "id": 15,
  "firstName": "Ana",
  "lastName": "Gomez",
  "enabled": true,
  "category": { "id": 1, "name": "Clinica medica", "enabled": true },
  "weeklySchedules": [
    {
      "id": 21,
      "dayOfWeek": "MONDAY",
      "startTime": "09:00:00",
      "endTime": "12:00:00",
      "slotDurationMinutes": 30,
      "enabled": true
    }
  ]
}
```

Turnos acepta las horas en `HH:mm` y en `HH:mm:ss`. Un profesional sin horarios devuelve `weeklySchedules` vacío.

| Respuesta de catálogo | Qué hace turnos |
| --- | --- |
| `200` | Usa los datos. En T03, si `enabled` es falso, responde `409` `PROFESSIONAL_DISABLED` (T03 4.4). |
| `401` o `403` | Error de integración interna: se registra y se responde `500` `INTERNAL_ERROR` (T03 4.1, T04 4.4). |
| `404` `PROFESSIONAL_NOT_FOUND` | Responde `404` `PROFESSIONAL_NOT_FOUND` (T03 4.2, T04 4.1). |
| `503` (incluye `CATALOG_NOT_INITIALIZED`), timeout o corte de conexión | Reintenta con espera creciente y un máximo de intentos. Agotados, responde `503` `CATALOG_UNAVAILABLE` sin llamar a la cátedra (T03 4.3, T04 4.3). |
| Cualquier otra respuesta (por ejemplo `400` o `500`) | Se registra y se responde `500` `INTERNAL_ERROR`. |

### 4.2 Servicio de la cátedra: REST


#### 4.2.1 Reglas comunes

- Salvo el login técnico, todas las rutas llevan `Authorization: Bearer <jwt-tecnico>` y exigen `ROLE_STUDENT_CLIENT`.
- La integración se obtiene siempre del JWT; ninguna operación acepta `groupId`.
- El JWT técnico se guarda solo en memoria. Nunca se envía a la app ni a catálogo.
- Los errores usan `application/problem+json`. Se decide por `status` y `code`, nunca por el texto de `detail`. Los `503` y `500` no garantizan `code`.
- Un `401` en cualquier ruta ejecuta el login técnico (4.2.2) y repite la operación una sola vez.
- Un `403` `STUDENT_INTEGRATION_NOT_FOUND` es un error de configuración: se registra y se responde `503` `SERVICE_DEGRADED` sin exponer detalles.
- Ante timeout, corte de conexión, `503` o `500`, se reintenta con espera creciente y un máximo de intentos.
- `externalPatientId` es el `sub` del JWT del usuario, como texto.

#### 4.2.2 Login técnico

`POST /api/authenticate`, sin JWT. Turnos lo ejecuta al iniciar y ante cualquier `401` de la cátedra (X01). También lo ejecuta ante una falla de credenciales de Kafka (T06 5.2).

```json
{
  "username": "juan.perez",
  "password": "una-clave-segura",
  "rememberMe": true
}
```

`username` tiene entre 1 y 254 caracteres; `password`, entre 4 y 100. Para la vigencia larga se debe enviar `rememberMe: true`. Las credenciales se externalizan.

Respuesta `200`:

```json
{
  "id_token": "<jwt-tecnico-de-un-ano>",
  "integration": {
    "groupId": "proyecto-juan-perez",
    "redisHost": "redis.catedra.internal",
    "redisPort": 6379,
    "redisUsername": "grp_proyecto-juan-perez",
    "redisPassword": "<secreto-redis>",
    "redisReadNamespace": "catedra:sync:*",
    "redisWriteNamespace": "alumnos:proyecto-juan-perez:*",
    "kafkaBootstrapServers": "kafka.catedra.internal:9092",
    "kafkaConsumerGroupId": "alumnos-proyecto-juan-perez",
    "kafkaCatalogTopic": "catedra.catalog.proyecto-juan-perez",
    "kafkaAppointmentActionsTopic": "alumnos.turnos.acciones.proyecto-juan-perez",
    "kafkaAppointmentPhoneTopic": "catedra.turnos.telefono.proyecto-juan-perez",
    "provisioningStatus": "PROVISIONED"
  }
}
```

| Campo | Uso en turnos |
| --- | --- |
| `id_token` | JWT técnico para todas las llamadas REST. Solo en memoria. |
| `integration.provisioningStatus` | Debe ser `PROVISIONED`; si no, la integración no se habilita. |
| `integration.groupId` | Se envía en cada `AdditionalInformationSubmitted`. |
| `integration.kafkaBootstrapServers` | Conexión a Kafka. |
| `integration.kafkaConsumerGroupId` | Consumer group con el que se leen los resultados. |
| `integration.kafkaAppointmentActionsTopic` | Topic que turnos consume (T07, T08). |
| `integration.kafkaAppointmentPhoneTopic` | Topic donde turnos publica (T06). |

Turnos no usa Redis: ignora los campos `redis*` y `kafkaCatalogTopic`.

| Respuesta de la cátedra | Qué hace turnos |
| --- | --- |
| `401` | Credenciales incorrectas: error de configuración, no se reintenta y se informa. |
| `403` o `404` `STUDENT_INTEGRATION_NOT_FOUND` | Igual que el `401`. |
| Timeout o `503` | Reintentos limitados con espera creciente. El servicio queda degradado y no realiza operaciones que dependan de la cátedra. |
| `200` con `provisioningStatus` distinto de `PROVISIONED` | No habilita la integración; reintenta con límite. |

#### 4.2.3 Ocupaciones

Lo usa T03 (paso 5).

```http
GET /api/appointment-occupancies?professionalId=15&from=2026-10-20&to=2026-10-20
Authorization: Bearer <jwt-tecnico>
```

| Parámetro | Obligatorio | Descripción |
| --- | --- | --- |
| `professionalId` | Sí | ID del profesional. |
| `from` | Sí | Inicio inclusivo (`yyyy-MM-dd`). |
| `to` | Sí | Fin inclusivo; no puede ser anterior a `from`. Turnos envía el mismo valor que `from`. |

Respuesta `200`:

```json
{
  "professionalId": 15,
  "from": "2026-10-20",
  "to": "2026-10-20",
  "occupancies": [
    {
      "date": "2026-10-20",
      "startTime": "09:00:00",
      "status": "CONFIRMED",
      "expiresAt": null
    },
    {
      "date": "2026-10-20",
      "startTime": "09:30:00",
      "status": "HELD",
      "expiresAt": "2026-10-12T14:44:50Z"
    }
  ]
}
```

`status` es `CONFIRMED` o `HELD`; ambos se tratan como turno ocupado. `expiresAt` solo tiene valor para `HELD`. Una lista vacía es válida.

| Respuesta de la cátedra | Qué hace turnos |
| --- | --- |
| `200` | Guarda en el caché la entrada del profesional y la fecha, con `dataAsOf` (T03 6). |
| `401` | Login técnico y una sola repetición (T03 5.1). |
| `403` `STUDENT_INTEGRATION_NOT_FOUND` | Responde `503` `SERVICE_DEGRADED` (T03 5.2). |
| Timeout, corte, `503` o `500` | Reintenta. Agotados, usa el caché si existe y no superó la antigüedad máxima (`stale: true`); si no, responde `503` `CENTRAL_UNAVAILABLE` (T03 5.3). |

#### 4.2.4 Crear un hold

Lo usa T04 (paso 5).

```http
POST /api/appointment-holds
Authorization: Bearer <jwt-tecnico>
Content-Type: application/json
```

```json
{
  "professionalId": 15,
  "date": "2026-10-20",
  "startTime": "09:30",
  "externalPatientId": "1042"
}
```

Todos los campos son obligatorios. `externalPatientId` no puede estar en blanco y admite hasta 100 caracteres. La fecha, hora y profesional deben identificar un slot habilitado de la agenda.

Respuesta `201`:

```json
{
  "holdId": "hold-6c0c2f41",
  "reservationProcessId": "process-a92b47d0",
  "status": "HELD",
  "expiresAt": "2026-10-12T14:44:50Z"
}
```

El hold es temporal. La vigencia real se determina por `expiresAt`; una copia local no extiende el TTL.

| Respuesta de la cátedra | Qué hace turnos |
| --- | --- |
| `201` | Crea el proceso local en `HELD` (T04 7). |
| `401` | Login técnico y una sola repetición (T04 5.1). |
| `403` `STUDENT_INTEGRATION_NOT_FOUND` | Responde `503` `SERVICE_DEGRADED` (T04 5.2). |
| `404` `PROFESSIONAL_NOT_FOUND` | Responde `404` `PROFESSIONAL_NOT_FOUND` (T04 5.3). |
| `400` `PROFESSIONAL_DISABLED` | Responde `409` `PROFESSIONAL_DISABLED` (T04 5.4). |
| `400` `INVALID_SLOT` | Responde `400` `INVALID_SLOT` (T04 5.5). |
| `409` `SLOT_ALREADY_RESERVED` o `SLOT_ALREADY_HELD` | Responde `409` `SLOT_NOT_AVAILABLE` (T04 5.6 y 5.7). |
| Timeout, corte, `503` o `500` | Reintenta. Agotados, responde `503` `CENTRAL_UNAVAILABLE` y no crea ningún proceso (T04 5.8). |

#### 4.2.5 Confirmar un hold

Lo usa T05 (paso 4).

```http
POST /api/appointment-holds/{holdId}/confirm
Authorization: Bearer <jwt-tecnico>
Content-Type: application/json
```

```json
{
  "externalPatientId": "1042",
  "patientFirstName": "María",
  "patientLastName": "Gómez"
}
```

| Campo | Obligatorio | Restricciones |
| --- | --- | --- |
| `externalPatientId` | Sí | No blanco, máximo 100, debe coincidir con el hold. |
| `patientFirstName` | Sí | No blanco, 2 a 100. Turnos envía el `firstName` del perfil del usuario. |
| `patientLastName` | Sí | No blanco, 2 a 100. Turnos envía el `lastName` del perfil del usuario. |

Respuesta `202 Accepted`:

```json
{
  "reservationProcessId": "process-a92b47d0",
  "status": "WAITING_FOR_PHONE",
  "expiresAt": "2026-10-12T14:44:50Z"
}
```

La cátedra crea una reserva `PHONE_PENDING` y publica `AdditionalInformationRequested` (4.3). El proceso debe completarse antes de `expiresAt`.

| Respuesta de la cátedra | Qué hace turnos |
| --- | --- |
| `202` | Pasa el proceso local a `WAITING_FOR_PHONE` con el nuevo `expiresAt` (T05 6). |
| `401` | Login técnico y una sola repetición (T05 4.1). |
| `403` `STUDENT_INTEGRATION_NOT_FOUND` | Responde `503` `SERVICE_DEGRADED` (T05 4.2). |
| `404` `HOLD_NOT_FOUND` | Responde `404` `HOLD_NOT_FOUND` (T05 4.3). |
| `404` `HOLD_EXPIRED` | Marca el proceso `EXPIRED` y responde `409` `HOLD_EXPIRED` (T05 4.4). |
| `403` `HOLD_OWNER_MISMATCH` | No debería ocurrir: se registra como error grave y se responde `500` `INTERNAL_ERROR` (T05 4.5). |
| `400` `EXTERNAL_PATIENT_MISMATCH` | Responde `409` `PATIENT_MISMATCH` (T05 4.6). |
| `409` `RESERVATION_PROCESS_ALREADY_CONFIRMED` | No es un error: actualiza el proceso local si estaba desactualizado y responde `200` con el estado actual (T05 4.7). |
| `404` `PROFESSIONAL_NOT_FOUND`, `400` `INVALID_SLOT` o `409` `SLOT_ALREADY_RESERVED` | Responde `404` `PROFESSIONAL_NOT_FOUND`, `400` `INVALID_SLOT` o `409` `SLOT_NOT_AVAILABLE`, respectivamente (T05 4.8). |
| Timeout, corte, `503` o `500` | Reintenta. Agotados, responde `503` `CENTRAL_UNAVAILABLE`, el proceso sigue en `HELD` y se marca para reconciliar (T05 4.9). |

#### 4.2.6 Listar reservas

Lo usa solo T13 (paso 4), para reconciliar.

```http
GET /api/appointments?status=CONFIRMED&from=2026-10-01&to=2026-10-31&externalPatientId=1042&page=0&size=20
Authorization: Bearer <jwt-tecnico>
```

Filtros opcionales y combinables:

- `status`: `PHONE_PENDING`, `CONFIRMED`, `CANCELLED` o `FAILED`.
- `from`: fecha mínima inclusiva.
- `to`: fecha máxima inclusiva.
- `externalPatientId`: coincidencia exacta.
- `page`, `size`, `sort`: paginación Spring/JHipster.

El orden predeterminado es fecha de turno descendente y hora descendente. La respuesta incluye `X-Total-Count` y `Link`.

Respuesta `200`:

```json
[
  {
    "reservationId": 8251,
    "reservationProcessId": "process-a92b47d0",
    "externalPatientId": "1042",
    "appointmentDate": "2026-10-20",
    "startTime": "09:30:00",
    "endTime": "10:00:00",
    "professionalId": 15,
    "professionalFirstName": "Ana",
    "professionalLastName": "Gomez",
    "patientFirstName": "María",
    "patientLastName": "Gómez",
    "patientPhone": "+5492615551234",
    "status": "CONFIRMED",
    "createdAt": "2026-10-12T14:35:00Z",
    "confirmedAt": "2026-10-12T14:36:00Z",
    "cancelledAt": null,
    "cancellationReason": null
  }
]
```

La cátedra devuelve las reservas de toda la cuenta técnica. Turnos filtra por el `externalPatientId` del usuario dueño del proceso y por un rango de fechas que incluya la del turno, y nunca expone reservas ajenas.

| Estado en la cátedra | Estado local |
| --- | --- |
| `CONFIRMED` | `CONFIRMED`, con `reservationId` y `confirmedAt` |
| `CANCELLED` | `CANCELLED` |
| `PHONE_PENDING` | `WAITING_FOR_PHONE`, salvo que el teléfono ya se haya informado localmente |
| `FAILED` | `EXPIRED` |
| Sin registro para el `reservationProcessId` | Se deja abierto si está en `HELD` y no venció, con el margen de seguridad; en otro caso, `EXPIRED` (T13 6) |

| Respuesta de la cátedra | Qué hace turnos |
| --- | --- |
| `401` | Login técnico y una sola repetición (T13 4.1). |
| Timeout, corte o `503` | Reintenta. Agotados, el proceso queda pendiente para el siguiente ciclo, sin cambios (T13 4.2). |

#### 4.2.7 Cancelar una reserva

Lo usa T11 (paso 6).

```http
POST /api/appointments/{reservationProcessId}/cancel
Authorization: Bearer <jwt-tecnico>
Content-Type: application/json
```

El body es opcional. `reason` admite hasta 500 caracteres.

```json
{
  "reason": "Cambio solicitado por el usuario"
}
```

Respuesta `200`:

```json
{
  "reservationId": 8251,
  "reservationProcessId": "process-a92b47d0",
  "status": "CANCELLED",
  "cancelledAt": "2026-10-13T15:10:00Z"
}
```

La cancelación de una reserva ya `CANCELLED` es idempotente y devuelve su estado actual. La operación publica `AppointmentCancelled` (4.3).

Nota: el anexo (§13.1) indica que solo se puede cancelar una reserva en `CONFIRMED` o `CANCELLED`. El profesor confirmó que este endpoint también acepta un proceso en `HELD` o `WAITING_FOR_PHONE`. En ese caso `reservationId` puede faltar, porque la reserva todavía no se confirmó.

| Respuesta de la cátedra | Qué hace turnos |
| --- | --- |
| `200` | Pasa el proceso local a `CANCELLED` con `cancelledAt` y el motivo (T11 8). |
| `401` | Login técnico y una sola repetición (T11 6.1). |
| `403` `STUDENT_INTEGRATION_NOT_FOUND` | Responde `503` `SERVICE_DEGRADED` (T11 6.2). |
| `404` `RESERVATION_NOT_FOUND` | Inconsistencia: se registra, se marca para reconciliar y se responde `500` `INTERNAL_ERROR` (T11 6.3). |
| `403` `RESERVATION_OWNER_MISMATCH` | No debería ocurrir: se registra como error grave y se responde `500` `INTERNAL_ERROR` (T11 6.4). |
| `409` `RESERVATION_NOT_CANCELLABLE` | Inconsistencia de estado con la cátedra: se registra, se marca para reconciliar y se responde `409` `RESERVATION_NOT_CANCELLABLE` (T11 6.5). |
| Timeout, corte, `503` o `500` | Reintenta. Agotados, responde `503` `CENTRAL_UNAVAILABLE`; el estado local no cambia y se marca para reconciliar (T11 6.6). |

### 4.3 Servicio de la cátedra: Kafka


| Evento | Topic | Dirección | Caso |
| --- | --- | --- | --- |
| `AdditionalInformationRequested` | `alumnos.turnos.acciones.{groupId}` | Cátedra a turnos | T07 |
| `AppointmentConfirmed`, `AdditionalInformationRejected`, `AppointmentProcessExpired`, `AppointmentProcessInvalid`, `AppointmentCancelled` | `alumnos.turnos.acciones.{groupId}` | Cátedra a turnos | T08 |
| `AdditionalInformationSubmitted` | `catedra.turnos.telefono.{groupId}` | Turnos a cátedra | T06 |

Los nombres concretos de los topics y el consumer group salen de `integration` (4.2.2).

#### 4.3.1 Reglas comunes

- `schemaVersion` vale `1`. Un mensaje con otra versión se descarta.
- Dentro de v1 no se eliminan campos ni se cambia su tipo o significado. Pueden agregarse campos opcionales y turnos ignora los que no conoce.
- La entrega es al menos una vez: pueden llegar mensajes duplicados. `eventId` es la clave de idempotencia.
- La message key de los eventos de turnos es `reservationProcessId`. Los eventos de un mismo proceso conservan el orden dentro de una partición.
- El topic de acciones transporta seis tipos de evento. Turnos los distingue por `eventType`: `AdditionalInformationRequested` va a T07 y los otros cinco a T08. Un `eventType` desconocido se descarta, se registra y se confirma el offset.
- El offset se confirma después de guardar el cambio de estado y de registrar el `eventId` como procesado, en la misma transacción local. Si la persistencia falla tras los reintentos, no se confirma y Kafka reentrega el mensaje.

#### 4.3.2 `AdditionalInformationRequested`

Topic de acciones. Lo procesa T07.

```json
{
  "eventId": "request-event-8a31",
  "eventType": "AdditionalInformationRequested",
  "reservationProcessId": "process-a92b47d0",
  "holdId": "hold-6c0c2f41",
  "requiredField": "PHONE_NUMBER",
  "message": "Ingrese un numero de telefono para continuar.",
  "expiresAt": "2026-10-12T14:44:50Z",
  "occurredAt": "2026-10-12T14:35:10Z",
  "schemaVersion": 1
}
```

Debe conservarse `eventId` para enviarlo como `requestEventId` en la respuesta (4.3.3). Turnos usa `eventId`, `reservationProcessId`, `holdId`, `requiredField` (debe ser `PHONE_NUMBER`), `expiresAt` y `schemaVersion`. No usa `message`.

| Caso | Qué hace turnos |
| --- | --- |
| Mensaje válido y proceso en `HELD` o `WAITING_FOR_PHONE` | Guarda el `eventId` como `requestEventId` y actualiza el `expiresAt` del proceso. Si estaba en `HELD`, lo pasa a `WAITING_FOR_PHONE`. Registra el `eventId` y confirma el offset (T07 5 a 7). |
| Faltan campos requeridos, `schemaVersion` desconocida o `requiredField` distinto de `PHONE_NUMBER` | Descarta, registra y confirma el offset (T07 2.1). |
| `eventId` ya procesado | Sin efectos; confirma el offset (T07 3.1). |
| No existe un proceso con ese `reservationProcessId` | Evento huérfano: registra y confirma el offset (T07 4.1). |
| El `holdId` no coincide con el del proceso | Inconsistencia: registra y confirma el offset (T07 4.2). |
| El proceso no está en `HELD` ni en `WAITING_FOR_PHONE` | Registra y confirma el offset (T07 5.1). |
| Falla la persistencia | Reintenta con espera creciente. Agotados, registra y no confirma el offset (T07 6.1). |

#### 4.3.3 `AdditionalInformationSubmitted`

Topic de teléfono. Lo publica T06. Message key: `reservationProcessId`.

```json
{
  "eventId": "phone-submitted-b91e",
  "eventType": "AdditionalInformationSubmitted",
  "groupId": "proyecto-juan-perez",
  "reservationProcessId": "process-a92b47d0",
  "holdId": "hold-6c0c2f41",
  "requestEventId": "request-event-8a31",
  "information": {
    "phoneNumber": "+5492615551234"
  },
  "occurredAt": "2026-10-12T14:36:00Z",
  "schemaVersion": 1
}
```

Todos los campos son obligatorios. `eventType` y `schemaVersion` deben tener exactamente los valores mostrados. La message key debe coincidir con `reservationProcessId`.

| Campo | Valor que pone turnos |
| --- | --- |
| `eventId` | Único en cada envío. Un nuevo intento tras un rechazo lleva un `eventId` nuevo. |
| `groupId` | El de `integration` (4.2.2). |
| `reservationProcessId`, `holdId` | Los del proceso local. |
| `requestEventId` | El guardado por T07. No cambia entre intentos. |
| `information.phoneNumber` | El teléfono ya normalizado. |
| `occurredAt` | Instante de la publicación, en UTC. |

Antes de validar, la cátedra elimina espacios, guiones, paréntesis y puntos. El resultado debe cumplir `^\+?[0-9]{7,15}$`. Turnos aplica la misma regla antes de publicar.

| Caso | Qué hace turnos |
| --- | --- |
| Publicado | Guarda el teléfono y pasa el proceso a `PHONE_SUBMITTED` (T06 6). |
| Kafka no está disponible (timeout, corte, broker caído) | Reintenta con espera creciente. Agotados, registra, marca el proceso para reconciliar y responde `503` `CENTRAL_UNAVAILABLE` (T06 5.1). |
| Kafka rechaza las credenciales | Ejecuta el login técnico (4.2.2) y reintenta una vez. Si persiste, lo trata como el caso anterior (T06 5.2). |

#### 4.3.4 Eventos de resultado

Los cinco llegan por el topic de acciones, con la message key `reservationProcessId`, y los procesa T08.

##### `AppointmentConfirmed`

```json
{
  "eventId": "confirmation-event-7d10",
  "eventType": "AppointmentConfirmed",
  "groupId": "proyecto-juan-perez",
  "reservationProcessId": "process-a92b47d0",
  "holdId": "hold-6c0c2f41",
  "requestEventId": "request-event-8a31",
  "reservationId": 8251,
  "professionalId": 15,
  "date": "2026-10-20",
  "startTime": "09:30",
  "status": "CONFIRMED",
  "occurredAt": "2026-10-12T14:36:01Z",
  "schemaVersion": 1
}
```

##### `AdditionalInformationRejected`

```json
{
  "eventId": "rejection-event-7d11",
  "eventType": "AdditionalInformationRejected",
  "groupId": "proyecto-juan-perez",
  "reservationProcessId": "process-a92b47d0",
  "holdId": "hold-6c0c2f41",
  "requestEventId": "request-event-8a31",
  "reason": "INVALID_PHONE_NUMBER",
  "message": "El telefono ingresado no es valido.",
  "expiresAt": "2026-10-12T14:44:50Z",
  "occurredAt": "2026-10-12T14:36:01Z",
  "schemaVersion": 1
}
```

El proceso continúa disponible hasta `expiresAt`; puede enviarse otro evento válido con un `eventId` nuevo (4.3.3).

##### `AppointmentProcessExpired`

```json
{
  "eventId": "process-event-7d12",
  "eventType": "AppointmentProcessExpired",
  "groupId": "proyecto-juan-perez",
  "reservationProcessId": "process-a92b47d0",
  "holdId": "hold-6c0c2f41",
  "requestEventId": "request-event-8a31",
  "reason": "HOLD_EXPIRED",
  "occurredAt": "2026-10-12T14:44:52Z",
  "schemaVersion": 1
}
```

##### `AppointmentProcessInvalid`

```json
{
  "eventId": "process-event-7d13",
  "eventType": "AppointmentProcessInvalid",
  "groupId": "proyecto-juan-perez",
  "reservationProcessId": "process-a92b47d0",
  "holdId": "hold-6c0c2f41",
  "requestEventId": "request-event-8a31",
  "reason": "REQUEST_EVENT_MISMATCH",
  "occurredAt": "2026-10-12T14:36:01Z",
  "schemaVersion": 1
}
```

`reason` describe la inconsistencia detectada. El cliente debe tratar el proceso como inválido y reconciliarlo mediante REST si necesita confirmar el estado persistido (4.2.6).

##### `AppointmentCancelled`

```json
{
  "eventId": "cancellation-event-7d14",
  "eventType": "AppointmentCancelled",
  "groupId": "proyecto-juan-perez",
  "reservationProcessId": "process-a92b47d0",
  "holdId": "hold-6c0c2f41",
  "requestEventId": "request-event-8a31",
  "reservationId": 8251,
  "professionalId": 15,
  "date": "2026-10-20",
  "startTime": "09:30",
  "status": "CANCELLED",
  "reason": "Cambio solicitado por el usuario",
  "occurredAt": "2026-10-13T15:10:00Z",
  "schemaVersion": 1
}
```

`reason` puede ser `null`. Turnos no exige `requestEventId` ni `reservationId`, que pueden faltar si el proceso se canceló antes de confirmarse.

##### Efecto de cada evento

| Evento | Efecto local |
| --- | --- |
| `AppointmentConfirmed` | Crea o actualiza la reserva confirmada con `reservationId`, `professionalId`, `date` y `startTime`. Guarda `occurredAt` como `confirmedAt` y pasa el proceso a `CONFIRMED`. |
| `AdditionalInformationRejected` | Vuelve el proceso a `WAITING_FOR_PHONE`, actualiza `expiresAt` y guarda `reason` como `rejectionReason`. No usa `message`. El `requestEventId` guardado no cambia y sigue siendo válido para el próximo intento (T06). |
| `AppointmentProcessExpired` | Pasa el proceso a `EXPIRED`. |
| `AppointmentProcessInvalid` | Pasa el proceso a `INVALID` y lo marca para reconciliar (T13). |
| `AppointmentCancelled` | Pasa la reserva o el proceso a `CANCELLED`. Guarda `reason` como `cancellationReason` y `occurredAt` como `cancelledAt`. |

##### Casos comunes de T08

| Caso | Qué hace turnos |
| --- | --- |
| Faltan campos requeridos según el tipo, `schemaVersion` desconocida o `eventType` no reconocido | Descarta, registra y confirma el offset (T08 2.1). |
| `eventId` ya procesado | Sin efectos; confirma el offset (T08 3.1). |
| No existe un proceso con ese `reservationProcessId` | Evento huérfano: registra y confirma el offset (T08 4.1). |
| El `holdId` no coincide con el del proceso | Inconsistencia: registra y confirma el offset (T08 4.2). |
| El proceso ya está en un estado final (`CONFIRMED`, `EXPIRED`, `CANCELLED` o `INVALID`) | No lo reabre: ignora el efecto, registra el `eventId` y confirma el offset (T08 5.1). Excepción: `AppointmentCancelled` sobre un proceso `CONFIRMED` sí se aplica. |
| Falla la persistencia | Reintenta con espera creciente. Agotados, registra y no confirma el offset (T08 5.2). |
