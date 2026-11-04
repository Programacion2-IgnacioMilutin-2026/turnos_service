# 015 — Un proceso en curso se abandona solo en turnos, sin avisar a la cátedra

- **Fecha:** 2026-11-04
- **Funcionalidad:** T11 — Cancelar reserva (`docs/sdd/t11-cancelar-reserva/`, decisiones D1 y D2 de la SPEC, propuestas por el usuario)
- **Estado:** aceptada

## Situación

Los documentos decían que T11 le pedía a la cátedra cancelar también un proceso en `HELD` o `WAITING_FOR_PHONE`, para que el usuario pudiera iniciar otro. La nota del contrato §4.2.7 decía que el profesor lo había confirmado.

La cátedra no lo permite: el anexo §13.1 solo acepta cancelar `CONFIRMED` o `CANCELLED`, y la cátedra real respondió `404` `RESERVATION_NOT_FOUND` en `HELD` y `409` `RESERVATION_NOT_CANCELLABLE` en `WAITING_FOR_PHONE`. No hay respuesta del profesor sobre esa diferencia.

## Problema

Turnos permite un solo proceso abierto por usuario (decisión 012). Si el usuario no puede abandonar uno, T04 le responde `OPEN_PROCESS_EXISTS` hasta que venza: unos 10 minutos en los que no puede elegir otro turno, aunque se haya arrepentido o el bloqueo haya salido mal.

## Decisión

Un proceso en `HELD` o `WAITING_FOR_PHONE` pasa a `CANCELLED` **solo en turnos**, sin llamar a la cátedra, con la fecha actual y el motivo. Una reserva `CONFIRMED` se sigue cancelando en la cátedra.

**Por qué alcanza.** El límite de un proceso abierto es de turnos: el anexo solo rechaza un hold si ese mismo turno ya está tomado, y no limita por paciente. Cuando el hold de la cátedra vence, los dos lados dicen lo mismo: el turno está libre y el proceso terminó sin reserva. Para el usuario no hay diferencia, porque un proceso cancelado sin confirmar no se lista en T10.

**Un proceso vencido se abandona igual (D2).** No se compara el vencimiento: el usuario pidió cancelar, y el resultado es el mismo que con `EXPIRED`: no se lista y puede bloquear otro turno. Se pierde que ese proceso figure como `EXPIRED`.

## El límite: un proceso marcado para reconciliar no se abandona

Responde `409` `INVALID_PROCESS_STATE`, y el usuario espera a que venza.

El caso que evita:

1. T06 publica el teléfono, la publicación da timeout, y el proceso vuelve a `WAITING_FOR_PHONE` con la marca. Un timeout no prueba que el mensaje no haya salido.
2. El usuario abandona el proceso.
3. El mensaje llega igual a la cátedra y confirma una reserva real.
4. T08 ignora esa confirmación, porque el proceso ya terminó, y solo la registra como error.

Quedaría una reserva confirmada que el usuario no ve y que nadie cancela, con el turno ocupado. Por eso la marca lo impide: significa que no se sabe qué tiene la cátedra. También alcanza a un `HELD` marcado por una confirmación dudosa de T05, donde el riesgo es menor, para tener una sola regla.

## Qué se acepta

- **El turno sigue tomado hasta que venza el hold.** Durante esos minutos no figura libre, ni para este usuario ni para otros. Pasaría igual si el usuario esperara a que venza.
- **La cátedra y turnos difieren hasta entonces.** La cátedra dice `HELD` o `WAITING_FOR_PHONE`, y turnos dice `CANCELLED`. Un evento tardío de ese proceso no lo reabre (T08).
- **Un proceso sin marca no puede confirmarse después.** Sin un teléfono publicado, a la cátedra no le llega nada que lo confirme.

## Alternativas descartadas

- **Esperar a que venza:** es lo que permite la cátedra sin estados propios, pero deja al usuario trabado hasta 10 minutos por una limitación que no es de la cátedra.
- **Llamar igual a la cátedra:** hoy siempre falla, y cada intento daría un error al usuario y dejaría el proceso marcado.

## Qué se comprobó y qué falta

- **Comprobado contra la cátedra real (2026-11-04, E2E corrido por el usuario):** después de abandonar un hold, aceptó otro hold de otro turno con el primero activo, y rechazó ese primer turno para otro usuario hasta que venza.
- Si el profesor responde que la cátedra acepta cancelar un proceso en curso, esta decisión se revisa.

## Dónde está en el código

- **La regla:** `ReservationProcess.canBeAbandoned` y `abandon`, en `reservation/domain/model`.
- **El flujo:** `CancelReservationProcessUseCaseImpl`, en `reservation/application/usecases`: abandona sin llamar a la cátedra, y solo un `CONFIRMED` llega a `AppointmentCanceller`.
- **La cátedra:** `CancelAppointmentUseCase` y `CatedraAppointmentClient`, en `catedra`, con su adaptador. Son piezas propias de "appointments", no de holds (P1 del PLAN de T11).
- **Las pruebas:** los tests del caso de uso y de integración de T11, y el E2E que bloquea, abandona y bloquea otro turno.
