# 014 — Kafka con la cátedra: cómo funciona, cómo se usa y decisiones

- **Fecha:** 2026-10-29 (creado); se actualiza con cada decisión nueva sobre Kafka
- **Funcionalidad:** T07 — Procesar solicitud de información adicional (`docs/sdd/t07-solicitud-informacion/`, decisiones D1, D3, D5, D6, D7 y D8 de la SPEC). Lo amplían T08 y T06
- **Estado:** vivo. Lo marcado "por verificar" se completa cuando se compruebe contra el Kafka real

A diferencia de los demás textos de decisiones, este documento reúne todo lo de Kafka en un solo lugar: los conceptos, cómo lo usa turnos y las decisiones de cada caso de uso que son propias de Kafka. Una decisión que también cambia algo fuera de Kafka (por ejemplo, el candado por usuario) tiene su propio texto o vive en su SPEC, y acá solo se nombra.

## 1. Cómo funciona Kafka (lo que usamos)

| Concepto | Qué es |
| --- | --- |
| **Servidores** (*bootstrap servers*) | La dirección a la que se conecta el cliente. Desde ahí descubre el resto del clúster |
| **Topic** | Un canal con nombre donde se publican mensajes. Los mensajes **no se borran al leerlos**: quedan guardados el tiempo que fije el servidor (retención) |
| **Partición** | Cada topic se divide en una o más listas ordenadas. Cada mensaje tiene una **posición** (*offset*) dentro de su partición. El orden solo está garantizado dentro de una partición |
| **Clave** (*message key*) | Decide en qué partición cae un mensaje: misma clave, misma partición. Así los mensajes con la misma clave llegan en orden |
| **Productor** | Quien publica mensajes en un topic |
| **Consumidor** | Quien lee mensajes de un topic |
| **Consumer group** | El **nombre de un señalador**. Kafka guarda, por cada consumer group y partición, hasta qué posición se leyó. No es una cuenta ni un usuario. Dos lectores con el mismo consumer group se **reparten** las particiones (cada mensaje lo recibe uno solo); dos consumer groups distintos leen todo, cada uno con su señalador |
| **Confirmar el offset** (*commit*) | Avisarle a Kafka "leí hasta la posición N" de un consumer group. Marca como leído **todo lo anterior** a N: no es un "ok" por cada mensaje |
| **Posición inicial** (*auto.offset.reset*) | Por dónde empieza un consumer group que no tiene una posición guardada: desde el mensaje más antiguo que se conserva (`earliest`) o solo los nuevos (`latest`). Kafka borra las posiciones de un consumer group inactivo después de un tiempo (7 días por defecto; el de la cátedra, por verificar) |
| **Leer sin consumer group** | Indicar a mano la partición y la posición, sin confirmar nada. No mueve ningún señalador |
| **Al menos una vez** | Un mensaje puede llegar más de una vez (por ejemplo, si el consumidor se cae después de procesarlo y antes de confirmar). Por eso hace falta idempotencia |
| **Seguridad** | El servidor puede estar abierto (`PLAINTEXT`), pedir usuario y contraseña (`SASL_PLAINTEXT`, con un mecanismo: `PLAIN`, `SCRAM-SHA-256` o `SCRAM-SHA-512`) o, además, cifrar la conexión (`SASL_SSL`). El cliente tiene que usar exactamente la misma configuración |

## 2. Kafka en este proyecto

**Dos "grupos" que no son lo mismo:**

| Nombre | Qué es | De quién |
| --- | --- | --- |
| `groupId` | Identificador de la integración de la cuenta técnica ante la cátedra (anexo §2.1). Forma parte de los nombres de los topics | De la cuenta técnica del alumno; uno solo |
| Consumer group (`kafkaConsumerGroupId`) | El señalador con el que se leen los topics. La cátedra lo **sugiere** en `integration` (anexo §15.2) | Del alumno |

Cada alumno tiene sus propios topics y su consumer group: lo que se haga sobre ellos no afecta a nadie más.

**Topics** (los nombres concretos llegan en `integration`, contrato §4.3):

| Topic | Dirección | Eventos | Caso |
| --- | --- | --- | --- |
| `alumnos.turnos.acciones.{groupId}` | Cátedra → turnos | `AdditionalInformationRequested` | T07 |
| (el mismo) | Cátedra → turnos | `AppointmentConfirmed`, `AdditionalInformationRejected`, `AppointmentProcessExpired`, `AppointmentProcessInvalid`, `AppointmentCancelled` | T08 |
| `catedra.turnos.telefono.{groupId}` | Turnos → cátedra | `AdditionalInformationSubmitted` | T06 |
| `catedra.catalog.{groupId}` | Cátedra → catálogo | `CatalogUpdated` | `catalog_service` (turnos no lo usa) |

La clave de los mensajes de turnos es el `reservationProcessId`: los eventos de un mismo proceso caen en la misma partición y llegan en orden.

**De dónde salen los datos de conexión:** del login técnico (X01). `integration` trae los servidores, el consumer group sugerido, los topics y el `groupId`; no trae credenciales de Kafka (a diferencia de Redis, que sí trae usuario y contraseña). Turnos nunca los fija en el código.

**Idempotencia:** cada evento trae un `eventId`. Turnos registra los que procesó en la misma transacción que el cambio de estado, y recién después confirma el offset. Si un `eventId` ya está registrado, no repite ningún efecto (dominio, regla 17).

## 3. Decisiones

### 3.1 Autenticación (T07, D1)

Si el servidor exige credenciales, son las de la cuenta técnica, las mismas de la configuración externa de X01; si no las exige, alcanza con `integration`. El protocolo y el mecanismo son configurables por configuración externa, para no fijar una suposición en el código. **Verificado (2026-10-30, TASK-02 de T07):** la prueba de conexión de solo lectura conectó con `PLAINTEXT`, sin credenciales, al primer intento. El Kafka de la cátedra no exige autenticación ni tiene control de acceso: la lista de topics muestra los de todas las integraciones. El valor por defecto (`PLAINTEXT`) es el correcto, y en la práctica no puede ocurrir un rechazo de credenciales al publicar (T06 6.2, ver 3.16). El servicio usa solo los nombres de su `integration`, así que nunca toca topics ajenos.

### 3.2 Eventos de resultado antes de que exista T08 (T07, D3)

Mientras T08 no existe, los cinco eventos de resultado se descartan con un registro en el log y se confirma el offset, sin registrarlos como procesados. No confirmarlos detendría la partición: Kafka entrega en orden y un mensaje sin confirmar frena a los que vienen detrás. Lo que se descarte en ese lapso solo puede venir de procesos de prueba. Se revisa al implementar T08. **Reemplazada por T08 (ver 3.14): los cinco eventos ya se procesan.**

### 3.3 Falla de la base al procesar un mensaje (T07, D5)

Se reintenta con espera creciente y un máximo de intentos; agotados, se registra el error, se hace una pausa configurable y se vuelve a intentar **el mismo** mensaje, sin confirmar su offset. Los que vienen detrás en su partición esperan en Kafka, sin perderse. Motivo: como confirmar un offset marca como leído todo lo anterior, no se puede dejar uno pendiente y seguir con los siguientes; esperar conserva el orden y no pierde nada, y con la base caída tampoco se podría procesar otro. Solo se reintenta así una falla de la base: qué se hace con un error inesperado se define en el PLAN de T07, para que no detenga la partición para siempre. **Una falla de la base también puede ser al leer**, y es lo primero que pasa con la base caída: al principio solo se traducía la falla al guardar, y con Postgres caído el mensaje se descartaba (defecto de T07, corregido en TASK-12 de T08; lo prueba `DatabaseDownKafkaIntegrationTest`, con Postgres pausado de verdad). Descartadas: apartar el mensaje en un topic de reintentos o una tabla (complejo, rompe el orden y probablemente no se puedan crear topics) y descartarlo (contradice T07 6.1).

### 3.4 Posición inicial (T07, D6)

Sin una posición guardada, se lee desde el mensaje más antiguo que Kafka conserve (`earliest`). Los mensajes viejos de procesos que la base no conoce quedan como huérfanos y se descartan. Así nunca se pierde un pedido de un proceso que sí existe localmente. Descartada: `latest`, que pierde lo publicado mientras turnos no estaba conectado.

### 3.5 Cuándo se conecta y qué pasa si cambian los datos (T07, D7)

Turnos empieza a consumir apenas la integración queda habilitada, con el login del arranque o con uno posterior. Si al arrancar la cátedra no estaba disponible, empieza con el próximo login exitoso, que hoy ocurre a pedido (decisión 005); mientras tanto los mensajes esperan en Kafka. La conexión conserva los datos del primer login exitoso hasta reiniciar: si un login posterior trae otros, se registra un warning y no se cambia. Descartadas: consumir solo si el login del arranque salió bien; reintentar el login periódicamente (cambia X01); reconectarse con los datos nuevos (cortar el consumo a mitad de un mensaje y perder las posiciones del consumer group anterior).

### 3.6 Pruebas contra el Kafka real (T07, D8)

Criterio del usuario: **ninguna prueba afecta lo de otros alumnos ni rompe el servicio de turnos real ni la cátedra**; se puede dejar algo escrito en lo propio si no tiene efecto. El E2E verifica turnos con los servicios reales, no a la cátedra ni a catálogo (decisión 010). Todas las pruebas reales las corre el usuario en su terminal, después de confirmar qué hace cada una.

| Prueba | Qué hace | Qué deja |
| --- | --- | --- |
| Conexión | Pide la lista de topics sin credenciales y con las de la cuenta técnica; consulta, sin leer mensajes, si la cátedra permite un consumer group con otro nombre | Nada |
| Grabaciones | Lee el topic de acciones **sin consumer group** y sin confirmar nada; si no hay pedidos, se crea uno con un bloqueo y una confirmación (ocupa un turno 10 minutos) | Nada en Kafka. Los mensajes se limpian antes de versionarlos |
| E2E (a) | Si la cátedra lo permite, el servicio consume con un **consumer group propio de tests**, configurado solo para el E2E; prueba el camino completo (autenticación, consumo, posición) | Un segundo consumer group propio, sin efecto sobre el del servicio |
| E2E plan B (a') | Si no lo permite, el test lee el pedido real **sin consumer group** y se lo entrega al procesamiento de turnos | Nada. El consumo real del servicio se prueba en el E2E de T06. **No hizo falta**: la cátedra permite cualquier nombre (sección 4), así que no se implementó |

Por eso el consumer group se puede reemplazar por configuración externa; sin ella, se usa el de `integration`. Descartado: correr el E2E con el consumer group del servicio, porque podía leer el pedido de un proceso real abierto en la app local y descartarlo como huérfano, y ese proceso nunca lo recibiría.

**Condición de uso:** nunca correr a la vez dos lectores con el mismo consumer group (la app local y otra instancia): se repartirían los mensajes.

### 3.7 Dónde vive Kafka en la arquitectura (PLAN de T07, P1 y P10)

**Todo lo de Kafka está en `catedra/infrastructure/kafka`, y en ningún otro lugar** (regla pedida por el usuario). `catedra` es la única puerta hacia la cátedra (decisión 009), también por Kafka: las credenciales de la cuenta técnica y la renovación de la sesión no salen de ahí. El resto del código habla de "un pedido de información adicional de la cátedra" y de "la integración se habilitó", sin tipos ni nombres de Kafka.

```
Kafka ─► catedra/infrastructure/kafka (consumidor: lee el JSON, valida el formato, lo convierte al modelo)
           └─► service de catedra ─► caso de uso "recibir un pedido" ─► puerto de salida "quien procesa los pedidos"
                                                                              ▲ lo implementa
                         reservation/infrastructure/gateway ─► ReservationService ─► caso de uso de T07
```

- El consumidor es un **adaptador de entrada**, como un controller: entra por el service de `catedra`, no llama a puertos (skill §5.3, P10). Lo mismo el gateway de `reservation` respecto de su propio slice.
- `catedra` no conoce a `reservation`: declara un puerto de salida y Spring le inyecta el adaptador. La dependencia sigue siendo `reservation` → `catedra`. Excepciones a la skill autorizadas: el gateway que implementa un puerto de otro slice y el paquete `infrastructure/kafka`.
- Cómo vuelve el resultado: `reservation` responde aplicado o descartado, o lanza su excepción de falla de la base; el gateway lo traduce a un resultado de `catedra`, y `catedra` lanza su propia excepción cuando hay que reintentar (3.3). Ninguno conoce las excepciones del otro.
- Descartadas: Kafka en `reservation` (las credenciales saldrían de `catedra`), un slice propio para Kafka, que `catedra` llame a `reservation` (dependencia en las dos direcciones) y eventos de Spring (esconden el recorrido).

### 3.8 Librería y forma de consumir (PLAN de T07, P2)

spring-kafka, con `spring-boot-starter-kafka` (BOM de Boot 4.1.1: spring-kafka 4.1.1, kafka-clients 4.2.1). Como los datos de conexión llegan con el login y no al arrancar, el contenedor de consumo se arma **por programa**; no se usa `@KafkaListener` ni `spring.kafka.bootstrap-servers`. La librería ya resuelve confirmar el offset de cada mensaje después de procesarlo, volver a leer el mismo mensaje con espera creciente y detener o arrancar el consumo. Descartado: kafka-clients puro, que obligaba a escribir y probar a mano lo más delicado.

Detalles de la implementación (TASK-10 de T07):

- Un solo consumidor (`KafkaMessageListenerContainer`) con `AckMode.RECORD` y sin confirmación automática: cada mensaje se confirma recién cuando terminó de procesarse sin error.
- La espera entre entregas es propia (`MessageRetryBackOff`), para que los intentos cuenten entregas totales, igual que los de las operaciones con la cátedra, y para tener las dos variantes de 3.3 y 3.10.
- El manager pide el oyente a Spring recién al armar el consumo (`ObjectProvider`), no al crearse. Si lo recibiera al crearse, Spring no podría armar el servicio: el login avisa al manager, el oyente llega hasta `reservation`, y `reservation` (al bloquear un turno) termina usando el login. Es una dependencia circular entre objetos al arrancar, no entre capas ni slices.

### 3.9 Cómo arranca el consumo (PLAN de T07, P3)

Los casos de uso de login de `catedra` (arranque y renovación), después de guardar una sesión habilitada, avisan a un puerto de salida que la integración se habilitó. Lo implementa la infraestructura de Kafka: arranca el consumo aparte del hilo del login, o, si ya corría y los datos cambiaron, registra el warning de 3.5. Descartados: que el repositorio de la sesión avise al guardar (efecto escondido) y un chequeo periódico (demora y una tarea más).

### 3.10 Errores que no son de la base (PLAN de T07, P6)

Se reintentan unas pocas veces con la misma espera creciente; agotados, se registra un error grave con el `eventId` y el motivo, y se confirma el offset: el mensaje se descarta y el consumo sigue. Un error de código no detiene las reservas de todos los usuarios. Solo la falla de la base espera sin límite (3.3).

### 3.11 Configuración (PLAN de T07, P7 y P8)

Bajo `turnos.catedra.kafka` en `application.yaml`:

| Propiedad | Variable | Por defecto |
| --- | --- | --- |
| Consumo habilitado | `TURNOS_CATEDRA_KAFKA_ENABLED` | Verdadero |
| Protocolo de seguridad | `TURNOS_CATEDRA_KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` (se ajusta con la prueba de conexión) |
| Mecanismo de autenticación (solo con `SASL_…`) | `TURNOS_CATEDRA_KAFKA_SASL_MECHANISM` | `PLAIN` |
| Usuario y contraseña | Las de la cuenta técnica (`TURNOS_CATEDRA_USERNAME`, `TURNOS_CATEDRA_PASSWORD`) | — |
| Consumer group | Ninguna en la aplicación; solo la suite E2E la completa con `TURNOS_E2E_KAFKA_CONSUMER_GROUP` | El de `integration` |
| Reintentos: espera inicial, multiplicador, máximo | `TURNOS_CATEDRA_KAFKA_RETRY_INITIAL_DELAY`, `…_MULTIPLIER`, `…_MAX_ATTEMPTS` | 500 ms, 2, 3 |
| Pausa antes de volver a intentar el mismo mensaje | `TURNOS_CATEDRA_KAFKA_RETRY_PAUSE` | 30 s |

No son configurables: leer desde el mensaje más antiguo (3.4) y confirmar el offset solo después de procesar cada mensaje. El consumer group no tiene variable propia en la aplicación porque la app local lee el mismo `.env` que la suite E2E: así nunca usa el de tests por error.

### 3.12 Tests (PLAN de T07, P8)

- **Suite normal:** Kafka real en Testcontainers, como Postgres, un solo contenedor compartido. La cátedra simulada devuelve en el login los servidores del contenedor, así que se prueba el recorrido completo con los mensajes reales grabados. Los tests que no son de Kafka apagan el consumo (`enabled` en falso): si no, intentarían conectarse a los servidores inventados de la cátedra simulada. Descartado: el Kafka embebido de `spring-kafka-test`.
- **Prueba de conexión y grabaciones:** un programa Java de un solo archivo, en el scratchpad, con el mismo cliente de Kafka que el servicio, corrido por el usuario y borrado después.
- **Suite E2E:** su configuración común obliga a una de dos cosas: el consumer group de tests (`TURNOS_E2E_KAFKA_CONSUMER_GROUP`) o el consumo apagado (plan B de 3.6). Ningún E2E, tampoco los de T03 y T05, puede consumir con el consumer group del servicio.

### 3.13 Relacionadas, fuera de este documento

- Un pedido que llega mientras opera el usuario se procesa con el mismo candado por usuario que bloquear y confirmar (T07 D2, decisión 013).
- La publicación de T06 también va dentro de ese candado, con un tope de 10 s (T06 D4, decisión 013).
- La idempotencia se guarda en `reservation`: tabla `processed_events` propia, escrita en la misma transacción que el cambio del proceso con un puerto técnico de transacción (PLAN de T07, P4). Sin control de versión además del candado (P5).

### 3.14 Eventos de resultado (SPEC de T08)

- **Un solo camino (D1):** los cinco eventos entran por un solo caso de uso en cada slice, porque validar, correlacionar, registrar el evento y tomar el candado es igual para los cinco.
- **Campos obligatorios (D8):** solo los que el efecto necesita, con el mismo criterio que el pedido de teléfono: un descarte no tiene vuelta atrás, y nunca se vio un mensaje real.
- **Un mensaje no puede frenar el consumo por su tamaño (D4, RF-17):** un valor más largo que su columna haría fallar el guardado, y eso se trata como una caída de la base: se reintentaría sin fin y frenaría a todos (3.3). Por eso `reason` se recorta a 500 caracteres con un warning, y un `eventId`, `reservationProcessId` o `holdId` de más de 255 caracteres hace inválido el mensaje, también en el pedido de teléfono. Lo mismo con el carácter nulo, que Postgres no acepta en un texto: invalida un identificador y se quita de `reason`, con un warning.
- **Cómo se lee un mensaje (PLAN de T08, P3):** un solo DTO con las fechas, instantes y números como texto, y el lector convierte solo los campos que usa ese tipo de evento. Un formato distinto en un campo que no se usa no descarta el mensaje, y si hay que descartarlo, el log conserva el `eventId`. Descartadas: tipos en el DTO, que pierde el mensaje entero, y un DTO por evento.
- **Sin prueba contra el Kafka real (SPEC de T08):** la cátedra no publica en el topic de acciones y T06 todavía no existe. Se revisa al hacer T06. **Actualizado (2026-11-06):** verificado con la cátedra publicando; ver la sección 4.

### 3.15 Cuando una publicación no termina (SPEC de T06, D1 a D3)

T06 guarda `PHONE_SUBMITTED` antes de publicar (D2 y D5 de T08), así que puede quedar guardado sin que el teléfono haya salido. En las dos primeras situaciones se eligió lo más simple, porque lo que se pierde no es grave:

- **El servicio se cae entre guardar y publicar (D1):** T06 no agrega nada. El proceso queda en `PHONE_SUBMITTED` y el usuario no puede reenviar. Lo resuelve T13 como cualquier `PHONE_SUBMITTED`: en su ejecución de arranque lo consulta y lo cierra como `EXPIRED` cuando la cátedra lo da por vencido. Una caída es poco común y no justifica otra escritura en cada envío. Limitación aceptada: el usuario pierde el turno, y hasta que exista T13 ese proceso queda abierto y no puede bloquear otro. Descartadas: encender `needsReconciliation` al guardar y devolverla tras el acuse de Kafka, para que T13 lo devuelva a `WAITING_FOR_PHONE` (una escritura más en cada envío), y guardar el mensaje pendiente para reenviarlo al arrancar (columnas y una pieza de arranque, y confía en que la cátedra descarte repetidos por `eventId`, sin verificar).
- **Kafka no responde y se agotan los reintentos (D2):** el proceso vuelve a `WAITING_FOR_PHONE`, queda marcado para reconciliar y T06 responde `503`; el usuario puede reintentar. No se restaura nada: un motivo de rechazo anterior no vuelve a verse, y el teléfono guardado queda el del intento fallido. Descartadas: restaurar el motivo y el teléfono anteriores (más lógica para algo poco grave) y quedarse en `PHONE_SUBMITTED` marcado aceptando un reenvío (la app mostraría "Confirmando teléfono" aunque nada salió).
- **La base falla (D3):** después de publicar no se escribe nada, así que el caso original de T06 ("se publicó y no se pudo guardar") ya no existe. Si falla el guardado inicial, no se publicó nada: `500` `INTERNAL_ERROR`. Si Kafka falló y tampoco se puede volver atrás, el proceso queda en `PHONE_SUBMITTED` sin marca, igual que tras una caída: `500` `LOCAL_STATE_OUT_OF_SYNC` y un error grave en el log. Es el código que T05 y T11 usan cuando el estado local no refleja lo que pasó, con el mismo sentido. Descartado: `INTERNAL_ERROR` en los dos, que no distingue "no pasó nada" de "el estado quedó mal", aunque la app los trate igual (A08 5.8).

**Un timeout no prueba que el mensaje no haya llegado.** Si llegó, y el proceso volvió a `WAITING_FOR_PHONE`: una confirmación se aplica, porque vale desde cualquier estado abierto, y un rechazo se ignora (D2 de T08), así que el usuario no ve el motivo, pero puede reenviar.

### 3.16 Sin sesión y credenciales rechazadas al publicar (SPEC de T06, D5 y D6)

- **Sin sesión técnica o con error de configuración (D5):** sin sesión no se conocen el topic ni el `groupId`, así que no se puede publicar. Se comprueba antes de guardar, con un intento de login si no hay sesión: el proceso no cambia y no se marca, porque es seguro que nada salió. Responde siempre `503` `CENTRAL_UNAVAILABLE`, también ante un error de configuración, a diferencia de T03, T04, T05 y T11, que responden `SERVICE_DEGRADED`; el motivo queda en el log. Motivo: la app trata igual todo `503` (A08 5.8). Descartado: separar los dos casos, que suma una fila al contrato y una rama al controller.
- **Credenciales rechazadas (D6):** se tratan como Kafka no disponible (3.15), sin reintentar, porque repetir no lo arregla, y con el motivo en el log. No se hace un login técnico: hoy no puede ocurrir (3.1), ese código no se ejecutaría nunca y solo se podría probar con un doble. Limitación aceptada: si la cátedra empezara a pedir credenciales o las cambiara, T06 fallaría hasta reiniciar o hasta que otro login renueve la sesión; ahí se revisa. Descartado: el login técnico y un reintento, como decía T06 antes de este cambio. El anexo no lo exige.

### 3.17 Sin prueba del productor contra el Kafka real (SPEC de T06, D8)

Mientras la cátedra no publique el pedido, T06 no tiene `requestEventId` y no llega a publicar en el recorrido real. Lo publicado se comprueba en un Kafka en contenedor contra el anexo §15.5, y el E2E suma el paso de informar el teléfono, bloqueado como el de T07. Descartado: un programa de una sola vez que publique en el topic real con un `requestEventId` inventado, porque no se sabe qué hace la cátedra con ese mensaje y sería probar a la cátedra (decisión 010). Se puede sumar después como una tarea. **Actualizado (2026-11-06):** ya no hace falta; el E2E publicó el teléfono en el topic real, la cátedra lo consumió y confirmó la reserva (sección 4).

### 3.18 Cómo se publica (PLAN de T06, P2 y P3)

- **Cliente (P2):** `KafkaProducer` de `kafka-clients`, sin `KafkaTemplate`. spring-kafka se eligió para el consumo porque resuelve lo más delicado (3.8), y publicar no tiene esa dificultad. Los tests prueban rechazos y reintentos con `MockProducer`.
- **Intentos (P2):** un bucle propio en `infrastructure/kafka`, porque el anexo §18.4 pide reintentos observables: cada intento fallido queda en el log. Solo se reintentan las `RetriableException`; el resto, como un rechazo de credenciales, corta enseguida. Descartado: dejar los reintentos solo al cliente, que no permite verlos ni contarlos. No se usa `CatedraOperationRunner`: entrega un JWT, que Kafka no necesita, y no entrega la integración.
- **Tiempos (D4 de la SPEC):** `max.block.ms`, `request.timeout.ms` y `delivery.timeout.ms` iguales a `attempt-timeout`, y `linger.ms=0`, porque por defecto el cliente puede bloquear hasta 60 s. Un intento no empieza si no entra en el tope. Se configura bajo `turnos.catedra.kafka.publish`:

  | Propiedad | Variable | Por defecto |
  | --- | --- | --- |
  | Tope para responderle al usuario | `TURNOS_CATEDRA_KAFKA_PUBLISH_TIMEOUT` | 10 s |
  | Duración de un intento | `TURNOS_CATEDRA_KAFKA_PUBLISH_ATTEMPT_TIMEOUT` | 2,5 s |
  | Intentos | `TURNOS_CATEDRA_KAFKA_PUBLISH_MAX_ATTEMPTS` | 3 |
  | Espera inicial y multiplicador | `TURNOS_CATEDRA_KAFKA_PUBLISH_INITIAL_DELAY`, `…_MULTIPLIER` | 500 ms, 2 |

- **Confirmación de escritura:** `acks=all`; la idempotencia queda en su valor por defecto. Si un intento vence, el mensaje puede haber salido y se reenvía con el mismo `eventId`: el anexo §15.1 lo prevé, pero que la cátedra descarte repetidos no está verificado.
- **Conexión (P3):** el productor se crea en el primer envío, porque la dirección llega con el login, y la conserva hasta reiniciar, como el consumo (3.5). El topic y el `groupId` salen de la sesión vigente en cada envío. Descartado: crear otro productor si cambia la dirección, que publicaría en el Kafka nuevo mientras el consumo sigue en el viejo: el resultado no llegaría nunca y el proceso quedaría trabado en `PHONE_SUBMITTED`.
- **`turnos.catedra.kafka.enabled`** solo apaga el consumo. Sin consumo no hay `requestEventId`, así que tampoco se llega a publicar.

## 4. Verificado y por verificar contra el Kafka real

**Verificado (2026-10-30, TASK-02 de T07, lecturas sin consumer group corridas por el usuario):**

- Conexión `PLAINTEXT` sin credenciales; sin control de acceso (3.1).
- Se puede usar un consumer group con otro nombre: el sugerido y uno con `-e2e` al final no existían, y la consulta respondió "no existe", no un error de permisos. El E2E usa la opción (a) de 3.6.
- El topic de acciones tiene **1 partición** y conserva los mensajes **7 días** (`retention.ms=604800000`, `cleanup.policy=delete`).
- El consumer group del servicio no tiene posiciones guardadas: nunca se consumió con él.
- **El servicio consume del Kafka real** (E2E de T07, 2026-10-31): con el consumer group de tests, se conectó, recibió la asignación del topic de acciones y se posicionó desde el principio. El consumer group del servicio siguió sin posiciones después de la corrida. El pedido de teléfono no llegó porque la cátedra no publica, así que el E2E falla en ese paso (CA-14 de T07, bloqueado).
- **Hasta el 2026-11-03, la cátedra no publicaba en el topic de acciones:** estaba vacío desde siempre (posiciones 0 → 0), aunque se confirmaron holds el 2026-10-28, el 2026-10-29 y el 2026-10-30 (después de la confirmación de las 02:00 y después de su vencimiento de las 02:10, siguió vacío). Contradice el anexo §10 y §15.4. Le pasa también a otros alumnos: un compañero ya se lo consultó al profesor. Hipótesis sin comprobar: hay un topic `kafkaProducer-out-0`, que es el nombre por defecto de una salida de Spring Cloud Stream sin destino configurado. Última corrida del E2E: 2026-11-03, sin pedido en 30 s después de confirmar. Esa corrida no mira el topic, así que no distingue si la cátedra no publica o publica más tarde.

**Verificado (2026-11-06, E2E de reserva y lectura del topic sin consumer group, corridos por el usuario):**

- **La cátedra publica desde el 2026-11-04.** Ese día, a las 11:15Z, publicó juntos los 20 mensajes atrasados de las pruebas del 28 al 31 de octubre (pedidos de teléfono y vencimientos `HOLD_EXPIRED`), con su `occurredAt` original: un evento puede llegar una semana tarde. El servicio los trata como huérfanos o tardíos, sin cambios.
- **Recorrido completo con el servicio:** pedido de teléfono recibido y guardado, teléfono publicado en el topic de teléfono, la cátedra lo consumió y confirmó la reserva (`AppointmentConfirmed`), cancelación con T11 y su `AppointmentCancelled` procesado. Cierra T07 TASK-12, T06 TASK-11 y CA-17 de T11, y lo que 3.17 dejaba sin probar.
- **Formato real igual al anexo §15** en los cuatro tipos vistos (`AdditionalInformationRequested`, `AppointmentConfirmed`, `AppointmentCancelled`, `AppointmentProcessExpired`), con los mismos campos que los mensajes armados. Diferencias: los instantes llegan con nanosegundos, y una cancelación sin motivo trae `reason` en `null`. La clave del mensaje es el `reservationProcessId` y no hay headers. Grabados en `src/test/resources/catedra/kafka/*-real.json` y probados en `AppointmentActionReaderTest`.
- El topic de acciones tenía 23 mensajes (posiciones 0 → 23) y el consumer group del servicio seguía sin existir: ni el E2E ni las lecturas lo usaron.

**Verificado solo con Kafka en contenedor (2026-11-02, T06):** con el contenedor congelado, los tres intentos y el `503` entran en el tope de 10 s; un intento vencido puede llegar cuando Kafka vuelve, así que el caso de 3.15 ocurre de verdad. No se probó contra el Kafka de la cátedra (3.17).

**Por verificar:**

- Cuánto conserva Kafka las posiciones de un consumer group inactivo.
- El formato real de `AdditionalInformationRejected` y `AppointmentProcessInvalid`: la cátedra todavía no los publicó. Los tests usan los mensajes armados desde el anexo.
- Si `catalog_service` usará el mismo consumer group sugerido sobre su topic: dos servicios en un mismo grupo provocan reacomodos entre ellos aunque cada uno lea su topic. Se revisa al hacer catálogo.

## 5. Por definir en los PLAN

Nada pendiente: lo de T06 quedó en 3.15 a 3.18.

## Historial

- 2026-10-29: creado con las decisiones de la SPEC de T07.
- 2026-10-30: decisiones del PLAN de T07 (3.7 a 3.12), aprobado por el usuario.
- 2026-10-30: resultados de la prueba de conexión (3.1 y sección 4): `PLAINTEXT` sin credenciales, consumer group libre, 1 partición, 7 días de retención; la cátedra no publica en el topic de acciones (consultado al profesor).
- 2026-10-31: detalles de la implementación del consumo (3.8); la cátedra sigue sin publicar, los tests usan mensajes armados desde el anexo.
- 2026-10-31: E2E con el consumer group de tests: el consumo real funciona; el plan B de 3.6 no se implementó. Cierre de T07 con CA-14 y las grabaciones reales pendientes.
- 2026-11-02: decisiones de la SPEC y del PLAN de T08 (3.14).
- 2026-11-02: corregido que una falla de la base al leer descartara el mensaje (3.3).
- 2026-11-02: T08 implementada (3.14). Comprobado contra Postgres: sin las protecciones de 3.14, un valor largo o con el carácter nulo frena el consumo. La cátedra sigue sin publicar, así que el formato real de los eventos sigue sin verificar.
- 2026-11-02: SPEC de T06: qué pasa cuando una publicación no termina (3.15), publicación dentro del candado (3.13), sin sesión y credenciales rechazadas (3.16), sin prueba real del productor (3.17).
- 2026-11-02: PLAN de T06: cómo se publica (3.18).
- 2026-11-02: T06 implementada: comprobado con Kafka en contenedor (sección 4). El E2E sumó el paso de publicar, pero no se corrió: la cátedra sigue sin publicar el pedido.
- 2026-11-03: el E2E de reserva pasa a esperar el resultado: `CONFIRMED`, cancelación directa en la cátedra y `CANCELLED` local, porque una reserva confirmada no vence sola (indicación del usuario). Si la cátedra publica, también prueba el formato real de esos eventos. Sin correr: lo corre el usuario.
- 2026-11-03: E2E de reserva corrido por el usuario: la cátedra no entregó el pedido de teléfono en 30 s. T07 TASK-12 y T06 TASK-11 siguen bloqueadas.
- 2026-11-06: la cátedra publica desde el 2026-11-04 (sección 4). E2E de reserva completo con el Kafka real, y lectura del topic sin consumer group: formato igual al anexo, con nanosegundos y `reason` en `null`; cuatro mensajes reales grabados. T07 TASK-02 y TASK-12, T06 TASK-11 y CA-17 de T11 verificados.
