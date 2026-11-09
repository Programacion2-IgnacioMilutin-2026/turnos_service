# SPEC: T12 — Expirar procesos vencidos

**Estado:** Aprobada <!-- Borrador | En revisión | Aprobada -->

<!-- PARA LA PERSONA
Copia esta plantilla como SPEC.md en una carpeta de la funcionalidad.
Pide al agente que la complete contigo usando BACKEND_GUIDELINES.md.
SPEC.md define qué debe cumplirse; PLAN.md desarrolla cómo implementarlo;
TASKS.md organiza los pasos de ejecución.
Los casos de uso, diagramas, contratos y el anexo de la cátedra se referencian
por ID; esta SPEC no los reescribe.
-->

<!-- PARA EL AGENTE
- Lee las instrucciones del proyecto, BACKEND_GUIDELINES.md y los documentos
  citados en Referencias (casos de uso, diagramas, contratos, modelo de dominio
  y anexo de la cátedra). Inspecciona el repositorio para comprobar el
  comportamiento actual. Si falta un documento, pide su ubicación.
- Los casos de uso y los contratos son la fuente de verdad. No los reescribas ni
  los contradigas: referencia sus pasos y flujos alternativos por ID. Si esta
  spec y un documento de referencia difieren, o este es ambiguo, detente y consulta.
- Completa esta spec con la persona: investiga lo comprobable y consulta las
  decisiones pendientes. Haz pocas preguntas por vez y actualiza las respuestas.
- No inventes requisitos ni exclusiones. Distingue propuestas de decisiones
  confirmadas y marca como PENDIENTE lo que aún no esté resuelto.
- Aplica las consideraciones de backend relevantes sin ampliar el alcance automáticamente.
- No incluyas diseño de clases, tablas, componentes, archivos o algoritmos:
  esos detalles pertenecen a PLAN.md. Sí registra restricciones explícitas del pedido.
- Mantén el documento breve y proporcional a la funcionalidad. Conserva los comentarios.
- Un documento completo no está aprobado automáticamente. Solicita aprobación
  antes de marcarlo como Aprobada. No implementes durante esta etapa.
-->

## Referencias

<!-- Documentos de Documentacion en los que se apoya esta funcionalidad. Indica
qué partes cubre esta spec, con IDs exactos de archivo, caso de uso, paso y flujo
alternativo. Una funcionalidad puede cubrir solo una parte de un caso de uso: lo
que no se nombra aquí no forma parte de ella. -->

| Documento | Partes cubiertas |
| --- | --- |
| Caso de uso T12 (`docs/casos_de_uso/turnos_y_reservas_usecases.md`) | Pasos 1 a 5 y alternativos 2.1, 3.1 y 4.1. Contexto: T09 y T13 |
| Diagrama de secuencia (`docs/secuencias/turnos_y_reservas_secuencia.md`) | §7, parte de T12 |
| Contrato de turnos (`docs/contratos/turnos_y_reservas_contrato.md`) | §1 (T12 se dispara por tiempo, sin endpoint) y §2 (estados) |
| Modelo de dominio de turnos (`docs/turnos_y_reservas_dominio.md`) | `ReservationProcess`, §2; reglas 6, 7, 8, 9 y 14 |
| Anexo de la cátedra | §18.3 (un estado final no se reabre). T12 no llama a la cátedra |
| Decisiones 013 y 015 | Candado por usuario; qué significa la marca |
| SPEC de T08, T09 y T10 | D5 de T08 (cerrar apaga la marca), D2 de T09 (leer, tomar el candado, releer), D2 de T10 (T12 cierra lo que T10 deja afuera) |

## Qué construimos y para quién

<!-- Qué necesidad cubre, qué actor la inicia (la app KMP, otro servicio, el
tiempo o la cátedra) y qué podrá hacer o recibir. Describe el objetivo en
lenguaje de negocio. Resume y referencia: no copies la descripción del caso de uso. -->

El tiempo dispara un barrido que deja al día los procesos de reserva vencidos que nadie volvió a usar. Así, un proceso abandonado por el usuario no queda abierto para siempre, y uno con el teléfono enviado llega a T13 aunque nadie lo consulte. No contacta a la cátedra.

## Situación actual

<!-- Qué existe hoy y es relevante (código, datos, otras funcionalidades ya
implementadas), la limitación que queremos resolver y los comportamientos
existentes que deben conservarse. Si todavía no hay código para esto, indícalo.
No describas la arquitectura. -->

- Están implementados T01 a T11, X01 y la validación del JWT. T13 todavía no.
- T04, T05 y T06 cierran por vencimiento el proceso que usan, y T09 cierra o marca el que le consultan. T10 solo lee: deja afuera los `HELD` y `WAITING_FOR_PHONE` vencidos, sin cerrarlos. Nadie resuelve hoy un vencido que el usuario no vuelve a tocar.
- La regla del vencido ya existe en el modelo (`hasExpired`, y lo que T09 usa para resolverlo). También el candado por usuario y la relectura antes de escribir (decisión 013, D2 de T09).
- El servicio ya tiene tareas programadas, pero ninguna sobre procesos de reserva.
- Debe conservarse: un estado final no se reabre; un resultado de la cátedra que llega durante el barrido no se pisa.

## Dentro del alcance

<!-- Requisitos concretos, con identificadores estables para vincularlos a
criterios, decisiones del plan y tareas. Cada requisito indica el paso o flujo
alternativo del que proviene. No agregues requisitos que los documentos de
referencia no tengan sin consultarlo. -->

- **RF-01** · T12 paso 1: el barrido se dispara solo, a un intervalo configurable, 1 minuto por defecto (D2). El primero corre al arrancar, sin esperar un intervalo (D4).
- **RF-02** · T12 paso 2 y alternativo 2.1, guía de backend (concurrencia): nunca corren dos barridos a la vez. Si ya hay uno en curso, la ejecución se omite y la próxima repite la búsqueda.
- **RF-03** · T12 paso 3, dominio regla 7: busca los procesos abiertos cuyo `expiresAt`, más el margen de seguridad ya acordado, venció, de todos los usuarios. Incluye los `HELD` y `WAITING_FOR_PHONE` marcados (D1), y excluye los `PHONE_SUBMITTED` ya marcados (RF-05).
- **RF-04** · T12 paso 4, dominio regla 8: un proceso en `HELD` o `WAITING_FOR_PHONE` encontrado pasa a `EXPIRED`, y la marca queda apagada (dominio regla 9).
- **RF-05** · T12 paso 4, dominio regla 8: un proceso en `PHONE_SUBMITTED` encontrado no se cierra: queda marcado para reconciliar, sigue en `PHONE_SUBMITTED` y conserva sus datos. Uno ya marcado no se vuelve a escribir.
- **RF-06** · Guía de backend (concurrencia), decisión 013: el barrido no pisa otra operación. Cada proceso se decide de nuevo, con el candado de su usuario y volviendo a leerlo: si mientras tanto se cerró, se resolvió o cambió, queda como está. Una operación de un usuario no espera a todo el barrido, solo a lo que se esté escribiendo de ese usuario.
- **RF-07** · T12 alternativo 4.1: si falla la persistencia de un proceso, se registra el error de ese proceso, se sigue con los demás, y ese proceso queda como estaba para el próximo ciclo. Del error se registra solo el tipo, porque su mensaje podría incluir el teléfono.
- **RF-08** · T12 paso 5 y alternativo 3.1: cada barrido registra su resultado, aunque sea 0: cantidad de procesos cerrados y fecha. Además, por ser datos del mismo barrido, los marcados y los que fallaron.
- **RF-09** · Guía de backend (dependencias): si falla la búsqueda (por ejemplo, la base caída), se registra, el barrido termina sin cambios y las ejecuciones siguientes siguen corriendo.
- **RF-10** · T12, descripción: es local. No llama a la cátedra, no publica ni consume en Kafka.

## Fuera de alcance

<!-- Exclusiones acordadas, no deducidas por el agente. Incluye las partes de los
casos de uso que se dejan para otra funcionalidad. Si no hay exclusiones
adicionales, indícalo tras revisarlo con la persona. -->

- La reconciliación (T13). T12 solo deja la marca.
- El cierre reactivo de T04, T05, T06 y T09: no cambia.
- Borrar procesos cerrados: se conservan (dominio regla 14).
- Más de una instancia del servicio: vale el mismo límite que el candado (decisión 013).

## Flujo de la operación

<!-- Cómo se inicia (request, evento o tarea programada), qué verifica y qué
resultado produce, incluidas las alternativas relevantes. Si el caso de uso ya
lo describe, referencia sus pasos en lugar de repetirlos y describe solo lo que
cubre esta funcionalidad. -->

Los pasos 1 a 5 de T12 y la secuencia §7 describen el flujo. Lo que esta SPEC agrega:

1. El barrido arranca por tiempo (RF-01) y se omite si hay otro en curso (RF-02).
2. La búsqueda se hace sin candado (RF-03).
3. Cada proceso encontrado se resuelve de a uno: candado de su usuario, relectura, y cierre o marca solo si todavía corresponde (RF-04 a RF-06).
4. Termina registrando el resultado (RF-08).

## Datos y reglas de negocio

<!-- Información que necesita la operación, campos obligatorios, validaciones,
límites y reglas como duplicados u orden de presentación. Describe significado
y comportamiento, sin diseñar tablas, DTO, DAO ni almacenamiento. -->

- **Vencido** es lo mismo que en el resto del servicio: el instante actual supera `expiresAt` más el margen de seguridad (dominio regla 7), con el reloj del servicio. El límite se compara igual en la búsqueda y al decidir.
- Los procesos cerrados por T12 quedan `EXPIRED`: T10 no los lista (dominio regla 12), T09 responde ese estado y el usuario puede bloquear otro turno.
- T12 no cambia ningún otro dato del proceso. Un `PHONE_SUBMITTED` conserva su teléfono, y el dato no aparece en logs.
- Un proceso marcado en `HELD` o `WAITING_FOR_PHONE` se cierra igual al vencer, y la marca se apaga (D1).

## Casos alternativos y de error

<!-- Adapta la tabla usando BACKEND_GUIDELINES.md. Cuando el caso de uso ya define
el flujo alternativo, referencia su ID en lugar de repetirlo. Añade escenarios
relevantes. Marca No aplica con su motivo cuando corresponda. Expresa resultados
observables (estado HTTP, código de error, estado final del recurso, evento
publicado), no mecanismos técnicos. -->

| Situación | Comportamiento esperado |
| --- | --- |
| Entrada inválida | No aplica: lo dispara el tiempo, sin entrada. Un intervalo mal configurado: se define en el PLAN, con el criterio de las demás propiedades del servicio |
| Sin sesión o sin permiso | No aplica: no hay endpoint |
| Recurso inexistente o ajeno | Un proceso que desapareció entre la búsqueda y la relectura se omite, sin error (RF-06) |
| Dependencia no disponible (cátedra, catálogo, Redis, Kafka) | No aplica: es local (RF-10) |
| Timeout o espera excesiva | Si la base no responde, vale el límite de espera del servicio (D5 de T09) y RF-07 o RF-09 |
| Operación o mensaje repetido | Un proceso ya cerrado o ya marcado no se escribe de nuevo (RF-05, RF-06) |
| Evento tardío o fuera de orden | Un resultado de la cátedra que llega después del cierre no lo reabre (T08 5.1, dominio regla 6). Uno que llega antes de escribir gana: la relectura lo ve (RF-06) |
| Falla de persistencia | T12 4.1 (RF-07); falla de la búsqueda, RF-09 |
| Solicitudes simultáneas | Dos barridos, RF-02. Barrido y operación del mismo usuario, RF-06 |
| Reinicio con operaciones pendientes | El barrido no guarda estado: el que corre al arrancar encuentra lo que quedó vencido (D4) |
| Otros puntos aplicables de la guía | Observabilidad: RF-07 y RF-08. Fechas: instantes en UTC. Privacidad: RF-07 |

**Puntos de la guía no aplicables y motivo:** entrada y validación, autenticación, contrato HTTP y paginación: no hay endpoint. Migraciones de datos: se define en el PLAN si la búsqueda necesita un índice.

## Restricciones del pedido

<!-- Condiciones ya impuestas: compatibilidad, límites de alcance, requisitos
de seguridad o rendimiento medibles, o una tecnología expresamente exigida.
Ejemplo: Respetar el contrato de Documentacion y el anexo de la cátedra puede ser
una restricción; el diseño de los adaptadores va en PLAN.md.
No conviertas una preferencia del agente en una restricción. -->

- Respetar la máquina de estados del dominio (§2) y las reglas 6, 8 y 9.
- Las pruebas no llaman a la cátedra real: T12 no habla con ella.

## Criterios de aceptación

<!-- Resultados observables que permitan decidir si se cumple cada requisito.
Incluye los casos alternativos acordados: cada flujo alternativo cubierto debería
tener un criterio. No uses Funciona correctamente.
Repite el formato según sea necesario. -->

- **CA-01 · RF-01:** Dado un intervalo configurado, cuando pasa ese tiempo, entonces el barrido corre sin que nadie lo pida, y vuelve a correr al intervalo siguiente.
- **CA-02 · RF-02:** Dado un barrido en curso, cuando se dispara otro, entonces no corren a la vez y la ejecución omitida no cambia nada.
- **CA-03 · RF-03, RF-04:** Dado un proceso en `HELD` y otro en `WAITING_FOR_PHONE`, sin marca y vencidos con el margen, cuando corre el barrido, entonces los dos quedan `EXPIRED`.
- **CA-04 · RF-03:** Dado un proceso cuyo `expiresAt` venció pero sin superar el margen, cuando corre el barrido, entonces sigue como estaba.
- **CA-05 · RF-05:** Dado un proceso en `PHONE_SUBMITTED` vencido y sin marca, cuando corre el barrido, entonces queda marcado, en `PHONE_SUBMITTED` y con su teléfono.
- **CA-06 · RF-05:** Dado un proceso en `PHONE_SUBMITTED` vencido y ya marcado, cuando corre el barrido, entonces no se escribe.
- **CA-07 · RF-03:** Dados procesos en `CONFIRMED`, `CANCELLED`, `EXPIRED` e `INVALID` con `expiresAt` pasado, cuando corre el barrido, entonces no cambian.
- **CA-08 · RF-06:** Dado un proceso encontrado que un resultado de la cátedra confirma antes de que el barrido lo escriba, cuando el barrido llega a él, entonces queda `CONFIRMED`.
- **CA-09 · RF-06:** Dada una operación del mismo usuario con el candado tomado, cuando el barrido llega a su proceso, entonces espera a que termine y decide con lo que ella dejó.
- **CA-10 · RF-07:** Dados tres procesos vencidos y una falla al guardar el segundo, cuando corre el barrido, entonces el primero y el tercero quedan cerrados, se registra el error del segundo sin su teléfono, y el ciclo siguiente lo cierra.
- **CA-11 · RF-08:** Dado un barrido sin vencidos, cuando corre, entonces no cambia nada y registra cantidad 0. Dado uno con cierres y marcas, entonces registra las cantidades reales.
- **CA-12 · RF-09:** Dada la base caída, cuando corre el barrido, entonces se registra el error; cuando la base vuelve, el barrido siguiente cierra los vencidos.
- **CA-13 · RF-10:** Dado cualquier barrido, entonces no se hace ningún pedido a la cátedra simulada ni se publica nada en Kafka.
- **CA-14 · RF-04:** Dado un usuario cuyo proceso cerró el barrido, cuando pide bloquear otro turno, entonces no recibe `OPEN_PROCESS_EXISTS`, y T09 le informa `EXPIRED`.
- **CA-15 · RF-03, RF-04 (D1):** Dado un proceso en `HELD` o `WAITING_FOR_PHONE`, marcado y vencido con el margen, cuando corre el barrido, entonces queda `EXPIRED` y sin marca.
- **CA-16 · RF-01 (D4):** Dada una aplicación que arranca con un proceso vencido en la base, cuando termina de arrancar, entonces el proceso se cierra sin esperar un intervalo.

## Cómo se comprueba el comportamiento

<!-- Una fila por criterio: escenario y resultado que debemos comprobar.
La selección de tests, herramientas, comandos y evidencias se desarrolla en PLAN.md.
No marques los criterios como superados durante la especificación. -->

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | App completa con un intervalo corto y un proceso vencido | Queda cerrado sin ningún pedido; un segundo vencido se cierra en el ciclo siguiente |
| CA-02 | Un barrido retenido en la mitad y otro disparado | El segundo se omite; no hay dos escrituras |
| CA-03 a CA-07 | Procesos reales en Postgres, reloj controlado | Estado y marca de cada uno, leídos de la base |
| CA-08 | Resultado aplicado entre la búsqueda y la escritura | `CONFIRMED`, con sus datos |
| CA-09 | Candado tomado por otra operación durante el barrido | El barrido espera y respeta lo que quedó |
| CA-10 | Falla al guardar a pedido en el segundo proceso | Dos cerrados, un error en el log sin teléfono, cerrado en el ciclo siguiente |
| CA-11 | Barridos con y sin vencidos | Línea de resultado con las cantidades |
| CA-12 | Postgres pausado y reanudado | Error registrado y recuperación sola |
| CA-13 | Cátedra simulada y registro de pedidos | Ningún pedido |
| CA-14 | Bloquear con la app completa después del barrido | El bloqueo se acepta; T09 responde `EXPIRED` |
| CA-15 | Procesos marcados en Postgres, reloj controlado | `EXPIRED`, con la marca apagada |
| CA-16 | App completa que arranca con un vencido ya guardado y un intervalo largo | Cerrado sin que pase ese intervalo |

## Decisiones pendientes

<!-- Al resolverlas, actualiza las secciones afectadas. Escribe Ninguna cuando
no queden pendientes funcionales ni restricciones por decidir. -->

- **D1 · Un proceso marcado y vencido en `HELD` o `WAITING_FOR_PHONE` (opción a, decidida por el usuario el 2026-11-04):** T12 lo cierra como `EXPIRED`, como dicen T12 paso 4 y la regla 8 del dominio, y la marca se apaga (regla 9). No cambia ningún documento. Motivo: el margen de seguridad hace muy improbable perder una reserva. La cátedra solo puede confirmar antes de `expiresAt` (anexo §10) y T12 cierra después de `expiresAt` más el margen, así que el evento tiene al menos ese margen para llegar. Para perderla, T06 tiene que dar timeout con el mensaje entregado igual, el evento tiene que demorarse más que el margen o no llegar, y T13 no tiene que haber revisado el proceso antes. **Limitación aceptada:** en ese caso queda una reserva confirmada que el usuario no ve. Si el evento llega tarde, D6 de T08 lo deja en el log; si no llega, nada lo avisa. Sin comprobar: que la cátedra respete `expiresAt` al procesar el teléfono, porque no publica los pedidos. Descartadas: (b) que T12 y T09 no los cierren y decida T13, que deja abiertos también los `HELD` marcados por T05, sin riesgo porque sin teléfono no se confirman, y cambia cuatro documentos; (c) que tampoco los cierren T04, T05 y T06, que cambia tres casos de uso para un caso muy raro.
- **D2 · Valor por defecto del intervalo (opción a, decidida por el usuario el 2026-11-04):** 1 minuto, configurable. Es una consulta local, y T04, T09 y T10 ya tratan los vencidos por su cuenta. Descartadas: (b) 5 minutos, que demora la marca para T13 sin un ahorro que se note; (c) 30 segundos, sin beneficio visible.
- **D3 · Prueba contra la cátedra real (opción a, decidida por el usuario el 2026-11-04):** no se agrega al E2E. T12 no habla con la cátedra; alcanza con integración sobre Postgres real y la cátedra simulada, para comprobar que no recibe pedidos. Descartada: (b) un E2E que espera el vencimiento real, que tarda más de 10 minutos, deja un turno tomado y repite lo que prueba la integración.
- **D4 · Primer barrido (opción b, decidida por el usuario el 2026-11-04):** corre al arrancar, sin esperar un intervalo. **Limitación aceptada:** tras una caída, puede cerrar un proceso marcado antes de que el consumo de Kafka, que arranca después del login técnico, lea un `AppointmentConfirmed` pendiente. T08 solo lo registra como error (D6 de T08), y queda una reserva confirmada que el usuario no ve. Es el mismo caso raro de D1, sumado a una caída en ese momento. Descartada: (a) esperar un intervalo, que reduce ese caso sin garantizarlo, porque el login puede tardar más.

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el alcance está acordado, las referencias apuntan a pasos y flujos
que existen, los flujos son coherentes, los puntos de backend relevantes están
cubiertos y cada requisito tiene criterios comprobables.
Resuelve las dudas y los marcadores pendientes. Mantén el diseño técnico en PLAN.md.
-->
