# 010 — Tests simulados a partir de respuestas reales y suite E2E separada

- **Fecha:** 2026-10-24
- **Funcionalidad:** T03 — Consultar turnos libres (`docs/sdd/t03-turnos-libres/`, decisión P6 del PLAN)
- **Estado:** aceptada

## Situación

Desde X01, los tests de Maven nunca usan la cátedra real: la simulan con un servidor HTTP del JDK, y Surefire les pasa un `.env` inexistente para que nunca lean las credenciales locales. La cátedra real se probaba a mano, con la aplicación levantada y `curl`.

T03 es el primer caso de uso que consulta datos reales de la cátedra en cada request (las ocupaciones) y también el primero que depende del servicio de catálogo, que todavía no existe.

## Problema

Solo con tests simulados, la integración con la cátedra se apoya en lo que el anexo dice que responde, no en lo que responde de verdad. Una diferencia (un campo con otro formato, un error sin `code`) aparecería recién en la prueba manual o en producción. Pero si los tests usaran la cátedra real:

- dejarían de pasar cuando la cátedra no está disponible, aunque el código esté bien;
- no se podrían correr sin credenciales, por ejemplo en un clon nuevo o en GitHub Actions;
- no permitirían provocar los casos que no controlamos, como un `503` o un timeout.

## Decisión

Dos suites, cada una con su función:

**1. Suite normal (la de siempre): simulada, pero realista.**

- Es la que corren `./mvnw test`, `./mvnw verify` y GitHub Actions. Nunca usa la cátedra real ni lee el `.env`.
- Las respuestas que devuelve el servidor simulado salen de **respuestas reales grabadas de la cátedra**: el login, las ocupaciones y los errores que se puedan provocar, como un `401` con un token inválido o un `400` con un rango de fechas al revés. Se guardan como archivos en `src/test/resources/`.
- **Antes de guardarlas se limpian.** El JWT técnico, la contraseña de Redis y cualquier otro valor sensible se reemplazan por valores falsos, y se verifica que no quede ninguno real antes de versionarlas.
- Lo que no se puede provocar en la cátedra real (un `503`, un timeout, una conexión rechazada) se arma a mano, como hasta ahora.

**2. Suite E2E: contra la cátedra real, separada y opcional.**

- Vive en `src/test/java/e2e/`, fuera del paquete `turnos`, para que se vea que es otra suite. Como desde ahí `@SpringBootTest` no encuentra la aplicación solo, se le indica (`classes = TurnosServiceApplication.class`).
- Sus tests llevan la etiqueta `e2e`. La suite normal la excluye, y se corren solo con `./mvnw test -Pe2e`.
- El perfil `e2e` es el único que deja leer el `.env` local, de donde salen las credenciales de la cátedra, como en la aplicación. Las credenciales nunca pasan por el chat ni por el repositorio.
- Si no hay credenciales, los tests quedan **salteados**, no fallidos, con un mensaje que lo explica.
- Siguen la secuencia real todo lo posible: registro (T01), inicio de sesión (T02) y el caso de uso con el token que devolvió el login. Usan una base Postgres de Testcontainers, limpia en cada corrida, y la clave de test para firmar el JWT. Lo que se quiere probar contra lo real es la cátedra, no la base.
- Mientras el servicio de catálogo no exista, el catálogo sigue simulado también en los E2E. Cuando exista, se reemplaza por el real.
- La suite crece con cada caso de uso. En T03 solo consulta, sin cambiar nada en la cátedra. Desde T04, los E2E que creen datos reales (por ejemplo, un hold) tienen que deshacerlos al terminar, para no dejar turnos ocupados.

Alternativas descartadas:

- **Solo tests simulados:** no detectan diferencias entre el anexo y la cátedra real.
- **E2E dentro de la suite normal:** se romperían sin credenciales o con la cátedra caída, y no se podrían correr en GitHub Actions.
- **E2E en una carpeta de fuentes o un módulo de Maven aparte:** suman configuración y plugins para el mismo resultado práctico que un paquete, una etiqueta y un perfil.
- **E2E dentro del paquete `turnos`:** quedarían mezclados con los tests del servicio.

## Qué no cambia

La suite normal sigue sin leer el `.env` ni usar credenciales reales, y el test que lo comprueba sigue valiendo. La prueba manual con la aplicación levantada sigue siendo útil para ver el servicio completo, pero ya no es la única evidencia contra la cátedra real.

Excepción (2026-10-27, T04): la cátedra no permite cancelar un hold, así que el E2E de T04 intenta deshacerlo y, si no puede, lo informa y deja que venza solo (D9 de la SPEC de T04).

Actualización (2026-10-29, T07, D8 de su SPEC): **la suite E2E verifica turnos usando los servicios reales; no verifica el comportamiento de esos servicios** (ni de la cátedra ni de catálogo). Ninguna prueba contra sistemas reales puede afectar lo de otros alumnos ni romper el servicio de turnos real ni la cátedra; puede dejar algo escrito en lo propio si no tiene efecto. Cómo se aplica a Kafka (consumer group propio de tests, lectura sin consumer group): decisión 014.

Actualización (2026-10-30, PLAN de T07, P8): **Kafka en las dos suites.**

- **Suite normal:** Kafka real en Testcontainers, como Postgres, con un solo contenedor compartido. La cátedra simulada devuelve en el login los servidores del contenedor y se publican los mensajes reales grabados. Los tests que no son de Kafka apagan el consumo con `turnos.catedra.kafka.enabled` (variable `TURNOS_CATEDRA_KAFKA_ENABLED`, verdadera por defecto en la aplicación): si no, intentarían conectarse a los servidores inventados que devuelve la cátedra simulada.
- **Suite E2E:** después de T07, todo E2E hace el login técnico real y el consumo arrancaría con el consumer group del servicio. Por eso la configuración común de la suite E2E obliga a una de dos cosas: usar el consumer group de tests que el usuario define en su `.env` con **`TURNOS_E2E_KAFKA_CONSUMER_GROUP`** (el nombre dice que es del E2E; la aplicación no lee esa variable), o, si está vacía, apagar el consumo y leer los mensajes reales sin consumer group.
- **Grabaciones de Kafka:** se leen sin consumer group, con un programa de un solo archivo en el scratchpad que corre el usuario, y se limpian como las de REST antes de versionarlas.
