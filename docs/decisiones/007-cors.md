# 007 — CORS: ningún navegador habilitado por defecto, configurable por entorno

- **Fecha:** 2026-10-23
- **Funcionalidad:** Validación del JWT en endpoints protegidos (`docs/sdd/validacion-jwt/`, RF-13, decisión DG)
- **Estado:** aceptada

## Situación

Los navegadores aplican la *same-origin policy*: el JavaScript de una página solo puede leer respuestas de su mismo origen (protocolo, host y puerto). CORS (*Cross-Origin Resource Sharing*) es la forma en que un servidor le avisa al navegador que acepta a otros orígenes, con encabezados como `Access-Control-Allow-Origin`. Cuando el request lleva `Authorization` o un cuerpo JSON, como todos los de esta API, el navegador manda antes una consulta previa (*preflight*): un `OPTIONS` sin token que pregunta si el request real está permitido.

Solo los navegadores aplican CORS. La app Android nativa, Postman, `curl` y el servicio de catálogo no mandan `Origin` y lo ignoran. Por eso CORS no frena a un atacante con `curl`: lo que evita es que una página cualquiera use el navegador de un visitante para llamar a la API y leer las respuestas.

El enunciado (§9) pide configurar CORS de acuerdo con los clientes permitidos y documentar las decisiones de seguridad. Hasta esta funcionalidad no había ninguna configuración CORS en el servicio.

## Problema

El único cliente previsto es la app nativa, que no necesita CORS. Había que decidir:

- Si se configura CORS explícitamente o se deja sin configuración.
- Qué navegadores pueden usar la API, y cómo se habilitaría uno en el futuro (por ejemplo, si se agrega la segunda plataforma de KMP, que el enunciado deja opcional).
- Cómo convive con la validación del JWT: el preflight nunca lleva token, así que si se lo validara como cualquier request, ningún navegador permitido podría llamar a la API.

## Decisión

Se configura CORS explícitamente en la cadena de seguridad (`config/SecurityConfig`), con los orígenes permitidos leídos de la configuración externa:

- **Variable `TURNOS_CORS_ALLOWED_ORIGINS`** (propiedad `turnos.cors.allowed-origins`): orígenes exactos separados por comas. **Vacía por defecto: ningún navegador.** Para habilitar una web se define la variable y se reinicia el servicio, sin tocar código.
- **No usar `*`.** Habilitaría cualquier página del mundo, que es justo lo que CORS evita.
- **Lo que se permite a un origen habilitado:** métodos `GET`, `POST` y `OPTIONS`; encabezados `Authorization` y `Content-Type`; se expone `Retry-After` (lo usa T04); sin credenciales de navegador (cookies), porque la identidad viaja en el token.
- **El preflight se resuelve antes que la validación del token.** Si el origen está permitido, se responde sin pedir token; los requests reales siguen las reglas de siempre, y sus respuestas, incluido el `401`, llevan los encabezados CORS para que el navegador pueda leerlas.
- **La configuración se registra aunque la lista esté vacía.** Si Spring no encuentra ninguna configuración, deja pasar el preflight sin evaluarlo. Registrándola siempre, cualquier origen no permitido recibe la respuesta estándar de rechazo de Spring: `403` con el texto `Invalid CORS request`, también en un request real con `Origin`.

Alternativas descartadas:

- **No configurar CORS y documentarlo.** Era válida: se comprobó que, sin configuración, el preflight termina en `401` porque llega sin token, y un request con token tampoco lleva `Access-Control-Allow-Origin`, así que ningún navegador puede usar la API. Se descartó porque el rechazo dependería de otra regla (la validación del token), de modo que una ruta abierta para todos los métodos quedaría disponible para cualquier navegador sin que nadie lo decidiera, y porque habilitar una web exigiría cambiar código. El usuario lo evaluó después de implementada la configuración y decidió mantenerla.
- **Habilitar orígenes fijos en el código:** cada cambio exigiría una nueva versión del servicio.

## Qué no cambia

Los clientes que no mandan `Origin`, como la app nativa, se comportan exactamente igual con y sin orígenes configurados: CORS no interviene. La protección CSRF sigue desactivada por los motivos de la decisión 006; CORS y CSRF son mecanismos distintos (CSRF evita que una página use credenciales automáticas del navegador para *enviar* acciones; CORS decide si una página puede *leer* respuestas). El servicio de catálogo decide su propio CORS cuando se desarrolle.
