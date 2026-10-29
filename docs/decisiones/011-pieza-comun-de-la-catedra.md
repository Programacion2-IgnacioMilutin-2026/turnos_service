# 011 — Pieza común para las operaciones con la cátedra y paquete `application/support`

- **Fecha:** 2026-10-26
- **Funcionalidad:** T04 — Bloquear turno (`docs/sdd/t04-bloquear-turno/`, decisiones P2 y P3 del PLAN y D1 de la SPEC)
- **Estado:** aceptada

## Situación

En T03, la primera operación con la cátedra (consultar ocupaciones) tiene adentro tres reglas que valen para cualquier llamada a la cátedra:

1. **Sesión:** si la integración está en error de configuración, no se llama; si está degradada, se pide un login antes (decisión 005).
2. **`401`:** se pide un login nuevo y la llamada se repite una sola vez.
3. **Reintentos:** si la cátedra no está disponible, se reintenta con espera creciente y un máximo de intentos.

La decisión 009 ya anticipaba que, cuando apareciera una segunda operación, esa lógica se iba a extraer a una pieza común de `catedra`. T04 trae esa segunda operación: crear un hold. Después vendrán confirmarlo (T05), cancelar (T11) y listar reservas (T13).

## Problema

Había que resolver tres cosas:

- **Cómo compartir las reglas** sin copiarlas en cada caso de uso.
- **Cómo distinguir un caso nuevo.** Crear un hold es la primera operación que crea datos en la cátedra. Si la respuesta se pierde (timeout de respuesta, corte durante la respuesta o `500`), no se sabe si el hold se creó, y reintentar podría chocar con el propio hold. Por eso D1 de la SPEC de T04 decidió no reintentar en ese caso. La pieza común tiene que saber cuándo es seguro reintentar y cuándo no.
- **Dónde vive la pieza.** La skill de arquitectura hexagonal solo prevé, en `application`, los casos de uso, el service y las excepciones.

## Decisión

**Una respuesta común para todos los clientes de la cátedra.** Cada cliente HTTP de la cátedra hace un solo intento y devuelve el mismo tipo de respuesta, con estos casos:

| Caso | Significa | Qué hace la pieza común |
| --- | --- | --- |
| `Answered` | La cátedra respondió algo definitivo, con el contenido propio de la operación (por ejemplo, el hold concedido o "turno ya tomado") | Lo devuelve |
| `TokenRejected` | `401`: la cátedra rechazó el JWT técnico sin procesar el pedido | Pide un login nuevo y repite una sola vez |
| `ConfigurationError` | La integración no existe (`403` `STUDENT_INTEGRATION_NOT_FOUND`) | Lo devuelve, sin reintentar |
| `Unavailable` | Seguro que no llegó: conexión rechazada, timeout de conexión, `503` | Reintenta con espera creciente |
| `Uncertain` | Pudo haber llegado: timeout de respuesta, corte durante la respuesta, `500` | Lo devuelve, sin reintentar |
| `Failed` | Una respuesta no prevista | Lo devuelve, sin reintentar |

Cada cliente decide qué cuenta como `Unavailable` y qué como `Uncertain` según su operación. Una consulta, como la de ocupaciones, no crea nada, así que todo lo que no llegó o pudo llegar es `Unavailable`; crear un hold sí distingue los dos casos.

**Un componente que aplica las reglas.** Recibe "qué llamada hacer" (una función que usa el JWT técnico) y aplica las reglas de sesión, `401` y reintentos sobre la respuesta común. Cada caso de uso de `catedra` solo indica su llamada y traduce la respuesta a su propio resultado (`OccupanciesResult`, el resultado del hold). Las reglas se escriben y se prueban una sola vez. Agregar T05, T11 o T13 es escribir su cliente y usar el componente.

**No es un caso de uso.** Un caso de uso es una operación que el slice ofrece hacia afuera, con su puerto de entrada. Este componente es el cómo se llama a la cátedra, no el qué. Publicarlo como puerto de entrada dejaría que otros slices le pidieran "ejecutá esta función con el JWT técnico", y el token, que nunca sale de `catedra`, quedaría a su alcance. Por eso es una pieza interna de `application`, que solo usan los casos de uso del propio slice.

**Paquete `catedra/application/support/`.** Es una excepción a la skill autorizada por el usuario, con su comentario en el código. Es del mismo tipo que las de `infrastructure` (`client`, `startup`, `gateway`, `config`): un paquete cuyo nombre dice su función.

Alternativas descartadas:

- **Copiar las reglas en cada caso de uso:** se repetirían en T04, T05, T11 y T13, y una corrección habría que hacerla en cuatro lugares.
- **Una clase base abstracta de la que heredan los casos de uso:** obliga a escribir constructores a mano para pasarle las dependencias a la clase base, lo que choca con la inyección por `@RequiredArgsConstructor` del proyecto, y la herencia hace más difícil seguir qué hace cada caso de uso.
- **El componente en `application/usecases/`:** no suma un paquete, pero ese paquete tendría algo que no es un caso de uso ni sigue el nombre `…UseCaseImpl`, y quien lo lea podría buscarle un puerto de entrada.
- **En el dominio:** usa puertos, el caso de uso de renovar la sesión y esperas, así que no es una regla pura. La parte pura (cuántos intentos y cuánto esperar) ya vive en el dominio, en `CatedraOperationPolicy`.
- **En `infrastructure/client`, envolviendo a los clientes HTTP:** los casos de uso quedarían mínimos, como en `catalog`. Pero escondería en la infraestructura reglas de la integración (qué es seguro reintentar, qué hacer con la sesión degradada), y la infraestructura tendría que llamar al caso de uso de renovar la sesión. T03 ya había decidido dejar estas reglas en `application` (P7 de su PLAN).

## Qué no cambia

El comportamiento de T03 es el mismo: la consulta de ocupaciones aplica las mismas reglas, y sus tests actuales deben seguir pasando sin cambios de comportamiento. `GetOccupanciesUseCase` sigue devolviendo `OccupanciesResult`, así que `availability` no cambia, salvo un detalle: `OccupanciesResult.TokenRejected`, que se había agregado solo para el cliente (P8 del PLAN de T03), deja de usarse y se borra, junto con su rama en el adaptador de `availability`. El control de los logins (uno a la vez y el intervalo mínimo) sigue en X01 (decisión 005), y el JWT técnico sigue sin salir de `catedra`.
