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

Si el servidor exige credenciales, son las de la cuenta técnica, las mismas de la configuración externa de X01; si no las exige, alcanza con `integration`. El protocolo y el mecanismo son configurables por configuración externa, para no fijar una suposición en el código. **Por verificar:** se consulta al profesor y se comprueba con una prueba de conexión de solo lectura (ver 3.6).

### 3.2 Eventos de resultado antes de que exista T08 (T07, D3)

Mientras T08 no existe, los cinco eventos de resultado se descartan con un registro en el log y se confirma el offset, sin registrarlos como procesados. No confirmarlos detendría la partición: Kafka entrega en orden y un mensaje sin confirmar frena a los que vienen detrás. Lo que se descarte en ese lapso solo puede venir de procesos de prueba. Se revisa al implementar T08.

### 3.3 Falla de la base al procesar un mensaje (T07, D5)

Se reintenta con espera creciente y un máximo de intentos; agotados, se registra el error, se hace una pausa configurable y se vuelve a intentar **el mismo** mensaje, sin confirmar su offset. Los que vienen detrás en su partición esperan en Kafka, sin perderse. Motivo: como confirmar un offset marca como leído todo lo anterior, no se puede dejar uno pendiente y seguir con los siguientes; esperar conserva el orden y no pierde nada, y con la base caída tampoco se podría procesar otro. Solo se reintenta así una falla de la base: qué se hace con un error inesperado se define en el PLAN de T07, para que no detenga la partición para siempre. Descartadas: apartar el mensaje en un topic de reintentos o una tabla (complejo, rompe el orden y probablemente no se puedan crear topics) y descartarlo (contradice T07 6.1).

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
| E2E plan B (a') | Si no lo permite, el test lee el pedido real **sin consumer group** y se lo entrega al procesamiento de turnos | Nada. El consumo real del servicio se prueba en el E2E de T06 |

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
- La idempotencia se guarda en `reservation`: tabla `processed_events` propia, escrita en la misma transacción que el cambio del proceso con un puerto técnico de transacción (PLAN de T07, P4). Sin control de versión además del candado (P5).

## 4. Por verificar contra el Kafka real

- Protocolo, mecanismo y credenciales (3.1).
- Si se permite un consumer group con otro nombre (3.6).
- Cuántas particiones tiene el topic de acciones, cuánto tiempo conserva los mensajes y cuánto las posiciones de un consumer group inactivo.
- El formato real de los mensajes frente al anexo §15.
- Si `catalog_service` usará el mismo consumer group sugerido sobre su topic: dos servicios en un mismo grupo provocan reacomodos entre ellos aunque cada uno lea su topic. Se revisa al hacer catálogo.

## 5. Por definir en los PLAN

- T08: los efectos de los cinco eventos de resultado (hoy se descartan, 3.2).
- T06: configuración del productor (confirmación de escritura, reintentos y tiempos) y el caso de credenciales rechazadas al publicar.

## Historial

- 2026-10-29: creado con las decisiones de la SPEC de T07.
- 2026-10-30: decisiones del PLAN de T07 (3.7 a 3.12), aprobado por el usuario.
