# 001 — Puerto de salida `PasswordHasher` y paquete `infrastructure/security`

- **Fecha:** 2026-10-17
- **Funcionalidad:** T01 — Registrar usuario
- **Estado:** aceptada

## Situación

En T01 la contraseña llega en texto plano y debe guardarse solo como hash (T01, paso 5). El enunciado exige además un mecanismo criptográfico adecuado y prohíbe guardarla en texto plano. El hash lo calcula una librería externa (Spring Security), y quien necesita usarlo es el caso de uso `RegisterUserUseCaseImpl`, en la capa `application`.

## Problema

La skill de arquitectura hexagonal solo prevé un tipo de puerto de salida, `{Entity}Repository`, y en `infrastructure` solo los paquetes `persistence` y `web`. No dice cómo debe acceder un caso de uso a una capacidad técnica que no es persistencia. La alternativa directa, inyectar el `PasswordEncoder` de Spring Security en el caso de uso, no viola la letra de la skill, pero ata la capa `application` a una librería de seguridad.

## Decisión

Se definió el puerto de salida `PasswordHasher` en `domain/ports/out`, con el método `String hash(String password)`. Su implementación, `Pbkdf2PasswordHasherAdapter`, vive en el paquete nuevo `infrastructure/security`, autorizado como excepción a la skill y marcado con un comentario en el código.

Alternativas descartadas:

- Inyectar el `PasswordEncoder` de Spring Security en el caso de uso: menos piezas, pero `application` queda acoplada a la librería.
- Hashear en el controller: una regla de seguridad quedaría en la capa web, y cualquier otra entrada al caso de uso podría saltearla.
- Guardar la contraseña en `passwordHash` hasta hashearla: el campo mentiría sobre su contenido durante parte del recorrido, y un error podría persistirla en texto plano.

## Qué no cambia

El caso de uso sigue dependiendo solo de interfaces del dominio, igual que con `UserRepository`, y la regla de dependencias se mantiene: el puerto no importa nada de Spring Security. Cambiar el algoritmo afecta solo a `infrastructure/security`, y eso ya se comprobó al pasar de BCrypt a PBKDF2 (decisión 002) sin tocar el caso de uso. Como beneficio adicional, el caso de uso se prueba sin Spring, con un hasher falso. El mismo patrón queda disponible para otras capacidades técnicas que aparezcan después, como la firma del JWT en T02.
