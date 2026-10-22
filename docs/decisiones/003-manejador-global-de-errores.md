# 003 — Manejador global de errores (`ApiExceptionHandler`)

- **Fecha:** 2026-10-17
- **Funcionalidad:** T01 — Registrar usuario
- **Estado:** aceptada

## Situación

El contrato exige que todos los errores de la API respondan con `application/problem+json` y los campos `status`, `title`, `code` y `detail`. Los errores de validación agregan además la lista `fieldErrors`. La skill de arquitectura hexagonal resuelve los errores de otra forma: cada controller atrapa las excepciones de su slice y las traduce a un estado HTTP. Además, prohíbe agregar un `@ControllerAdvice` global, salvo pedido explícito.

## Problema

Ese patrón alcanza para los errores de negocio, como un login o un email ya registrados. Pero hay tres casos que ocurren fuera del alcance del controller:

- Los errores de validación de `@Valid`, que Spring lanza antes de ejecutar el método.
- El cuerpo JSON mal formado, que falla al leerse.
- Los errores inesperados, como una base de datos caída. T01 exige registrarlos y responder `500` sin exponer detalles.

Además, sin un manejador, ni siquiera los errores que relanza el controller salen en formato `problem+json` con su `code`.

## Decisión

Por eso se agregó un único `@RestControllerAdvice`, `ApiExceptionHandler`, en el paquete transversal `turnos.config`, como excepción autorizada a la skill. Extiende el manejador de errores de Spring y cumple tres funciones:

- Da formato `problem+json` a los errores que relanzan los controllers.
- Arma `VALIDATION_ERROR` con `fieldErrors`.
- Convierte cualquier error no previsto en `500 INTERNAL_ERROR`, dejando el error real en el log.

## Qué no cambia

El manejador no valida ni decide nada del negocio: solo da forma a errores que generan otros. Cada controller sigue decidiendo el estado y el `code` de las excepciones de su slice. Como el manejador no importa ninguna clase de los slices, la regla de dependencias de la arquitectura se mantiene.
