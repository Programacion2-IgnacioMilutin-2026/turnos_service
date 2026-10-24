# Secuencias — turnos y reservas

## 1. Registro e inicio de sesión (T01, T02)

Casos de uso: T01 y T02.

```mermaid
sequenceDiagram
    participant App as KMP App
    participant Service as Reservations Service
    participant DB as Reservations DB

    Note over App,DB: T01 — Register
    App->>Service: Register (login, password, firstName, lastName, email, langKey, imageUrl?)
    Service->>Service: Validate required fields and formats, discard backend-managed fields
    break Missing or invalid field
        Service-->>App: 400 (functional code, invalid fields)
    end
        Service->>DB: Find users matching login or email
    DB-->>Service: Matching users or none
    Note over Service: If both match, the login conflict is reported first
    break Login already registered
        Service-->>App: 400 (USERNAME_ALREADY_EXISTS)
    end
    break Email already registered
        Service-->>App: 400 (EMAIL_ALREADY_EXISTS)
    end
    Service->>Service: Hash password
    Service->>DB: Save user (activated, ROLE_USER, system fields)
    break Persistence fails
        Service->>Service: Log error
        Service-->>App: 500 (no internal details)
    end
    Service-->>App: 201 (public user data, no password, no JWT)

    Note over App,DB: T02 — Log in
    App->>Service: Log in (login, password)
    break Missing login or password
        Service-->>App: 400 (functional code)
    end
    Service->>DB: Find user by login
    DB-->>Service: User or none
    Service->>Service: Verify password against stored hash (if the user exists)
    break User not found or wrong password
        Service-->>App: 401 (same generic message in both cases)
    end
    Service->>Service: Sign user JWT (user id, role, lifetime from configuration)
    break JWT generation or signing fails
        Service->>Service: Log error
        Service-->>App: 500 (no internal details)
    end
    Service-->>App: 200 (JWT)
    Note over App: Keeps the JWT for the following requests (T03 onwards)
```

## 2. Consulta de turnos libres con caché de respaldo (T03)

Casos de uso: T03.

```mermaid
sequenceDiagram
    participant App as KMP App
    participant Service as Reservations Service
    participant Cache as Occupancy Cache (in memory)
    participant Catalog as Catalog Service
    participant Central as Central API

    App->>Service: Get free slots (professionalId, date), user JWT
    Service->>Service: Validate user JWT (signature, expiry, permissions)
    break JWT missing, invalid, expired or without permissions
        Service-->>App: 401 or 403 (no data)
    end
    Service->>Service: Validate professionalId and date (not before today)
    break Invalid parameters
        Service-->>App: 400 (functional code)
    end

    Service->>Catalog: Get professional and weekly schedules (C06), user JWT
    Catalog-->>Service: Professional with weekly schedules, or error
    break Catalog returns 401 or 403
        Service->>Service: Log internal integration error
        Service-->>App: 500
    end
    break Catalog returns 404
        Service-->>App: 404 (functional code)
    end
    break Catalog unavailable after retries (timeout, 503, connection lost, growing wait, max attempts)
        Service-->>App: 503 (catalog unavailable, Central API not queried)
    end
    break Professional disabled
        Service-->>App: 409 (PROFESSIONAL_DISABLED, Central API not queried)
    end
    Service->>Service: Take the enabled weekly schedules of the date's day of week
    break No enabled schedule that day
        Service-->>App: 200 (empty list, professional does not attend that day, Central API not queried)
    end

    Service->>Central: Get occupancies (professionalId, from = to = date), technical JWT
    Central-->>Service: Occupancies, or error
    opt 401 (technical JWT expired or rejected)
        Service->>Service: Run X01 (authenticate again), once
        Service->>Central: Get occupancies again
        Central-->>Service: Occupancies, or error
    end
    break 403 STUDENT_INTEGRATION_NOT_FOUND
        Service->>Service: Log configuration error
        Service-->>App: 503 (degraded service, no details)
    end
    alt Occupancies received
        Service->>Cache: Save or replace entry (professionalId, date) with occupancies and dataAsOf
    else Central API unavailable after retries (timeout, connection lost, 503, 500, growing wait, max attempts)
        Service->>Cache: Find entry (professionalId, date)
        Cache-->>Service: Entry or none
        opt Entry older than the configured max age
            Service->>Cache: Delete entry
        end
        break No usable entry (none, or discarded as too old)
            Service-->>App: 503 (slots could not be calculated)
        end
        Note over Service,Cache: Continues with the cached occupancies, response flagged as stale with their dataAsOf
    end

    Service->>Service: Generate possible slots (slotDurationMinutes), discard started ones if date is today
    Service->>Service: Discard slots occupied as CONFIRMED or HELD
    break No free slot left
        Service-->>App: 200 (empty list, no free slots for that date)
    end
    Service-->>App: 200 (free slots ordered by startTime, plus stale = true and dataAsOf if from cache)
```

## 3. Bloqueo del turno y confirmación inicial (T04, T05, T07)

Casos de uso: T04, T05 y T07.

```mermaid
sequenceDiagram
    participant App as KMP App
    participant Service as Reservations Service
    participant DB as Reservations DB
    participant Catalog as Catalog Service
    participant Central as Central API
    participant Kafka as Kafka

    Note over App,Kafka: T04 — Block the slot (create hold)
    App->>Service: Create hold (professionalId, date, startTime), user JWT
    Service->>Service: Validate user JWT (signature, expiry, permissions)
    break JWT missing, invalid, expired or without permissions
        Service-->>App: 401 or 403 (no data)
    end
    Service->>Service: Validate professionalId, date and startTime (slot not in the past)
    break Invalid parameters
        Service-->>App: 400 (functional code)
    end
    Service->>DB: Find an open process of the user (HELD, WAITING_FOR_PHONE or PHONE_SUBMITTED)
    DB-->>Service: Open process or none
    opt Process is HELD or WAITING_FOR_PHONE and expiresAt plus safety margin already reached
        Service->>DB: Mark process EXPIRED
    end
    break Process still open (not expired)
        Service-->>App: 409 (OPEN_PROCESS_EXISTS, with reservationProcessId, status and expiresAt)
    end

    Service->>Catalog: Get professional and weekly schedules (C06), user JWT
    Catalog-->>Service: Weekly schedules, or error
    break Catalog returns 401 or 403
        Service->>Service: Log internal integration error
        Service-->>App: 500
    end
    break Catalog returns 404
        Service-->>App: 404 (functional code)
    end
    break Catalog unavailable after retries (timeout, 503, connection lost, growing wait, max attempts)
        Service-->>App: 503 (operation cannot start, Central API not called)
    end
    Service->>Service: Check the slot falls inside an enabled weekly schedule of that day
    break Slot outside the enabled schedules
        Service-->>App: 400 (own functional code, Central API not called)
    end

    Service->>Central: Create hold (professionalId, date, startTime, externalPatientId), technical JWT
    Central-->>Service: Hold (holdId, reservationProcessId, HELD, expiresAt), or error
    opt 401 (technical JWT expired or rejected)
        Service->>Service: Run X01 (authenticate again), once
        Service->>Central: Create hold again
        Central-->>Service: Hold, or error
    end
    break 403 STUDENT_INTEGRATION_NOT_FOUND
        Service->>Service: Log configuration error
        Service-->>App: 503 (no details)
    end
    break 404 PROFESSIONAL_NOT_FOUND
        Service->>Service: Log error (catalog may be out of sync)
        Service-->>App: 404 (functional code)
    end
    break 400 PROFESSIONAL_DISABLED
        Service-->>App: 409 (functional code, professional no longer accepts appointments)
    end
    break 400 INVALID_SLOT
        Service-->>App: 400 (functional code)
    end
    break 409 SLOT_ALREADY_RESERVED or SLOT_ALREADY_HELD
        Service-->>App: 409 (same functional code for both, slot no longer available)
    end
    break Central API unavailable after retries (timeout, connection lost, 503, 500, growing wait, max attempts)
        Service-->>App: 503 (hold could not be created, no local process)
    end
    Service->>DB: Create process HELD (user, holdId, reservationProcessId, slot, expiresAt, professional name and category)
    break Local persistence fails (orphan hold in Central API)
        Service->>Service: Log critical error
        Service-->>App: 500 (functional code, expiresAt received from Central API, Retry-After header)
    end
    Service-->>App: Process created (reservationProcessId, status HELD, expiresAt)

    Note over App,Kafka: T05 — Confirm reservation (initial confirmation)
    App->>Service: Confirm (reservationProcessId), user JWT
    Service->>Service: Validate user JWT (signature, expiry, permissions)
    break JWT missing, invalid, expired or without permissions
        Service-->>App: 401 or 403 (no data)
    end
    Service->>DB: Find process by reservationProcessId and user
    DB-->>Service: Process or none
    break Process does not exist or belongs to another user
        Service-->>App: 404 (same answer for both cases)
    end
    break Process is not HELD
        Service-->>App: 409 (functional code, current status)
    end
    break expiresAt plus safety margin already reached
        Service->>DB: Mark process EXPIRED (if it was not)
        Service-->>App: 409 (HOLD_EXPIRED, Central API not called)
    end
    Service->>Central: Confirm hold (externalPatientId, patientFirstName and patientLastName from the user profile), technical JWT
    Central-->>Service: reservationProcessId, WAITING_FOR_PHONE and new expiresAt, or error
    opt 401 (technical JWT expired or rejected)
        Service->>Service: Run X01 (authenticate again), once
        Service->>Central: Confirm hold again
        Central-->>Service: Result, or error
    end
    break 403 STUDENT_INTEGRATION_NOT_FOUND
        Service->>Service: Log configuration error
        Service-->>App: 503 (no details)
    end
    break 404 HOLD_NOT_FOUND
        Service->>Service: Log error
        Service-->>App: 404 (functional code)
    end
    break 404 HOLD_EXPIRED
        Service->>DB: Mark process EXPIRED
        Service-->>App: 409 (HOLD_EXPIRED)
    end
    break 403 HOLD_OWNER_MISMATCH
        Service->>Service: Log serious error (both backends share the technical account)
        Service-->>App: 500
    end
    break 400 EXTERNAL_PATIENT_MISMATCH
        Service->>Service: Log error
        Service-->>App: 409 (functional code)
    end
    break 409 RESERVATION_PROCESS_ALREADY_CONFIRMED (probably an app retry)
        Service->>DB: Update local copy if outdated
        Service-->>App: Current status (not an error)
    end
    break 404 PROFESSIONAL_NOT_FOUND, 400 INVALID_SLOT or 409 SLOT_ALREADY_RESERVED
        Service->>Service: Log error
        Service-->>App: Matching functional code, same as T04
    end
    break Central API unavailable after retries (timeout, connection lost, 503, 500, growing wait, max attempts)
        Service->>DB: Mark process for reconciliation (T13), it stays HELD
        Service-->>App: 503
    end
    Service->>DB: Update process to WAITING_FOR_PHONE with the new expiresAt
    break Local persistence fails (Central API already advanced the process)
        Service->>Service: Log critical error and mark for reconciliation (T13)
        Service-->>App: 500 (functional code)
    end
    Service-->>App: Updated process (status WAITING_FOR_PHONE, expiresAt)

    Note over App,Kafka: T07 — Process additional information request (asynchronous, can finish before or after the last step of T05)
    Central->>Kafka: Publish AdditionalInformationRequested (when it confirms the hold in T05)
    Kafka->>Service: AdditionalInformationRequested (eventId, reservationProcessId, holdId, requiredField, expiresAt, ...)
    Service->>Service: Validate message (required fields, schemaVersion = 1, requiredField = PHONE_NUMBER)
    break Invalid message or unsupported requiredField
        Service->>Service: Log error or limitation
        Service->>Kafka: Commit offset
    end
    Service->>DB: Find processed event by eventId
    DB-->>Service: Event or none
    break eventId already processed
        Service->>Kafka: Commit offset
    end
    Service->>DB: Find process by reservationProcessId
    DB-->>Service: Process or none
    break No local process (orphan event)
        Service->>Service: Log orphan event
        Service->>Kafka: Commit offset
    end
    break holdId differs from the stored one
        Service->>Service: Log inconsistency
        Service->>Kafka: Commit offset
    end
    break Process is neither HELD nor WAITING_FOR_PHONE
        Service->>Service: Log (state already advanced, no going back)
        Service->>Kafka: Commit offset
    end
    Service->>DB: One transaction: save requestEventId, update expiresAt, HELD to WAITING_FOR_PHONE if needed, register eventId as processed
    break Persistence fails after retries (growing wait, max attempts)
        Service->>Service: Log error, offset NOT committed, Kafka redelivers later
    end
    Service->>Kafka: Commit offset
```


## 4. Envío del teléfono y resultado de la reserva (T06, T08)

Casos de uso: T06 y T08.

```mermaid
sequenceDiagram
    participant App as KMP App
    participant Service as Reservations Service
    participant DB as Reservations DB
    participant Kafka as Kafka
    participant Central as Central API

    Note over App,Central: T06 — Submit phone number
    App->>Service: Submit phone (reservationProcessId, phoneNumber), user JWT
    Service->>Service: Validate user JWT (signature, expiry, permissions)
    break JWT missing, invalid, expired or without permissions
        Service-->>App: 401 or 403
    end
    Service->>DB: Find process by reservationProcessId and user
    DB-->>Service: Process or none
    break Process does not exist or belongs to another user
        Service-->>App: 404 (same answer for both cases)
    end
    break Process is not WAITING_FOR_PHONE
        Service-->>App: 409 (functional code, current status)
    end
    break expiresAt plus safety margin already reached
        Service->>DB: Mark process EXPIRED (if it was not)
        Service-->>App: 409 (HOLD_EXPIRED, nothing published)
    end
    break requestEventId not stored yet (T07 has not processed the request)
        Service-->>App: 409 (PHONE_REQUEST_NOT_READY, retry in a few seconds, nothing published)
    end
    Service->>Service: Validate phone format (optional leading +, 7 to 15 digits, after removing spaces, hyphens, parentheses and dots), then normalize
    break Invalid phone format
        Service-->>App: 400 (functional code, nothing published)
    end

    Service->>Kafka: Publish AdditionalInformationSubmitted (reservationProcessId, holdId, requestEventId, phoneNumber, own eventId)
    Kafka-->>Service: Acknowledgment, or error
    opt Kafka rejects the credentials (authentication failure)
        Service->>Service: Run X01 (get current configuration), once
        Service->>Kafka: Publish again (same eventId and content)
        Kafka-->>Service: Acknowledgment, or error
    end
    break Kafka unavailable after retries, or credentials still rejected (growing wait, max attempts, same eventId and content)
        Service->>Service: Log error
        Service->>DB: Mark process for reconciliation (T13)
        Service-->>App: 503 (the user can retry T06 while expiresAt has not passed)
    end
    Service->>DB: Only if the process is still WAITING_FOR_PHONE: save phone, clear previous rejection reason, set PHONE_SUBMITTED (if T08 already advanced it, nothing changes)
    break Local persistence fails (event already sent to Central API)
        Service->>Service: Log critical error and mark for reconciliation (T13)
        Service-->>App: 500 (functional code)
    end
    Service-->>App: Phone submitted (the result is available through T09)
    Note over Kafka,Central: Central API consumes the message and later publishes the result (T08)

    Note over App,Central: T08 — Process reservation result (asynchronous, can arrive before the save in T06)
    Central->>Kafka: Publish result event (Confirmed, Rejected, Expired, Invalid or Cancelled)
    Kafka->>Service: Result event (eventId, eventType, reservationProcessId, holdId, ...)
    Service->>Service: Validate message (required fields by event type, schemaVersion = 1, known eventType)
    break Invalid message or unknown eventType
        Service->>Service: Log error
        Service->>Kafka: Commit offset
    end
    Service->>DB: Find processed event by eventId
    DB-->>Service: Event or none
    break eventId already processed
        Service->>Kafka: Commit offset
    end
    Service->>DB: Find process by reservationProcessId
    DB-->>Service: Process or none
    break No local process (orphan event)
        Service->>Service: Log orphan event
        Service->>Kafka: Commit offset
    end
    break holdId differs from the stored one
        Service->>Service: Log inconsistency
        Service->>Kafka: Commit offset
    end
    break Process already in a final state (CONFIRMED, EXPIRED, CANCELLED or INVALID), except AppointmentCancelled over CONFIRMED
        Service->>DB: Register eventId as processed
        Service->>Kafka: Commit offset
    end
    Note over Service,DB: The effect and the processed eventId are saved in one transaction
    alt AppointmentConfirmed
        Service->>DB: Save the confirmed reservation (reservationId, professionalId, date, startTime), confirmedAt = occurredAt, mark process CONFIRMED
    else AdditionalInformationRejected
        Service->>DB: Back to WAITING_FOR_PHONE, update expiresAt, save the rejection reason (code only), requestEventId unchanged
        Note over App,Service: The user sees the reason (T09) and repeats T06 with a new phone, the stored requestEventId is still valid
    else AppointmentProcessExpired
        Service->>DB: Mark process EXPIRED
    else AppointmentProcessInvalid
        Service->>DB: Mark process INVALID and for reconciliation (T13)
    else AppointmentCancelled
        Service->>DB: Mark reservation or process CANCELLED (reason, which may be null, and occurredAt)
    end
    Service->>DB: Register eventId as processed
    break Persistence fails after retries (growing wait, max attempts)
        Service->>Service: Log error, offset NOT committed, Kafka redelivers later
    end
    Service->>Kafka: Commit offset
```

## 5. Consulta del estado y de mis reservas (T09, T10)

Casos de uso: T09 y T10.

```mermaid
sequenceDiagram
    participant App as KMP App
    participant Service as Reservations Service
    participant DB as Reservations DB

    Note over App,DB: T09 — Query process status
    loop While the status is PHONE_SUBMITTED (A09, the app queries every few seconds)
        App->>Service: Get process status (reservationProcessId), user JWT
        Service->>Service: Validate user JWT (signature, expiry, permissions)
        break JWT missing, invalid, expired or without permissions
            Service-->>App: 401 or 403 (no data)
        end
        Service->>DB: Find process by reservationProcessId and user
        DB-->>Service: Process or none
        break Local database access fails
            Service->>Service: Log error
            Service-->>App: 500
        end
        break Process does not exist or belongs to another user
            Service-->>App: 404 (same answer for both cases)
        end
        opt Process is HELD or WAITING_FOR_PHONE and expiresAt plus safety margin already reached
            Service->>DB: Mark process EXPIRED
        end
        opt Process is PHONE_SUBMITTED and expiresAt plus safety margin already reached
            Service->>DB: Mark process for reconciliation (T13), it stays PHONE_SUBMITTED
        end
        Service-->>App: Status (status, professionalId, date, startTime, expiresAt if still open, reservationId if CONFIRMED, rejection reason if the last event was a rejection)
        Note over Service,DB: T08 (section 4) can update the process between two queries
    end

    Note over App,DB: T10 — Query my reservations
    App->>Service: List my reservations (optional status filter, page, size), user JWT
    Service->>Service: Validate user JWT (signature, expiry, permissions)
    break JWT missing, invalid, expired or without permissions
        Service-->>App: 401 or 403 (no data)
    end
    Service->>Service: Validate status (HELD, WAITING_FOR_PHONE, PHONE_SUBMITTED, CONFIRMED or CANCELLED) and pagination limits
    break Invalid status or pagination out of range
        Service-->>App: 400 (functional code)
    end
    Service->>DB: Mark EXPIRED the user's HELD or WAITING_FOR_PHONE processes past expiresAt plus safety margin
    Service->>DB: Mark for reconciliation (T13) the user's PHONE_SUBMITTED processes past expiresAt plus safety margin
    Service->>DB: Query the user's reservations and processes matching the filter, excluding EXPIRED, INVALID and CANCELLED never confirmed, open process first, then by date and startTime (nearest or most recent first), requested page plus total
    DB-->>Service: Page of results and total
    break Local database access fails (closing expired processes or querying)
        Service->>Service: Log error
        Service-->>App: 500
    end
    Service-->>App: 200 (page with total, each item with reservationProcessId, status, professionalId, professional name and category, date, startTime, expiresAt and rejection reason if open, cancellation reason if cancelled, reservationId if it exists)
    Note over Service,App: No matches, or a page beyond the last, gives an empty page with the real total (0 if there are none)
```

## 6. Cancelación de reserva o proceso en curso (T11)

Casos de uso: T11.

```mermaid
sequenceDiagram
    participant App as KMP App
    participant Service as Reservations Service
    participant DB as Reservations DB
    participant Central as Central API

    App->>Service: Cancel (reservationProcessId, optional reason), user JWT
    Service->>Service: Validate user JWT (signature, expiry, permissions)
    break JWT missing, invalid, expired or without permissions
        Service-->>App: 401 or 403 (no data)
    end
    Service->>Service: Validate reason (up to 500 characters, if present)
    break reason too long
        Service-->>App: 400 (functional code)
    end
    Service->>DB: Find reservation or process by reservationProcessId and user
    DB-->>Service: Reservation or none
    break Does not exist or belongs to another user
        Service-->>App: 404
    end
    break Already CANCELLED locally
        Service-->>App: Current status (idempotent, Central API not called)
    end
    break Status is PHONE_SUBMITTED, EXPIRED or INVALID (not cancellable)
        Service-->>App: 409 (functional code)
    end
    break Open process (HELD or WAITING_FOR_PHONE) and expiresAt plus safety margin already reached
        Service->>DB: Mark process EXPIRED
        Service-->>App: 409 (HOLD_EXPIRED, Central API not called)
    end

    Service->>Central: Cancel reservation (reservationProcessId, reason if present), technical JWT
    Central-->>Service: reservationProcessId, CANCELLED, cancelledAt and reservationId if applicable, or error
    opt 401 (technical JWT expired or rejected)
        Service->>Service: Run X01 (authenticate again), once
        Service->>Central: Cancel reservation again
        Central-->>Service: Result, or error
    end
    break 403 STUDENT_INTEGRATION_NOT_FOUND
        Service->>Service: Log configuration error
        Service-->>App: 503 (no details)
    end
    break 404 RESERVATION_NOT_FOUND
        Service->>Service: Log inconsistency between local and Central API
        Service->>DB: Mark for reconciliation (T13)
        Service-->>App: 500
    end
    break 403 RESERVATION_OWNER_MISMATCH
        Service->>Service: Log serious error (both backends share the technical account)
        Service-->>App: 500
    end
    break 409 RESERVATION_NOT_CANCELLABLE
        Service->>Service: Log state inconsistency with Central API
        Service->>DB: Mark for reconciliation (T13)
        Service-->>App: 409 (functional code)
    end
    break Central API unavailable after retries (timeout, connection lost, 503, 500, growing wait, max attempts)
        Service->>DB: Mark for reconciliation (T13), local status unchanged
        Service-->>App: 503
    end
    Service->>DB: Update to CANCELLED with the cancelledAt received and the reason
    break Local persistence fails (Central API already cancelled)
        Service->>Service: Log critical error and mark for reconciliation (T13)
        Service-->>App: 500 (functional code)
    end
    Service-->>App: Cancellation confirmed (status CANCELLED, cancelledAt)
    Note over Central,Service: Central API also publishes AppointmentCancelled, handled by T08 (section 4), which repairs the local state if the update above failed
```


## 7. Vencimiento y reconciliación periódicos (T12, T13)

Casos de uso: T12 y T13.

```mermaid
sequenceDiagram
    participant Timer as Timer
    participant Service as Reservations Service
    participant DB as Reservations DB
    participant Central as Central API

    Note over Timer,Central: T12 — Expire overdue processes
    Timer->>Service: Trigger sweep (configurable interval)
    break Another sweep already running
        Service->>Service: Skip this run (the next one repeats the search)
    end
    Service->>DB: Find HELD, WAITING_FOR_PHONE and PHONE_SUBMITTED processes with expiresAt plus safety margin already reached
    DB-->>Service: Overdue processes, or none
    loop For each overdue process
        alt Process is HELD or WAITING_FOR_PHONE
            Service->>DB: Mark process EXPIRED
        else Process is PHONE_SUBMITTED
            Service->>DB: Mark process for reconciliation (T13)
        end
        opt Persistence fails for this process
            Service->>Service: Log error, the process stays pending for the next cycle
        end
    end
    Service->>Service: Log sweep result (number of processes closed and date, 0 if none)
    Note over Service,DB: Processes marked here are picked up by T13

    Note over Timer,Central: T13 — Reconcile pending processes
    Timer->>Service: Trigger reconciliation (at service startup, then at periodic intervals)
    break Another reconciliation already running
        Service->>Service: Skip this run
    end
    Service->>DB: Find processes marked for reconciliation (on the startup run only, also any process left in HELD, WAITING_FOR_PHONE or PHONE_SUBMITTED)
    DB-->>Service: Processes to reconcile
    loop For each process
        Service->>Central: List appointments (externalPatientId of the owner, date range that includes the slot date), technical JWT
        Central-->>Service: Appointments, or error
        opt 401 (technical JWT expired or rejected)
            Service->>Service: Run X01 (authenticate again), once
            Service->>Central: List appointments again
            Central-->>Service: Appointments, or error
        end
        alt Central API unavailable after retries (timeout, connection lost, 503, growing wait, max attempts)
            Service->>Service: The process stays pending for the next cycle, unchanged
        else Appointments received
            alt Appointment found for the reservationProcessId
                Service->>DB: Update local state if it differs (CONFIRMED to CONFIRMED, CANCELLED to CANCELLED, PHONE_PENDING to WAITING_FOR_PHONE unless the phone was already submitted locally, FAILED to EXPIRED), save reservationId and confirmedAt when informed, clear the reconciliation mark
            else No record for the reservationProcessId
                alt Local process is HELD and expiresAt plus safety margin not reached
                    Service->>Service: Leave it open (the Central API list shows reservations, not holds)
                else Any other case
                    Service->>DB: Mark process EXPIRED, clear the reconciliation mark
                end
            end
            opt Persistence fails for this process
                Service->>Service: Log error, the process stays pending for the next cycle
            end
            Service->>Service: Log reconciliation result for this process (changed or not)
        end
    end
```


