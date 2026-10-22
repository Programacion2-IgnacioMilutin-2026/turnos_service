# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)

## 2026-10-13
### Added
- Documentacion relacionada con el servicio: contratos con otros servicios, casos de uso, modelo de dominio y diagramas de secuencia.

## 2026-10-15
### Added
- Creada la estructura del proyecto y dependencias añadidas. Springboot con Postgres compose container.

## 2026-10-16
### Added
- Slice base de User con sus tests creados. No se incluyen clases y directorios que no so utilizados todavia.

## 2026-10-17
### Added
- Directorios y archivos que utiliza el caso de uso T01-Registrar-Usuario.
- Tests de los archivos del caso de uso T01-Registar-Usuario.
- Tests de Integracion del slice user utlizando Testcontainer.
- ApiExceptionHandler en carpeta config para manejar el formato de errores y excepciones.
- SecurityConfig en carpeta config para personalizar la seguridad de endpoints y requests.
### Changed
- Caso de uso T01 dentro de docs modifica su flujo alternativo

## 2026-10-20
### Added
- Archivo .gitignore
- Documentacion del SDD para aplicar el caso de uso T02 (SPEC, PLAN y TASK) y una toma de decision relacionada con la firma del jwt
- Archivos y tests relacionados con el caso de uso T02-IniciarSesion
- Property al pom.xml y buil.yaml en .github/workflow/ para integrar SonarQube

## 2026-10-21
### Added
- Documentacion de SDD para aplicar el caso de uso X01-AutenticacionCuentaTecnica.
- Slice Integration. Contiene la integracion/login de con el servicio de catedra.
- Clock en config para reintentos de inicio de sesion a la catedra.
- Dependencia en pom.xml para poder elgir un .env distinto al real en los tests.
- Tests del slice Integracion. Incluy servidor HTTP para simular la catedra.

## 2026-10-22
### Changed
- En userController se cambio el orden de llamada a toDomain para que sonarqube no marque el error 'A NULL object should not be accessed'.