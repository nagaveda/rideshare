# Phase 3: Location Tracking — Code Review

Commit: `6381d4c` — Phase 3: Location Tracking - Kafka location pipeline, PostGIS + Redis GEO dual-write

---

## Overview

Phase 3 implements the real-time driver location pipeline. When a driver sends a GPS update, it flows through Kafka into two storage backends: PostGIS (durable, spatial queries) and Redis GEO (fast, in-memory proximity lookups). This dual-write design gives us sub-millisecond matching queries via Redis while keeping PostGIS as a durable fallback.

### Data flow

```
Driver app: POST /api/v1/drivers/location
       │
       ▼
LocationController (validates driver is AVAILABLE/BUSY)
       │
       ▼
LocationProducer → Kafka topic "driver-location-updates" (keyed by driverId)
       │
       ▼
LocationConsumer (Kafka listener)
       ├──▶ PostGIS: upsert into driver_locations table
       └──▶ Redis GEO: GEOADD to drivers:active + set 30s TTL key
```

---

## Files Created

### 1. Config Layer

#### `config/KafkaProducerConfig.java`

Defines the Kafka producer factory and `KafkaTemplate` bean.

- **ProducerFactory<String, Object>** — uses `StringSerializer` for keys (driverId) and `JsonSerializer` for values (LocationEvent). The `Object` value type allows reuse for future Kafka topics (e.g., ride-events in Phase 5).
- **KafkaTemplate** — the Spring abstraction we inject into producer services to call `send(topic, key, value)`.
- Bootstrap server address is read from `spring.kafka.bootstrap-servers` in application.yml.

#### `config/KafkaConsumerConfig.java`

Defines the Kafka consumer factory and listener container factory.

- **ConsumerFactory<String, Object>** — uses `StringDeserializer` for keys, `JsonDeserializer` for values.
- **`AUTO_OFFSET_RESET_CONFIG: earliest`** — when a new consumer group starts (or has no committed offset), it reads from the beginning of the topic. This ensures we don't miss location events on first startup.
- **`TRUSTED_PACKAGES: com.rideshare.*`** — required by Spring's `JsonDeserializer`. Without this, deserialization of `LocationEvent` fails because Jackson refuses to instantiate classes from untrusted packages. This is a security measure to prevent arbitrary class instantiation.
- **ConcurrentKafkaListenerContainerFactory** — wraps the consumer factory and is used by `@KafkaListener` annotations. "Concurrent" means it can run multiple consumer threads, though we default to 1 partition = 1 thread for now.

#### `config/RedisConfig.java`

Defines the `RedisTemplate<String, Object>` bean.

- **Key serializer: `StringRedisSerializer`** — Redis keys are plain strings (`drivers:active`, `driver:active:{uuid}`).
- **Value serializer: `GenericJackson2JsonRedisSerializer`** — values are stored as JSON. We use the "generic" variant (includes `@class` type info in the JSON) so Spring can deserialize back to the correct Java type without us specifying it explicitly.
- **Hash serializers** — same pattern for hash keys/values, used if we store Redis hashes later.
- The `RedisConnectionFactory` is auto-configured by Spring Boot from `spring.data.redis.host` and `spring.data.redis.port` in application.yml.

---

### 2. DTO Layer

#### `location/dto/LocationUpdateRequest.java`

The REST request body that drivers send. Validated with Jakarta Bean Validation:

| Field | Type | Validation | Required |
|-------|------|-----------|----------|
| latitude | Double | `@NotNull`, `@DecimalMin(-90)`, `@DecimalMax(90)` | Yes |
| longitude | Double | `@NotNull`, `@DecimalMin(-180)`, `@DecimalMax(180)` | Yes |
| heading | Double | `@Min(0)` | No (defaults to 0.0) |
| speed | Double | `@Min(0)` | No (defaults to 0.0) |

- `heading` is compass direction in degrees (0-360). Optional because not all GPS chips report it.
- `speed` is in km/h. Optional for the same reason.
- Uses `Double` (boxed) rather than `double` (primitive) so that `null` checks work for optional fields.

#### `location/dto/LocationEvent.java`

The Kafka message payload. This is what gets serialized to JSON and published to the topic.

| Field | Type | Notes |
|-------|------|-------|
| driverId | UUID | Kafka message key is also this value (ensures per-driver ordering) |
| latitude | double | Primitive — always has a value by the time we build the event |
| longitude | double | |
| heading | double | Defaults to 0.0 if not provided in request |
| speed | double | Defaults to 0.0 if not provided in request |
| timestamp | Instant | Server-side timestamp set in the controller, not client-provided |

Why a separate class from `LocationUpdateRequest`? The request DTO is the external contract (what the client sends). The event is the internal contract (what flows through Kafka). They decouple: the request has validation annotations and nullable fields, the event has primitives and a server-set timestamp. If the API changes, the Kafka contract doesn't have to.

---

### 3. Kafka Layer

#### `location/kafka/LocationProducer.java`

Publishes `LocationEvent` to the `driver-location-updates` topic.

```java
kafkaTemplate.send(TOPIC, event.getDriverId().toString(), event);
```

- **Key = driverId**: Kafka guarantees ordering within a partition. By keying on driverId, all location updates for the same driver go to the same partition and are processed in order. Without this, a driver's location could be processed out of order and the "latest" position in PostGIS could actually be stale.
- **Topic name is a constant**, not configurable via properties. This is intentional — the topic name is a contract between producer and consumer within the same application.
- Logging at `DEBUG` level — location updates happen every 3-5 seconds per driver, so `INFO` would be too noisy.

#### `location/kafka/LocationConsumer.java`

Listens to the `driver-location-updates` topic and writes to both storage backends.

```java
@KafkaListener(topics = "driver-location-updates", groupId = "rideshare-group")
@Transactional
public void consume(LocationEvent event) { ... }
```

Key design decisions:

1. **`@Transactional`** — the PostGIS upsert runs inside a database transaction. If it fails, the Kafka offset is not committed (Spring Kafka's default behavior with `enable.auto.commit=false`), so the message will be retried.

2. **PostGIS writes first, Redis second** — PostGIS is the durable store. If the PostGIS write succeeds but Redis fails, we still have the correct driver position persisted. The Redis GEO set will self-heal on the next location update (3-5 seconds later).

3. **Redis failure is caught and logged as a warning** — the `try/catch` around the Redis call means a Redis outage doesn't block the location pipeline. PostGIS continues to receive updates. This is the foundation for the Redis GEO fallback discussed in the matching algorithm (Phase 5).

4. **Consumer group = `rideshare-group`** — matches the application.yml config. In a multi-instance deployment, all instances share this group and Kafka partitions are distributed across them automatically.

---

### 4. Entity & Repository Layer

#### `location/model/DriverLocation.java`

JPA entity mapped to the `driver_locations` table (created in Flyway migration V3).

```java
@Column(columnDefinition = "geometry(Point,4326)", nullable = false)
private Point location;
```

- **`Point`** is from `org.locationtech.jts.geom.Point` (JTS Topology Suite), the geometry library that Hibernate Spatial uses under the hood.
- **`columnDefinition = "geometry(Point,4326)"`** tells Hibernate this column is a PostGIS geometry of type Point in SRID 4326 (WGS 84 — the standard GPS coordinate system).
- **Does not extend `BaseEntity`** — the `driver_locations` table has its own schema (`updated_at` but no `created_at`). The table stores one row per driver (upserted), so `createdAt` would be meaningless.
- **`driverId` is unique** — enforced both in the DB (unique constraint) and JPA (`unique = true`). One row per driver.

#### `location/repository/DriverLocationRepository.java`

Two native SQL queries using PostGIS functions:

**1. `upsertLocation()`** — Insert or update the driver's current position.

```sql
INSERT INTO driver_locations (id, driver_id, location, heading, speed, updated_at)
VALUES (gen_random_uuid(), :driverId, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), :heading, :speed, NOW())
ON CONFLICT (driver_id)
DO UPDATE SET location = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326),
              heading = :heading, speed = :speed, updated_at = NOW()
```

- `ST_MakePoint(lng, lat)` creates a PostGIS point. Note: **longitude comes first** — PostGIS follows the (x, y) convention where x = longitude, y = latitude.
- `ST_SetSRID(..., 4326)` tags the point with the WGS 84 coordinate reference system. Without this, spatial queries that use `::geography` casting would fail.
- `ON CONFLICT (driver_id)` leverages the unique constraint to make this an upsert. First call inserts, subsequent calls update.
- `@Modifying` tells Spring this is a write query (not a SELECT), so it uses `executeUpdate()` internally.

**2. `findAvailableDriversWithinRadius()`** — Find nearby available drivers (used by MatchingService in Phase 5).

```sql
SELECT dl.* FROM driver_locations dl
JOIN driver_profiles dp ON dp.user_id = dl.driver_id
WHERE ST_DWithin(dl.location::geography, ST_MakePoint(:lng, :lat)::geography, :radiusMeters)
  AND dp.status = 'AVAILABLE'
ORDER BY ST_Distance(dl.location::geography, ST_MakePoint(:lng, :lat)::geography)
```

- **`::geography` cast** — critical. Without it, `ST_DWithin` uses planar geometry (degrees), which is meaningless for distance on Earth's surface. The `::geography` cast tells PostGIS to calculate distances in meters on the Earth's spheroid.
- **`ST_DWithin` vs `ST_Distance < N`** — `ST_DWithin` can use the GiST spatial index (created in migration V3). A `WHERE ST_Distance(...) < N` clause cannot use the index and does a full table scan.
- **JOIN to `driver_profiles`** — filters by `status = 'AVAILABLE'`. This is the PostGIS-only fallback path: when Redis is down, the MatchingService calls this method directly instead of the Redis pre-filter + PostGIS confirm two-step.
- Results are ordered by distance ascending (closest driver first).

---

### 5. Redis GEO Service

#### `location/service/DriverLocationRedisService.java`

Wraps Redis GEO commands behind a clean service interface.

**Constants:**
- `GEO_KEY = "drivers:active"` — the sorted set that holds all active driver positions.
- `ACTIVE_KEY_PREFIX = "driver:active:"` — per-driver TTL keys.
- `ACTIVE_TTL = 30 seconds` — if a driver stops sending updates for 30s, they're considered inactive.

**`updateDriverLocation(driverId, lng, lat)`**

```java
redisTemplate.opsForGeo().add(GEO_KEY, new Point(longitude, latitude), driverId.toString());
redisTemplate.opsForValue().set(ACTIVE_KEY_PREFIX + driverId, "1", ACTIVE_TTL);
```

- `GEOADD drivers:active lng lat driverId` — adds/updates the driver's position in the GEO sorted set. Redis GEO uses a sorted set internally, where the score is a 52-bit geohash of the coordinates.
- The companion key `driver:active:{uuid}` with 30s TTL acts as a heartbeat. When it expires, a cleanup mechanism (to be implemented — could be a scheduled task or Redis keyspace notification) removes the driver from the GEO set. This prevents stale drivers from appearing in proximity searches.

**`findDriversWithinRadius(lng, lat, radiusKm)`**

```java
redisTemplate.opsForGeo().radius(GEO_KEY,
    new Circle(new Point(longitude, latitude), new Distance(radiusKm, KILOMETERS)),
    GeoRadiusCommandArgs.newGeoRadiusArgs().sortAscending()
);
```

- Maps to `GEORADIUS drivers:active lng lat N km ASC`. Returns driver IDs sorted by distance (closest first).
- Returns `List<UUID>` — the caller (MatchingService in Phase 5) uses these IDs to query PostGIS for status confirmation.

**`removeDriver(driverId)`**

- `ZREM drivers:active driverId` — removes from the GEO set.
- Deletes the TTL key.
- Called when a driver explicitly goes OFFLINE via the status endpoint.

**`isAvailable()`**

- Pings Redis to check connectivity. Returns `false` on any exception.
- Used by MatchingService (Phase 5) to decide whether to use the Redis fast path or fall back to PostGIS-only queries.

---

### 6. Controller Layer

#### `location/controller/LocationController.java`

`POST /api/v1/drivers/location` — accepts GPS updates from the driver app.

**Security:**
- `@PreAuthorize("hasRole('DRIVER')")` — only users with the DRIVER role can hit this endpoint. The role is extracted from the JWT by `JwtAuthenticationFilter`.
- `@AuthenticationPrincipal UUID userId` — the userId is set as the principal in the security context by the JWT filter.

**Validation flow:**
1. `@Valid @RequestBody` triggers Jakarta Bean Validation on `LocationUpdateRequest` (lat/lng bounds, non-null checks).
2. Fetches the `DriverProfile` from the database to check the driver's current status.
3. Rejects OFFLINE drivers with a `BadRequestException`. Only AVAILABLE and BUSY drivers should be sending location updates. An OFFLINE driver sending GPS data is either a bug or a client that hasn't synced status.
4. Builds a `LocationEvent` with server-side timestamp (`Instant.now()`), defaulting heading/speed to 0.0 if not provided.
5. Publishes to Kafka via `LocationProducer`.
6. Returns `200 OK` with an empty body. The client doesn't need confirmation data — location updates are fire-and-forget from the driver's perspective.

---

## Doc Changes

### `docs/architecture.md`

Updated the Matching Algorithm section (step 3a) to document the Redis GEO fallback behavior:
- If Redis is unavailable, MatchingService falls back to PostGIS-only queries using `ST_DWithin` + `ST_Distance`.
- Added explanatory note about the dual-write design ensuring PostGIS always has current positions.

### `docs/feature-checklist.md`

Added "Redis GEO fallback" as an explicit line item under Phase 5.2 (Matching Service).

### `docs/progress.md`

Checked off all 9 Phase 3 items.

---

## Design Decisions Summary

| Decision | Rationale |
|----------|-----------|
| Kafka between controller and storage | Decouples the API response from the write path. Driver gets 200 OK immediately. Writes happen async. Also gives us a replayable event stream. |
| Key by driverId | Guarantees per-driver ordering in Kafka. Prevents stale-location overwrites. |
| PostGIS upsert (one row per driver) | Current-location table, not a history table. Keeps the table small for fast spatial queries. Historical tracking will use `ride_route_points` during active rides. |
| Redis GEO as fast pre-filter | Sub-millisecond proximity queries vs ~5-10ms in PostGIS. At scale, this matters for matching latency. |
| Redis failure is non-fatal | PostGIS is the source of truth. Redis is a performance optimization. Losing it degrades speed, not correctness. |
| 30s TTL for active drivers | If a driver crashes or loses connectivity, they're automatically removed from the active set after 30s instead of showing up as phantom matches. |
| `::geography` cast in PostGIS queries | Without it, distance calculations use planar degrees instead of meters on Earth's surface. A degree of longitude is ~111km at the equator but ~0km at the poles. |
| Server-side timestamp | Client clocks can't be trusted. The timestamp reflects when the server received the update, not when the GPS chip reported it. |
