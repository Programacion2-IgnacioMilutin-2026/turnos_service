# 009 — Slices pasarela hacia la cátedra y hacia catálogo

- **Fecha:** 2026-10-24
- **Funcionalidad:** T03 — Consultar turnos libres (`docs/sdd/t03-turnos-libres/`, decisiones A, B, P1 y P2 del PLAN)
- **Estado:** aceptada

## Situación

Hasta T02 cada slice trabajaba solo. X01 creó el slice dueño de la sesión técnica con la cátedra, que se llamó `integration` hasta T03 y desde entonces se llama `catedra` (ver Nombres). T03 necesita, por primera vez, datos de otros sistemas: los horarios del profesional, que vienen del servicio de catálogo (C06), y las ocupaciones, que vienen de la cátedra. No será la única vez:

- La cátedra la van a llamar también T04 (crear un hold), T05 (confirmarlo), T11 (cancelar) y T13 (listar reservas). Todas esas llamadas siguen las mismas reglas: llevan el JWT técnico, un `401` pide un login nuevo y repite la operación una sola vez, un `403` `STUDENT_INTEGRATION_NOT_FOUND` es un error de configuración, y los `5xx` y los timeouts se reintentan con espera creciente.
- C06 lo vuelve a usar T04.

## Problema

La skill de arquitectura hexagonal no permite que un slice use las clases internas de otro, y deja en manos del usuario cómo se resuelve una dependencia entre slices (§9). Había que decidir:

1. Dónde viven los clientes hacia la cátedra y hacia catálogo, para no repetir en cada caso de uso las reglas del `401`, los reintentos y la traducción de errores.
2. Cómo usa esos clientes el slice de cada caso de uso sin acoplarse a ellos.
3. Cómo se llaman los slices, para que se entienda con qué sistema habla cada uno.

## Decisión

**Un slice "pasarela" por cada sistema externo.** Es la única puerta del servicio hacia ese sistema y aplica adentro sus reglas comunes:

- `catedra`: la sesión técnica (X01) y las operaciones con la cátedra. En T03 suma "consultar ocupaciones". También resuelve qué pasa si la sesión no está habilitada: en error de configuración responde que hay un problema de configuración; degradada, pide un login nuevo y, si no lo consigue, responde que la cátedra no está disponible. Así nadie más necesita conocer el estado de la sesión.
- `catalog`: las consultas al servicio de catálogo. En T03, C06 con sus reintentos.

Cada operación devuelve un resultado cerrado (por ejemplo, encontrado, no existe, no disponible, error de configuración, respuesta inesperada), para que quien la usa decida qué responder sin conocer HTTP.

**El slice del caso de uso usa las pasarelas por puertos propios.** `availability` (T03) declara en su dominio lo que necesita, con sus propios tipos: "la agenda del profesional" y "las ocupaciones de un día". Esos puertos los implementan adaptadores de su capa de infraestructura que llaman al puerto de entrada de `catalog` y de `catedra`, y traducen sus resultados. Es la opción "puerto de salida hacia el otro slice" de la skill (§9):

```
availability                                              catalog / catedra
  caso de uso ──▶ domain/ports/out (propio)                domain/ports/in
                       ▲ lo implementa                          ▲ lo llama
              infrastructure/gateway/… ─────────────────────────┘
```

- El dominio y la aplicación de `availability` no importan nada de otro slice.
- Los adaptadores solo usan los puertos de entrada y los modelos de las pasarelas, nunca su `application` ni su `infrastructure`.
- Viven en un paquete propio, `infrastructure/gateway`, que solo se usa para esto: todos los cruces entre slices quedan juntos y a la vista. Es una excepción a la skill, con su comentario en el código.
- Cada slice tiene su copia de los datos que usa (por ejemplo, el horario semanal existe en `catalog` y en `availability`). La duplicación es buscada: cada slice evoluciona sin romper al otro, y los adaptadores traducen.

**Nombres.** Java no admite guiones en los paquetes, así que no se puede usar `integration-catalog`. Se eligieron nombres cortos y simétricos que dicen con qué sistema habla cada uno: `catedra` y `catalog`. `catedra` ya era el nombre de las propiedades (`turnos.catedra.*`) y de las clases (`CatedraLoginClientAdapter`, `CatedraProperties`). Por eso el slice de la sesión técnica, que hasta T03 se llamaba `integration`, pasó a llamarse `catedra`, como primera tarea de T03 y sin cambios de comportamiento. Los documentos SDD de X01 conservan el nombre anterior, porque registran lo que se hizo en su momento, y llevan una nota al principio que lo aclara. En el contrato de turnos y en la SPEC de X01, `integration` también es el nombre del campo JSON que devuelve el login de la cátedra; ese no cambia.

Alternativas descartadas:

- **Que cada slice tenga sus propios clientes:** T04 repetiría el cliente de C06, y cada caso de uso volvería a escribir las reglas del `401`, los reintentos y los errores de la cátedra. Además, el slice de la sesión técnica tendría que exponer su estado.
- **Que el caso de uso llame directamente al puerto de entrada de la pasarela:** acoplaría la aplicación de `availability` a los tipos de otro slice.
- **Poner los adaptadores en `infrastructure/client`:** mezclaría las llamadas HTTP reales con las llamadas a otro slice del mismo programa.
- **Los nombres `catedraintegration` y `catalogintegration`, o conservar `integration`:** los primeros son largos y difíciles de leer; el segundo no dice con qué se integra ahora que hay dos.

## Qué no cambia

La sesión técnica funciona igual (decisión 005): un solo login a la vez, el intervalo mínimo entre logins y el error de configuración sin reintentos. Las propiedades de la cátedra no cambian de nombre. `config` sigue sin importar ningún slice. En T03, la lógica del `401` y los reintentos está dentro de la primera operación de `catedra`; cuando T04 agregue la segunda, se extrae a una pieza común del mismo slice.
