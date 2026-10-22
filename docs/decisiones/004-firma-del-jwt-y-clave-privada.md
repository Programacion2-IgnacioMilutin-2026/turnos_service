# 004 — Firma del JWT y manejo de la clave privada

- **Fecha:** 2026-10-20
- **Funcionalidad:** T02 — Iniciar sesión (`docs/sdd/t02-login/`)
- **Estado:** aceptada

## Situación

En T02, turnos es el único emisor del JWT de los usuarios. El contrato común (§1.1 a §1.3) fija que el token se firma con `RS256`, usando un par RSA de 2048 bits: turnos guarda la clave privada en su configuración externa y catálogo recibe solo la pública. La clave privada no se versiona, no se entrega a catálogo y no aparece en logs.

## Problema

Había que resolver varias cuestiones que los documentos no detallan:

- Cómo llega la clave privada a la aplicación, en desarrollo y en los tests, sin versionarla.
- Qué hace el servicio si la clave falta o no sirve.
- Si turnos necesita también la clave pública, y cuándo.
- Dónde viven, en la arquitectura, la firma y su configuración. La skill no lo define.

## Decisión

- **Firma como puerto de salida.** El caso de uso depende de `TokenIssuer`, en `domain/ports/out`, igual que de `PasswordHasher` (decisión 001). Lo implementa `JwtTokenIssuerAdapter`, en `infrastructure/security`, con `NimbusJwtEncoder` de Spring Security, que ya estaba en el proyecto. Su configuración (`JwtProperties`) vive junto al adaptador, que es el único que la usa.
- **La clave se entrega como archivo.** La variable `TURNOS_JWT_PRIVATE_KEY_LOCATION` indica la ubicación del archivo PEM (en desarrollo, `file:./secrets/jwt-private.pem`). No se pega el contenido en una variable: un PEM tiene varias líneas, que se manejan mal en `.env` y en Docker Compose. `secrets/` y `*.pem` están en el `.gitignore`, y no se versiona ninguna clave de ejemplo: cualquier clave subida sería una clave real utilizable.
- **Sin clave válida, el servicio no arranca.** Si la variable falta, el archivo no existe, no es una clave RSA PKCS#8 o tiene menos de 2048 bits, la aplicación se detiene al iniciar con un mensaje que nombra la propiedad y nunca muestra la clave. La alternativa, arrancar y responder `500` en cada inicio de sesión, mostraría el error tarde y ante un usuario.
- **Solo se configura la clave privada.** El adaptador obtiene la clave pública de la privada, porque una clave PKCS#8 incluye el módulo y el exponente público. La pública se configura en turnos y en catálogo cuando cada uno empiece a validar tokens (T03 y C06). No se expone por un endpoint: el contrato la distribuye por configuración.
- **Los tests generan su propio par en cada corrida** y le pasan a la aplicación la ubicación de un archivo temporal.

## Qué no cambia

El caso de uso no conoce JWT, RSA ni la configuración: solo pide un token para un usuario. Cambiar la forma de firmar afecta solo a `infrastructure/security`. El contrato no cambia: el token lleva exactamente `sub`, `auth`, `iat` y `exp`. El encabezado incluye además un `kid`, que agrega `NimbusJwtEncoder`; el contrato no lo exige ni lo prohíbe. La rotación de la clave sigue siendo manual, como define el contrato común §1.6.
