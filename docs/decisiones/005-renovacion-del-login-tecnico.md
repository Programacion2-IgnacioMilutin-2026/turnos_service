# 005 — Cuándo se renueva el login técnico y cómo se protege a la cátedra

- **Fecha:** 2026-10-21
- **Funcionalidad:** X01 — Autenticar cuenta técnica y obtener configuración (`docs/sdd/x01-cuenta-tecnica/`)
- **Estado:** aceptada

## Situación

El servicio de turnos necesita el JWT técnico de la cátedra para todas las operaciones de T03 en adelante. X01 lo obtiene al arrancar y dice que el login se repite cada vez que la cátedra rechaza el JWT con un `401` en otro caso de uso. El contrato de turnos (§4.2.1) agrega que, tras ese nuevo login, la operación se repite una sola vez.

## Problema

Tomado literalmente, "repetir el login ante cada `401`" tiene dos riesgos:

- **Logins que no sirven.** Si el problema son las credenciales mal configuradas o una integración inexistente, ningún login nuevo lo arregla: la cátedra va a responder siempre lo mismo.
- **Saturar a la cátedra.** Si la integración no está disponible y cada request de los usuarios dispara su propio login, la cátedra recibe una avalancha de logins justo cuando tiene problemas.

Además, no todo `401` significa lo mismo. Un `401` en una operación indica que el JWT guardado ya no sirve, y un login nuevo lo resuelve. Un `401` en el propio login indica credenciales incorrectas, y no se resuelve repitiéndolo.

## Decisión

La integración tiene tres estados, y cada uno define si se puede llamar a la cátedra:

| Estado | Cómo se llega | Qué pasa con los pedidos de login |
| --- | --- | --- |
| Habilitada | Login con `provisioningStatus` `PROVISIONED` | Los casos de uso usan el JWT guardado, sin logins extra. Solo un `401` en una operación pide un login nuevo, con el mismo intervalo mínimo que en el estado degradado |
| Error de configuración | `401` en el login, `403`/`404` `STUDENT_INTEGRATION_NOT_FOUND`, o propiedades faltantes | No se envía ningún login más hasta reiniciar el servicio. Los pedidos se responden al instante, sin llamar a la cátedra |
| Degradada | Reintentos agotados por timeout, `503` o `500`, o un estado distinto de `PROVISIONED` | Un pedido dispara un login solo si pasó el intervalo mínimo desde el último intento (60 segundos por defecto, configurable). Antes, se responde al instante |

En todos los casos, nunca hay dos logins simultáneos: los pedidos que llegan mientras hay uno en curso esperan su resultado. Y si el JWT que rechazó la cátedra ya no es el vigente, porque otro pedido lo renovó, se devuelve el vigente sin otro login. El intervalo mínimo vale también con la integración habilitada: así, aunque la cátedra rechazara cada JWT nuevo, no habría un login por request. Un `401` legítimo no lo nota, porque el JWT técnico dura un año. Así, la carga sobre la cátedra queda acotada a un login por intervalo, sin importar el tráfico del servicio. Es el patrón conocido como *circuit breaker*.

Alternativas descartadas:

- Reintentar el login en cada pedido: satura a la cátedra y no resuelve los errores de configuración.
- Reintentos periódicos en segundo plano: generan tráfico aunque nadie necesite la integración.

## Qué no cambia

Repetir la operación que falló, una sola vez, sigue siendo responsabilidad de cada caso de uso que llama a la cátedra (desde T03), como dice el contrato §4.2.1. Eso evita bucles: si la operación vuelve a recibir un `401` después de un login exitoso, no se repite de nuevo. T01 y T02 no dependen de la cátedra y funcionan en cualquiera de los tres estados.
