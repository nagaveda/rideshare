# Phase 5: Ride Management — Code Review

Atomic commits:
- `4fc93ac` — RideStatus enum, Ride entity, RideRepository
- `b7a6197` — RideRoutePoint entity and repository
- `02c792f` — ride_events migration, entity, repository
- `b8b603d` — MatchingService (Redis + PostGIS fallback)
- `4f9cff9` — RideEventProducer + RideEventConsumer (Kafka pipeline)
- `7cd2ee1` — RideService (lifecycle + state machine)
- `97bfb7a` — RideController + DTOs

---

## Overview

Phase 5 implements the core ride lifecycle: a rider requests a ride, the system finds a nearby driver, the driver progresses through pickup/dropoff states, and the ride completes (or is cancelled). Every state change is published to Kafka and persisted to an immutable audit log.

Three things make this phase load-bearing for the rest of the system:

1. **The state machine** — `RideStatus.canTransitionTo()` is the single authority on what state moves are legal. Every service method routes through it.
2. **The matching algorithm** — radius expansion (3 → 5 → 10 km) with Redis GEO as the primary index and PostGIS as a fallback when Redis is unavailable.
3. **The event audit log** — every transition produces a Kafka event, which a consumer writes to `ride_events`. The rides table holds the latest state; ride_events holds the full history.

### Ride lifecycle

```
   ┌──────────────┐
   │  REQUESTED   │ ── rider creates ride, system tries to match a driver
   └──────┬───────┘
          │ accept (driver) / auto-assign
          ▼
   ┌──────────────────┐
   │ DRIVER_ASSIGNED  │ ── driver acknowledged the ride
   └──────┬───────────┘
          │ en-route
          ▼
   ┌──────────────────┐
   │ DRIVER_EN_ROUTE  │ ── driver heading to pickup
   └──────┬───────────┘
          │ arrived
          ▼
   ┌──────────────┐
   │   ARRIVED    │ ── driver at pickup location
   └──────┬───────┘
          │ start
          ▼
   ┌──────────────┐
   │ IN_PROGRESS  │ ── rider in vehicle, trip underway
   └──────┬───────┘
          │ complete
          ▼
   ┌──────────────┐
   │  COMPLETED   │ ── terminal
   └──────────────┘

Any non-terminal state can transition to → CANCELLED (terminal),
EXCEPT IN_PROGRESS which can only complete.
```

### Ride request flow (end-to-end)

```
Rider POST /api/v1/rides/request
              │
              ▼
       RideService.requestRide()
       │
       ├─ 1. Reject if rider already has an active ride
       ├─ 2. FareCalculator.estimate() → distance, duration, surge, fare
       ├─ 3. SurgeService.incrementDemand() → Redis INCR on pickup zone
       ├─ 4. Save Ride (status=REQUESTED) → rides table
       ├─ 5. MatchingService.findNearestDriver()
       │      ├─ Redis GEORADIUS (3km → 5km → 10km)
       │      └─ Filter to AVAILABLE drivers
       ├─ 6. publishEvent(REQUESTED) → ride-events Kafka topic
       │      └─ RideEventConsumer → ride_events table
       └─ 7. If matched: assignDriver() → status=DRIVER_ASSIGNED, driver→BUSY
              └─ publishEvent(DRIVER_ASSIGNED)
              │
              ▼
        Return RideResponse to rider
```

---

## Files Created

### 1. State Machine

#### `ride/model/RideStatus.java`

The state machine is encoded as a static `Map<RideStatus, Set<RideStatus>>` of allowed transitions. `canTransitionTo()` returns true only if the target state is in the allowed set for the current state.

```java
private static final Map<RideStatus, Set<RideStatus>> ALLOWED_TRANSITIONS = Map.of(
    REQUESTED,       EnumSet.of(DRIVER_ASSIGNED, CANCELLED),
    DRIVER_ASSIGNED, EnumSet.of(DRIVER_EN_ROUTE, CANCELLED),
    DRIVER_EN_ROUTE, EnumSet.of(ARRIVED, CANCELLED),
    ARRIVED,         EnumSet.of(IN_PROGRESS, CANCELLED),
    IN_PROGRESS,     EnumSet.of(COMPLETED),
    COMPLETED,       EnumSet.noneOf(RideStatus.class),
    CANCELLED,       EnumSet.noneOf(RideStatus.class)
);
```

**Why this design:**
- **Single source of truth.** Putting the rules in the enum (not scattered through service methods) means there's exactly one place to change them, and the service can't accidentally allow an invalid move.
- **`EnumSet` for the value set.** `EnumSet` is a bitmask under the hood — fast contains-check and tiny memory footprint. The map size never grows so a static initializer is fine.
- **Terminal states have empty sets.** `COMPLETED` and `CANCELLED` can't transition to anything. `isTerminal()` is a convenience for guards in service code (e.g., "don't poll driver location for a terminal ride").
- **`IN_PROGRESS` cannot cancel.** Once the trip has started (rider in the car), cancellation isn't a valid operation. The only way out is `COMPLETED`.

This is a simple, declarative state machine. It would only be worth replacing with Spring StateMachine or a dedicated library if we needed transition listeners, hierarchical states, or external persistence of state history (which the `ride_events` table already covers).

---

### 2. Ride Entity

#### `ride/model/Ride.java`

The main aggregate for the ride domain. Extends `BaseEntity` (id, createdAt, updatedAt).

| Field | Type | Notes |
|-------|------|-------|
| riderId | UUID | Always set. References `users.id` via FK in DB. |
| driverId | UUID | Nullable — only set after a driver is assigned. |
| status | RideStatus | `@Enumerated(STRING)` — stored as varchar, not ordinal. Survives enum reordering. |
| pickupLocation | Point | `geometry(Point,4326)` — JTS Point with SRID 4326 (WGS-84 lat/lng). |
| dropoffLocation | Point | Same. |
| pickupAddress | String(500) | Optional human-readable address. |
| dropoffAddress | String(500) | Same. |
| estimatedFare | BigDecimal(10,2) | Frozen at request time. |
| actualFare | BigDecimal(10,2) | Set on completion. For MVP, equals estimated. |
| surgeMultiplier | BigDecimal(4,2) | Frozen at request time, e.g. 1.50. |
| distanceKm | BigDecimal(8,2) | Straight-line, from FareCalculator. |
| durationMinutes | BigDecimal(8,2) | Estimated, from FareCalculator. |
| requestedAt | LocalDateTime | Always set on creation. |
| acceptedAt / startedAt / completedAt / cancelledAt | LocalDateTime | Each set as the ride progresses. Together they form a transition timeline that's queryable directly on the rides table without joining ride_events. |
| cancellationReason | String(500) | Optional, set only when cancelled. |

**Design decisions:**
- **Money is `BigDecimal`, not `double`.** Same reason as Phase 4 — exact decimal arithmetic.
- **`@Enumerated(EnumType.STRING)`.** The DB stores `"REQUESTED"`, not `0`. If we add a new enum value in the middle, existing rows don't silently get the wrong meaning.
- **Per-state timestamps on the entity.** The audit log in `ride_events` is the source of truth for transitions, but having `acceptedAt/startedAt/completedAt` directly on the ride avoids a join for the common "show me my recent rides" query.
- **No `@OneToMany` to RideRoutePoint or RideEvent.** Those are queried by `rideId` directly on their own repositories. Keeping the aggregate small avoids accidental N+1 fetches when listing rides.

#### `ride/repository/RideRepository.java`

```java
Optional<Ride> findFirstByRiderIdAndStatusNotInOrderByRequestedAtDesc(UUID riderId, List<RideStatus> excludedStatuses);
Optional<Ride> findFirstByDriverIdAndStatusNotInOrderByRequestedAtDesc(UUID driverId, List<RideStatus> excludedStatuses);
Page<Ride> findByRiderIdOrderByRequestedAtDesc(UUID riderId, Pageable pageable);
Page<Ride> findByDriverIdOrderByRequestedAtDesc(UUID driverId, Pageable pageable);
```

- **`findFirstBy...StatusNotIn`** is the "active ride" lookup. Pass `[COMPLETED, CANCELLED]` and you get the rider's (or driver's) currently in-flight ride, if any. Used to prevent double-booking.
- **`Page<Ride>` for history.** Riders/drivers may have hundreds of rides over time — paginate from the start.
- All queries are derived from method names. No `@Query` needed because the joins/filters are simple.

---

### 3. Per-Ride GPS History

#### `ride/model/RideRoutePoint.java`

Captures location samples during an active ride. Independent of `DriverLocation` (which is the driver's *current* location, overwritten on each update).

```java
@Entity
@Table(name = "ride_route_points")
public class RideRoutePoint {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID rideId;
    private Point location;            // geometry(Point,4326)
    private LocalDateTime recordedAt;
}
```

**Why a separate table:**
- `driver_locations` is one row per driver, kept fresh by the location consumer. Reading "where was driver D 5 minutes ago" from there is impossible — the old value is gone.
- `ride_route_points` accumulates samples during a single ride for receipt generation, post-trip route replay, and dispute resolution.
- Not a child of `BaseEntity` because we don't need an `updatedAt` — these rows are write-once.

**Note:** Phase 5 lays down the entity and repository but doesn't yet write samples. The hook will live in `LocationConsumer` (write a route point if the driver has an active ride) — wired in a follow-up. The schema is ready so that later change is purely additive.

#### `ride/repository/RideRoutePointRepository.java`

```java
List<RideRoutePoint> findByRideIdOrderByRecordedAtAsc(UUID rideId);
```

Single query — pull the full route in chronological order for replay/receipt.

---

### 4. Ride Events Audit Log

The rides table holds the *latest* state of a ride. The `ride_events` table holds the *history* of every transition. Together they let us answer both "what's the ride doing right now?" and "show me everything that happened to this ride."

#### `db/migration/V8__create_ride_events_table.sql`

```sql
CREATE TABLE ride_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ride_id UUID NOT NULL REFERENCES rides (id),
    rider_id UUID NOT NULL REFERENCES users (id),
    driver_id UUID REFERENCES users (id),
    status VARCHAR(20) NOT NULL CHECK (status IN (...)),
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ride_events_ride_id ON ride_events (ride_id);
CREATE INDEX idx_ride_events_rider_id ON ride_events (rider_id);
CREATE INDEX idx_ride_events_driver_id ON ride_events (driver_id);
CREATE INDEX idx_ride_events_created_at ON ride_events (created_at);
```

- **Append-only.** Nothing in the codebase updates rows here — only inserts. This makes it safe to use as an audit/timeline source and trivially shippable to long-term cold storage later.
- **`status VARCHAR(20)` with CHECK constraint.** Mirrors the enum at the DB level. Catches drift if the enum and the table get out of sync (Flyway migration would be needed to add a new value).
- **`metadata JSONB`.** Free-form per-event detail (e.g., cancellation reason, surge multiplier at the time, decline reasons later). JSONB so we can index/query inside it without schema changes.
- **Indexes on `ride_id`, `rider_id`, `driver_id`, `created_at`.** All four are common access patterns: full ride history, all events for a user, recent events for monitoring.

#### `ride/model/RideEvent.java`

```java
@Entity
@Table(name = "ride_events")
public class RideEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID rideId;
    private UUID riderId;
    private UUID driverId;       // nullable
    @Enumerated(EnumType.STRING)
    private RideStatus status;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;
    private LocalDateTime createdAt;
}
```

- **`@JdbcTypeCode(SqlTypes.JSON)`** is the modern Hibernate 6 way to bind a `String` field to a `JSONB` column. No custom `@TypeDef` or `@Type` plumbing required.
- The field is a `String` rather than a `Map<String, Object>` because callers serialize their own JSON (see `RideService.cancelRide` — it builds a small JSON literal inline). Keeping it as a string avoids forcing every producer to use the same DTO shape for metadata.

#### `ride/repository/RideEventRepository.java`

```java
List<RideEvent> findByRideIdOrderByCreatedAtAsc(UUID rideId);
```

Single query — full event log for a ride in chronological order.

---

### 5. Matching Service

#### `matching/service/MatchingService.java`

The driver-finding algorithm. The contract:

> Given a pickup point and a set of drivers to skip, return the UUID of the nearest available driver, or empty if none exist within 10km.

```java
private static final double[] RADIUS_EXPANSION_KM = {3.0, 5.0, 10.0};

public Optional<UUID> findNearestDriver(double pickupLng, double pickupLat, Set<UUID> skippedDrivers) {
    for (double radiusKm : RADIUS_EXPANSION_KM) {
        List<UUID> candidates = findCandidates(pickupLng, pickupLat, radiusKm);
        for (UUID driverId : candidates) {
            if (skippedDrivers.contains(driverId)) continue;
            if (isDriverAvailable(driverId)) {
                log.info("Matched driver {} at radius {}km", driverId, radiusKm);
                return Optional.of(driverId);
            }
        }
        log.debug("No available drivers within {}km, expanding radius", radiusKm);
    }
    return Optional.empty();
}
```

**Why radius expansion (3 → 5 → 10):**
- Most matches happen within 3km in dense areas — start narrow to find the *nearest* driver, not just *a* driver.
- Expand to 5km, then 10km, only if the smaller radius came up empty. This avoids shipping back the closest driver in a 10km sweep when there's a perfectly good driver 2km away.
- 10km is the hard cap. If no driver is within 10km, the ride stays in `REQUESTED` and the rider sees "looking for drivers" until a retry (future phase) succeeds.

**The `skippedDrivers` parameter:**
- Reserved for future "driver declined → retry with a different driver" logic. The current MVP auto-assigns and doesn't yet pass anything here, but the API is in place so the matching service doesn't need to change when decline-and-retry lands.

#### Redis primary, PostGIS fallback

```java
private List<UUID> findCandidates(double lng, double lat, double radiusKm) {
    if (redisService.isAvailable()) {
        try {
            return redisService.findDriversWithinRadius(lng, lat, radiusKm);
        } catch (Exception e) {
            log.warn("Redis GEO query failed, falling back to PostGIS: {}", e.getMessage());
        }
    }
    double radiusMeters = radiusKm * 1000.0;
    List<DriverLocation> drivers = driverLocationRepository.findAvailableDriversWithinRadius(lng, lat, radiusMeters);
    return drivers.stream().map(DriverLocation::getDriverId).toList();
}
```

- **Why Redis first.** GEORADIUS on a geo-set is sub-millisecond. PostGIS with a GiST index is fast (~5-10ms) but Redis is roughly an order of magnitude faster, and the location-update pipeline already keeps Redis warm.
- **Why fallback to PostGIS.** If Redis is down or evicted, matching must still work. The dual-write in `LocationConsumer` (Phase 3) means PostGIS holds the same data, just slightly less fresh. Better a 5ms PostGIS query than refusing all rides.
- **`isAvailable()` ping-check first.** Avoids the per-call try/catch overhead when Redis is healthy *and* short-circuits when it's known down.
- The fallback path uses `findAvailableDriversWithinRadius` which already JOINs `driver_profiles` and filters for `AVAILABLE` status. The Redis path returns *all* drivers in the radius (no status filter), so the caller still has to call `isDriverAvailable()` per candidate — that's why the loop in `findNearestDriver` checks again. A bit redundant when PostGIS-fallback returns pre-filtered results, but the cost is one DB lookup per candidate, and it keeps the two paths uniform.

#### `isDriverAvailable(driverId)`

```java
private boolean isDriverAvailable(UUID driverId) {
    return driverProfileRepository.findByUserId(driverId)
            .map(p -> p.getStatus() == DriverStatus.AVAILABLE)
            .orElse(false);
}
```

The status check that catches drivers who are in Redis GEO but currently `BUSY` or `OFFLINE`. The location pipeline doesn't synchronously remove a driver from Redis the moment they go BUSY (they stay in geo until they next send a location update or TTL expires), so this guard prevents handing the same driver multiple concurrent rides.

---

### 6. Kafka Pipeline for Ride Events

#### `ride/dto/RideEventPayload.java`

The Kafka message payload. Plain DTO with Jackson defaults.

```java
public class RideEventPayload {
    private UUID rideId;
    private UUID riderId;
    private UUID driverId;     // nullable
    private RideStatus status;
    private String metadata;   // optional JSON
    private Instant timestamp;
}
```

- **`Instant`, not `LocalDateTime`.** Kafka events cross process boundaries and may eventually cross regions — `Instant` is unambiguous (UTC). The consumer converts to `LocalDateTime` using the system zone before storing.

#### `ride/kafka/RideEventProducer.java`

```java
private static final String TOPIC = "ride-events";

public void publish(RideEventPayload event) {
    kafkaTemplate.send(TOPIC, event.getRideId().toString(), event);
}
```

- **Keyed by `rideId`.** All events for the same ride go to the same partition, which means they're consumed in order. Without that key, two events for the same ride could land on different partitions and be processed out of order — leading to a wrong final state in the audit log.
- **Fire-and-forget.** Not awaited. If the broker is briefly unreachable, Kafka client retries handle it. If the broker is *durably* down, the ride still progresses (state lives in Postgres) — we'd lose audit log entries, which is a degraded-but-acceptable mode for an event-sourced *audit* table (vs. a primary store).

#### `ride/kafka/RideEventConsumer.java`

```java
@KafkaListener(topics = "ride-events", groupId = "rideshare-group")
@Transactional
public void consume(RideEventPayload payload) {
    RideEvent event = RideEvent.builder()
            .rideId(payload.getRideId())
            .riderId(payload.getRiderId())
            .driverId(payload.getDriverId())
            .status(payload.getStatus())
            .metadata(payload.getMetadata())
            .createdAt(LocalDateTime.ofInstant(payload.getTimestamp(), ZoneId.systemDefault()))
            .build();
    rideEventRepository.save(event);
}
```

- **`@Transactional`.** The save runs in a transaction so a failure rolls back cleanly and Kafka redelivers.
- **Reuses `groupId: "rideshare-group"`** from Phase 3 — one consumer group per service replica. Adding a second consumer group later (e.g., for an analytics sink) requires no producer changes.

**Why a Kafka log at all instead of writing to `ride_events` synchronously?**
- Decouples write latency: ride state changes commit to `rides` immediately; the audit log writes asynchronously.
- Lets future consumers subscribe (notifications, analytics, fraud detection) without touching `RideService`.
- The trade-off is at-least-once delivery semantics — the consumer could in theory write the same event twice on redelivery. For an append-only audit log this is acceptable; the timestamp + payload makes duplicates obvious if we ever need to deduplicate.

---

### 7. Geometry Utility

#### `common/util/GeometryUtils.java`

```java
public static Point point(double longitude, double latitude) {
    Point p = FACTORY.createPoint(new Coordinate(longitude, latitude));
    p.setSRID(SRID_WGS84);  // 4326
    return p;
}
```

A small utility extracted because building a JTS `Point` correctly requires three things every time:
1. `(longitude, latitude)` order, *not* `(latitude, longitude)`. PostGIS uses `(x=lng, y=lat)`. Mixing this up is a silent bug — the point ends up off the coast of Antarctica.
2. SRID must be 4326 to match the column type. Without SRID, PostGIS won't spatially index the value.
3. A shared `GeometryFactory` instance — cheaper than constructing one per call.

`RideService` and `LocationConsumer` (Phase 3, retroactively could use this) build points often enough that centralizing this prevents drift.

---

### 8. RideService — The Lifecycle Orchestrator

#### `ride/service/RideService.java`

The fattest service in the project. Coordinates 7 dependencies:

```java
private final RideRepository rideRepository;
private final DriverProfileRepository driverProfileRepository;
private final DriverLocationRepository driverLocationRepository;
private final MatchingService matchingService;
private final FareCalculator fareCalculator;
private final SurgeService surgeService;
private final RideEventProducer rideEventProducer;
```

Every public method is `@Transactional` (or `@Transactional(readOnly = true)` for queries). Spring's PROPAGATION_REQUIRED means the transaction wraps the whole flow including the DB save and the Kafka publish — note that Kafka's send is *not* transactional with the DB by default, so the publish succeeds even if the transaction rolls back. For a production system you'd add a transactional outbox; for this MVP, the DB is the source of truth and Kafka events are best-effort.

#### `requestRide(riderId, request)`

The most complex method. Steps:

1. **Reject duplicate active ride** — `findFirstByRiderIdAndStatusNotInOrderByRequestedAtDesc(riderId, [COMPLETED, CANCELLED])`. If anything comes back, throw `BadRequestException`. Prevents a rider from spamming requests.
2. **Estimate fare** — calls `fareCalculator.estimate()` (Phase 4). Captures the fare/surge at request time so the rider isn't surprised by a different price at completion.
3. **Increment surge demand** — `surgeService.incrementDemand()` ticks the Redis counter for the pickup zone. Future requests for the same zone will see higher surge.
4. **Save the ride** in `REQUESTED` state with all fields populated.
5. **Try to match** — `matchingService.findNearestDriver()` with empty skip set. The MVP doesn't yet retry on decline.
6. **Publish REQUESTED event** — even if no driver matches yet, the audit log shows the request.
7. **If matched: auto-assign** — `assignDriver()` sets `driverId`, transitions to `DRIVER_ASSIGNED`, sets the driver to `BUSY`, publishes `DRIVER_ASSIGNED`.

The auto-assign-on-match is an MVP shortcut. A real system would publish a "ride offer" to the driver, wait for accept/decline within a window (e.g., 15s), and on decline retry matching with the declining driver in the skip set. The retry plumbing — `skippedDrivers` parameter on `MatchingService` — is already in place; only the offer/decline workflow is missing.

#### Lifecycle methods (`acceptRide`, `startEnRoute`, `arrivedAtPickup`, `startRide`, `completeRide`)

All five follow the same template:

```java
public Ride lifecycleStep(UUID rideId, UUID driverId) {
    Ride ride = findRide(rideId);
    validateAssignedDriver(ride, driverId);
    transition(ride, RideStatus.NEW_STATE);
    // optionally: set timestamps, update driver status
    ride = rideRepository.save(ride);
    publishEvent(ride, null);
    return ride;
}
```

- `validateAssignedDriver` — the caller's `driverId` must match the ride's assigned `driverId`. Prevents Driver A from progressing Driver B's ride.
- `transition` — funnels through `RideStatus.canTransitionTo()`. Throws `BadRequestException` if illegal.
- `publishEvent` — fires Kafka after the DB save (so the audit log mirrors what's persisted).

`acceptRide` exists alongside `requestRide`'s auto-assign for the future flow where the driver explicitly accepts. Today, after auto-assign, the next call would be `startEnRoute` directly. `acceptRide` is unused by the happy path but kept as the formal entry point so the API doesn't change when decline-and-retry lands.

`completeRide` does two extra things:
- Sets `actualFare = estimatedFare` (MVP — real system would adjust for actual distance/duration).
- Transitions the driver back to `AVAILABLE` so they can take the next ride.

#### `cancelRide(rideId, userId, reason)`

Special-cased because:
- Either the rider *or* the driver can cancel.
- It's the only transition where the metadata payload carries non-empty content (the cancellation reason).
- It bypasses the "validate assigned driver" check — both rider and driver are valid cancellers.

```java
if (!ride.getRiderId().equals(userId) && !userId.equals(ride.getDriverId())) {
    throw new UnauthorizedException("Only the rider or assigned driver can cancel this ride");
}
if (!ride.getStatus().canTransitionTo(RideStatus.CANCELLED)) {
    throw new BadRequestException("Ride cannot be cancelled from status " + ride.getStatus());
}
```

The state machine handles the "you can't cancel an `IN_PROGRESS` ride" rule — that's encoded once in `RideStatus`, not duplicated here.

The metadata payload uses a hand-rolled JSON literal:
```java
publishEvent(ride, reason != null ? "{\"reason\":\"" + reason.replace("\"", "\\\"") + "\"}" : null);
```
Hand-rolling JSON is normally a smell, but for a single optional field with a string-escape pass it's lighter than wiring up an `ObjectMapper` here. If metadata grows beyond one or two fields, switch to `ObjectMapper.writeValueAsString(Map.of(...))`.

#### `getDriverLocationForRide(rideId, riderId)`

The polling endpoint backing the rider's "where's my driver?" UI. Three guards before returning the location:
1. Caller must be the rider on this ride.
2. A driver must be assigned (`driverId != null`).
3. The ride must not be terminal — no point polling a completed ride.

Returns the `DriverLocation` from PostGIS (Phase 3's table). Polling — not WebSockets — keeps the MVP simple. WebSocket push is on the Phase 7 stretch list.

---

### 9. Controller and DTOs

#### `ride/controller/RideController.java`

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/api/v1/rides/request` | `ROLE_RIDER` | Body: `RideRequestDto`. Returns 201 with the new ride. |
| PUT | `/{id}/accept` | `ROLE_DRIVER` | Driver explicitly accepts. |
| PUT | `/{id}/en-route` | `ROLE_DRIVER` | Driver heading to pickup. |
| PUT | `/{id}/arrived` | `ROLE_DRIVER` | Driver at pickup. |
| PUT | `/{id}/start` | `ROLE_DRIVER` | Trip begins (rider in vehicle). |
| PUT | `/{id}/complete` | `ROLE_DRIVER` | Trip ends. |
| PUT | `/{id}/cancel` | any auth | Body: `CancelRideRequest`. Service-level check restricts to rider or assigned driver. |
| GET | `/{id}` | any auth | Service checks rider/driver ownership. |
| GET | `/{id}/driver-location` | `ROLE_RIDER` | Polling endpoint for tracking. |
| GET | `/history?page=&size=` | any auth | Paginated. Switches between rider/driver query based on the caller's role. |

**Why the role-based switch in `/history`:**

```java
boolean isDriver = authentication.getAuthorities().stream()
        .anyMatch(a -> a.getAuthority().equals("ROLE_DRIVER"));
Page<Ride> rides = isDriver
        ? rideService.getDriverHistory(userId, pageable)
        : rideService.getRiderHistory(userId, pageable);
```

Same path returns a different result set depending on who's calling. Riders see rides they requested; drivers see rides they were assigned to. Splitting into `/history/rider` and `/history/driver` would also work, but a single endpoint matches the "show me *my* history" mental model.

**`@AuthenticationPrincipal UUID userId`** — the JWT filter (Phase 2) puts the user's UUID in the security context as the principal. Spring's `@AuthenticationPrincipal` injects it directly into the controller method, so we never have to manually parse the token.

#### `ride/dto/RideRequestDto.java`

Validated request. Same `@DecimalMin/@DecimalMax` pattern as `LocationUpdateRequest` — coordinates bounded to [-90, 90] and [-180, 180]. Addresses optional, `@Size(max = 500)`.

#### `ride/dto/RideResponse.java`

The full ride view. `from(Ride)` factory unpacks the JTS `Point` to lat/lng:

```java
.pickupLatitude(ride.getPickupLocation().getY())
.pickupLongitude(ride.getPickupLocation().getX())
```

Client never sees the raw `Point` — they see two doubles. Note `Y=lat, X=lng` (the inverse of how humans say "lat, lng" but matches PostGIS internals).

#### `ride/dto/CancelRideRequest.java`

Just an optional `@Size(max = 500) String reason`. Could be just a plain `String` body, but a DTO leaves room for future fields (e.g., `cancelledByRole`, `categoryCode`) without an API break.

#### `ride/dto/DriverLocationResponse.java`

What the rider sees when polling for the driver's location:

```java
private double latitude;
private double longitude;
private Double heading;        // boxed — nullable
private Double speed;          // boxed — nullable
private LocalDateTime updatedAt;
```

Heading and speed are boxed because they're optional in the source `LocationUpdateRequest`. Latitude/longitude are primitives because we always have those.

---

## Design Decisions Summary

| Decision | Rationale |
|----------|-----------|
| State machine in the enum | Single source of truth; impossible to accidentally allow a bad transition from a service. |
| Per-state timestamps on `Ride` | Avoid joining `ride_events` for the common "show my recent rides" view. |
| `ride_events` as append-only audit log | Separate "current state" from "history of states". Append-only is easy to reason about and ship to cold storage. |
| Kafka for events (not synchronous insert) | Decouples write latency, lets future consumers subscribe without touching `RideService`. Trade-off: at-least-once and no DB/Kafka transactional consistency. |
| `JSONB` for event metadata | Free-form per-event detail without schema changes. Indexable inside JSONB if needed. |
| Radius expansion 3 → 5 → 10 km | Find the *nearest* driver, not just *a* driver. Hard cap prevents unboundedly old matches. |
| Redis primary, PostGIS fallback in matching | Sub-ms when healthy, still functional when Redis is down. Possible because the dual-write in Phase 3 keeps PostGIS in sync. |
| `findAvailableDriversWithinRadius` already filters by status (PostGIS path) | The Redis path returns unfiltered IDs, so the matcher does a per-candidate `isDriverAvailable()` check anyway. Both paths converge on the same correctness invariant. |
| Auto-assign on match (no explicit accept yet) | MVP. The `acceptRide` endpoint and the `skippedDrivers` parameter are in place so adding offer/decline/retry is purely additive. |
| `cancelRide` allows rider or driver | Real-world both parties need an exit. Service does the auth check rather than relying on `@PreAuthorize` because the rule is per-ride, not per-role. |
| `GeometryUtils.point()` | Centralize SRID + lng/lat ordering — easy place to get wrong silently. |
| BigDecimal for fares, primitives for coordinates | Money needs exactness; coordinates are physical measurements where double precision is fine. |
| `@AuthenticationPrincipal UUID userId` | JWT filter already injects the UUID. Controllers never touch the token directly. |
| Single `/history` endpoint that branches on role | "My history" is one mental model. The branch is a one-liner. |

---

## How Phase 5 Connects to Other Phases

- **Phase 2 (Auth):** `JwtAuthenticationFilter` puts the user's UUID as the principal, which Phase 5 consumes via `@AuthenticationPrincipal`. Role-based `@PreAuthorize` annotations rely on the authorities populated there.
- **Phase 3 (Location Tracking):** `MatchingService` queries the `DriverLocationRedisService` and falls back to `DriverLocationRepository` (both populated by Phase 3's location consumer). `getDriverLocationForRide` reads directly from the same Postgres table.
- **Phase 4 (Pricing):** `RideService.requestRide()` calls `FareCalculator.estimate()` and `SurgeService.incrementDemand()`. The fare and surge multiplier are frozen on the ride at request time.
- **Phase 6 (Payment & Ratings, upcoming):** `Payment` will be created when a ride transitions to `COMPLETED` — likely as a Kafka consumer on `ride-events` filtering for `status=COMPLETED`. `Rating` will reference the `rideId` and gate on the same terminal state.
- **Phase 7 (WebSocket, stretch):** Today the rider polls `/driver-location`. A WebSocket push would replace polling but use the same underlying `getDriverLocationForRide` logic.
