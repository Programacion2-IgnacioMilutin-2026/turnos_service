# 002 — PBKDF2 en lugar de BCrypt para el hash de contraseñas

- **Fecha:** 2026-10-17
- **Funcionalidad:** T01 — Registrar usuario
- **Estado:** aceptada

## Situación

El plan inicial era hashear las contraseñas con BCrypt, el algoritmo que usa JHipster. El contrato de turnos (§3.1) y T01 permiten contraseñas de 4 a 100 caracteres.

## Problema

BCrypt solo procesa los primeros 72 bytes de la contraseña. Las versiones nuevas de Spring Security, en lugar de recortarla sin avisar, lanzan una excepción. Se verificó con la versión del proyecto (`spring-security-crypto` 7.1.1):

| Contraseña | Resultado con BCrypt |
| --- | --- |
| 72 caracteres (72 bytes) | Correcto, hash de 60 caracteres |
| 73 caracteres | `IllegalArgumentException: password cannot be more than 72 bytes` |
| 100 caracteres | La misma excepción |
| 40 `ñ` (80 bytes, cada `ñ` ocupa 2) | La misma excepción |

Con BCrypt, contraseñas que el contrato acepta como válidas terminarían en un error `500`.

## Decisión

Se usa PBKDF2 (`Pbkdf2PasswordEncoder` con los parámetros por defecto de Spring Security). Viene incluido en `spring-security-crypto`, así que no agrega dependencias. Acepta contraseñas de 100 caracteres y genera un hash de 96 caracteres, que entra en la columna `password_hash VARCHAR(100)`. Es uno de los algoritmos que recomienda OWASP.

Alternativas descartadas:

- Argon2id: es la primera opción de OWASP, pero requiere agregar BouncyCastle al proyecto y su hash, de unos 97 caracteres, entra muy justo en la columna.
- BCrypt limitando la contraseña a 72 bytes: obliga a cambiar el contrato §3.1, T01 y A01, y el límite queda en bytes y no en caracteres, lo que es difícil de explicar a quien se registra.

## Qué no cambia

No cambia ningún comportamiento observable: el hash nunca sale del backend, y el enunciado solo exige un mecanismo criptográfico adecuado. El cambio quedó contenido en `Pbkdf2PasswordHasherAdapter`, gracias al puerto `PasswordHasher` (decisión 001). Un test fija los casos límite: contraseñas de 4 y de 100 caracteres, y 50 `ñ` (100 bytes).
