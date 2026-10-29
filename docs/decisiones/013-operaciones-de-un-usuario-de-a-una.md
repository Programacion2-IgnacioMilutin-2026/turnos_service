# 013 — Las operaciones de un mismo usuario se atienden de a una: candado compartido en `reservation/application/support`

- **Fecha:** 2026-10-28
- **Funcionalidad:** T05 — Confirmar reserva (`docs/sdd/t05-confirmar-reserva/`, decisión D5 de la SPEC y P1 del PLAN)
- **Estado:** aceptada

## Situación

En T04, para que dos pedidos simultáneos de un mismo usuario no crearan dos holds, el caso de uso de bloquear tiene un candado por usuario: un mapa de candados en memoria, uno por usuario, que se descarta cuando nadie lo usa (decisión 012). Es un campo privado de `CreateReservationProcessUseCaseImpl`. La decisión 012 anticipó que se extraería si otra operación lo necesitaba.

T05 (confirmar) lo necesita. Si un bloqueo y una confirmación del mismo usuario corren a la vez, el bloqueo puede cerrar por vencido el proceso mientras la confirmación espera a la cátedra, y la confirmación después lo reabriría: un estado final que vuelve atrás (regla 6 del dominio), que además choca con el índice de un proceso abierto por usuario. La SPEC de T05 decidió que las operaciones de un mismo usuario sobre sus procesos se atienden de a una (D5).

## Problema

- **Tiene que ser el mismo candado.** Dos candados distintos, uno por operación, solo excluyen a los pedidos de su propio tipo: un bloqueo y una confirmación del mismo usuario seguirían corriendo a la vez. El candado tiene que salir del caso de uso de T04 para que lo tomen los dos.
- **Dónde va.** La skill de arquitectura hexagonal solo prevé, en `application`, los casos de uso, el service y las excepciones. Y el candado tiene dos lados: la regla ("de a una por usuario") es de la aplicación, y el mecanismo (cómo se espera) es técnico.

## Decisión

**Una pieza en `reservation/application/support/` (`UserOperationLock`)**, con un método que ejecuta un trabajo con exclusividad para un usuario. La inyectan los casos de uso de bloquear y de confirmar; Spring crea una sola instancia, así que los dos usan el mismo mapa de candados. El código es el mismo que tenía T04, movido. Es una excepción a la skill autorizada por el usuario, con su comentario en el código, del mismo tipo que `catedra/application/support` (decisión 011): no es un caso de uso, no se ofrece a otros slices y solo coordina a los casos de uso de su slice.

**Por qué en `application` y no detrás de un puerto.** Lo que va detrás de un puerto, con su adaptador en `infrastructure`, es lo que depende de una tecnología que puede cambiar: la base (`ReservationProcessRepository`) o una librería (`PasswordHasher`, decisión 001). Este candado es Java puro (`java.util.concurrent`), sin base, red ni librerías, y hoy el servicio corre en una sola instancia. Ponerlo detrás de un puerto prepararía un cambio que no está previsto.

**Cuándo cambiarlo.** Si el servicio pasa a correr en más de una instancia, un candado en memoria ya no alcanza: cada instancia tendría el suyo. Ese es el momento de pasar a un puerto de salida con un adaptador que use un candado compartido (en la base o en Redis), sin tocar los casos de uso más allá de la inyección.

Alternativas descartadas:

- **Un puerto de salida con un adaptador en memoria en `infrastructure`:** separa la regla del mecanismo y deja listo el cambio a un candado distribuido, pero son dos piezas y un paquete de infraestructura más para algo que hoy no hace falta. Ya la había descartado la decisión 012.
- **Que T05 tome el candado llamando a T04:** un caso de uso dependería de otro, algo que la skill no prevé.
- **Dos candados, uno por operación:** no excluyen un bloqueo de una confirmación del mismo usuario, que es justo el caso que se quiere evitar.

## Qué no cambia

El comportamiento de T04 es el mismo: los pedidos de bloqueo de un usuario se atienden de a uno y los de usuarios distintos no se esperan; sus tests de concurrencia siguen pasando sin cambios. Lo único nuevo para T04 es que un bloqueo espera a que termine una confirmación en curso del mismo usuario. El índice parcial de la base (decisión 012) sigue siendo la garantía final de un proceso abierto por usuario, aunque no protege la carrera entre bloquear y confirmar: con más de una instancia, eso exigiría el cambio descrito arriba.
