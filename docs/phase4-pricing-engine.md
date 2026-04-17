# Phase 4: Pricing Engine — Code Review

Commit: Phase 4: Pricing Engine - surge pricing strategies, fare calculator, estimate endpoint

---

## Overview

Phase 4 implements dynamic pricing using the Strategy Pattern. Two surge pricing strategies calculate a multiplier based on real-time conditions (demand/supply ratio or time of day). A fare calculator combines a base fare with the surge multiplier to produce an estimate. Redis stores short-lived demand/supply counters per geographic zone.

### Pricing flow

```
Rider: POST /api/v1/rides/estimate
       { pickupLat, pickupLng, dropoffLat, dropoffLng }
              │
              ▼
       PricingController
              │
              ▼
       FareCalculator
       ├── 1. Haversine distance (pickup → dropoff)
       ├── 2. Estimated duration (distance / avg speed)
       ├── 3. Resolve zone from pickup coordinates
       ├── 4. Fetch demand/supply metrics from Redis
       ├── 5. Calculate surge multiplier via strategy
       └── 6. baseFare × surgeMultiplier = estimatedTotal
              │
              ▼
       Return FareEstimate
```

### How surge metrics flow through the system

```
Rider requests ride ──▶ SurgeService.incrementDemand() ──▶ Redis INCR surge:zone:{z}:demand (60s TTL)
                                                              │
Driver sends location ──▶ SurgeService.updateSupply() ──▶ Redis SET surge:zone:{z}:supply (60s TTL)
                                                              │
Fare estimate request ──▶ SurgeService.getMetrics() ◀────────┘
                              │
                              ▼
                   DemandSupplyRatioStrategy.calculateMultiplier()
                              │
                              ▼
                   surge = demand / supply (capped at 3.0x)
```

---

## Files Created

### 1. Strategy Pattern

#### `pricing/strategy/SurgePricingStrategy.java`

The interface that all surge pricing strategies implement:

```java
public interface SurgePricingStrategy {
    double calculateMultiplier(String zone, SurgeMetrics metrics);
}
```

- Returns `1.0` for no surge, higher values for surge pricing.
- Accepts a `zone` identifier and `SurgeMetrics` (demand/supply counts). Different strategies can choose which inputs to use — demand/supply ratio uses metrics, time-based ignores them and uses the clock.
- Both implementations are annotated `@Component`, so Spring autowires them by bean name. The `FareCalculator` selects the active strategy via `@Qualifier`.

#### `pricing/strategy/DemandSupplyRatioStrategy.java`

The primary surge strategy. Calculates surge based on the ratio of ride requests to available drivers in a zone.

**Algorithm:**
```
if supply <= 0:
    if demand > 0: return maxMultiplier (3.0)
    else: return 1.0 (no demand, no surge)

ratio = demand / max(supply, minSupply)

if ratio <= 1.0: return 1.0  (supply meets demand)
else: return min(ratio, maxMultiplier)
```

**Configuration (from application.yml):**
- `pricing.surge.max-multiplier: 3.0` — hard cap. Even if demand is 10x supply, the multiplier never exceeds 3.0. This prevents runaway pricing.
- `pricing.surge.min-supply: 1` — floor for the supply denominator. Prevents division by very small numbers from producing extreme multipliers.

**Edge cases:**
- Zero supply with zero demand → 1.0 (no surge). Nobody's requesting rides and nobody's driving, so no surge is needed.
- Zero supply with nonzero demand → max multiplier. Drivers are needed, price goes up to attract them.
- Supply exceeds demand → 1.0. No surge when there are plenty of drivers.

#### `pricing/strategy/TimeBasedSurgeStrategy.java`

A secondary strategy that applies fixed multipliers based on time of day. Ignores the `metrics` parameter entirely — surge is purely clock-driven.

**Peak hours:**

| Time Window | Multiplier | Rationale |
|-------------|-----------|-----------|
| 7:00 AM – 9:00 AM | 1.5x | Morning commute |
| 5:00 PM – 7:00 PM | 1.5x | Evening commute |
| 10:00 PM – 4:00 AM | 1.3x | Late night (fewer drivers) |
| All other hours | 1.0x | Off-peak |

- Uses `LocalTime.now(ZoneId.systemDefault())` for the current hour. Server timezone is used, which is correct for a single-region deployment. Multi-region would need per-request timezone.
- The late-night window wraps around midnight (`hour >= 22 || hour < 4`).
- Peak hours and multipliers are constants, not configurable via properties. This is intentional for a first implementation — making them configurable adds complexity without much value until there's data to inform the values.

**When to use which strategy:**
The `FareCalculator` defaults to `DemandSupplyRatioStrategy` via `@Qualifier`. The `TimeBasedSurgeStrategy` is available if you want to swap it in (e.g., during early launch when there isn't enough traffic to produce meaningful demand/supply ratios). Future enhancement: compose both strategies (take the max).

---

### 2. Service Layer

#### `pricing/service/SurgeService.java`

Manages Redis-based demand and supply counters per geographic zone.

**Redis key scheme:**
- `surge:zone:{zoneId}:demand` — ride request count in the zone over the last 60 seconds.
- `surge:zone:{zoneId}:supply` — driver count in the zone.
- Both keys have a 60-second TTL, creating a rolling window. When the TTL expires, the counter resets to zero. This means surge naturally subsides when demand drops.

**Zone resolution — `resolveZone(longitude, latitude)`:**

Converts a lat/lng pair to a grid cell identifier:

```java
long gridLng = Math.round(Math.floor(longitude / 0.01));
long gridLat = Math.round(Math.floor(latitude / 0.01));
return gridLng + ":" + gridLat;
```

- `ZONE_GRID_SIZE = 0.01` degrees ≈ 1.1km at the equator. This creates a ~1km x ~1km grid.
- `Math.floor` ensures coordinates within the same cell always map to the same zone. Without floor, `40.741` and `40.749` would round to different values.
- Zone IDs look like: `-7398:4074` (for NYC area). Not human-readable, but that's fine — they're internal keys.
- This is a simplification. Real ride-sharing apps use H3 hexagonal grids or custom geofences. Grid cells distort near the poles (longitude degrees shrink). For a portfolio project in a single city, this is sufficient.

**`incrementDemand(lng, lat)`:**
- Called by the ride request flow (Phase 5). Uses `INCR` which is atomic — safe under concurrent ride requests.
- Sets TTL on every increment, so the counter auto-expires 60s after the last request.

**`updateSupply(zone, count)`:**
- Called by the location consumer to update how many drivers are active in a zone.
- Uses `SET` instead of `INCR` because supply is a snapshot count, not a running counter.

**`getMetrics(zone)`:**
- Reads both counters and returns a `SurgeMetrics` object.
- `getLongValue()` handles the type complexity of Redis values — they can come back as `Integer`, `Long`, `String`, or `null` depending on the serializer and Redis state. The method handles all cases defensively.

#### `pricing/service/FareCalculator.java`

The core pricing logic. Combines distance, duration, and surge into a fare estimate.

**Formula:**
```
baseFare = BASE_RATE + (PER_KM × distanceKm) + (PER_MINUTE × durationMinutes)
estimatedTotal = baseFare × surgeMultiplier
estimatedTotal = max(estimatedTotal, MINIMUM_FARE)
```

**Configuration (from application.yml):**
- `pricing.base-rate: 2.50` — flat fee charged for every ride
- `pricing.per-km: 1.50` — per-kilometer charge
- `pricing.per-minute: 0.25` — per-minute charge (accounts for traffic/slow routes)
- `pricing.minimum-fare: 5.00` — floor price, even for very short rides

**Distance calculation — Haversine formula:**

```java
private double haversineDistance(double lat1, double lng1, double lat2, double lng2)
```

Calculates the great-circle distance between two points on Earth's surface. This is a straight-line distance ("as the crow flies"), not road distance. For an MVP, this is acceptable — road distance would require a routing API (Google Directions, OSRM, etc.).

The Haversine formula:
1. Convert lat/lng differences to radians
2. Calculate `a = sin²(Δlat/2) + cos(lat1) × cos(lat2) × sin²(Δlng/2)`
3. `c = 2 × atan2(√a, √(1-a))`
4. `distance = R × c` where `R = 6371 km` (Earth's mean radius)

Accuracy: within ~0.5% of actual great-circle distance. The main source of error is that roads aren't straight — actual road distance is typically 1.2-1.4x the straight-line distance.

**Duration estimation:**
```java
double durationMinutes = (distanceKm / AVERAGE_SPEED_KMH) * 60.0;
```
- Assumes 30 km/h average city driving speed. Simple but reasonable for urban areas with traffic.
- In production, this would come from a routing API that accounts for real-time traffic.

**Strategy injection:**
```java
public FareCalculator(SurgeService surgeService,
                      @Qualifier("demandSupplyRatioStrategy") SurgePricingStrategy surgePricingStrategy)
```
- `@Qualifier("demandSupplyRatioStrategy")` selects the demand/supply strategy. Spring's default bean naming convention converts `DemandSupplyRatioStrategy` → `demandSupplyRatioStrategy`.
- To switch to time-based pricing, change the qualifier to `"timeBasedSurgeStrategy"`. No other code changes needed — that's the point of the Strategy Pattern.

**BigDecimal for money:**
- All fare amounts use `BigDecimal` with `HALF_UP` rounding to 2 decimal places. Using `double` for money is a classic bug — `0.1 + 0.2 ≠ 0.3` in floating point. `BigDecimal` gives exact decimal arithmetic.

---

### 3. DTO Layer

#### `pricing/dto/SurgeMetrics.java`

Internal DTO passed from `SurgeService` to the pricing strategies.

| Field | Type | Notes |
|-------|------|-------|
| demandCount | long | Number of ride requests in the zone in the last 60s |
| supplyCount | long | Number of active drivers in the zone |
| timestamp | Instant | When these metrics were read |

The `timestamp` is included for observability/logging, not used in the multiplier calculation.

#### `pricing/dto/FareEstimateRequest.java`

REST request body for the estimate endpoint. Four coordinate fields with the same validation pattern as `LocationUpdateRequest`:

| Field | Type | Validation |
|-------|------|-----------|
| pickupLatitude | Double | `@NotNull`, -90 to 90 |
| pickupLongitude | Double | `@NotNull`, -180 to 180 |
| dropoffLatitude | Double | `@NotNull`, -90 to 90 |
| dropoffLongitude | Double | `@NotNull`, -180 to 180 |

#### `pricing/dto/FareEstimate.java`

Response DTO returned by the estimate endpoint.

| Field | Type | Notes |
|-------|------|-------|
| baseFare | BigDecimal | Before surge is applied |
| surgeMultiplier | double | 1.0 = no surge |
| estimatedTotal | BigDecimal | baseFare × surgeMultiplier (or minimum fare) |
| distanceKm | double | Straight-line distance |
| durationMinutes | double | Estimated trip duration |
| zone | String | The zone used for surge calculation (useful for debugging) |

The `zone` field is exposed intentionally — it helps with debugging and understanding why a particular surge multiplier was applied. In production you might omit it.

---

### 4. Controller Layer

#### `pricing/controller/PricingController.java`

`POST /api/v1/rides/estimate` — returns a fare estimate for a trip.

```java
@RestController
@RequestMapping("/api/v1/rides")
@RequiredArgsConstructor
public class PricingController { ... }
```

- **No `@PreAuthorize` annotation** — the estimate endpoint is accessible to any authenticated user (rider or driver). Riders need it to see pricing before requesting a ride. Drivers might use it for informational purposes.
- **Mapped under `/api/v1/rides`** (not `/api/v1/pricing`) because from the rider's perspective, fare estimation is part of the ride flow. The URL matches the feature checklist spec.
- Delegates entirely to `FareCalculator` — the controller has no business logic.

---

### 5. Configuration Changes

#### `application.yml`

Added the `pricing` section:

```yaml
pricing:
  base-rate: 2.50
  per-km: 1.50
  per-minute: 0.25
  minimum-fare: 5.00
  surge:
    max-multiplier: 3.0
    min-supply: 1
```

All values have defaults in the `@Value` annotations, so the application works even without these properties. Having them in `application.yml` makes them visible and easy to tune.

---

## Design Decisions Summary

| Decision | Rationale |
|----------|-----------|
| Strategy Pattern for surge pricing | Swap algorithms without changing the caller. DemandSupplyRatio for real-time, TimeBased for early launch. Future: compose strategies (take max). |
| `@Qualifier` to select active strategy | Simple, compile-time selection. Alternative: `@Primary` annotation, or a `SurgeStrategySelector` service that picks at runtime. `@Qualifier` is explicit and sufficient. |
| Grid-based zone resolution (0.01°) | ~1km cells, trivial to compute, no external dependencies. Good enough for MVP. Real systems use H3 hexagons. |
| 60-second TTL on surge counters | Creates a rolling demand window. Surge fades naturally when requests stop. No scheduled cleanup needed — Redis handles it. |
| `INCR` for demand, `SET` for supply | Demand is additive (each request adds 1). Supply is a snapshot (X drivers in zone right now). Different semantics, different Redis operations. |
| Haversine for distance | No external routing API dependency. Straight-line underestimates by ~20-40%, but consistent and fast. Can plug in a routing API later. |
| BigDecimal for money | Floating-point arithmetic is inexact with decimals. $2.50 + $1.50 must equal $4.00, not $3.9999999. |
| Minimum fare of $5.00 | Very short rides (e.g., 200m) would calculate to $2.80 — below operational cost. Floor prevents unprofitable rides. |
| Default to DemandSupplyRatio strategy | It responds to actual conditions. TimeBased is a fallback for when traffic volume is too low to produce meaningful ratios. |

---

## How Phase 4 Connects to Other Phases

- **Phase 3 (Location Tracking):** The location consumer will call `SurgeService.updateSupply()` to update the driver count per zone as drivers send location updates.
- **Phase 5 (Ride Management):** `RideService.requestRide()` will call `SurgeService.incrementDemand()` and `FareCalculator.estimate()` to set the ride's estimated fare and surge multiplier before triggering matching.
