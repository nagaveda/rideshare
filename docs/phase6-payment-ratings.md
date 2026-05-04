# Phase 6: Payment & Ratings — Code Review

Atomic commits:
- `952488c` — Payment entity, enums, and PaymentRepository
- `07243cc` — PaymentService and create-on-completion hook in RideService
- `ca425a1` — PaymentController with paginated history endpoint
- `1d59d85` — Rating entity and RatingRepository
- `5d514fc` — RatingService with submission rules and running average update
- `297f96b` — RatingController with POST /rides/{id}/rate and DTOs

---

## Overview

Phase 6 closes the loop on a completed ride: a payment record is created automatically on ride completion, and rider/driver can each submit a 1–5 rating for the other. The driver's rolling average rating is updated as ratings come in.

This phase is intentionally lightweight. There is no real payment gateway — `Payment` is a record-of-fact for the fare breakdown. The state machine for payment status is in place (`PENDING → COMPLETED → ...`) but the MVP creates each payment in `COMPLETED` status directly because cash settlement happens out-of-band.

### Payment flow

```
Driver: PUT /api/v1/rides/{id}/complete
              │
              ▼
       RideService.completeRide()
       ├─ status → COMPLETED
       ├─ actualFare = estimatedFare
       ├─ driver → AVAILABLE
       ├─ rideRepository.save()
       └─ paymentService.createPaymentForRide(ride)   ← new in Phase 6
              │
              ▼
       Payment row inserted (idempotent on rideId)
              │
              ▼
       publishEvent(COMPLETED) → ride-events Kafka topic
```

### Rating flow

```
User (rider OR driver): POST /api/v1/rides/{id}/rate
              │
              ▼
       RatingService.submitRating()
       ├─ Score must be 1..5
       ├─ Ride must be COMPLETED
       ├─ Caller must be the rider or the driver of this ride
       ├─ Resolve ratee = the other party
       ├─ Reject duplicate (uq_ratings_ride_rater)
       ├─ ratingRepository.save()
       └─ If ratee is driver: update DriverProfile rolling average
              │
              ▼
       Return RatingResponse
```

---

## Files Created

### 1. Payment Domain

#### `payment/model/PaymentMethod.java`, `PaymentStatus.java`

```java
public enum PaymentMethod { CASH, CARD }
public enum PaymentStatus { PENDING, COMPLETED, FAILED, REFUNDED }
```

The enum names mirror the `CHECK` constraints in the V6 migration. `@Enumerated(EnumType.STRING)` on the entity ensures the DB stores the names, not ordinals — same rule as `RideStatus`: enum reordering can't silently corrupt data.

`FAILED` and `REFUNDED` are unused at runtime today. They're modeled because the column accepts them and future logic (gateway integration, dispute handling) will need them. Removing them would force a migration later.

#### `payment/model/Payment.java`

Extends `BaseEntity` (id, createdAt, updatedAt). One row per ride, enforced by `ride_id UUID NOT NULL UNIQUE` in V6.

| Field | Type | Notes |
|-------|------|-------|
| rideId | UUID | Unique. The DB rejects duplicate payments per ride; the service double-checks via `existsByRideId` to fail fast with a friendly log. |
| riderId, driverId | UUID | Both required. Stored on the row so payment history queries don't have to join `rides`. |
| amount | BigDecimal(10,2) | Total charged. Equals `Ride.actualFare`. |
| baseFare | BigDecimal(10,2) | Pre-surge component. Computed: `amount / surgeMultiplier`. |
| surgeAmount | BigDecimal(10,2) | `amount - baseFare`. May be 0 if surge is 1.0. |
| paymentMethod | PaymentMethod | Defaulted to `CASH`. The MVP doesn't accept method input. |
| status | PaymentStatus | Set to `COMPLETED` directly in MVP. |

**Why store the breakdown if we have `Ride.estimatedFare` + `surgeMultiplier`?**
A payment is the immutable record. If pricing logic changes later or a ride row is mutated for any reason, the original fare snapshot lives on the payment row. Receipts and reporting query payments, not rides.

#### `payment/repository/PaymentRepository.java`

```java
Optional<Payment> findByRideId(UUID rideId);
boolean existsByRideId(UUID rideId);
Page<Payment> findByRiderIdOrderByCreatedAtDesc(UUID riderId, Pageable pageable);
Page<Payment> findByDriverIdOrderByCreatedAtDesc(UUID driverId, Pageable pageable);
```

- `existsByRideId` is the idempotency check. Cheaper than `findByRideId` because it can use a bare COUNT.
- History queries are paginated and ordered newest-first. Same pattern as `RideRepository`.

---

### 2. PaymentService

#### `payment/service/PaymentService.java`

**`createPaymentForRide(Ride ride)`** — called from `RideService.completeRide()` after the ride row is saved.

Guards:
1. Ride status must be `COMPLETED`. If a caller sneaks in with another status we throw `BadRequestException` rather than silently inserting a row.
2. Idempotency on `rideId` — return the existing payment instead of throwing on duplicate. The caller is `RideService` inside the same transaction, so a duplicate should be impossible in normal flow, but if Kafka redelivery or a retry ever lands here, returning the existing row is the safe behavior.

Fare breakdown:
```java
BigDecimal total = ride.getActualFare();
BigDecimal surgeMultiplier = ride.getSurgeMultiplier() != null ? ride.getSurgeMultiplier() : BigDecimal.ONE;
BigDecimal baseFare = surgeMultiplier.compareTo(BigDecimal.ZERO) > 0
        ? total.divide(surgeMultiplier, 2, RoundingMode.HALF_UP)
        : total;
BigDecimal surgeAmount = total.subtract(baseFare).max(BigDecimal.ZERO);
```

- `total / surgeMultiplier = baseFare` is the inverse of how `FareCalculator` builds the total. Reconstructing this on the payment side keeps the breakdown without needing to thread the original `baseFare` through the ride entity.
- `RoundingMode.HALF_UP` everywhere money is divided. The 2-scale (cents) keeps results consistent with how the rides table stores fares.
- `surgeAmount.max(0)` defends against rounding such that `total - baseFare` could be a tiny negative (e.g., `5.00 - 5.00`).

Status defaulted to `COMPLETED` and method to `CASH`. In a future phase that integrates a gateway, the flow becomes: create as `PENDING`, send authorization, listen for callback, transition to `COMPLETED` or `FAILED`.

**History methods** — `getRiderHistory` / `getDriverHistory`. Both `@Transactional(readOnly = true)`.

#### `RideService.completeRide()` change

One line added:
```java
paymentService.createPaymentForRide(ride);
```

Placed *before* `publishEvent(ride, null)` so the audit log entry for `COMPLETED` is published only after the payment row exists. Both calls run inside the same `@Transactional` boundary as the ride save, so a payment-creation failure rolls back the status transition. That's intentional — a completed ride without a payment is a worse state than a ride still in `IN_PROGRESS`.

The trade-off: PaymentService throwing means the driver's "complete" call returns 500 and the ride stays `IN_PROGRESS`. They can retry. For an MVP backed by cash this is fine; with a real gateway you'd decouple — let `COMPLETED` commit and have a Kafka consumer drive payment authorization separately.

---

### 3. Payment API

#### `payment/dto/PaymentResponse.java`

Public-facing view. Drops the row id out as `paymentId` for symmetry with `RideResponse.rideId`. Includes the breakdown, method, status, and `createdAt`.

#### `payment/controller/PaymentController.java`

Single endpoint:

```
GET /api/v1/payments/history?page=0&size=20
```

Same role-branching pattern as `RideController.getHistory`:

```java
boolean isDriver = authentication.getAuthorities().stream()
        .anyMatch(a -> a.getAuthority().equals("ROLE_DRIVER"));
Page<Payment> payments = isDriver
        ? paymentService.getDriverHistory(userId, pageable)
        : paymentService.getRiderHistory(userId, pageable);
```

A rider calling sees rides they paid for; a driver calling sees rides they earned on. One endpoint, "my history" mental model.

No `@PreAuthorize` — both roles can call. The branch is the auth model.

---

### 4. Rating Domain

#### `rating/model/Rating.java`

Standalone entity (not `BaseEntity`). Append-only — there's no need for `updatedAt`. Schema mirrors V7:

| Field | Type | Notes |
|-------|------|-------|
| id | UUID | `@GeneratedValue(strategy = GenerationType.UUID)` — same approach as `RideEvent`, `RideRoutePoint`. |
| rideId | UUID | FK to `rides.id`. |
| raterId | UUID | The user submitting the rating. |
| rateeId | UUID | The user being rated. Resolved from the ride, not accepted as input — prevents a malicious caller from rating the wrong party. |
| score | Integer | 1..5. Validated in DTO and again at service entry. DB also has `CHECK (score >= 1 AND score <= 5)`. |
| comment | String(500) | Optional. |
| createdAt | LocalDateTime | Set explicitly in service to `LocalDateTime.now()`. |

The `@Table` annotation re-declares the unique constraint:
```java
@Table(name = "ratings", uniqueConstraints = @UniqueConstraint(
        name = "uq_ratings_ride_rater", columnNames = {"ride_id", "rater_id"}))
```
The migration already creates this constraint at the DB level (`uq_ratings_ride_rater`). Re-declaring it on the JPA side is purely documentation — Hibernate doesn't add a duplicate constraint when the migration owns the schema, but having it in the entity matches the table definition for anyone reading the code.

#### `rating/repository/RatingRepository.java`

```java
boolean existsByRideIdAndRaterId(UUID rideId, UUID raterId);
```

The duplicate-rating guard. The unique constraint at the DB layer is the safety net; this method lets us throw `BadRequestException("You have already rated this ride")` with a friendly message before the insert reaches the DB.

---

### 5. RatingService

#### `rating/service/RatingService.java`

**`submitRating(rideId, raterId, score, comment)`** — five validations in order:

1. **Score range** (1..5) — defense in depth even though the DTO already enforces it.
2. **Ride exists** — `ResourceNotFoundException` if not.
3. **Ride is COMPLETED** — riding-in-progress and cancelled rides can't be rated.
4. **Caller is part of the ride** — `resolveRatee()` returns the *other* party or throws `UnauthorizedException`. This implicitly confirms the caller is rider or driver.
5. **No duplicate** — `existsByRideIdAndRaterId` check.

`resolveRatee()` is the small invariant that makes the rating safe:

```java
if (raterId.equals(ride.getRiderId())) return ride.getDriverId();   // rider rates driver
if (raterId.equals(ride.getDriverId())) return ride.getRiderId();   // driver rates rider
throw new UnauthorizedException(...);
```

The client never specifies who they're rating — the ride has exactly two parties, and the ratee is whichever one isn't the caller. This rules out a class of bugs where a rater could attribute a rating to the wrong user.

**Running average update — driver only:**

```java
if (rateeId.equals(ride.getDriverId())) {
    updateDriverRunningAverage(rateeId, score);
}
```

Why only drivers? `DriverProfile` has `rating` and `total_rides` columns. Riders don't have a profile entity in the MVP — there's no place to store the rolling average for a rider. Driver-rates-rider is recorded in `ratings` but not aggregated. If/when a `RiderProfile` lands, the same pattern can apply there.

**Rolling average math:**

```java
newAverage = (oldAverage × oldCount + newScore) / (oldCount + 1)
```

- Stored as `BigDecimal` with `RoundingMode.HALF_UP` to scale 2 (e.g., `4.73`).
- `total_rides` is bumped by 1 each time. **Note:** `total_rides` here counts ratings received, not rides completed. The schema only has these two columns, so we use `total_rides` as the denominator. A real system would have a separate `rating_count` column to keep the two metrics independent.
- Not concurrency-safe under high write rates: two simultaneous rating submissions for the same driver could read the same `oldCount` and produce a slightly wrong average. Acceptable for this MVP because rating frequency per driver is low. If it becomes a hotspot, switch to a SQL UPDATE that computes the new value atomically:
  ```sql
  UPDATE driver_profiles
  SET rating = ((rating * total_rides) + :score) / (total_rides + 1),
      total_rides = total_rides + 1
  WHERE user_id = :driverId
  ```

---

### 6. Rating API

#### `rating/dto/RatingRequest.java`

```java
@NotNull @Min(1) @Max(5) private Integer score;
@Size(max = 500) private String comment;
```

`Integer` (boxed) so `@NotNull` actually fires — a primitive `int` would default to 0 silently.

#### `rating/dto/RatingResponse.java`

Plain projection of the entity with a `from(Rating)` factory. Same pattern as `RideResponse.from()` and `PaymentResponse.from()`.

#### `rating/controller/RatingController.java`

```
POST /api/v1/rides/{id}/rate
```

- **Mounted under `/api/v1/rides`**, not `/api/v1/ratings`. Matches the feature checklist spec and reads naturally — you rate *the ride*, not abstract ratings.
- **No `@PreAuthorize`** — both rider and driver are valid callers. The service's `resolveRatee` enforces the actual rule.
- Returns `201 CREATED` with the new rating. Future: also expose `GET /api/v1/users/{id}/ratings` once there's a need for a profile-style "show me everyone's ratings" view.

---

## Design Decisions Summary

| Decision | Rationale |
|----------|-----------|
| Payment created synchronously in `completeRide` | Same transaction as the status transition. A completed ride without a payment is worse than a stuck transition. Acceptable while there's no real gateway. |
| Idempotent `createPaymentForRide` | Returns the existing payment instead of throwing. Survives accidental double-call from Kafka redelivery or retry without surfacing a confusing 400. |
| Reconstruct `baseFare = total / surgeMultiplier` | Avoids adding new fields to `Ride` just to thread the breakdown to the payment. Same numbers as `FareCalculator` produced. |
| `BigDecimal` with HALF_UP for all money | Same rule as Phase 4. Floating-point on currency is a bug-by-default. |
| `PaymentMethod = CASH`, `PaymentStatus = COMPLETED` defaulted | MVP — no real settlement. Enums model the future state machine; the values are picked at construction. |
| Rate endpoint mounted under `/rides/{id}/rate` | Reads naturally; matches the spec. Avoids a separate `/ratings` namespace nobody would discover. |
| `resolveRatee` from the ride, not the request | Caller doesn't specify who they're rating — only `score` and `comment`. Ratee is determined from membership in the ride. Closes a class of attribution bugs. |
| Running average updated only when ratee is the driver | Riders have no profile entity. Storing in DriverProfile keeps the change minimal. Trivial to add for riders later by introducing a profile table. |
| `total_rides` doubles as the rating count | The schema only has two columns. A separate `rating_count` is an easy follow-up if the metrics need to diverge. |
| Read-modify-write for the rolling average | Simple. Race-prone under concurrent writes per driver. Migrate to a SQL `UPDATE ... SET rating = expr` if it ever becomes a hotspot. |
| `existsByRideIdAndRaterId` precheck on duplicates | Friendly error before hitting the DB unique constraint. The constraint is still the source of truth. |
| `@Min(1) @Max(5)` in DTO + service-level recheck | DTO catches malformed requests at the controller; service guards against any internal caller that bypasses the DTO. Cheap defense. |

---

## How Phase 6 Connects to Other Phases

- **Phase 2 (Auth):** Both controllers rely on `@AuthenticationPrincipal UUID` from the JWT filter. The PaymentController history branch and the RatingService caller-validation both need authority info from the security context.
- **Phase 4 (Pricing):** The fare breakdown stored on `Payment` mirrors what `FareCalculator` produced — Phase 6 reverses the math (`total / surgeMultiplier`) to reconstruct it.
- **Phase 5 (Ride Management):** `PaymentService.createPaymentForRide` is hooked into `RideService.completeRide`. `RatingService` reads `Ride` directly to validate state and party membership. Both phases depend on `RideStatus.COMPLETED` being a stable terminal state.
- **Phase 7 (Polish, upcoming):** Integration test for the full flow — request → match → complete → payment → rating — exercises everything in this phase end to end.
