# turnos_service

Los comandos se ejecutan desde la raíz de este repositorio.

## Requisitos

- Docker en ejecución: lo usan el compose y los tests.
- Un archivo `.env` creado a partir de `.env.example`, con `TURNOS_DB_PASSWORD` completo. No se versiona.

## Comandos

| Para | Comando |
| --- | --- |
| Levantar Postgres de desarrollo y esperar a que esté listo | `docker compose up -d --wait` |
| Ver el estado de Postgres | `docker compose ps` |
| Correr la aplicación (necesita Postgres levantado) | `./mvnw spring-boot:run` |
| Correr todos los tests (Testcontainers crea su propio Postgres; no usan el compose) | `./mvnw test` |
| Correr una sola clase de test | `./mvnw test -Dtest=NombreDeLaClase` |
| Compilar desde cero y correr los tests | `./mvnw clean test` |
| Apagar Postgres conservando los datos | `docker compose down` |
| Apagar Postgres y borrar los datos | `docker compose down -v` |

## Datos del proyecto

- `{basePackage}` de la skill `hexagonal-vertical-slicing`: `turnos`.
- La aplicación escucha en el puerto 8080; Postgres en `TURNOS_DB_PORT` (5432 por defecto).
