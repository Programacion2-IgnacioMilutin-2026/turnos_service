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
### Added
- Documentacion de decision tomada acerca de porque se desactiva el CSRF
### Changed
- En UserController se cambio el orden de llamada a toDomain para que sonarqube no marque el error 'A NULL object should not be accessed'.
- Expresion regular de UserRequest para pasar solucionar los issues de sonarqube. Se cambiaron las alternativas de esta y se soluciono para evitar StackOverflow en un input largo.

## 2026-10-24
### Added
- Documentacion de SDD para la validacion de jwt, utlizado pra proteger endpoints.
- Archivos relacionados con la validacion del jwt.
- Tests de la validacion del jwt.
- Tests para el CORS.
- Documentacion de decisiones sobre la validacion del jwt y configuracion de CORS.
- Se maneja error 'no resource' para que no revelar informacion.
### Changed
- SecurityConfig ahora funciona con la cadena de seguridad, sumando el CORS.
- La configuracion del CORS se suma al application.yaml. Le agrega los orgines permitidos.
- Se modificaron los test de otras funciones pra que funcionen a corde a esta actualizacion.

## 2026-10-26
### Added
- Servidor http para tests simulando el servicio de catedra y de catalogo.
- Slice 'Catalog'. Maneja las comunicaciones con el servicio de catalogo. 
- Archivos para la comunicacion con la catedra. Dentro de slice 'Catedra'.
- Slice 'Availability'. Maneja los turnos libres.
- Tests para lo añadido en 'Catalog', 'Catedra' y 'Availability'.
- Cache de 'Availability'.
- Endpoint para obtener 'free-slots'.
- Test End to End que incluye solo la consulta de turnos libres (T03). Solo llamados con el servicio de catedra ya que en este momento el servicio de catalogo no existe. 
- Documentacion para el SDD de T04-CrearHold y las decisiones tomadas.
### Changed
- Se cambio el nombre de el slice 'Integration' a 'Catedra', representando y manejando las comunicaciones con la catedra.
- application.yaml actualizado con lo incluido en el slice 'Availability'.
- Documentacion de contratos, casos de uso y secuencias debido a decisiones tomadas para aplicar T04-CrearHold.

## 2026-10-27
### Added
- Mas atributos en slice 'Catalog'. Los que se agregan porque los utiliza el caso de uso T04-CrearHold.
- Pieza comun a slice 'Catedra'.
- Archivos para la creacion del hold en slice 'Catedra'. Es la comunicacion y pedrile al servicio de catedra.
- En slice 'User', saber si existe un usuario, para utliarlo en el caso de uso T04-CrearHold.
- Nuevo slice 'Reservation'. Todos sus archivos principales y los creados debido al caso de uso T04-CrearHold.
- Tests para todo lo añadido en el dia de la fecha.
- Se incluyela creacion del hold en el test E2E.
### Changed
- Application.yaml para que se adapte al caso de uso T04-CrearHold.

## 2026-10-28
### Added
- Documentacion de SDD para el caso de uso T05-ConfirmarReserva
### Changed
- Documentacion actualizada debido a que se descubrio que una confrimacion no reincia el contador expiresAt de un proceso abierto.

## 2026-10-29
### Added
- Archivos a slice 'User' para poder realizar la consulta por a traves de un UsaerName.
- Archivos a slice 'Catedra' para manejar la confirmacion de un hold con el servicio de catedra.
- En slice 'Reservation', se sumo el atributo needsReconciliation, por lo que se agrega a todos los archivos que nombren a los atributos. Ademas de funciones para cambiar el valor de este atributo.
- Lock o candado general en slice 'Reservation', de esta forma el hold y luego de confirmar utlizan el mismo lock. 
### Changed
- Se descubrio que la catedra devuelve HOLD_NOT_FOUND cuiando este vence, por lo que se actualizo la documentacion para que sea consistente con la realidad.
- Ahora el candado del hold del slice 'Reservation' es general, por lo que se lo quito y modifico del CreateReservationProcessUseCaseImpl.