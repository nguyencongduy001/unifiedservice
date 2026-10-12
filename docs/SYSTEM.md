# Unified Service Scheduler — System & API Reference

A Spring Boot REST service that books **vehicle service appointments at car dealerships**, checking in real time that a **service bay** and a **qualified technician** are free for the whole duration of the service before confirming a booking.

---

## 1. Technology stack

| Layer | Choice |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 4.1.1 (`spring-boot-starter-webmvc`, `data-jpa`, `validation`) |
| Persistence | Spring Data JPA / Hibernate (`ddl-auto: update`) |
| Database | PostgreSQL (driver `org.postgresql:postgresql`) |
| Database schema | `schema.sql` — all objects live in the PostgreSQL namespace `unifiedservice` (set via `spring.jpa.properties.hibernate.default_schema`) |
| JSON | Jackson 3 (default `java.time` serialization → ISO-8601 strings) |
| Boilerplate | Lombok (`@Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor`) |
| Tests | JUnit 5, Spring test starters, Testcontainers (PostgreSQL) |
| Build | Maven (`./mvnw`) |

### Configuration (`src/main/resources/application.yaml`)

| Property | Default | Purpose |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/unifiedservice` (`${DB_URL}`) | JDBC URL |
| `spring.datasource.username` | `postgres` (`${DB_USERNAME}`) | DB user |
| `spring.datasource.password` | `postgres` (`${DB_PASSWORD}`) | DB password |
| `spring.jpa.hibernate.ddl-auto` | `update` | Auto-migrate entity mappings |
| `spring.jpa.open-in-view` | `false` | No session held across the HTTP response |
| `spring.jpa.properties.hibernate.default_schema` | `unifiedservice` | Namespace for every table |
| `app.seed-demo-data` | `true` | Load a demo dataset on startup (idempotent) |

---

## 2. Architecture

```
src/main/java/com/keyloop/unifiedservice/
├── UnifiedserviceApplication.java     Spring Boot entry point
├── controller/
│   └── AppointmentController.java     HTTP layer (@RestController, base path /api)
├── service/
│   └── AppointmentService.java        Business rules: availability, locking, booking
├── repository/                        Spring Data JPA repositories (7)
├── entity/                            JPA entities (7) + AppointmentStatus enum
├── dto/                               Request/response records + ApiError
├── exception/                         ResourceNotFoundException, NoResourceAvailableException,
│                                      GlobalExceptionHandler (@RestControllerAdvice)
└── config/
    └── DemoDataSeeder.java            CommandLineRunner, optional demo data
```

Flow: **Controller → Service → Repository → PostgreSQL**. The controller holds no logic; all rules live in `AppointmentService`.

---

## 3. Domain model

| Entity | Table | Fields | Relationships |
|---|---|---|---|
| `Customer` | `customer` | `id`, `name`, `email` | owns many `Vehicle` |
| `Dealership` | `dealership` | `id`, `name` | has many `ServiceBay`, `Technician`, `Appointment` |
| `ServiceType` | `service_type` | `id`, `name`, `durationMinutes` (int, NOT NULL) | many-to-many with `Technician` |
| `Vehicle` | `vehicle` | `id`, `registration`, `make`, `model` | `owner` → `Customer` (NOT NULL) |
| `ServiceBay` | `service_bay` | `id`, `name` | `dealership` → `Dealership` (NOT NULL) |
| `Technician` | `technician` | `id`, `name` | `dealership` → `Dealership`; `qualifiedServiceTypes` → `Set<ServiceType>` (join table `technician_service_type`) |
| `Appointment` | `appointment` | `id`, `startAt`, `endAt`, `status` | → `Customer`, `Vehicle`, `ServiceType`, `Dealership`, `ServiceBay`, `Technician` (all `@ManyToOne(optional=false, LAZY)`) |

`AppointmentStatus` enum: **`CONFIRMED`**, **`CANCELLED`** (stored as string, DB CHECK constraint).

### Indexes (`schema.sql`)
- `idx_appointment_dealership_window (dealership_id, start_at, end_at)` — drives the overlap query
- `idx_appointment_service_bay`, `idx_appointment_technician`, `idx_appointment_vehicle`
- `idx_vehicle_owner`, `idx_service_bay_dealership`, `idx_technician_dealership`, `idx_technician_service_type_service_type`

> Postgres does not index foreign keys automatically; these are explicit.

---

## 4. Business rules

1. **Duration is derived** — `endAt = desiredStart + serviceType.durationMinutes`. The client never sends an end time.
2. **Customer is derived** — taken from the vehicle's owner; never sent in the request.
3. **Both resources required** — a booking is accepted only if at least one service bay **and** one qualified technician are free for the *entire* `[startAt, endAt)` window.
4. **Qualification filter** — a technician is a candidate only if `qualifiedServiceTypes` contains the requested service type.
5. **Overlap definition** — `a.startAt < requestedEndAt AND a.endAt > requestedStartAt`, for the same dealership, restricted to statuses in `ACTIVE_STATUSES` (currently only `CONFIRMED`; cancelled appointments release their resources).
6. **Double-booking prevention** — booking runs inside a transaction and loads bays/technicians with `PESSIMISTIC_WRITE` locks (`findByDealershipIdLocking`), so concurrent requests serialize; the availability check and the assignment are atomic.
7. **Deterministic assignment** — bays and technicians are ordered by `id`; the first free one is assigned.
8. **No updates/deletes exposed** — there is no API to cancel, reschedule or amend an appointment.

---

## 5. HTTP API

Base path: **`/api`** — defined once on `AppointmentController`.

| # | Method | Path | Purpose |
|---|---|---|---|
| 1 | `POST` | `/api/appointments` | Book a service appointment |
| 2 | `GET` | `/api/appointments/{id}` | Fetch a single appointment |
| 3 | `GET` | `/api/dealerships/{dealershipId}/availability` | Real-time availability of bays & technicians |

That is the complete public surface — there are no other controllers.

---

### 5.1 `POST /api/appointments` — Book an appointment

Books a service appointment subject to bay and technician availability.

**Request body** — `CreateAppointmentRequest`

```json
{
  "vehicleId": 1,
  "serviceTypeId": 1,
  "dealershipId": 1,
  "desiredStart": "2026-10-15T09:30:00"
}
```

| Field | Type | Validation |
|---|---|---|
| `vehicleId` | number | required (`vehicleId is required`) |
| `serviceTypeId` | number | required (`serviceTypeId is required`) |
| `dealershipId` | number | required (`dealershipId is required`) |
| `desiredStart` | ISO date-time | required **and** must be in the future (`desiredStart must be in the future`) |

**Behaviour** (`AppointmentService.create`, `@Transactional`)

1. Load vehicle, service type, dealership → `404` if any id is unknown.
2. Compute `endAt` from the service type duration.
3. Pessimistically lock the dealership's bays and technicians.
4. Query overlapping `CONFIRMED` appointments and exclude busy bays / busy technicians.
5. Filter remaining technicians by qualification for the requested service type.
6. If either list is empty → `409` with a message naming the missing resource(s).
7. Persist an appointment with `status = CONFIRMED`, the first free bay and first free qualified technician.

**Responses**

| Status | Body |
|---|---|
| `201 Created` | `AppointmentResponse` (see §5.4) |
| `400 Bad Request` | `ApiError` with `fieldErrors` |
| `404 Not Found` | `ApiError` — `Vehicle 42 not found`, `Service type 3 not found`, or `Dealership 7 not found` |
| `409 Conflict` | `ApiError` — e.g. `No service bay and no qualified technician available at dealership 'Keyloop Demo Dealership' between 2026-10-15T09:30 and 2026-10-15T10:00` |

**cURL**

```bash
curl -X POST http://localhost:8080/api/appointments \
  -H 'Content-Type: application/json' \
  -d '{"vehicleId":1,"serviceTypeId":1,"dealershipId":1,"desiredStart":"2026-10-15T09:30:00"}'
```

---

### 5.2 `GET /api/appointments/{id}` — Fetch an appointment

Read-only lookup.

| Status | Body |
|---|---|
| `200 OK` | `AppointmentResponse` |
| `404 Not Found` | `ApiError` — `Appointment 99 not found` |

```bash
curl http://localhost:8080/api/appointments/1
```

---

### 5.3 `GET /api/dealerships/{dealershipId}/availability` — Check availability

Real-time view of what is still bookable at a dealership for a given service and start time. Read-only (no locking), safe for polling.

**Path parameter**

| Name | Type |
|---|---|
| `dealershipId` | number |

**Query parameters**

| Name | Type | Required | Format |
|---|---|---|---|
| `serviceTypeId` | number | yes | — |
| `startAt` | date-time | yes | ISO `yyyy-MM-dd'T'HH:mm:ss` (`@DateTimeFormat(iso = DATE_TIME)`) |

```
GET /api/dealerships/1/availability?serviceTypeId=1&startAt=2026-10-15T09:30:00
```

**Response** — `AvailabilityResponse`

```json
{
  "dealershipId": 1,
  "dealershipName": "Keyloop Demo Dealership",
  "serviceTypeId": 1,
  "serviceDurationMinutes": 30,
  "startAt": "2026-10-15T09:30:00",
  "endAt": "2026-10-15T10:00:00",
  "availableServiceBays": [
    { "id": 1, "name": "Bay 1" },
    { "id": 2, "name": "Bay 2" }
  ],
  "availableTechnicians": [
    { "id": 1, "name": "Alice Ahmed" }
  ]
}
```

| Status | Body |
|---|---|
| `200 OK` | `AvailabilityResponse` (empty lists ⇒ nothing bookable at that slot) |
| `404 Not Found` | `ApiError` — `Dealership 7 not found` / `Service type 3 not found` |
| `400 Bad Request` | `ApiError` — missing query params or unparsable `startAt` |

```bash
curl 'http://localhost:8080/api/dealerships/1/availability?serviceTypeId=1&startAt=2026-10-15T09:30:00'
```

---

### 5.4 Shared response shapes

**`AppointmentResponse`**

```json
{
  "id": 1,
  "status": "CONFIRMED",
  "customerId": 1,
  "customerName": "Jane Doe",
  "vehicleId": 1,
  "vehicleRegistration": "KL-01-DEMO",
  "serviceTypeId": 1,
  "serviceTypeName": "Oil Change",
  "dealershipId": 1,
  "dealershipName": "Keyloop Demo Dealership",
  "serviceBayId": 1,
  "serviceBayName": "Bay 1",
  "technicianId": 1,
  "technicianName": "Alice Ahmed",
  "startAt": "2026-10-15T09:30:00",
  "endAt": "2026-10-15T10:00:00"
}
```

**`ApiError`** (all error responses)

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Appointment 99 not found",
  "fieldErrors": { "desiredStart": "desiredStart must be in the future" }
}
```

`fieldErrors` is an empty object except for `400` validation failures.

---

### 5.5 Error handling summary

`GlobalExceptionHandler` (`@RestControllerAdvice`):

| Exception | HTTP status |
|---|---|
| `MethodArgumentNotValidException` (Bean Validation) | `400 Bad Request` |
| `ResourceNotFoundException` | `404 Not Found` |
| `NoResourceAvailableException` (no free bay/technician) | `409 Conflict` |

Any other exception falls through to Spring Boot's default error handling (generic `500`).

---

## 6. Data access layer

| Repository | Notable methods |
|---|---|
| `AppointmentRepository` | `findOverlapping(dealershipId, statuses, startAt, endAt)` — JPQL overlap query; plus standard `JpaRepository` CRUD |
| `ServiceBayRepository` | `findByDealershipId`, `findByDealershipIdLocking` (`PESSIMISTIC_WRITE`, ordered by id) |
| `TechnicianRepository` | `findByDealershipId`, `findByDealershipIdLocking` (`PESSIMISTIC_WRITE`, ordered by id) |
| `CustomerRepository`, `DealershipRepository`, `ServiceTypeRepository`, `VehicleRepository` | plain `JpaRepository` CRUD |

---

## 7. Startup data seeding

`config/DemoDataSeeder` (a `CommandLineRunner`), controlled by `app.seed-demo-data`.

- **Skipped** if the flag is `false`, or if any dealership/customer already exists (idempotent — never overwrites real data).
- Creates:
  - Dealership: *Keyloop Demo Dealership*
  - Service types: *Oil Change* (30 min), *Tyre Rotation* (45 min), *Full Service* (120 min)
  - Bays: *Bay 1*, *Bay 2*
  - Technicians: *Alice Ahmed* (Oil Change, Tyre Rotation), *Bob Barnes* (Tyre Rotation, Full Service)
  - Customer: *Jane Doe* (`jane.doe@example.com`) with vehicle *KL-01-DEMO* (Volkswagen Golf)

---

## 8. Running the project

```bash
# 1. Create the schema (one-off)
psql -U postgres -d unifiedservice -f schema.sql

# 2. Start the API (defaults: localhost:5432, postgres/postgres)
./mvnw spring-boot:run

# 3. Exercise it
curl 'http://localhost:8080/api/dealerships/1/availability?serviceTypeId=1&startAt=2026-10-15T09:30:00'
curl -X POST http://localhost:8080/api/appointments \
  -H 'Content-Type: application/json' \
  -d '{"vehicleId":1,"serviceTypeId":1,"dealershipId":1,"desiredStart":"2026-10-15T09:30:00"}'
curl http://localhost:8080/api/appointments/1
```

Environment overrides: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.

### Tests

```bash
./mvnw test
```

- `UnifiedserviceApplicationTests` — context loads.
- `AppointmentSchedulerIntegrationTests` — booking/availability scenarios against a Testcontainers PostgreSQL instance (`TestcontainersConfiguration`).

---

## 9. Known gaps / future work

- **No CRUD for reference data** — customers, dealerships, vehicles, service types, bays and technicians can only be created via the seeder or direct SQL.
- **No list/search endpoints** — appointments cannot be listed per customer, vehicle or date range (only fetched by id).
- **No cancel/reschedule API** — `CANCELLED` exists in the enum and the DB CHECK constraint, but nothing writes it; there is no `PATCH`/`DELETE`.
- **No authentication/authorization** — the API is open.
- **No pagination** — moot until list endpoints exist.
- **No API documentation** — no OpenAPI/Springdoc, no Actuator health endpoint.
- **Assignment is "first free"** — no load balancing, preferences or skills weighting beyond the qualification set.
- **Time zone** — all times are naive `LocalDateTime`; no zone handling.
