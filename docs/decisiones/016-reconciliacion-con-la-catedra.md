# 016 — Cómo turnos reconcilia sus procesos con la cátedra

- **Fecha:** 2026-11-06
- **Funcionalidad:** T13 — Reconciliar y recuperar procesos pendientes (`docs/sdd/t13-reconciliar-procesos/`, decisiones D1 a D10 de la SPEC y P1 a P4 del PLAN, todas elegidas por el usuario)
- **Estado:** aceptada; implementación en curso

## Situación

Turnos guarda una copia de cada proceso de reserva, pero la fuente de verdad es la cátedra (dominio regla 15). Casi siempre las dos coinciden, porque turnos cambia su copia con lo que la cátedra responde o publica por Kafka. A veces no puede saber qué pasó del otro lado, y entonces enciende la marca `needsReconciliation`:

| Quién marca | Cuándo | Estado que queda |
| --- | --- | --- |
| T05 (confirmar) | La confirmación dio timeout o la respuesta se perdió | `HELD` |
| T06 (teléfono) | La publicación en Kafka falló: el teléfono pudo haber salido igual | `WAITING_FOR_PHONE` |
| T08 (resultado) | La cátedra informó el proceso como inválido | `INVALID` |
| T09 y T12 (vencimiento) | Venció con el teléfono enviado: la cátedra pudo haber confirmado | `PHONE_SUBMITTED` |
| T11 (cancelar) | La cancelación dio timeout, `404` o `409`, o la cátedra canceló y turnos no lo pudo guardar | `CONFIRMED` |

Además, un reinicio a mitad de una operación puede dejar un proceso abierto sin marca y desactualizado; por ejemplo, un `PHONE_SUBMITTED` guardado cuyo mensaje nunca se publicó (decisión 014 §3.15).

## Problema

Hasta T13 nadie resolvía la marca. Un `PHONE_SUBMITTED` marcado no se cerraba nunca y su usuario recibía `OPEN_PROCESS_EXISTS` al intentar otro turno; una reserva que la cátedra canceló podía seguir `CONFIRMED` en turnos. Hacía falta una tarea que le preguntara a la cátedra el estado real y corrigiera la copia, sin pisar lo que el usuario o un evento hagan mientras tanto.

## Decisión

### Cuándo corre

Una sola tarea programada con espera fija: la primera ejecución, al arrancar, y después cada minuto desde que termina la anterior (D8). Nunca corren dos a la vez. Tiene su propio hilo, para que una cátedra lenta no demore al barrido de T12.

La primera ejecución es especial: revisa también los procesos abiertos sin marca, que pudieron quedar a mitad de camino. Si la cátedra no contesta por todos, esa revisión se repite en cada ciclo hasta completarla; se recuerda en memoria, porque un reinicio dispara otra ejecución de arranque (D3). Como el login técnico inicial empieza después de la primera ejecución, la consulta de T13 espera a que termine, con un tope de 2 minutos (P1). Las operaciones de los usuarios no esperan: siguen respondiendo enseguida "no disponible" durante el arranque.

### T12 y T13 trabajan en cadena

T12 decide solo con el reloj local: cierra lo que esperaba al usuario y marca lo que tiene el teléfono enviado, porque eso no lo puede decidir. T13 resuelve lo marcado preguntándole a la cátedra. Van separadas porque T13 depende de la cátedra y T12 no: si fueran una, una cátedra caída frenaría también los cierres por vencimiento. Un ciclo de T13 sin procesos marcados no llama a la cátedra.

### Cómo consulta

Para cada proceso pide el listado de reservas filtrado **solo por el paciente** (el id del usuario como texto), recorre todas las páginas y toma el registro con el mismo `reservationProcessId` y el mismo paciente (D1).

Los documentos decían filtrar también por un rango de fechas, pero la cátedra real responde `500` a todo listado con `from` y `to`; sin fechas funciona. El anexo dice que los filtros son opcionales y combinables, así que se eligió un uso válido del anexo que se puede verificar. Costo: trae todas las reservas del usuario y no solo las de la fecha.

El slice `catedra` recorre las páginas, elige el registro y valida solo ese (P3). Los nombres y teléfonos de otros registros nunca salen de `catedra`, y un registro mal formado de otro proceso no afecta al buscado.

### Qué hace con la respuesta

La tabla vive en el modelo (`ReservationProcess`), como las demás reglas del proceso (P2).

**Proceso abierto** (contrato §4.2.6):

| La cátedra dice | Resultado |
| --- | --- |
| `CONFIRMED` | `CONFIRMED`, con `reservationId`, `confirmedAt`, profesional, fecha y hora de la cátedra; warning si el turno difiere (D10, como T08) |
| `CANCELLED` | `CANCELLED`, con fecha y motivo |
| `PHONE_PENDING` | `WAITING_FOR_PHONE`, conservando su vencimiento. Con el teléfono ya enviado sigue `PHONE_SUBMITTED` y conserva la marca: se vuelve a consultar hasta que la cátedra diga `CONFIRMED` o `FAILED` (D4) |
| `FAILED` | `EXPIRED` |
| Sin registro | Un `HELD` no vencido sigue abierto y pierde la marca: la confirmación dudosa no llegó (D5). Cualquier otro, `EXPIRED` |

**Proceso cerrado y marcado** (`CONFIRMED` de T11, `INVALID` de T08): la cátedra manda solo hacia un estado final, y nunca se reabre (D2). `CONFIRMED` y `CANCELLED` se aplican, `FAILED` lo pasa a `EXPIRED`, y sin registro o con `PHONE_PENDING` queda como está, pierde la marca y deja un error en el log. Reabrirlo chocaría con el único proceso abierto por usuario (decisión 012) y volvería atrás un estado final (dominio regla 6).

### Convivencia con el usuario y con Kafka

La consulta a la cátedra se hace **sin** el candado del usuario. Después se toma el candado, se vuelve a leer el proceso y el resultado se aplica solo si el estado y la marca siguen como antes de la llamada; si cambiaron, porque llegó un evento o el usuario hizo algo, se descarta y queda registrado (D6). Así nadie espera a la cátedra por T13, ni el usuario ni el consumidor de Kafka, que es un solo hilo para todos. Es el mismo criterio de T09 y T12 (decisión 013).

### Fallas

- **Generales** (cátedra no disponible después de los reintentos, error de configuración, JWT rechazado después de renovarlo): se corta el ciclo. El proceso y los que faltan quedan como estaban, con una sola línea de log. Cuentan como no disponible el timeout, el corte, `500` y `503` (D7).
- **De un proceso** (respuesta mal formada, `4xx` inesperado, falla al guardar): se registra, ese proceso queda para el ciclo siguiente y se sigue con los demás.

Cada proceso deja una línea de log con su resultado, y el ciclo una con los totales. El log nunca lleva nombre ni teléfono del paciente.

## Qué se acepta

- Un cerrado marcado sin registro o con `PHONE_PENDING` no se corrige: queda a la vista en el log, como un `EXPIRED` que la cátedra confirmó tarde (D6 de T08).
- Un proceso cerrado sin marca no se revisa nunca, como un abandonado (decisión 015).
- Con la cátedra caída, el usuario con un `PHONE_SUBMITTED` vencido sigue recibiendo `OPEN_PROCESS_EXISTS` hasta que vuelva.
- Una sola instancia del servicio (decisión 013).

## Alternativas descartadas

- Filtrar por paciente y fechas, como decían los documentos: con la cátedra real, dejaría todo pendiente.
- Reconciliar solo los abiertos: una reserva cancelada en la cátedra seguiría `CONFIRMED` para siempre.
- Tomar el candado durante la llamada: con la cátedra lenta frenaría al usuario y a todos los eventos.
- Seguir con todos los procesos cuando la cátedra está caída: el ciclo duraría minutos y repetiría la falla en el log.
- Dos disparadores (arranque y periódico) con una guarda: más código, y la guarda haría falta justo porque pueden solaparse.
- Aceptar que la primera ejecución falle por el login inicial: la reconciliación real llegaría un minuto tarde en cada arranque.

## Qué se comprobó y qué falta

Con la cátedra real (2026-11-05, scripts corridos por el usuario): el listado con fechas responde `500`; sin fechas funciona por paciente, por estado y paginado; una reserva recién confirmada figura de inmediato en `PHONE_PENDING` y una vencida sin teléfono, en `FAILED`; los holds no aparecen. Las respuestas están grabadas en `src/test/resources/catedra/appointments-*.json`.

No se puede producir un `CONFIRMED` ni un `CANCELLED` en la cátedra real sin enviar el teléfono por Kafka: esos casos se arman a mano según el contrato.

## Dónde está en el código

Se completa al terminar la implementación (TASK-10 de T13).
