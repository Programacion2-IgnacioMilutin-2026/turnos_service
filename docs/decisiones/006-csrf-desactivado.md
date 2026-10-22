# 006 — Protección CSRF desactivada en la API de turnos

- **Fecha:** 2026-10-22
- **Funcionalidad:** T01 y T02 (`config/SecurityConfig`); revisada por un issue de SonarQube (`java:S4502`)
- **Estado:** aceptada

## Situación

`SecurityConfig` desactiva la protección CSRF de Spring Security (`csrf(AbstractHttpConfigurer::disable)`) desde T01. Al analizar el PR de `develop` a `main`, SonarQube la marcó como vulnerabilidad crítica (*"Make sure disabling Spring Security's CSRF protection is safe here"*) y el Quality Gate quedó con la calificación de seguridad en D.

## Problema

Un ataque CSRF engaña al navegador de una persona con sesión iniciada para que envíe un request a la aplicación. Funciona porque el navegador adjunta automáticamente las credenciales que tiene guardadas para ese sitio, normalmente una cookie de sesión, y la aplicación cree que el request es legítimo. La protección de Spring lo evita exigiendo un token secreto extra en cada `POST`, `PUT` o `DELETE`.

Había que decidir si esa protección es necesaria en turnos, o si desactivarla deja una puerta abierta.

## Decisión

Se mantiene desactivada, y el issue se marcó en SonarQube como falso positivo con la justificación. Las condiciones que hacen posible un ataque CSRF no existen en este servicio:

| Condición del ataque | En turnos |
| --- | --- |
| Una credencial que el navegador envía solo, como una cookie de sesión | No hay: la API es sin estado (`SessionCreationPolicy.STATELESS`), no crea sesiones ni cookies |
| Identidad transmitida de forma automática | No: desde T03 la identidad viaja en el encabezado `Authorization: Bearer <jwt>`, que el navegador nunca agrega solo y que una página ajena no conoce |
| Un navegador como cliente | No: el cliente es la app KMP |

Con la protección activa, en cambio, Spring rechazaría con `403` todo `POST` sin token CSRF (se comprobó en T01 al quitar la línea), y para usarla la app tendría que recibir el token en una cookie: justamente el mecanismo que hace posible el ataque. La documentación de Spring Security recomienda desactivarla en servicios usados solo por clientes que no son navegadores.

Alternativas descartadas:

- Activar CSRF con el token en una cookie: agrega una cookie sin ganar seguridad y obliga a la app a un paso extra.
- Eximir solo algunas rutas: SonarQube también lo marca, y no cambia el razonamiento.

## Qué no cambia

La seguridad del servicio se apoya en el JWT del usuario (contrato común §1.4), que se valida en cada endpoint protegido desde T03. Si en el futuro se agregara un cliente web que se autentique con cookies, esta decisión deja de valer y hay que reactivar la protección CSRF.
