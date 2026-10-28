# 012 — Cómo se guarda el proceso de reserva: identificador, dueño y un proceso abierto por usuario

- **Fecha:** 2026-10-26
- **Funcionalidad:** T04 — Bloquear turno (`docs/sdd/t04-bloquear-turno/`, decisiones D3 y D5 de la SPEC y P5, P6a y P6b del PLAN)
- **Estado:** aceptada

## Situación

T04 es el primer caso de uso que guarda datos de negocio además de los usuarios: el proceso de reserva, que nace en `HELD` cuando la cátedra concede un hold y que después van a avanzar T05 a T13. Se guarda en la base de turnos, en el slice nuevo `reservation`, y pertenece al usuario del token (`sub`).

El dominio (`docs/turnos_y_reservas_dominio.md`) fija tres reglas que afectan cómo se guarda:

- **Regla 4:** el proceso se identifica por el `reservationProcessId` que asigna la cátedra; turnos no genera identificadores propios.
- **Regla 5:** un usuario tiene como máximo un proceso abierto (`HELD`, `WAITING_FOR_PHONE` o `PHONE_SUBMITTED`).
- **Regla 14:** los procesos se guardan en la base y no se borran al cerrarse.

## Problema

- **El identificador.** La skill de arquitectura hexagonal pide que toda entidad tenga un `Long id` autogenerado. La regla 4 dice lo contrario.
- **El dueño.** El token del usuario es sin estado: puede seguir siendo válido aunque el usuario ya no exista en la base, por ejemplo después de reiniciar la base de desarrollo. Si el proceso se guarda después de crear el hold y el usuario no existe, el hold queda huérfano. Y si la base no controla que el usuario exista, un proceso de un usuario borrado queda a nombre de nadie, o lo hereda el próximo usuario que reciba ese id.
- **Un proceso abierto por usuario.** La verificación "¿tiene un proceso abierto?" se hace antes de pedir el hold, y el proceso recién se guarda después de que la cátedra responde. Entre una cosa y la otra pasan varios segundos. Dos pedidos simultáneos del mismo usuario (un doble toque, dos dispositivos) pasan los dos la verificación y crean dos holds. Es el problema de verificar primero y actuar después.

## Decisión

**La clave primaria es el `reservationProcessId` de la cátedra** (P6a). Es una excepción a la skill, con su comentario en el código. Hay un solo identificador del proceso en los logs, la API, la base y los mensajes de la cátedra, y las búsquedas de T05 a T11 van directo a la clave primaria. Consecuencias aceptadas:

- La entidad implementa `Persistable`. Sin eso, como la clave viene asignada desde afuera, Spring Data no sabe si la entidad es nueva y hace un `SELECT` antes de cada `INSERT`.
- La columna es amplia, porque el anexo de la cátedra no fija el largo del id. Se confirma con una respuesta real grabada.
- Si la cátedra repitiera un id, el `INSERT` falla por clave duplicada y se trata como una falla de persistencia después del hold (`500` `HOLD_NOT_SAVED`).

**El dueño se verifica antes y la base lo exige** (D5):

- Antes de pedir el hold, `reservation` pregunta a `user` si el usuario existe, con un puerto de entrada que responde solo sí o no (`ExistsUserByIdUseCase`, P4), sin sacar del slice ningún dato del usuario. Si no existe, se responde `401` igual que ante un token inválido, y no se crea ningún hold. Cuesta una consulta por clave primaria en cada bloqueo.
- La tabla tiene una clave foránea a `users`. Ningún proceso puede quedar a nombre de un usuario inexistente, y un usuario con procesos no se puede borrar: si algún día se quiere dar de baja a alguien, habrá que decidir qué pasa con su historial (por ejemplo, desactivarlo, como JHipster con `activated`).
- Como la clave foránea garantiza que el dueño existe, T05 no necesita volver a verificarlo; lee el nombre y el apellido porque los necesita.

**Un proceso abierto por usuario, con dos protecciones** (D3, P5):

- **Un candado por usuario en memoria**, como campo del caso de uso de T04, igual que el candado del login técnico de X01. Los pedidos de bloqueo de un mismo usuario se atienden de a uno: el segundo encuentra el proceso que guardó el primero y recibe `409` `OPEN_PROCESS_EXISTS` sin pedir un hold. Los pedidos de usuarios distintos no se esperan. Cada candado se descarta al soltarse.
- **Un índice único parcial en la base**: un solo proceso en estado abierto por usuario. Es la garantía final aunque algún día haya más de una instancia del servicio, que un candado en memoria no cubre. Si rechaza un proceso, se responde `409` `OPEN_PROCESS_EXISTS` y se registra el hold que quedó sin proceso, que vence solo.
- La clave foránea y el índice tienen nombres propios, para distinguir cuál de las dos reglas rechazó un `INSERT`.

**La tabla tiene solo lo que usa T04** (P6b): los ids de la cátedra, el usuario, el profesional (id, nombre, apellido y categoría, como dato histórico), la fecha, la hora, el estado y el vencimiento. Cada caso de uso siguiente suma sus columnas con su propia migración.

Alternativas descartadas:

- **Un `Long id` interno además del `reservationProcessId`:** respeta la skill, pero deja dos identificadores para lo mismo y contradice la regla 4. Solo serviría para guardar procesos antes de que la cátedra los conozca, algo que hoy no se hace.
- **Clave foránea sin verificar antes:** el usuario inexistente se descubre recién al guardar, con el hold ya creado.
- **Solo el id del usuario, sin clave foránea:** borrar un usuario dejaría sus procesos sin dueño, y un token viejo podría dejar procesos que heredaría el próximo usuario con ese id.
- **Solo el índice de la base, sin candado:** en cada doble toque se crearía un hold de más, que le ocupa el turno a otro usuario hasta que vence.
- **Solo el candado, sin índice:** la regla dependería de que haya una única instancia del servicio.
- **Un candado detrás de un puerto de salida, con su adaptador:** permitiría cambiarlo por un candado distribuido, pero son más piezas para un solo uso. Se extrae si otra operación lo necesita. *(Actualización 2026-10-28: T05 lo necesitó; el candado salió del caso de uso a `reservation/application/support`, decisión 013.)*
- **Todas las columnas del dominio desde T04:** la mayoría quedaría vacía hasta los casos de uso siguientes.

## Qué no cambia

Las tablas de usuarios no cambian. El slice `reservation` no lee la tabla de usuarios: la clave foránea es una regla de la base, y la pregunta "¿existe este usuario?" pasa por el puerto de entrada de `user` (decisión 009). La convención de que el controller obtiene la identidad del token (decisión 008) no cambia. Sigue vigente una limitación del token sin estado, independiente de esta decisión: si se reinicia la base sin cambiar el par de claves, un token emitido antes vale para el nuevo usuario que reciba ese id hasta que vence.
