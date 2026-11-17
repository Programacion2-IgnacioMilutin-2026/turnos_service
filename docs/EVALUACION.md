<!-- evaluacion:inicio -->
# Evaluación — Programacion2-IgnacioMilutin-2026/turnos_service

> Documento **generado automáticamente** por la cátedra de Programación II (UM): RepoSense + análisis de calidad de **7 dimensiones**. **No editar a mano**: se regenera en cada evaluación.
> Rama evaluada: `main`. Análisis **heurístico** (regex + métricas) + build: es una criba para orientar la revisión, **no una nota**. La **documentación** del repositorio no se evalúa.
> Generado: 2026-11-17.

## 1. Veredicto

| Aspecto | Resultado |
|---|---|
| Índice de calidad | **77/100** |
| Tipo de repositorio | `backend` |
| Build (`mvnw verify` / `gradlew check`) | OK |
| Código (LOC) | 27,869 |
| Archivos de código | 431 |
| Clases | 553 |
| Código dentro de clases (%POO) | 73% |
| Funciones fuera de clases | 0 |
| Duplicación (puerta) | 4.3% |
| Duplicación lógica | 4.6% |
| Duplicación de boilerplate | 15.4% |
| Cobertura | 98% |
| Tope duro aplicado a D1 | `POO_BAJO` → D1 ≤ 60 |

> Este informe **describe** lo que el análisis midió con los criterios ya establecidos de la materia. No es una nota.

## 2. Semáforo de aprobación

Criterios de la cátedra (`CRITERIOS-CALIDAD.md`): **Aprobar** se juega con el perfil `estandar`; **destacar**, con el `exigente` (meta de excelencia).

| Semáforo | Resultado |
|---|---|
| Aprobar (`estandar`) | **no cumple** — 6/11 |
| Destacar (`exigente`) | **no cumple** — 5/11 |

**Para aprobar, falta cumplir:** SOLID; Duplicación

**Para destacar, falta cumplir:** SOLID; POO; Duplicación

## 3. Autoría (RepoSense)

| Autor | Commits | Código (LOC) | Tests (LOC) | Docs (LOC) | Días activos | % del código |
|---|---:|---:|---:|---:|---:|---:|
| Ignacio Milutin | 108 | 9,252 | 24,063 | 12,049 | 24 | 100% |

> _El LOC de autoría lo calcula RepoSense sobre el historial y puede diferir del LOC de código del análisis (que mira el estado de `main`)._

## 4. Dimensiones evaluadas

Cada dimensión indica **qué mide**, **por qué importa** y **tu resultado**. Cuando el puntaje queda por debajo de la referencia, se agregan preguntas para **investigar la causa** (no hay recetas).

| Dimensión | Peso | Puntaje |
|:--|--:|--:|
| **D1** Diseño OO, SOLID y patrones (20%) | 20% | 1 |
| **D2** Arquitectura hexagonal — backend (15%) | 15% | 100 |
| **D3** Frontend KMP: MVVM y buenas prácticas (10%) | 10% | N/A |
| **D4** Integración y contrato (10%) | 10% | 90 |
| **D5** Seguridad y robustez (10%) | 10% | 100 |
| **D6** Pruebas y cobertura (20%) | 20% | 100 |
| **D7** Proceso y entrega (15%) | 15% | 100 |

### D1. Diseño OO, SOLID y patrones (20%)

- **Qué mide:** Métricas orientadas a objetos (clases, métodos, campos, cohesión, acoplamiento, herencia) y proxies de SOLID, más patrones de diseño.
- **Por qué importa:** El eje de la materia es el buen uso de la POO y su arquitectura: un diseño con clases claras y poco acopladas es más fácil de entender, probar y cambiar.
- **Tu resultado:** **1/100**
- **Para investigar la causa:**
    - ¿Se pensó el modelo de clases antes de codificar o se agrupó después?
    - ¿Qué clases concentran muchas responsabilidades a la vez?
    - ¿Los colaboradores llegan como parámetros o se crean por dentro?

### D2. Arquitectura hexagonal — backend (15%)

- **Qué mide:** Separación en capas (dominio, puertos de entrada/salida, adaptadores) y dirección de las dependencias del backend.
- **Por qué importa:** Aislar el dominio de la infraestructura permite cambiar la base de datos, el framework o el transporte sin tocar las reglas de negocio.
- **Tu resultado:** **100/100**

### D4. Integración y contrato (10%)

- **Qué mide:** Cumplimiento del contrato de integración de la cátedra (formato de errores, paginación, autenticación, endpoints esperados).
- **Por qué importa:** Los servicios deben poder integrarse entre sí y con la app sin sorpresas; el contrato es lo que hacen cumplir las pruebas de integración.
- **Tu resultado:** **90/100**

### D5. Seguridad y robustez (10%)

- **Qué mide:** Credenciales embebidas, consultas SQL por concatenación, y presencia de config de entorno; aislamiento de secretos.
- **Por qué importa:** Un secreto en el repositorio o una consulta construida por concatenación son riesgos concretos, no hipotéticos.
- **Tu resultado:** **100/100**

### D6. Pruebas y cobertura (20%)

- **Qué mide:** Cantidad y resultado de pruebas y cobertura de línea (JaCoCo/Kover, Surefire/Failsafe) cuando hay build.
- **Por qué importa:** Las pruebas son la red de seguridad para refactorizar e integrar; la cobertura acota las zonas sin verificar.
- **Tu resultado:** **100/100**

### D7. Proceso y entrega (15%)

- **Qué mide:** Artefactos verificables de proceso/entrega: `compose`, `.env.example`, CI (`.github/workflows/`), `CODEOWNERS` y wrapper de build (`mvnw`/`gradlew`). **No** puntúa documentación.
- **Por qué importa:** Un proyecto que compila, se levanta y se valida solo es más fácil de revisar y de integrar en equipo.
- **Tu resultado:** **100/100**

## 5. Señales detectadas

El análisis encontró las siguientes señales (archivo, sin número de línea a propósito):

**SRP/responsabilidad** (97)
- clase 'KafkaConsumerManager' grande (LOC=121, métodos=15, campos=5) — `src/main/java/turnos/catedra/infrastructure/kafka/KafkaConsumerManager.java`
- clase 'KafkaConsumerManager' con baja cohesión (24%) — `src/main/java/turnos/catedra/infrastructure/kafka/KafkaConsumerManager.java`
- método 'KafkaConsumerManager.info' de 57 líneas — `src/main/java/turnos/catedra/infrastructure/kafka/KafkaConsumerManager.java`
- clase 'KafkaAdditionalInformationPublisher' grande (LOC=124, métodos=12, campos=6) — `src/main/java/turnos/catedra/infrastructure/kafka/KafkaAdditionalInformationPublisher.java`
- clase 'KafkaAdditionalInformationPublisher' con baja cohesión (17%) — `src/main/java/turnos/catedra/infrastructure/kafka/KafkaAdditionalInformationPublisher.java`
- método 'KafkaAdditionalInformationPublisher.send' de 34 líneas — `src/main/java/turnos/catedra/infrastructure/kafka/KafkaAdditionalInformationPublisher.java`
- método 'KafkaAdditionalInformationPublisher.send' con complejidad 8 — `src/main/java/turnos/catedra/infrastructure/kafka/KafkaAdditionalInformationPublisher.java`
- clase 'AppointmentActionReader' grande (LOC=169, métodos=15, campos=20) — `src/main/java/turnos/catedra/infrastructure/kafka/AppointmentActionReader.java`
- clase 'AppointmentActionReader' con baja cohesión (3%) — `src/main/java/turnos/catedra/infrastructure/kafka/AppointmentActionReader.java`
- método 'AppointmentActionReader.read' de 39 líneas — `src/main/java/turnos/catedra/infrastructure/kafka/AppointmentActionReader.java`
- método 'AppointmentActionReader.read' con complejidad 11 — `src/main/java/turnos/catedra/infrastructure/kafka/AppointmentActionReader.java`
- método 'AppointmentActionReader.toAction' con complejidad 7 — `src/main/java/turnos/catedra/infrastructure/kafka/AppointmentActionReader.java`
- clase 'AdditionalInformationSubmittedMessage' grande (LOC=19, métodos=0, campos=10) — `src/main/java/turnos/catedra/infrastructure/kafka/dto/AdditionalInformationSubmittedMessage.java`
- clase 'AppointmentActionMessage' grande (LOC=15, métodos=0, campos=13) — `src/main/java/turnos/catedra/infrastructure/kafka/dto/AppointmentActionMessage.java`
- clase 'CatedraHoldClientAdapter' grande (LOC=152, métodos=22, campos=6) — `src/main/java/turnos/catedra/infrastructure/client/CatedraHoldClientAdapter.java`
- clase 'CatedraHoldClientAdapter' con baja cohesión (8%) — `src/main/java/turnos/catedra/infrastructure/client/CatedraHoldClientAdapter.java`
- método 'CatedraHoldClientAdapter.startTime' con complejidad 7 — `src/main/java/turnos/catedra/infrastructure/client/CatedraHoldClientAdapter.java`
- método 'CatedraHoldClientAdapter.externalPatientId' con complejidad 7 — `src/main/java/turnos/catedra/infrastructure/client/CatedraHoldClientAdapter.java`
- método 'CatedraHoldClientAdapter.contentType' con complejidad 6 — `src/main/java/turnos/catedra/infrastructure/client/CatedraHoldClientAdapter.java`
- método 'CatedraHoldClientAdapter.accept' con complejidad 6 — `src/main/java/turnos/catedra/infrastructure/client/CatedraHoldClientAdapter.java`
- método 'CatedraHoldClientAdapter.body' con complejidad 6 — `src/main/java/turnos/catedra/infrastructure/client/CatedraHoldClientAdapter.java`
- método 'CatedraHoldClientAdapter.exchange' con complejidad 6 — `src/main/java/turnos/catedra/infrastructure/client/CatedraHoldClientAdapter.java`
- método 'CatedraHoldClientAdapter.wasNotSent' de 36 líneas — `src/main/java/turnos/catedra/infrastructure/client/CatedraHoldClientAdapter.java`
- método 'CatedraHoldClientAdapter.wasNotSent' con complejidad 7 — `src/main/java/turnos/catedra/infrastructure/client/CatedraHoldClientAdapter.java`
- método 'CatedraHoldClientAdapter.patientFirstName' con complejidad 6 — `src/main/java/turnos/catedra/infrastructure/client/CatedraHoldClientAdapter.java`
- método 'CatedraHoldClientAdapter.patientLastName' con complejidad 6 — `src/main/java/turnos/catedra/infrastructure/client/CatedraHoldClientAdapter.java`
- método 'CatedraHoldClientAdapter.confirmationRejection' con complejidad 9 — `src/main/java/turnos/catedra/infrastructure/client/CatedraHoldClientAdapter.java`
- clase 'CatedraAppointmentClientAdapter' grande (LOC=104, métodos=12, campos=7) — `src/main/java/turnos/catedra/infrastructure/client/CatedraAppointmentClientAdapter.java`
- clase 'CatedraAppointmentClientAdapter' con baja cohesión (17%) — `src/main/java/turnos/catedra/infrastructure/client/CatedraAppointmentClientAdapter.java`
- método 'CatedraAppointmentClientAdapter.rejected' con complejidad 7 — `src/main/java/turnos/catedra/infrastructure/client/CatedraAppointmentClientAdapter.java`
- clase 'CatedraLoginClientAdapter' con baja cohesión (8%) — `src/main/java/turnos/catedra/infrastructure/client/CatedraLoginClientAdapter.java`
- método 'CatedraLoginClientAdapter.login' de 36 líneas — `src/main/java/turnos/catedra/infrastructure/client/CatedraLoginClientAdapter.java`
- método 'CatedraLoginClientAdapter.login' con complejidad 9 — `src/main/java/turnos/catedra/infrastructure/client/CatedraLoginClientAdapter.java`
- clase 'CatedraOccupancyClientAdapter' con baja cohesión (14%) — `src/main/java/turnos/catedra/infrastructure/client/CatedraOccupancyClientAdapter.java`
- método 'CatedraOccupancyClientAdapter.fetchOccupancies' con complejidad 7 — `src/main/java/turnos/catedra/infrastructure/client/CatedraOccupancyClientAdapter.java`
- clase 'AppointmentResponse' grande (LOC=12, métodos=0, campos=10) — `src/main/java/turnos/catedra/infrastructure/client/dto/AppointmentResponse.java`
- clase 'CatedraOperationRunner' con baja cohesión (7%) — `src/main/java/turnos/catedra/application/support/CatedraOperationRunner.java`
- método 'CatedraOperationRunner.run' con complejidad 8 — `src/main/java/turnos/catedra/application/support/CatedraOperationRunner.java`
- clase 'RenewTechnicalSessionUseCaseImpl' con baja cohesión (10%) — `src/main/java/turnos/catedra/application/usecases/RenewTechnicalSessionUseCaseImpl.java`
- método 'RenewTechnicalSessionUseCaseImpl.attemptLogin' con complejidad 6 — `src/main/java/turnos/catedra/application/usecases/RenewTechnicalSessionUseCaseImpl.java`
- método 'CancelAppointmentUseCaseImpl.cancelAppointment' con complejidad 7 — `src/main/java/turnos/catedra/application/usecases/CancelAppointmentUseCaseImpl.java`
- clase 'FindAppointmentUseCaseImpl' con baja cohesión (0%) — `src/main/java/turnos/catedra/application/usecases/FindAppointmentUseCaseImpl.java`
- método 'FindAppointmentUseCaseImpl.problem' con complejidad 7 — `src/main/java/turnos/catedra/application/usecases/FindAppointmentUseCaseImpl.java`
- clase 'SubmitAdditionalInformationUseCaseImpl' sin métodos (4 campos): solo datos — `src/main/java/turnos/catedra/application/usecases/SubmitAdditionalInformationUseCaseImpl.java`
- método 'CreateHoldUseCaseImpl.createHold' con complejidad 7 — `src/main/java/turnos/catedra/application/usecases/CreateHoldUseCaseImpl.java`
- clase 'StartTechnicalSessionUseCaseImpl' con baja cohesión (20%) — `src/main/java/turnos/catedra/application/usecases/StartTechnicalSessionUseCaseImpl.java`
- método 'StartTechnicalSessionUseCaseImpl.startTechnicalSession' de 30 líneas — `src/main/java/turnos/catedra/application/usecases/StartTechnicalSessionUseCaseImpl.java`
- método 'StartTechnicalSessionUseCaseImpl.startTechnicalSession' con complejidad 8 — `src/main/java/turnos/catedra/application/usecases/StartTechnicalSessionUseCaseImpl.java`
- método 'GetOccupanciesUseCaseImpl.getOccupancies' con complejidad 7 — `src/main/java/turnos/catedra/application/usecases/GetOccupanciesUseCaseImpl.java`
- clase 'HoldConfirmationResult' grande (LOC=33, métodos=12, campos=0) — `src/main/java/turnos/catedra/domain/model/HoldConfirmationResult.java`
- clase 'Hold' sin métodos (3 campos): solo datos — `src/main/java/turnos/catedra/domain/model/Hold.java`
- clase 'AdditionalInformationSubmission' sin métodos (6 campos): solo datos — `src/main/java/turnos/catedra/domain/model/AdditionalInformationSubmission.java`
- clase 'JpaReservationProcessRepositoryAdapter' grande (LOC=122, métodos=16, campos=6) — `src/main/java/turnos/reservation/infrastructure/persistence/adapter/JpaReservationProcessRepositoryAdapter.java`
- método 'JpaReservationProcessRepositoryAdapter.findOverdue' de 34 líneas — `src/main/java/turnos/reservation/infrastructure/persistence/adapter/JpaReservationProcessRepositoryAdapter.java`
- método 'JpaReservationProcessRepositoryAdapter.findOverdue' con complejidad 6 — `src/main/java/turnos/reservation/infrastructure/persistence/adapter/JpaReservationProcessRepositoryAdapter.java`
- método 'JpaReservationProcessRepositoryAdapter.map' de 33 líneas — `src/main/java/turnos/reservation/infrastructure/persistence/adapter/JpaReservationProcessRepositoryAdapter.java`
- método 'JpaReservationProcessRepositoryAdapter.map' con complejidad 6 — `src/main/java/turnos/reservation/infrastructure/persistence/adapter/JpaReservationProcessRepositoryAdapter.java`
- clase 'ReservationProcessEntity' grande (LOC=47, métodos=4, campos=20) — `src/main/java/turnos/reservation/infrastructure/persistence/entity/ReservationProcessEntity.java`
- método 'CatedraEventAdapter.toReservationResult' con complejidad 6 — `src/main/java/turnos/reservation/infrastructure/gateway/CatedraEventAdapter.java`
- método 'CatedraHoldAdapter.requestHold' con complejidad 9 — `src/main/java/turnos/reservation/infrastructure/gateway/CatedraHoldAdapter.java`
- método 'CatalogProfessionalAdapter.getProfessional' con complejidad 6 — `src/main/java/turnos/reservation/infrastructure/gateway/CatalogProfessionalAdapter.java`
- método 'CatedraAppointmentAdapter.cancel' con complejidad 8 — `src/main/java/turnos/reservation/infrastructure/gateway/CatedraAppointmentAdapter.java`
- método 'CatedraAppointmentAdapter.find' con complejidad 6 — `src/main/java/turnos/reservation/infrastructure/gateway/CatedraAppointmentAdapter.java`
- método 'CatedraAppointmentAdapter.confirmedAt' con complejidad 6 — `src/main/java/turnos/reservation/infrastructure/gateway/CatedraAppointmentAdapter.java`
- método 'CatedraAppointmentAdapter.status' con complejidad 6 — `src/main/java/turnos/reservation/infrastructure/gateway/CatedraAppointmentAdapter.java`
- clase 'ReservationProcessDtoMapper' grande (LOC=65, métodos=10, campos=3) — `src/main/java/turnos/reservation/infrastructure/web/mapper/ReservationProcessDtoMapper.java`
- clase 'ReservationProcessDtoMapper' con baja cohesión (2%) — `src/main/java/turnos/reservation/infrastructure/web/mapper/ReservationProcessDtoMapper.java`
- clase 'ReservationProcessResponse' grande (LOC=17, métodos=0, campos=14) — `src/main/java/turnos/reservation/infrastructure/web/dto/ReservationProcessResponse.java`
- clase 'ReservationService' grande (LOC=44, métodos=10, campos=10) — `src/main/java/turnos/reservation/application/service/ReservationService.java`
- clase 'ReservationService' con baja cohesión (4%) — `src/main/java/turnos/reservation/application/service/ReservationService.java`
- clase 'CancelReservationProcessUseCaseImpl' con baja cohesión (21%) — `src/main/java/turnos/reservation/application/usecases/CancelReservationProcessUseCaseImpl.java`
- clase 'ExpireOverdueProcessesUseCaseImpl' con baja cohesión (27%) — `src/main/java/turnos/reservation/application/usecases/ExpireOverdueProcessesUseCaseImpl.java`
- clase 'ProcessReservationResultUseCaseImpl' grande (LOC=125, métodos=15, campos=5) — `src/main/java/turnos/reservation/application/usecases/ProcessReservationResultUseCaseImpl.java`
- clase 'ProcessReservationResultUseCaseImpl' con baja cohesión (7%) — `src/main/java/turnos/reservation/application/usecases/ProcessReservationResultUseCaseImpl.java`
- método 'ProcessReservationResultUseCaseImpl.applies' con complejidad 6 — `src/main/java/turnos/reservation/application/usecases/ProcessReservationResultUseCaseImpl.java`
- método 'ProcessReservationResultUseCaseImpl.apply' con complejidad 6 — `src/main/java/turnos/reservation/application/usecases/ProcessReservationResultUseCaseImpl.java`
- método 'SubmitPhoneUseCaseImpl.submit' con complejidad 6 — `src/main/java/turnos/reservation/application/usecases/SubmitPhoneUseCaseImpl.java`
- clase 'CreateReservationProcessUseCaseImpl' con baja cohesión (47%) — `src/main/java/turnos/reservation/application/usecases/CreateReservationProcessUseCaseImpl.java`
- clase 'ReconcilePendingProcessesUseCaseImpl' grande (LOC=150, métodos=12, campos=6) — `src/main/java/turnos/reservation/application/usecases/ReconcilePendingProcessesUseCaseImpl.java`
- clase 'ReconcilePendingProcessesUseCaseImpl' con baja cohesión (11%) — `src/main/java/turnos/reservation/application/usecases/ReconcilePendingProcessesUseCaseImpl.java`
- método 'ReconcilePendingProcessesUseCaseImpl.reconcilePendingProcesses' de 30 líneas — `src/main/java/turnos/reservation/application/usecases/ReconcilePendingProcessesUseCaseImpl.java`
- método 'ReconcilePendingProcessesUseCaseImpl.reconcile' con complejidad 6 — `src/main/java/turnos/reservation/application/usecases/ReconcilePendingProcessesUseCaseImpl.java`
- clase 'ReservationProcess' grande (LOC=307, métodos=34, campos=20) — `src/main/java/turnos/reservation/domain/model/ReservationProcess.java`
- método 'ReservationProcess.isOpen' con complejidad 6 — `src/main/java/turnos/reservation/domain/model/ReservationProcess.java`
- método 'ReservationProcess.reconcileWith' con complejidad 6 — `src/main/java/turnos/reservation/domain/model/ReservationProcess.java`
- método 'ReservationProcess.reconcileClosedWith' con complejidad 6 — `src/main/java/turnos/reservation/domain/model/ReservationProcess.java`
- clase 'ReservationHold' sin métodos (3 campos): solo datos — `src/main/java/turnos/reservation/domain/model/ReservationHold.java`
- método 'CatalogAgendaAdapter.getAgenda' con complejidad 6 — `src/main/java/turnos/availability/infrastructure/gateway/CatalogAgendaAdapter.java`
- clase 'CatalogClientAdapter' grande (LOC=107, métodos=12, campos=6) — `src/main/java/turnos/catalog/infrastructure/client/CatalogClientAdapter.java`
- clase 'CatalogClientAdapter' con baja cohesión (26%) — `src/main/java/turnos/catalog/infrastructure/client/CatalogClientAdapter.java`
- método 'CatalogClientAdapter.requestOnce' con complejidad 8 — `src/main/java/turnos/catalog/infrastructure/client/CatalogClientAdapter.java`
- clase 'WeeklySchedule' sin métodos (5 campos): solo datos — `src/main/java/turnos/catalog/domain/model/WeeklySchedule.java`
- clase 'Professional' sin métodos (6 campos): solo datos — `src/main/java/turnos/catalog/domain/model/Professional.java`
- clase 'UserEntity' grande (LOC=23, métodos=0, campos=12) — `src/main/java/turnos/user/infrastructure/persistence/entity/UserEntity.java`
- clase 'UserRequest' grande (LOC=26, métodos=0, campos=8) — `src/main/java/turnos/user/infrastructure/web/dto/UserRequest.java`
- clase 'UserResponse' grande (LOC=10, métodos=0, campos=8) — `src/main/java/turnos/user/infrastructure/web/dto/UserResponse.java`
- clase 'User' grande (LOC=17, métodos=1, campos=12) — `src/main/java/turnos/user/domain/model/User.java`

**DIP/concretos** (85)
- clase 'KafkaConsumerManager' muy acoplada (CBO=39) — `src/main/java/turnos/catedra/infrastructure/kafka/KafkaConsumerManager.java`
- clase 'KafkaConsumerManager' instancia 4 concretos (CBO=39) — `src/main/java/turnos/catedra/infrastructure/kafka/KafkaConsumerManager.java`
- clase 'KafkaAdditionalInformationPublisher' muy acoplada (CBO=46) — `src/main/java/turnos/catedra/infrastructure/kafka/KafkaAdditionalInformationPublisher.java`
- clase 'AppointmentActionListener' muy acoplada (CBO=8) — `src/main/java/turnos/catedra/infrastructure/kafka/AppointmentActionListener.java`
- clase 'AppointmentActionReader' muy acoplada (CBO=34) — `src/main/java/turnos/catedra/infrastructure/kafka/AppointmentActionReader.java`
- clase 'AppointmentActionReader' instancia 13 concretos (CBO=34) — `src/main/java/turnos/catedra/infrastructure/kafka/AppointmentActionReader.java`
- clase 'CatedraKafkaProperties' muy acoplada (CBO=19) — `src/main/java/turnos/catedra/infrastructure/kafka/CatedraKafkaProperties.java`
- clase 'CatedraHoldClientAdapter' muy acoplada (CBO=68) — `src/main/java/turnos/catedra/infrastructure/client/CatedraHoldClientAdapter.java`
- clase 'CatedraAppointmentClientAdapter' muy acoplada (CBO=45) — `src/main/java/turnos/catedra/infrastructure/client/CatedraAppointmentClientAdapter.java`
- clase 'CatedraLoginClientAdapter' muy acoplada (CBO=29) — `src/main/java/turnos/catedra/infrastructure/client/CatedraLoginClientAdapter.java`
- clase 'CatedraOccupancyClientAdapter' muy acoplada (CBO=33) — `src/main/java/turnos/catedra/infrastructure/client/CatedraOccupancyClientAdapter.java`
- clase 'CatedraOperationRunner' muy acoplada (CBO=29) — `src/main/java/turnos/catedra/application/support/CatedraOperationRunner.java`
- clase 'ReceiveAppointmentResultUseCaseImpl' muy acoplada (CBO=8) — `src/main/java/turnos/catedra/application/usecases/ReceiveAppointmentResultUseCaseImpl.java`
- clase 'RenewTechnicalSessionUseCaseImpl' muy acoplada (CBO=22) — `src/main/java/turnos/catedra/application/usecases/RenewTechnicalSessionUseCaseImpl.java`
- clase 'CancelAppointmentUseCaseImpl' muy acoplada (CBO=15) — `src/main/java/turnos/catedra/application/usecases/CancelAppointmentUseCaseImpl.java`
- clase 'FindAppointmentUseCaseImpl' muy acoplada (CBO=26) — `src/main/java/turnos/catedra/application/usecases/FindAppointmentUseCaseImpl.java`
- clase 'SubmitAdditionalInformationUseCaseImpl' muy acoplada (CBO=14) — `src/main/java/turnos/catedra/application/usecases/SubmitAdditionalInformationUseCaseImpl.java`
- clase 'CheckTechnicalIntegrationUseCaseImpl' muy acoplada (CBO=9) — `src/main/java/turnos/catedra/application/usecases/CheckTechnicalIntegrationUseCaseImpl.java`
- clase 'ReceiveAdditionalInformationRequestUseCaseImpl' muy acoplada (CBO=8) — `src/main/java/turnos/catedra/application/usecases/ReceiveAdditionalInformationRequestUseCaseImpl.java`
- clase 'CreateHoldUseCaseImpl' muy acoplada (CBO=18) — `src/main/java/turnos/catedra/application/usecases/CreateHoldUseCaseImpl.java`
- clase 'ConfirmHoldUseCaseImpl' muy acoplada (CBO=15) — `src/main/java/turnos/catedra/application/usecases/ConfirmHoldUseCaseImpl.java`
- clase 'StartTechnicalSessionUseCaseImpl' muy acoplada (CBO=21) — `src/main/java/turnos/catedra/application/usecases/StartTechnicalSessionUseCaseImpl.java`
- clase 'GetOccupanciesUseCaseImpl' muy acoplada (CBO=17) — `src/main/java/turnos/catedra/application/usecases/GetOccupanciesUseCaseImpl.java`
- clase 'TechnicalSession' muy acoplada (CBO=12) — `src/main/java/turnos/catedra/domain/model/TechnicalSession.java`
- clase 'TechnicalSession' instancia 4 concretos (CBO=12) — `src/main/java/turnos/catedra/domain/model/TechnicalSession.java`
- clase 'JpaReservationProcessRepository' muy acoplada (CBO=35) — `src/main/java/turnos/reservation/infrastructure/persistence/repository/JpaReservationProcessRepository.java`
- clase 'JpaProcessedEventRepositoryAdapter' muy acoplada (CBO=10) — `src/main/java/turnos/reservation/infrastructure/persistence/adapter/JpaProcessedEventRepositoryAdapter.java`
- clase 'JpaReservationProcessRepositoryAdapter' muy acoplada (CBO=33) — `src/main/java/turnos/reservation/infrastructure/persistence/adapter/JpaReservationProcessRepositoryAdapter.java`
- clase 'JpaReservationProcessRepositoryAdapter' instancia 9 concretos (CBO=33) — `src/main/java/turnos/reservation/infrastructure/persistence/adapter/JpaReservationProcessRepositoryAdapter.java`
- clase 'ReservationProcessEntity' muy acoplada (CBO=12) — `src/main/java/turnos/reservation/infrastructure/persistence/entity/ReservationProcessEntity.java`
- clase 'CatedraEventAdapter' muy acoplada (CBO=19) — `src/main/java/turnos/reservation/infrastructure/gateway/CatedraEventAdapter.java`
- clase 'CatedraHoldAdapter' muy acoplada (CBO=37) — `src/main/java/turnos/reservation/infrastructure/gateway/CatedraHoldAdapter.java`
- clase 'CatedraHoldAdapter' instancia 17 concretos (CBO=37) — `src/main/java/turnos/reservation/infrastructure/gateway/CatedraHoldAdapter.java`
- clase 'CatalogProfessionalAdapter' muy acoplada (CBO=16) — `src/main/java/turnos/reservation/infrastructure/gateway/CatalogProfessionalAdapter.java`
- clase 'CatalogProfessionalAdapter' instancia 4 concretos (CBO=16) — `src/main/java/turnos/reservation/infrastructure/gateway/CatalogProfessionalAdapter.java`
- clase 'CatedraAppointmentAdapter' muy acoplada (CBO=31) — `src/main/java/turnos/reservation/infrastructure/gateway/CatedraAppointmentAdapter.java`
- clase 'CatedraAppointmentAdapter' instancia 11 concretos (CBO=31) — `src/main/java/turnos/reservation/infrastructure/gateway/CatedraAppointmentAdapter.java`
- clase 'ReservationProcessController' muy acoplada (CBO=86) — `src/main/java/turnos/reservation/infrastructure/web/controller/ReservationProcessController.java`
- clase 'ReservationProcessDtoMapper' muy acoplada (CBO=17) — `src/main/java/turnos/reservation/infrastructure/web/mapper/ReservationProcessDtoMapper.java`
- clase 'ReservationProcessListRequest' muy acoplada (CBO=8) — `src/main/java/turnos/reservation/infrastructure/web/dto/ReservationProcessListRequest.java`
- clase 'ReservationService' muy acoplada (CBO=19) — `src/main/java/turnos/reservation/application/service/ReservationService.java`
- clase 'ConfirmReservationProcessUseCaseImpl' muy acoplada (CBO=29) — `src/main/java/turnos/reservation/application/usecases/ConfirmReservationProcessUseCaseImpl.java`
- clase 'ConfirmReservationProcessUseCaseImpl' instancia 7 concretos (CBO=29) — `src/main/java/turnos/reservation/application/usecases/ConfirmReservationProcessUseCaseImpl.java`
- clase 'CancelReservationProcessUseCaseImpl' muy acoplada (CBO=29) — `src/main/java/turnos/reservation/application/usecases/CancelReservationProcessUseCaseImpl.java`
- clase 'CancelReservationProcessUseCaseImpl' instancia 5 concretos (CBO=29) — `src/main/java/turnos/reservation/application/usecases/CancelReservationProcessUseCaseImpl.java`
- clase 'FindReservationProcessesByUserUseCaseImpl' muy acoplada (CBO=11) — `src/main/java/turnos/reservation/application/usecases/FindReservationProcessesByUserUseCaseImpl.java`
- clase 'ExpireOverdueProcessesUseCaseImpl' muy acoplada (CBO=19) — `src/main/java/turnos/reservation/application/usecases/ExpireOverdueProcessesUseCaseImpl.java`
- clase 'GetReservationProcessByIdUseCaseImpl' muy acoplada (CBO=14) — `src/main/java/turnos/reservation/application/usecases/GetReservationProcessByIdUseCaseImpl.java`
- clase 'ProcessReservationResultUseCaseImpl' muy acoplada (CBO=30) — `src/main/java/turnos/reservation/application/usecases/ProcessReservationResultUseCaseImpl.java`
- clase 'ProcessPhoneRequestUseCaseImpl' muy acoplada (CBO=21) — `src/main/java/turnos/reservation/application/usecases/ProcessPhoneRequestUseCaseImpl.java`
- clase 'SubmitPhoneUseCaseImpl' muy acoplada (CBO=26) — `src/main/java/turnos/reservation/application/usecases/SubmitPhoneUseCaseImpl.java`
- clase 'SubmitPhoneUseCaseImpl' instancia 8 concretos (CBO=26) — `src/main/java/turnos/reservation/application/usecases/SubmitPhoneUseCaseImpl.java`
- clase 'CreateReservationProcessUseCaseImpl' muy acoplada (CBO=34) — `src/main/java/turnos/reservation/application/usecases/CreateReservationProcessUseCaseImpl.java`
- clase 'CreateReservationProcessUseCaseImpl' instancia 8 concretos (CBO=34) — `src/main/java/turnos/reservation/application/usecases/CreateReservationProcessUseCaseImpl.java`
- clase 'ReconcilePendingProcessesUseCaseImpl' muy acoplada (CBO=40) — `src/main/java/turnos/reservation/application/usecases/ReconcilePendingProcessesUseCaseImpl.java`
- clase 'Result' muy acoplada (CBO=8) — `src/main/java/turnos/reservation/application/usecases/ReconcilePendingProcessesUseCaseImpl.java`
- clase 'ReservationProcessRepository' muy acoplada (CBO=9) — `src/main/java/turnos/reservation/domain/ports/out/ReservationProcessRepository.java`
- clase 'ReservationProcess' muy acoplada (CBO=42) — `src/main/java/turnos/reservation/domain/model/ReservationProcess.java`
- clase 'ReservationProcess' instancia 10 concretos (CBO=42) — `src/main/java/turnos/reservation/domain/model/ReservationProcess.java`
- clase 'ReservationEventOutcome' muy acoplada (CBO=8) — `src/main/java/turnos/reservation/domain/model/ReservationEventOutcome.java`
- clase 'ReservationStatus' muy acoplada (CBO=8) — `src/main/java/turnos/reservation/domain/model/ReservationStatus.java`
- clase 'CatedraOccupancyAdapter' muy acoplada (CBO=14) — `src/main/java/turnos/availability/infrastructure/gateway/CatedraOccupancyAdapter.java`
- clase 'CatedraOccupancyAdapter' instancia 3 concretos (CBO=14) — `src/main/java/turnos/availability/infrastructure/gateway/CatedraOccupancyAdapter.java`
- clase 'CatalogAgendaAdapter' muy acoplada (CBO=16) — `src/main/java/turnos/availability/infrastructure/gateway/CatalogAgendaAdapter.java`
- clase 'CatalogAgendaAdapter' instancia 4 concretos (CBO=16) — `src/main/java/turnos/availability/infrastructure/gateway/CatalogAgendaAdapter.java`
- clase 'AvailabilityController' muy acoplada (CBO=35) — `src/main/java/turnos/availability/infrastructure/web/controller/AvailabilityController.java`
- clase 'AvailabilityDtoMapper' muy acoplada (CBO=11) — `src/main/java/turnos/availability/infrastructure/web/mapper/AvailabilityDtoMapper.java`
- clase 'GetFreeSlotsUseCaseImpl' muy acoplada (CBO=19) — `src/main/java/turnos/availability/application/usecases/GetFreeSlotsUseCaseImpl.java`
- clase 'GetFreeSlotsUseCaseImpl' instancia 3 concretos (CBO=19) — `src/main/java/turnos/availability/application/usecases/GetFreeSlotsUseCaseImpl.java`
- clase 'ProfessionalAgenda' muy acoplada (CBO=12) — `src/main/java/turnos/availability/domain/model/ProfessionalAgenda.java`
- clase 'CatalogClientAdapter' muy acoplada (CBO=33) — `src/main/java/turnos/catalog/infrastructure/client/CatalogClientAdapter.java`
- clase 'ApiExceptionHandler' muy acoplada (CBO=32) — `src/main/java/turnos/config/ApiExceptionHandler.java`
- clase 'SecurityProblemHandler' muy acoplada (CBO=20) — `src/main/java/turnos/config/SecurityProblemHandler.java`
- clase 'SecurityConfig' muy acoplada (CBO=30) — `src/main/java/turnos/config/SecurityConfig.java`
- clase 'SecurityConfig' instancia 4 concretos (CBO=30) — `src/main/java/turnos/config/SecurityConfig.java`
- clase 'JwtSigningKeys' muy acoplada (CBO=17) — `src/main/java/turnos/user/infrastructure/security/JwtSigningKeys.java`
- clase 'JwtSigningKeys' instancia 7 concretos (CBO=17) — `src/main/java/turnos/user/infrastructure/security/JwtSigningKeys.java`
- clase 'JwtValidationConfiguration' muy acoplada (CBO=28) — `src/main/java/turnos/user/infrastructure/security/JwtValidationConfiguration.java`
- clase 'JwtValidationConfiguration' instancia 3 concretos (CBO=28) — `src/main/java/turnos/user/infrastructure/security/JwtValidationConfiguration.java`
- clase 'JwtTokenIssuerAdapter' muy acoplada (CBO=16) — `src/main/java/turnos/user/infrastructure/security/JwtTokenIssuerAdapter.java`
- clase 'UserEntity' muy acoplada (CBO=8) — `src/main/java/turnos/user/infrastructure/persistence/entity/UserEntity.java`
- clase 'UserController' muy acoplada (CBO=20) — `src/main/java/turnos/user/infrastructure/web/controller/UserController.java`
- clase 'UserRequest' muy acoplada (CBO=10) — `src/main/java/turnos/user/infrastructure/web/dto/UserRequest.java`
- clase 'RegisterUserUseCaseImpl' muy acoplada (CBO=10) — `src/main/java/turnos/user/application/usecases/RegisterUserUseCaseImpl.java`
- clase 'AuthenticateUserUseCaseImpl' muy acoplada (CBO=8) — `src/main/java/turnos/user/application/usecases/AuthenticateUserUseCaseImpl.java`

**OCP/type-switch** (13)
- clase 'KafkaConsumerManager' con 1 type-checks — `src/main/java/turnos/catedra/infrastructure/kafka/KafkaConsumerManager.java`
- clase 'KafkaAdditionalInformationPublisher' con 1 type-checks — `src/main/java/turnos/catedra/infrastructure/kafka/KafkaAdditionalInformationPublisher.java`
- clase 'CatedraHoldClientAdapter' con 3 type-checks — `src/main/java/turnos/catedra/infrastructure/client/CatedraHoldClientAdapter.java`
- clase 'CatedraOperationRunner' con 4 type-checks — `src/main/java/turnos/catedra/application/support/CatedraOperationRunner.java`
- clase 'ReceiveAppointmentResultUseCaseImpl' con 1 type-checks — `src/main/java/turnos/catedra/application/usecases/ReceiveAppointmentResultUseCaseImpl.java`
- clase 'FindAppointmentUseCaseImpl' con 1 type-checks — `src/main/java/turnos/catedra/application/usecases/FindAppointmentUseCaseImpl.java`
- clase 'ReceiveAdditionalInformationRequestUseCaseImpl' con 1 type-checks — `src/main/java/turnos/catedra/application/usecases/ReceiveAdditionalInformationRequestUseCaseImpl.java`
- clase 'JpaReservationProcessRepositoryAdapter' con 1 type-checks — `src/main/java/turnos/reservation/infrastructure/persistence/adapter/JpaReservationProcessRepositoryAdapter.java`
- clase 'CatedraPhoneAdapter' con 1 type-checks — `src/main/java/turnos/reservation/infrastructure/gateway/CatedraPhoneAdapter.java`
- clase 'ProcessReservationResultUseCaseImpl' con 1 type-checks — `src/main/java/turnos/reservation/application/usecases/ProcessReservationResultUseCaseImpl.java`
- clase 'CatalogClientAdapter' con 1 type-checks — `src/main/java/turnos/catalog/infrastructure/client/CatalogClientAdapter.java`
- clase 'ApiExceptionHandler' con 2 type-checks — `src/main/java/turnos/config/ApiExceptionHandler.java`
- clase 'JwtSigningKeys' con 1 type-checks — `src/main/java/turnos/user/infrastructure/security/JwtSigningKeys.java`

**?** (36)
- clase 'CatedraEventNotSavedException' con herencia profunda (DIT=2) — `src/main/java/turnos/catedra/application/exception/CatedraEventNotSavedException.java`
- clase 'ReservationSlotInPastException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationSlotInPastException.java`
- clase 'ReservationSlotNotAvailableException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationSlotNotAvailableException.java`
- clase 'ReservationLocalStateOutOfSyncException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationLocalStateOutOfSyncException.java`
- clase 'ReservationNotCancellableException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationNotCancellableException.java`
- clase 'ReservationServiceDegradedException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationServiceDegradedException.java`
- clase 'ReservationProcessNotFoundException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationProcessNotFoundException.java`
- clase 'ReservationHoldExpiredException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationHoldExpiredException.java`
- clase 'ReservationCatalogUnavailableException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationCatalogUnavailableException.java`
- clase 'ReservationNotSavedException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationNotSavedException.java`
- clase 'ReservationOpenProcessExistsException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationOpenProcessExistsException.java`
- clase 'ReservationCentralUnavailableException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationCentralUnavailableException.java`
- clase 'ReservationPatientMismatchException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationPatientMismatchException.java`
- clase 'ReservationIntegrationException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationIntegrationException.java`
- clase 'ReservationOpenProcessConflictException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationOpenProcessConflictException.java`
- clase 'ReservationHoldNotFoundException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationHoldNotFoundException.java`
- clase 'ReservationAppointmentNotFoundException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationAppointmentNotFoundException.java`
- clase 'ReservationProfessionalDisabledException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationProfessionalDisabledException.java`
- clase 'ReservationAppointmentOwnerMismatchException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationAppointmentOwnerMismatchException.java`
- clase 'ReservationInvalidProcessStateException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationInvalidProcessStateException.java`
- clase 'ReservationInvalidSlotException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationInvalidSlotException.java`
- clase 'ReservationHoldNotSavedException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationHoldNotSavedException.java`
- clase 'ReservationInvalidPhoneNumberException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationInvalidPhoneNumberException.java`
- clase 'ReservationUserNotFoundException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationUserNotFoundException.java`
- clase 'ReservationPhoneRequestNotReadyException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationPhoneRequestNotReadyException.java`
- clase 'ReservationProfessionalNotFoundException' con herencia profunda (DIT=2) — `src/main/java/turnos/reservation/application/exception/ReservationProfessionalNotFoundException.java`
- clase 'AvailabilityCatalogUnavailableException' con herencia profunda (DIT=2) — `src/main/java/turnos/availability/application/exception/AvailabilityCatalogUnavailableException.java`
- clase 'AvailabilityProfessionalDisabledException' con herencia profunda (DIT=2) — `src/main/java/turnos/availability/application/exception/AvailabilityProfessionalDisabledException.java`
- clase 'AvailabilityServiceDegradedException' con herencia profunda (DIT=2) — `src/main/java/turnos/availability/application/exception/AvailabilityServiceDegradedException.java`
- clase 'AvailabilityCentralUnavailableException' con herencia profunda (DIT=2) — `src/main/java/turnos/availability/application/exception/AvailabilityCentralUnavailableException.java`
- clase 'AvailabilityProfessionalNotFoundException' con herencia profunda (DIT=2) — `src/main/java/turnos/availability/application/exception/AvailabilityProfessionalNotFoundException.java`
- clase 'AvailabilityDateInPastException' con herencia profunda (DIT=2) — `src/main/java/turnos/availability/application/exception/AvailabilityDateInPastException.java`
- clase 'AvailabilityIntegrationException' con herencia profunda (DIT=2) — `src/main/java/turnos/availability/application/exception/AvailabilityIntegrationException.java`
- clase 'UserLoginAlreadyExistsException' con herencia profunda (DIT=2) — `src/main/java/turnos/user/application/exception/UserLoginAlreadyExistsException.java`
- clase 'UserEmailAlreadyExistsException' con herencia profunda (DIT=2) — `src/main/java/turnos/user/application/exception/UserEmailAlreadyExistsException.java`
- clase 'UserInvalidCredentialsException' con herencia profunda (DIT=2) — `src/main/java/turnos/user/application/exception/UserInvalidCredentialsException.java`

**ISP/interfaces** (5)
- interfaz 'AppointmentCancellationResult' con 7 métodos — `src/main/java/turnos/catedra/domain/model/AppointmentCancellationResult.java`
- interfaz 'HoldConfirmationResult' con 12 métodos — `src/main/java/turnos/catedra/domain/model/HoldConfirmationResult.java`
- interfaz 'AppointmentLookupResult' con 5 métodos — `src/main/java/turnos/catedra/domain/model/AppointmentLookupResult.java`
- interfaz 'HoldResult' con 8 métodos — `src/main/java/turnos/catedra/domain/model/HoldResult.java`
- interfaz 'ProfessionalResult' con 5 métodos — `src/main/java/turnos/catalog/domain/model/ProfessionalResult.java`

**LSP/overrides** (1)
- 'ReconcilePendingProcessesUseCaseImpl.reconcilePendingProcesses' vacío o 'no implementado' — `src/main/java/turnos/reservation/application/usecases/ReconcilePendingProcessesUseCaseImpl.java`

**POO** (1)
- clase 'FreeSlotsResult' solo con métodos estáticos (¿utilitaria?) — `src/main/java/turnos/availability/domain/model/FreeSlotsResult.java`

**duplicación** (6)
- duplicación lógica: 4.3% · boilerplate: 15.4%
- bloque de 8 líneas repetido 11 veces en src/test/java/turnos/reservation/infrastructure/web/controller/ReservationProcessControllerTest.java:141, src/test/java/turnos/reservation/infrastructure/web/controller/ReservationProcessControllerTest.java:142, src/test/java/turnos/reservation/infrastructure/web/controller/ReservationProcessControllerTest.java:143 — `src/test/java/turnos/reservation/infrastructure/web/controller/ReservationProcessControllerTest.java`
- bloque de 8 líneas repetido 7 veces en src/test/java/turnos/reservation/application/usecases/ProcessReservationResultUseCaseImplTest.java:441, src/test/java/turnos/reservation/application/usecases/ReconcilePendingProcessesUseCaseImplTest.java:523, src/test/java/turnos/reservation/application/usecases/ProcessPhoneRequestUseCaseImplTest.java:207 — `src/test/java/turnos/reservation/application/usecases/ProcessReservationResultUseCaseImplTest.java`
- bloque de 8 líneas repetido 4 veces en src/test/java/turnos/reservation/OverdueSweepIntegrationTest.java:341, src/test/java/turnos/reservation/ReservationProcessListIntegrationTest.java:339, src/test/java/turnos/reservation/ReservationProcessStatusIntegrationTest.java:350 — `src/test/java/turnos/reservation/OverdueSweepIntegrationTest.java`
- bloque de 8 líneas repetido 4 veces en src/test/java/turnos/reservation/application/usecases/ReconcilePendingProcessesUseCaseImplTest.java:499, src/test/java/turnos/reservation/application/usecases/ExpireOverdueProcessesUseCaseImplTest.java:308, src/test/java/turnos/reservation/application/usecases/CancelReservationProcessUseCaseImplTest.java:254 — `src/test/java/turnos/reservation/application/usecases/ReconcilePendingProcessesUseCaseImplTest.java`
- bloque de 8 líneas repetido 4 veces en src/test/java/turnos/reservation/OverdueSweepIntegrationTest.java:339, src/test/java/turnos/reservation/ReservationProcessListIntegrationTest.java:337, src/test/java/turnos/reservation/ReservationProcessStatusIntegrationTest.java:348 — `src/test/java/turnos/reservation/OverdueSweepIntegrationTest.java`

## 6. Evolución por commit

Métricas calculadas sobre el estado de `main` en **cada commit** (8 commits con trabajo), del primero al último. La calidad usa el perfil `exigente`; el aporte de código lo mide RepoSense. El índice puede **bajar** al crecer el proyecto: se acumulan señales.

```mermaid
xychart-beta
    title "Índice de calidad a lo largo de los commits"
    x-axis ["6e7a2d5", "3e18dbb", "4b378e7", "8951005", "bc56f20", "6c1dc34", "2f67164", "8151679"]
    y-axis "Índice" 0 --> 100
    line [0, 15, 15, 62, 65, 65, 66, 66]
```

| # | Fecha | Commit | Autor | LOC | Índice | %POO | Duplic. | Sueltas |
|---|---|---|---|---:|---:|---:|---:|---:|
| 1 | 2026-09-14 | `6e7a2d5` | Ing. Daniel Quinteros | 0 | 0 | 0% | 0.0% | 0 |
| 2 | 2026-09-14 | `3e18dbb` | Ing. Daniel Quinteros | 0 | 15 | 0% | 0.0% | 0 |
| 3 | 2026-10-13 | `4b378e7` | IgnacioMilutin | 0 | 15 | 0% | 0.0% | 0 |
| 4 | 2026-10-22 | `8951005` | IgnacioMilutin | 3,488 | 62 | 68% | 3.5% | 0 |
| 5 | 2026-10-29 | `bc56f20` | IgnacioMilutin | 12,766 | 65 | 69% | 2.6% | 0 |
| 6 | 2026-11-02 | `6c1dc34` | IgnacioMilutin | 19,786 | 65 | 72% | 3.9% | 0 |
| 7 | 2026-11-04 | `2f67164` | IgnacioMilutin | 23,726 | 66 | 72% | 4.2% | 0 |
| 8 | 2026-11-09 | `8151679` | IgnacioMilutin | 27,869 | 66 | 73% | 4.3% | 0 |

---
_Informe generado por la cátedra. Las dimensiones y umbrales corresponden a `calidad.py` (perfil `exigente`); el semáforo, a `CRITERIOS-CALIDAD.md`. La documentación del repositorio no se evalúa. Ante dudas, revisá el archivo señalado._
<!-- evaluacion:fin -->
