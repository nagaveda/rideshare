# Architecture

## System Overview

```
┌──────────────┐       ┌──────────────┐
│  Rider App   │       │  Driver App  │
│  (Frontend)  │       │  (Frontend)  │
└──────┬───────┘       └──────┬───────┘
       │ REST                  │ REST (location POST every 3-5s)
       │                       │
┌──────▼───────────────────────▼───────┐
│          Spring Boot API             │
│          /api/v1/...                 │
│  ┌─────────────────────────────────┐ │
│  │  Controllers → Services → Repos │ │
│  └─────────────────────────────────┘ │
└──┬──────────┬──────────┬────────────┘
   │          │          │
   ▼          ▼          ▼
┌──────┐  ┌───────┐  ┌────────────────┐
│Kafka │  │ Redis │  │ PostgreSQL     │
│      │  │ GEO   │  │ + PostGIS      │
└──┬───┘  └───────┘  └────────────────┘
   │
   ▼
┌──────────────────┐
│  Kafka Consumers │
│  (in same app)   │
│  - Location      │
│  - Ride Events   │
└──────────────────┘
```

## Domain Breakdown

| Domain | Responsibility |
|--------|---------------|
| **User/Auth** | Registration, login, JWT, profiles |
| **Driver** | Driver profile, status (online/offline), vehicle info |
| **Location** | GPS ingestion via Kafka, Redis GEO + PostGIS writes |
| **Matching** | Find nearest available driver, assignment with retry/radius expansion |
| **Pricing** | Base fare calculation, surge pricing via Strategy Pattern |
| **Ride** | Ride lifecycle, state machine, ride history |
| **Payment** | Fare finalization, payment records |
| **Rating** | Post-ride ratings for riders and drivers |

## Package Structure

```
com.rideshare
├── RideShareApplication.java
├── config/
│   ├── SecurityConfig.java         # Spring Security filter chain
│   ├── JwtAuthenticationFilter.java
│   ├── KafkaProducerConfig.java
│   ├── KafkaConsumerConfig.java
│   ├── RedisConfig.java
│   ├── CorsConfig.java
│   └── SwaggerConfig.java
├── common/
│   ├── dto/
│   │   └── ApiResponse.java        # Standard response wrapper
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java
│   │   ├── ResourceNotFoundException.java
│   │   ├── BadRequestException.java
│   │   └── UnauthorizedException.java
│   ├── entity/
│   │   └── BaseEntity.java         # id, createdAt, updatedAt
│   └── util/
│       └── GeometryUtils.java      # Haversine + JTS geometry helpers
├── user/
│   ├── controller/
│   │   ├── AuthController.java
│   │   └── RiderController.java
│   ├── service/
│   │   ├── AuthService.java
│   │   ├── JwtService.java
│   │   └── UserService.java
│   ├── repository/UserRepository.java
│   ├── model/
│   │   ├── User.java
│   │   └── Role.java               # Enum: RIDER, DRIVER
│   └── dto/
│       ├── RegisterRequest.java
│       ├── LoginRequest.java
│       ├── AuthResponse.java
│       ├── UpdateProfileRequest.java
│       └── UserProfileResponse.java
├── driver/
│   ├── controller/DriverController.java
│   ├── service/DriverService.java
│   ├── repository/DriverProfileRepository.java
│   ├── model/
│   │   ├── DriverProfile.java
│   │   └── DriverStatus.java       # Enum: OFFLINE, AVAILABLE, BUSY
│   └── dto/
│       ├── DriverProfileResponse.java
│       ├── UpdateDriverProfileRequest.java
│       └── UpdateDriverStatusRequest.java
├── location/
│   ├── controller/LocationController.java
│   ├── service/DriverLocationRedisService.java
│   ├── kafka/
│   │   ├── LocationProducer.java
│   │   └── LocationConsumer.java
│   ├── repository/DriverLocationRepository.java
│   ├── model/DriverLocation.java
│   └── dto/
│       ├── LocationUpdateRequest.java
│       └── LocationEvent.java      # Kafka payload
├── matching/
│   └── service/MatchingService.java
├── pricing/
│   ├── controller/PricingController.java
│   ├── service/
│   │   ├── FareCalculator.java
│   │   └── SurgeService.java       # Manages Redis demand/supply counters
│   ├── strategy/
│   │   ├── SurgePricingStrategy.java      # Interface
│   │   ├── DemandSupplyRatioStrategy.java
│   │   └── TimeBasedSurgeStrategy.java
│   └── dto/
│       ├── FareEstimate.java
│       ├── FareEstimateRequest.java
│       └── SurgeMetrics.java
├── ride/
│   ├── controller/RideController.java
│   ├── service/RideService.java
│   ├── kafka/
│   │   ├── RideEventProducer.java
│   │   └── RideEventConsumer.java
│   ├── repository/
│   │   ├── RideRepository.java
│   │   ├── RideRoutePointRepository.java
│   │   └── RideEventRepository.java
│   ├── model/
│   │   ├── Ride.java
│   │   ├── RideStatus.java         # Enum + transition logic
│   │   ├── RideRoutePoint.java
│   │   └── RideEvent.java          # Audit log entity
│   └── dto/
│       ├── RideRequestDto.java
│       ├── RideResponse.java
│       ├── CancelRideRequest.java
│       ├── DriverLocationResponse.java
│       └── RideEventPayload.java   # Kafka payload
├── payment/
│   ├── controller/PaymentController.java
│   ├── service/PaymentService.java
│   ├── repository/PaymentRepository.java
│   ├── model/
│   │   ├── Payment.java
│   │   ├── PaymentMethod.java      # Enum: CASH, CARD
│   │   └── PaymentStatus.java      # Enum: PENDING, COMPLETED, FAILED, REFUNDED
│   └── dto/PaymentResponse.java
└── rating/
    ├── controller/RatingController.java
    ├── service/RatingService.java
    ├── repository/RatingRepository.java
    ├── model/Rating.java
    └── dto/
        ├── RatingRequest.java
        └── RatingResponse.java
```

## Database Schema

### users
| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| email | VARCHAR(255) | Unique |
| password_hash | VARCHAR(255) | BCrypt |
| first_name | VARCHAR(100) | |
| last_name | VARCHAR(100) | |
| phone | VARCHAR(20) | |
| role | VARCHAR(10) | RIDER or DRIVER |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

### driver_profiles
| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| user_id | UUID | FK → users, unique |
| license_number | VARCHAR(50) | |
| vehicle_make | VARCHAR(50) | |
| vehicle_model | VARCHAR(50) | |
| vehicle_year | INT | |
| vehicle_color | VARCHAR(30) | |
| license_plate | VARCHAR(20) | |
| status | VARCHAR(20) | OFFLINE, AVAILABLE, BUSY |
| rating | DECIMAL(3,2) | Running average |
| total_rides | INT | |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

### driver_locations
| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| driver_id | UUID | FK → users, unique (one row per driver, upserted) |
| location | GEOMETRY(Point, 4326) | PostGIS point (lng, lat) |
| heading | DOUBLE | Direction in degrees |
| speed | DOUBLE | km/h |
| updated_at | TIMESTAMP | |

**Index:** GiST spatial index on `location` column.

> One row per driver, upserted on each location update. This is the "current location" table.
> Historical tracking is done via `ride_route_points` during active rides.

### rides
| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| rider_id | UUID | FK → users |
| driver_id | UUID | FK → users, nullable until matched |
| status | VARCHAR(20) | See ride state machine below |
| pickup_location | GEOMETRY(Point, 4326) | |
| dropoff_location | GEOMETRY(Point, 4326) | |
| pickup_address | VARCHAR(500) | |
| dropoff_address | VARCHAR(500) | |
| estimated_fare | DECIMAL(10,2) | |
| actual_fare | DECIMAL(10,2) | Null until completed |
| surge_multiplier | DECIMAL(4,2) | |
| distance_km | DECIMAL(8,2) | |
| duration_minutes | DECIMAL(8,2) | |
| requested_at | TIMESTAMP | |
| accepted_at | TIMESTAMP | |
| started_at | TIMESTAMP | |
| completed_at | TIMESTAMP | |
| cancelled_at | TIMESTAMP | |
| cancellation_reason | VARCHAR(500) | |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

### ride_route_points
| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| ride_id | UUID | FK → rides |
| location | GEOMETRY(Point, 4326) | |
| recorded_at | TIMESTAMP | |

**Index:** btree on `ride_id`, GiST on `location`.

### payments
| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| ride_id | UUID | FK → rides, unique |
| rider_id | UUID | FK → users |
| driver_id | UUID | FK → users |
| amount | DECIMAL(10,2) | Total charged |
| base_fare | DECIMAL(10,2) | |
| surge_amount | DECIMAL(10,2) | |
| payment_method | VARCHAR(20) | CASH, CARD (record only) |
| status | VARCHAR(20) | PENDING, COMPLETED, FAILED, REFUNDED |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

### ratings
| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| ride_id | UUID | FK → rides |
| rater_id | UUID | FK → users (who gave the rating) |
| ratee_id | UUID | FK → users (who received the rating) |
| score | INT | 1-5 |
| comment | VARCHAR(500) | Optional |
| created_at | TIMESTAMP | |

**Constraint:** Unique on (ride_id, rater_id) — one rating per person per ride.

### ride_events (audit log)
| Column | Type | Notes |
|--------|------|-------|
| id | UUID | PK |
| ride_id | UUID | FK → rides |
| rider_id | UUID | FK → users |
| driver_id | UUID | FK → users, nullable |
| status | VARCHAR(20) | The new ride status |
| metadata | JSONB | Flexible — cancellation reason, fare info, etc. |
| created_at | TIMESTAMP | |

Populated by the Kafka consumer listening to the `ride-events` topic. Provides a full audit trail of every ride state change.

## Ride State Machine

```
REQUESTED
  ├── → DRIVER_ASSIGNED  (driver accepts)
  └── → CANCELLED        (rider cancels or no drivers found)

DRIVER_ASSIGNED
  ├── → DRIVER_EN_ROUTE  (driver starts heading to pickup)
  └── → CANCELLED        (rider or driver cancels)

DRIVER_EN_ROUTE
  ├── → ARRIVED          (driver arrives at pickup)
  └── → CANCELLED        (rider or driver cancels)

ARRIVED
  ├── → IN_PROGRESS      (rider picked up, ride starts)
  └── → CANCELLED        (rider no-show or cancels)

IN_PROGRESS
  └── → COMPLETED        (driver ends ride at destination)

COMPLETED → terminal state (triggers payment creation + allows rating)
CANCELLED → terminal state
```

## Kafka Topics

### `driver-location-updates`
- **Producer:** LocationController (on driver location POST)
- **Consumer:** LocationConsumer → writes to Redis GEO + PostGIS
- **Key:** driverId (ensures ordering per driver)
- **Payload:** `{driverId, latitude, longitude, heading, speed, timestamp}`

### `ride-events`
- **Producer:** RideService (on any ride state change)
- **Consumer:** RideEventConsumer → writes audit log to `ride_events` table. Future: notifications, surge metrics
- **Key:** rideId
- **Payload:** `{rideId, riderId, driverId, status, timestamp, metadata}`

## Redis Usage

### Driver Locations (GEO)
- **Key:** `drivers:active`
- **Commands:** `GEOADD`, `GEOSEARCH`, `ZREM`
- **Purpose:** Fast proximity queries for matching
- **Eviction:** Companion key `driver:{id}:active` with 30s TTL. When TTL expires, a cleanup process removes the driver from the GEO set.

### Surge Metrics
- **Keys:** `surge:zone:{zoneId}:demand`, `surge:zone:{zoneId}:supply`
- **Commands:** `INCR`, `DECR`, `GET`
- **TTL:** Short TTL (e.g., 60s) — counters auto-reset, giving a rolling demand window
- **Purpose:** Feed into DemandSupplyRatioStrategy

### Active Ride Cache
- **Key:** `ride:active:{rideId}`
- **Purpose:** Quick ride status lookups without hitting Postgres
- **TTL:** Cleared on ride completion/cancellation

## Strategy Pattern: Surge Pricing

```
                    ┌─────────────────────────┐
                    │  SurgePricingStrategy    │
                    │  (interface)             │
                    │                          │
                    │  + calculateMultiplier(  │
                    │      zone, metrics)      │
                    │    → double              │ß
                    └────────┬────────────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼                              ▼
┌─────────────────────────┐   ┌─────────────────────────┐
│ DemandSupplyRatioStrategy│   │ TimeBasedSurgeStrategy  │
│                          │   │                          │
│ Reads demand/supply from │   │ Returns fixed multiplier │
│ Redis counters per zone  │   │ based on time of day     │
│                          │   │ (peak hours config)      │
│ surge = demand / supply  │   │                          │
│ capped at max multiplier │   │ e.g., 7-9am = 1.5x      │
└──────────────────────────┘   └──────────────────────────┘

FareCalculator:
  baseFare = BASE_RATE + (PER_KM × distance) + (PER_MIN × duration)
  surgeMultiplier = activeSurgePricingStrategy.calculateMultiplier(zone, metrics)
  finalFare = baseFare × surgeMultiplier
```

The active strategy is selected at runtime (e.g., via config or contextual logic). Multiple strategies could also be composed (e.g., take the max of demand-based and time-based).

## Matching Algorithm

```
1. Rider requests ride at (lat, lng)
2. Set searchRadius = 3km, attempt = 0, maxAttempts = 3

3. LOOP:
   a. Try Redis GEOSEARCH within searchRadius → candidate driverIds
      - If Redis is unavailable (connection error/timeout):
        fall back to PostGIS-only query (ST_DWithin + status = AVAILABLE)
        Skip step (b) since PostGIS already filters and orders
   b. For candidates from Redis, query PostGIS to confirm:
      - status = AVAILABLE
      - precise distance ordering
   c. If no candidates:
      - Expand radius (3km → 5km → 10km)
      - If maxRadius exceeded → return "no drivers available"
      - Continue loop
   d. Pick closest available driver
   e. Send ride request to driver, set 30s timeout
   f. If driver accepts → assign, done
   g. If driver declines/timeout:
      - Mark driver as skipped for this ride
      - attempt++
      - If attempt >= maxAttempts at current radius → expand radius
      - Continue loop

4. If all attempts exhausted → return "no drivers available"
```

> **Redis GEO fallback:** Redis is the fast path for proximity queries, but PostGIS
> is the durable fallback. If Redis is down, MatchingService queries PostGIS directly
> using `ST_DWithin` + `ST_Distance` with the GiST index. This is slower (~5-10ms vs
> sub-millisecond) but functionally equivalent. The dual-write design (every location
> update writes to both stores) ensures PostGIS always has current driver positions.

## API Response Wrapper

All endpoints return this structure:

```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "timestamp": "2026-04-13T10:30:00Z"
}
```

Error response:
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "RIDE_NOT_FOUND",
    "message": "Ride with id xyz not found"
  },
  "timestamp": "2026-04-13T10:30:00Z"
}
```
