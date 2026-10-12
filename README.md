# Unified Service Scheduler

A Spring Boot REST API for booking vehicle service appointments at car dealerships. An appointment is only confirmed when a **service bay** and a **qualified technician** are both free for the entire duration of the service — checked in real time and protected against double-booking under concurrency.

- **Stack:** Java 17 · Spring Boot 4.1.1 · Spring Data JPA · PostgreSQL · Lombok · Testcontainers
- **API:** 3 endpoints under `/api` (book, fetch, availability check)
- **Docs:** [System & API reference](docs/SYSTEM.md)
- **OpenAPI:** [docs/openapi.yaml](docs/openapi.yaml) — machine-readable OpenAPI 3.1 specification of the API surface

---

# AI Collaboration Narrative

> How this repository was built in collaboration with an AI coding agent: the strategy used to steer the AI, the loop used to verify and refine its output, and the measures that ensured the final quality of the code.

## 1. High-level strategy for guiding the AI

The guiding principle was **"the AI writes the what, the human owns the why."** Instead of asking the AI to produce code from a vague prompt, every interaction started with a precise, testable statement of intent.

### 1.1 Requirements were expressed as contracts, not prose

The core requirements were pinned down *before* any code was requested, phrased so that each one was falsifiable:

1. Booking a service appointment requires a free service bay **and** a free qualified technician for the whole duration of the service.
2. Availability must be queryable in real time ("can I still book this slot?").
3. A confirmed appointment persists a record linking customer, vehicle, technician, service bay, service type and dealership.

Concrete, unambiguous rules were stated up front so the AI couldn't improvise them:

- Duration comes from the service type — the client never sends an end time.
- The customer is implied by the vehicle's owner — never sent in the request.
- Overlap means `start < requestedEnd AND end > requestedStart` for the same dealership, counting only non-cancelled appointments.
- A technician only counts as available if qualified for the requested service type.

### 1.2 The AI was constrained to a shape, then given freedom inside it

Rather than letting the project sprawl, the architecture was fixed first and the AI was asked to implement within it:

- One controller (`AppointmentController`), one service (`AppointmentService`), repositories per entity, records for DTOs, a global exception mapper.
- A fixed data model (`Customer`, `Dealership`, `ServiceType`, `Vehicle`, `ServiceBay`, `Technician`, `Appointment`) with known relationships.
- PostgreSQL as the database, with an explicit schema owned by the project (`schema.sql`).

Freedom was deliberately kept in the parts that benefit from it — how to query overlaps, how to lock resources, how to order assignment — while the externally visible surface (URLs, payloads, status codes) was treated as a contract.

### 1.3 Feedback was given in terms of evidence, not opinion

Every correction in the loop pointed at a specific observation:

- *"The overlap query returned bookings that end before the requested window starts — restrict `endAt > requestedStart`."*
- *"Two concurrent bookings for the same slot both got `201` — the check and the assignment must happen under a pessimistic lock in one transaction."*
- *"The error body was opaque — return a structured `fieldErrors` map for `400`s and a message that says which resource is missing for `409`s."*

Evidence-based corrections kept the AI from guessing at what "better" meant and turned every refinement into a measurable change.

## 2. Process for verifying and refining output

The loop was **Implement → Verify → Refine → Lock in**, repeated until the tests could stand as the specification.

### 2.1 Verification started at the boundary the AI can't fake: the database

A Postgres-backed integration test was set up as the first class of citizen, not an afterthought. Using **Testcontainers** with a real PostgreSQL image, the tests exercise the endpoints through Spring's `MockMvc` against an actual database — no H2, no mocks of the repository layer. This means every verification answer ("does the overlap query work?", "does the locking hold?") is a property of the real system, not of a test double. The container is configured with `create-drop` + `create_namespaces=true` so tests create the schema themselves and are hermetic.

### 2.2 The tests were written as an executable spec of the rules

Each business rule maps to at least one test that either passes or fails on evidence:

| Rule | Test that locks it in |
|---|---|
| Both resources free ⇒ `201` CONFIRMED with derived `endAt` | `createsConfirmedAppointmentWhenBayAndTechnicianAreFree` |
| Overlapping booking ⇒ `409`, nothing persisted | `rejectsOverlappingBookingWhenBayAndTechnicianAreBusy` |
| Booking at the exact end of the previous one is allowed | `acceptsBookingThatStartsAfterThePreviousOneEnds` |
| Missing qualification ⇒ `409` naming the missing resource | `rejectsBookingWhenNoTechnicianIsQualifiedForTheService` |
| Unknown ids ⇒ `404` in the standard error shape | `returnsNotFoundForUnknownVehicle` |
| Bad payload ⇒ `400` with per-field errors | `returnsValidationErrorsForIncompleteRequest` |
| Availability endpoint reflects bookings | `availabilityEndpointReflectsBookedResources` |
| **Concurrency:** exactly one of two simultaneous bookings wins | `onlyOneOfTwoConcurrentBookingsForTheSameSlotSucceeds` |

The concurrency test is the crux of the whole system's value. It uses a `CyclicBarrier` to fire two requests for the same slot at the same instant and asserts the outcome set is exactly `{201, 409}` and that exactly one row was inserted. That single test forced — and keeps forcing — the two correctness mechanisms present in `AppointmentService`:

- the window overlap query with the correct half-open interval semantics, and
- the `PESSIMISTIC_WRITE` locks on bays and technicians, so the read-then-assign happens atomically inside one transaction.

### 2.3 Refinement was driven by failing tests and code-reading, in that order

The sequence that produced the current implementation was:

1. **First pass failed the concurrency test.** The initial version checked availability and then persisted in separate steps without locks; overlapping requests each saw an empty schedule. The test produced a reproducible failure on a real database.
2. **The AI was asked to fix the *why*, not the symptom.** Rather than patching the test expectation, the service was redesigned around a single `@Transactional` booking method that loads bays and technicians with `PESSIMISTIC_WRITE`, computes clashes, and assigns resources in one atomic unit. The lock queries are explicit and isolated in repository interfaces (`findByDealershipIdLocking`) so the intent is visible.
3. **Every fix was re-verified, then the vocabulary was tightened.** After the logic passed, the code was gone through line by line: dead branches removed, the deterministic `order by id` assignment added so selection is stable under concurrency, and comments added only where the *why* is subtle (e.g. why cancelled statuses are excluded, why the locks live on the dealership's resource rows rather than the appointment rows).

### 2.4 The error contract was treated as part of the API

Verification covered not just happy paths but the full status-code surface. A single `GlobalExceptionHandler` maps the three failure classes to stable, documented responses — `400` with `fieldErrors`, `404` for unknown references, `409` when no bay and/or no qualified technician is free — and the message for `409` explicitly names *which* resource is missing. These shapes are asserted by the tests, so the error format itself is versioned by the suite.

### 2.5 Real-world seams were made explicit, then de-risked

A demo-data seeder was added so the API is usable the moment it boots, but it was written to be **idempotent** (it exits as soon as any dealership/customer exists) and switchable (`app.seed-demo-data=false`) so demo data can never corrupt production data. The schema file documents the rationale for the supporting indexes (`idx_appointment_dealership_window`, FK indexes that Postgres doesn't create automatically), and the JPA timezone/formatting behaviour was pinned down by checking what Jackson actually emits (ISO-8601, seconds always shown — mirrored by `iso()` in the tests).

## 3. How final quality was ensured

### 3.1 The tests are the source of truth, and they build against the real target

The final quality gate is `./mvnw test`: everything above runs against a real PostgreSQL from Testcontainers, including the concurrency scenario. If that suite stays green, the three core requirements are demonstrated, not asserted. No commit in the loop was accepted while a test was failing.

### 3.2 The AI's output was audited before it was trusted

After any implementation, the code was read in full — not just the diff the AI produced — checking that:

- **Nothing outside the contract was invented.** No endpoints beyond the three agreed ones, no hidden accumulation of "nice-to-have" features (no auth, no extra CRUD). Scope stayed where the contract put it.
- **The invariants were structurally enforced, not merely commented.** `@NotNull`/`@Future` on the request record make bad input unrepresentable; `optional = false` on every relationship makes dangling references unrepresentable at the mapping level; a single `ACTIVE_STATUSES` set makes "what consumes resources" a one-line change.
- **Concurrency was treated as a first-order correctness concern.** The locking is explicit, ordered, and covered by a test that genuinely races two requests — the strongest available proof short of formal verification.

### 3.3 Readability for the next engineer was engineered, not an afterthought

- Comments explain *why* (subtle invariants, locking, seed idempotency), never restate the code.
- The knowledge is written down twice: as tests (executable spec) and as a human reference — [docs/SYSTEM.md](docs/SYSTEM.md), which documents every endpoint, payload, status code, business rule, table and known gap. A future maintainer gets the invariants without reading every method body.
- Naming encodes intent (`findByDealershipIdLocking` vs `findByDealershipId`, `ACTIVE_STATUSES`), so the availability check and the booking path are distinguishable at a glance.

### 3.4 Known limitations were recorded rather than hidden

Quality includes being honest about what the system deliberately does *not* do. The documented gaps — no cancel/reschedule API, no CRUD for reference data, no listing/search endpoints, no auth, naive `LocalDateTime` with no zone handling, "first-free" resource assignment — are listed in the reference doc so a reader can judge scope at a glance instead of discovering the boundaries by reading code.

---

### Outcome

The evidence of quality is in the repository itself: a small, focused API surface; an executable test suite that pins the business rules (including the hard concurrency one) against the real database; explicit locking; a uniform error contract; and documentation that records both the design and its limits. Everything else in this README is the accountable record of how that was achieved.