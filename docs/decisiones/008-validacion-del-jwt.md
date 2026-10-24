# 008 — Validación del JWT: dónde vive y cómo obtiene la identidad cada controller

- **Fecha:** 2026-10-23
- **Funcionalidad:** Validación del JWT en endpoints protegidos (`docs/sdd/validacion-jwt/`, decisiones P1 a P3 del PLAN)
- **Estado:** aceptada

## Situación

Turnos emite el JWT de los usuarios en T02 (decisión 004) y, desde esta funcionalidad, lo valida en todos sus endpoints protegidos (T03 a T06 y T09 a T11, contrato común §1.4). La validación es transversal: ocurre en la cadena de filtros de Spring Security, antes de que el request llegue a cualquier controller, y no es un caso de uso.

Para validar hace falta saber cosas que hoy conoce solo el slice `user`: la clave (la misma que firma), el formato de los claims (`sub`, `auth`, `iat`, `exp`) y la lista de roles (el enum `Authority`, en `user/domain/model`).

## Problema

La skill de arquitectura hexagonal no define dónde vive la seguridad transversal. El proyecto ya tiene una regla para el paquete `config` (decisiones 003 y siguientes): solo arma cosas técnicas y **no importa nada de los slices**, y los slices no importan `config`. Había que resolver tres cosas sin romper esa regla:

1. Quién arma el validador, que necesita la clave y los roles de `user`.
2. Cómo se ignora el token en las rutas públicas (`POST /api/register` y `POST /api/authenticate`), que deben aceptar cualquier `Authorization`, aunque sea inválido. Por defecto, Spring lee el encabezado en todas las rutas y rechazaría un token inválido incluso en las públicas.
3. Cómo obtienen la identidad del usuario los controllers de los casos de uso protegidos, que vivirán en otros slices.

## Decisión

**1. El slice `user` ofrece las piezas; `config` solo las conecta.** `user/infrastructure/security/JwtValidationConfiguration` arma tres beans con tipos de Spring:

- el `JwtDecoder`: solo `RS256` con la clave pública que se obtiene de la privada (`JwtSigningKeys`, la misma clase que usa la firma), vencimiento sin tolerancia de reloj, `exp` obligatorio y `sub` formado solo por dígitos dentro del rango de un `Long`;
- el conversor del claim `auth` a autoridades de Spring;
- la regla de autorización "al menos uno de los roles de `Authority`".

`config/SecurityConfig` los recibe por esas interfaces de Spring, sin importar ninguna clase del slice. La emisión y la validación comparten clave, claims y roles en un solo lugar, y el enum sigue siendo la única lista de roles: uno nuevo queda aceptado sin tocar `config`, coherente con "cualquier rol" del contrato. Un endpoint futuro que exija un rol puntual necesitará su propia regla.

**2. Una sola cadena de seguridad con un lector de token que ignora las rutas públicas.** La misma lista de rutas públicas alimenta el `permitAll` y el lector del token, así no pueden quedar desparejas. Los rechazos (`401` y `403`) los escribe `config/SecurityProblemHandler` con un cuerpo fijo por estado, sin `code` y sin revelar el motivo; el `401` lleva `WWW-Authenticate: Bearer`, sin la descripción que agrega Spring por defecto.

**3. El controller recibe el `Jwt` validado y pasa valores simples al service.** Es la convención para T03 en adelante:

```java
@PostMapping("/reservation-processes")
public ResponseEntity<ReservationProcessResponse> createReservationProcess(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody ReservationProcessRequest request) {
    Long userId = Long.valueOf(jwt.getSubject());
    // el service recibe userId y, si reenvía el token a catálogo, jwt.getTokenValue()
}
```

El `Jwt` queda en la capa web, igual que los DTO; el service y el caso de uso reciben un `Long` y un `String`, sin conocer Spring Security, y se prueban sin Spring. `Long.valueOf` no puede fallar, porque el validador ya garantiza que el `sub` es numérico. La identidad nunca sale de un parámetro, del cuerpo ni de otro encabezado.

**Esta convención puede cambiar.** Si al implementar T03 a T11 la extracción se repite demasiado, o la identidad necesita más datos, se revisa con un paquete `shared` (*shared kernel*, skill §9) que contenga un tipo para la identidad y su armado. Es la alternativa más consistente después de la actual.

Alternativas descartadas:

- **Que `config` importe `Authority`, o arme la validación por su cuenta:** rompe la regla de que `config` no importa los slices.
- **Escribir los roles como texto en `config`:** dos listas de roles que hay que mantener iguales.
- **Mover `Authority` a un paquete `shared` ahora:** el dominio de `user` pasaría a importar un tipo de fuera de su slice (otra excepción a la skill) para un solo enum que hoy usa un solo slice.
- **Dos cadenas de seguridad (públicas y protegidas):** repiten la configuración común y la línea del CSRF.
- **Un tipo propio de identidad (`AuthenticatedUser`) en `config` o en `user`:** todos los slices protegidos pasarían a importar ese paquete, y dentro de `user` no hay una capa que otro slice pueda usar para algo técnico como esto.
- **Leer la identidad desde el caso de uso (`SecurityContextHolder`):** la capa `application` dependería de Spring Security.

## Qué no cambia

La firma del token de T02 es la misma (decisión 004): solo se separó la carga de la clave en `JwtSigningKeys`, para que la use también la validación. No se agrega configuración: la clave pública sale de la privada, y si la privada falta o no sirve, el servicio no arranca, como antes. La validación no consulta la base ni guarda estado (contrato común §1.6): un token vale hasta su `exp`, también después de reiniciar, mientras no cambie el par de claves. La forma de usar la integración con la cátedra (decisión 005) no cambia.
