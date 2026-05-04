# Feature Checklist

Granular task list for implementation. Each item is a single commit-sized unit of work.

---

## Phase 1: Project Foundation

### 1.1 Project Scaffolding
- [x] Generate Spring Boot project (Java 17+, Maven)
- [x] Add all dependencies to pom.xml:
  - spring-boot-starter-web
  - spring-boot-starter-data-jpa
  - spring-boot-starter-security
  - spring-boot-starter-validation
  - spring-kafka
  - spring-boot-starter-data-redis
  - hibernate-spatial (PostGIS support)
  - flyway-core
  - jjwt (JWT library)
  - lombok
  - springdoc-openapi (Swagger)
  - postgresql driver
- [x] Create application.yml with dev profile settings

### 1.2 Local Infrastructure
- [x] Docker Compose file with:
  - PostgreSQL 15 + PostGIS 3.4
  - Redis 7
  - Kafka (KRaft or with Zookeeper) + topic auto-creation
- [x] Verify all services start and are reachable

### 1.3 Database Migrations
- [x] Flyway migration V1: `users` table
- [x] Flyway migration V2: `driver_profiles` table
- [x] Flyway migration V3: `driver_locations` table with PostGIS geometry + GiST index
- [x] Flyway migration V4: `rides` table with PostGIS geometry columns
- [x] Flyway migration V5: `ride_route_points` table
- [x] Flyway migration V6: `payments` table
- [x] Flyway migration V7: `ratings` table

### 1.4 Common Infrastructure
- [x] BaseEntity with UUID id, createdAt, updatedAt (JPA auditing)
- [x] ApiResponse<T> wrapper class
- [x] Custom exceptions (ResourceNotFoundException, BadRequestException, UnauthorizedException)
- [x] GlobalExceptionHandler (@RestControllerAdvice)
- [x] CorsConfig (allow frontend origins)
- [x] SwaggerConfig (OpenAPI metadata)

---

## Phase 2: User & Auth

### 2.1 User Entity & Auth
- [x] User entity (id, email, passwordHash, firstName, lastName, phone, role)
- [x] Role enum (RIDER, DRIVER)
- [x] UserRepository
- [x] RegisterRequest and LoginRequest DTOs with validation annotations
- [x] AuthResponse DTO (token, user info)
- [x] AuthService — register (BCrypt hash, save) and login (verify, issue JWT)
- [x] AuthController — POST /api/v1/auth/register, POST /api/v1/auth/login

### 2.2 JWT & Security
- [x] JwtService — generateToken, validateToken, extractUserId, extractRole
- [x] JwtAuthenticationFilter (OncePerRequestFilter) — extract token from header, validate, set SecurityContext
- [x] SecurityConfig — configure filter chain, permit /auth/**, protect everything else, stateless session

### 2.3 Driver Profile
- [x] DriverProfile entity (vehicle details, license, status, rating, totalRides)
- [x] DriverStatus enum (OFFLINE, AVAILABLE, BUSY)
- [x] DriverProfileRepository
- [x] DriverService — create profile (on driver registration), update profile, update status
- [x] DriverController — GET/PUT profile, PUT status
- [x] Auto-create DriverProfile when a user registers as DRIVER

### 2.4 Rider Profile
- [x] Rider profile endpoints (GET /api/v1/riders/profile, PUT to update)
- [x] Profile returned from user data (no separate rider_profiles table needed)

---

## Phase 3: Location Tracking

### 3.1 Kafka Setup
- [x] KafkaProducerConfig (String key, JSON value serializer)
- [x] KafkaConsumerConfig (String key, JSON value deserializer, group ID)
- [x] LocationUpdateRequest DTO (latitude, longitude, heading, speed)
- [x] LocationEvent Kafka payload class

### 3.2 Location Producer
- [x] LocationProducer service — publishes to "driver-location-updates" topic, keyed by driverId
- [x] LocationController — POST /api/v1/drivers/location (authenticated, DRIVER role only)
- [x] Endpoint validates driver is AVAILABLE/BUSY before accepting updates

### 3.3 PostGIS Storage
- [x] DriverLocation entity with @Column(columnDefinition = "geometry(Point,4326)")
- [x] DriverLocationRepository with native PostGIS queries:
  - Upsert driver location (INSERT ON CONFLICT UPDATE)
  - Find drivers within radius: `ST_DWithin(location, ST_MakePoint(lng, lat)::geography, radiusMeters)`
  - Order by distance: `ST_Distance(location, ST_MakePoint(lng, lat)::geography)`

### 3.4 Redis GEO
- [x] RedisConfig with RedisTemplate and GEO operations
- [x] Redis GEO operations service:
  - `GEOADD drivers:active lng lat driverId`
  - `GEOSEARCH drivers:active FROMLONLAT lng lat BYRADIUS N km`
  - `ZREM drivers:active driverId` (remove offline driver)
- [x] Driver active TTL tracking:
  - Set `driver:{id}:active` with 30s TTL on each location update
  - Cleanup mechanism to remove expired drivers from GEO set

### 3.5 Location Consumer
- [x] LocationConsumer — listens to "driver-location-updates" topic
- [x] On each event: write to PostGIS (upsert) + update Redis GEO + reset TTL

---

## Phase 4: Pricing Engine

### 4.1 Surge Strategy Pattern
- [x] SurgePricingStrategy interface: `double calculateMultiplier(String zone, SurgeMetrics metrics)`
- [x] SurgeMetrics DTO (demand count, supply count, timestamp)
- [x] DemandSupplyRatioStrategy — surge = demand/supply, capped at configurable max (e.g., 3.0x)
- [x] TimeBasedSurgeStrategy — multiplier based on hour-of-day config (peak hours map)

### 4.2 Surge Service
- [x] SurgeService:
  - Increment demand counter on ride request: `INCR surge:zone:{zone}:demand` (with TTL ~60s)
  - Read current demand/supply from Redis
  - Determine zone from coordinates (simplified: grid-based or single zone for MVP)
- [x] Supply counter updated by location consumer (driver count in zone)

### 4.3 Fare Calculator
- [x] FareCalculator service:
  - Base fare computation: `BASE_RATE + (PER_KM * distance) + (PER_MIN * duration)`
  - Get surge multiplier from active strategy
  - Return FareEstimate (baseFare, surgeMultiplier, estimatedTotal, distance, duration)
- [x] Distance/duration estimation (straight-line for MVP, can integrate routing API later)
- [x] Fare estimation endpoint: POST /api/v1/rides/estimate

---

## Phase 5: Ride Management

### 5.1 Ride Entity
- [x] RideStatus enum (REQUESTED, DRIVER_ASSIGNED, DRIVER_EN_ROUTE, ARRIVED, IN_PROGRESS, COMPLETED, CANCELLED)
- [x] Ride entity with all fields (see architecture.md schema)
- [x] RideRepository with custom queries (active ride by rider, rides by driver, history with pagination)
- [x] RideRoutePoint entity and repository

### 5.2 Matching Service
- [x] MatchingService:
  - Query Redis GEO for nearby drivers → filter by AVAILABLE status in PostGIS
  - Redis GEO fallback: if Redis is unavailable, query PostGIS directly (ST_DWithin + ST_Distance)
  - Closest-first selection
  - Timeout handling (30s per driver)
  - Radius expansion logic (3km → 5km → 10km)
  - Max retry limit → "no drivers available"
  - Track skipped drivers per ride request

### 5.3 Ride Lifecycle
- [x] RideService:
  - requestRide() — create ride, get fare estimate, trigger matching
  - acceptRide() — driver accepts, status → DRIVER_ASSIGNED, driver status → BUSY
  - startEnRoute() — status → DRIVER_EN_ROUTE
  - arrivedAtPickup() — status → ARRIVED
  - startRide() — status → IN_PROGRESS
  - completeRide() — status → COMPLETED, calculate actual fare, trigger payment
  - cancelRide() — status → CANCELLED, set reason, release driver if assigned
- [x] State transition validation (can only go from valid states)

### 5.4 Ride Events & Audit Log
- [x] RideEventProducer — publish state changes to "ride-events" Kafka topic
- [x] Ride event published on every state transition
- [x] Flyway migration V8: `ride_events` table (ride_id, rider_id, driver_id, status, metadata JSONB, created_at)
- [x] RideEvent entity and RideEventRepository
- [x] RideEventConsumer — listens to "ride-events" topic, writes audit log to ride_events table

### 5.5 Ride Controller
- [x] POST /api/v1/rides/request — rider requests ride
- [x] PUT /api/v1/rides/{id}/accept — driver accepts
- [x] PUT /api/v1/rides/{id}/en-route — driver heading to pickup
- [x] PUT /api/v1/rides/{id}/arrived — driver arrived at pickup
- [x] PUT /api/v1/rides/{id}/start — ride starts
- [x] PUT /api/v1/rides/{id}/complete — ride ends
- [x] PUT /api/v1/rides/{id}/cancel — cancel ride
- [x] GET /api/v1/rides/{id} — ride details
- [x] GET /api/v1/rides/{id}/driver-location — rider polls driver position
- [x] GET /api/v1/rides/history — paginated ride history (rider or driver based on role)

---

## Phase 6: Payment & Ratings

### 6.1 Payment
- [x] Payment entity (see schema in architecture.md)
- [x] PaymentRepository
- [x] PaymentService:
  - createPayment() — called on ride completion, records fare breakdown
  - Payment status: PENDING → COMPLETED (auto, since no real gateway)
- [x] PaymentController:
  - GET /api/v1/payments/history — paginated payment history

### 6.2 Ratings
- [x] Rating entity (ride_id, rater_id, ratee_id, score, comment)
- [x] RatingRepository (unique constraint on ride_id + rater_id)
- [x] RatingService:
  - submitRating() — validate ride is COMPLETED, rater was part of ride
  - Update running average on DriverProfile or User
- [x] RatingController:
  - POST /api/v1/rides/{id}/rate — submit rating

---

## Phase 7: Polish & Enhancements

### 7.1 Testing
- [ ] Integration tests: full ride flow (request → match → accept → complete → payment)
- [ ] Integration tests: location pipeline (POST → Kafka → Redis + PostGIS)
- [ ] Integration tests: pricing with surge

### 7.2 WebSocket (Stretch)
- [ ] WebSocket configuration
- [ ] Real-time ride status updates to rider
- [ ] Real-time driver location push to rider during active ride

### 7.3 Performance
- [ ] Validate PostGIS query performance with sample data
- [ ] Redis GEO query benchmarks
