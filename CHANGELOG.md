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
- Caso de uso de confirmacion de hold en slice 'Reservation', con su gateway para poder realizar estos hablar con el slice 'Catedra'.
- Endpoint para confirmar el hold en slice 'Reservation' e integracion con la applicacion.
- Tests para todos los archivos agregados en este dia. 
- Se agregan las nuevas funcionalidades al test E2E.
### Changed
- Se descubrio que la catedra devuelve HOLD_NOT_FOUND cuiando este vence, por lo que se actualizo la documentacion para que sea consistente con la realidad.
- Ahora el candado del hold del slice 'Reservation' es general, por lo que se lo quito y modifico del CreateReservationProcessUseCaseImpl.

## 2026-10-31
### Added
- archivo de SDD para el caso de uso T07-ProcesarSolicitudInformaciónAdicional
- El login tecnico avisa a kafka que esta listo para consumir
- En slice 'Reservation', se añade la entidad evento, con sus archivos para la persistencia incluidos.
- Caso de uso en slice 'Reservation', su implementacion y tests para procesar un pedido de telefono de un proceso de reserva.
- Caso de uso y archivos en slice 'Catedra' para que pueda recibir peticion de telefono y reenviarlo al slice 'Reservation'.
### Changed
- Ahora la escritura en el repositorio de ReservationProcess, junto con la del evento, lo hace TransactionRunner para que sean consitente entre ellas.

## 2026-11-02
### Added
- Documentacion de SDD para el caso de uso T08-ResultadoReserva.
- Nuevos estados a 'Reservation' y los metodos que cambian estados, implementados con la alogica de cuando se deberian cambiar.
- Caso de uso de slice 'Reservation' para manejar el resultado de una reserva y sus distintos estados.}
- En slice 'Catedra', arhcivos para manejar los resultados de una reserva.
- Listener de kafka en slice 'Catedra' para obtener los resultados.
- Tests para los archivos agregados en el caso de uso T08.
- Documentacion de SDD para el caso de uso T06-InformarTelefono.
- En slice 'Reservation', validacion del numero de telefono y transiciones en la subida de este.
- Publisher de kafka para sunir el numerro de telefono.
- Caso de uso para publicar el numero de telefono y su gateway para comunicarse con catedra. Dentro de slice 'Reservation'.
- Endpoint en slice 'Reservation' para la publicacion del numero de telefono.
- Tests para los archivos agregados en el caso de uso T06. El test E2E al momento no funciona correctamente hasta que el servicio de catedra este arreglado
### Changed
- Actualizado el nombre de algunas clases para que estan mas acorde a lo que hacen, ya que se les añaden funcionalidades en T08.
- Ahora tambien vuelve a intentar cuando falla al leer la base, antes solo cuando escribia.
- Documentacion actualizada debido a decisiones tomadas.

## 2026-11-03
### Added
- Documentacion de SDD para el caso de uso T09-Consultar estado de un proceso de reserva.
- Metodos para manejar vencimientos para un proceso de reserva.
- Caso de uso en slice 'Reservation' para ver el estado de un proceso y decidir que hacer.
- Endpoint para traer un proceso de reserva por su id.
- Tests de los archvios agregados en el caso de uso T09.
- Tiempo que espera del servicio a al base de datos. Colocado en 30 segundos.
- Documentacion de SDD para el caso de uso T10-ConsultarMisReservas.}
- Query para obtener el listado de reservas, con paginado y filtro de estados.
- Caso de uso, service y endpoint para obtner las reservas de un usuario
- Documentacion de SDD para el caso de uso T11-CancelarReserva.
- Operacion para cancelar una reserva.
- Caso de uso, gateway y endpoint en slice 'Reservation' para cancelar una reserva.
- Tests de la funcionalidad cancelar una reserva, incluido la extension del test E2E.
### Changed
- Actualizados archivos de documentacion con las nuevas reglas o decisiones tomadas para T09, T10 y T11.

## 2026-11-04
### Added
- Documentacion de SDD para el caso de T12-Expirar procesos vencidos.
- Query para en ver los procesos que vencieron.
- Caso de uso en slice 'Reservation' para expirar los precesps de reserva vencidos.
- Schedulling en infraestructure de slice 'Reservation' encargado del barrido.
- Variables de entorno sobre duracion y activacion de la barrida periodica.
- Tests para el barrido.

## 2026-11-06
### Added 
- Documentacion de SDD para el caso de uso T13-ReconciliarProcesos.
- Modelos en slice 'Reservation' para reconciliar procesos.
- Archivos en slice 'Catedra' para obtener el estado real de una reserva en el servicio de catedra (utlizado por T13).
### Changed
- Documentacion debido a decisiones tomadas en T13.