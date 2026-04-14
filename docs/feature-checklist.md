# Feature Checklist

Granular task list for implementation. Each item is a single commit-sized unit of work.

---

## Phase 1: Project Foundation

### 1.1 Project Scaffolding
- [ ] Generate Spring Boot project (Java 17+, Maven)
- [ ] Add all dependencies to pom.xml:
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
- [ ] Create application.yml with dev profile settings

### 1.2 Local Infrastructure
- [ ] Docker Compose file with:
  - PostgreSQL 15 + PostGIS 3.4
  - Redis 7
  - Kafka (KRaft or with Zookeeper) + topic auto-creation
- [ ] Verify all services start and are reachable

### 1.3 Database Migrations
- [ ] Flyway migration V1: `users` table
- [ ] Flyway migration V2: `driver_profiles` table
- [ ] Flyway migration V3: `driver_locations` table with PostGIS geometry + GiST index
- [ ] Flyway migration V4: `rides` table with PostGIS geometry columns
- [ ] Flyway migration V5: `ride_route_points` table
- [ ] Flyway migration V6: `payments` table
- [ ] Flyway migration V7: `ratings` table

### 1.4 Common Infrastructure
- [ ] BaseEntity with UUID id, createdAt, updatedAt (JPA auditing)
- [ ] ApiResponse<T> wrapper class
- [ ] Custom exceptions (ResourceNotFoundException, BadRequestException, UnauthorizedException)
- [ ] GlobalExceptionHandler (@RestControllerAdvice)
- [ ] CorsConfig (allow frontend origins)
- [ ] SwaggerConfig (OpenAPI metadata)

---

## Phase 2: User & Auth

### 2.1 User Entity & Auth
- [ ] User entity (id, email, passwordHash, firstName, lastName, phone, role)
- [ ] Role enum (RIDER, DRIVER)
- [ ] UserRepository
- [ ] RegisterRequest and LoginRequest DTOs with validation annotations
- [ ] AuthResponse DTO (token, user info)
- [ ] AuthService — register (BCrypt hash, save) and login (verify, issue JWT)
- [ ] AuthController — POST /api/v1/auth/register, POST /api/v1/auth/login

### 2.2 JWT & Security
- [ ] JwtService — generateToken, validateToken, extractUserId, extractRole
- [ ] JwtAuthenticationFilter (OncePerRequestFilter) — extract token from header, validate, set SecurityContext
- [ ] SecurityConfig — configure filter chain, permit /auth/**, protect everything else, stateless session

### 2.3 Driver Profile
- [ ] DriverProfile entity (vehicle details, license, status, rating, totalRides)
- [ ] DriverStatus enum (OFFLINE, AVAILABLE, BUSY)
- [ ] DriverProfileRepository
- [ ] DriverService — create profile (on driver registration), update profile, update status
- [ ] DriverController — GET/PUT profile, PUT status
- [ ] Auto-create DriverProfile when a user registers as DRIVER

### 2.4 Rider Profile
- [ ] Rider profile endpoints (GET /api/v1/riders/profile, PUT to update)
- [ ] Profile returned from user data (no separate rider_profiles table needed)

---

## Phase 3: Location Tracking

### 3.1 Kafka Setup
- [ ] KafkaProducerConfig (String key, JSON value serializer)
- [ ] KafkaConsumerConfig (String key, JSON value deserializer, group ID)
- [ ] LocationUpdateRequest DTO (latitude, longitude, heading, speed)
- [ ] LocationEvent Kafka payload class

### 3.2 Location Producer
- [ ] LocationProducer service — publishes to "driver-location-updates" topic, keyed by driverId
- [ ] LocationController — POST /api/v1/drivers/location (authenticated, DRIVER role only)
- [ ] Endpoint validates driver is AVAILABLE/BUSY before accepting updates

### 3.3 PostGIS Storage
- [ ] DriverLocation entity with @Column(columnDefinition = "geometry(Point,4326)")
- [ ] DriverLocationRepository with native PostGIS queries:
  - Upsert driver location (INSERT ON CONFLICT UPDATE)
  - Find drivers within radius: `ST_DWithin(location, ST_MakePoint(lng, lat)::geography, radiusMeters)`
  - Order by distance: `ST_Distance(location, ST_MakePoint(lng, lat)::geography)`

### 3.4 Redis GEO
- [ ] RedisConfig with RedisTemplate and GEO operations
- [ ] Redis GEO operations service:
  - `GEOADD drivers:active lng lat driverId`
  - `GEOSEARCH drivers:active FROMLONLAT lng lat BYRADIUS N km`
  - `ZREM drivers:active driverId` (remove offline driver)
- [ ] Driver active TTL tracking:
  - Set `driver:{id}:active` with 30s TTL on each location update
  - Cleanup mechanism to remove expired drivers from GEO set

### 3.5 Location Consumer
- [ ] LocationConsumer — listens to "driver-location-updates" topic
- [ ] On each event: write to PostGIS (upsert) + update Redis GEO + reset TTL

---

## Phase 4: Pricing Engine

### 4.1 Surge Strategy Pattern
- [ ] SurgePricingStrategy interface: `double calculateMultiplier(String zone, SurgeMetrics metrics)`
- [ ] SurgeMetrics DTO (demand count, supply count, timestamp)
- [ ] DemandSupplyRatioStrategy — surge = demand/supply, capped at configurable max (e.g., 3.0x)
- [ ] TimeBasedSurgeStrategy — multiplier based on hour-of-day config (peak hours map)

### 4.2 Surge Service
- [ ] SurgeService:
  - Increment demand counter on ride request: `INCR surge:zone:{zone}:demand` (with TTL ~60s)
  - Read current demand/supply from Redis
  - Determine zone from coordinates (simplified: grid-based or single zone for MVP)
- [ ] Supply counter updated by location consumer (driver count in zone)

### 4.3 Fare Calculator
- [ ] FareCalculator service:
  - Base fare computation: `BASE_RATE + (PER_KM * distance) + (PER_MIN * duration)`
  - Get surge multiplier from active strategy
  - Return FareEstimate (baseFare, surgeMultiplier, estimatedTotal, distance, duration)
- [ ] Distance/duration estimation (straight-line for MVP, can integrate routing API later)
- [ ] Fare estimation endpoint: POST /api/v1/rides/estimate

---

## Phase 5: Ride Management

### 5.1 Ride Entity
- [ ] RideStatus enum (REQUESTED, DRIVER_ASSIGNED, DRIVER_EN_ROUTE, ARRIVED, IN_PROGRESS, COMPLETED, CANCELLED)
- [ ] Ride entity with all fields (see architecture.md schema)
- [ ] RideRepository with custom queries (active ride by rider, rides by driver, history with pagination)
- [ ] RideRoutePoint entity and repository

### 5.2 Matching Service
- [ ] MatchingService:
  - Query Redis GEO for nearby drivers → filter by AVAILABLE status in PostGIS
  - Closest-first selection
  - Timeout handling (30s per driver)
  - Radius expansion logic (3km → 5km → 10km)
  - Max retry limit → "no drivers available"
  - Track skipped drivers per ride request

### 5.3 Ride Lifecycle
- [ ] RideService:
  - requestRide() — create ride, get fare estimate, trigger matching
  - acceptRide() — driver accepts, status → DRIVER_ASSIGNED, driver status → BUSY
  - startEnRoute() — status → DRIVER_EN_ROUTE
  - arrivedAtPickup() — status → ARRIVED
  - startRide() — status → IN_PROGRESS
  - completeRide() — status → COMPLETED, calculate actual fare, trigger payment
  - cancelRide() — status → CANCELLED, set reason, release driver if assigned
- [ ] State transition validation (can only go from valid states)

### 5.4 Ride Events
- [ ] RideEventProducer — publish state changes to "ride-events" Kafka topic
- [ ] Ride event published on every state transition

### 5.5 Ride Controller
- [ ] POST /api/v1/rides/request — rider requests ride
- [ ] PUT /api/v1/rides/{id}/accept — driver accepts
- [ ] PUT /api/v1/rides/{id}/en-route — driver heading to pickup
- [ ] PUT /api/v1/rides/{id}/arrived — driver arrived at pickup
- [ ] PUT /api/v1/rides/{id}/start — ride starts
- [ ] PUT /api/v1/rides/{id}/complete — ride ends
- [ ] PUT /api/v1/rides/{id}/cancel — cancel ride
- [ ] GET /api/v1/rides/{id} — ride details
- [ ] GET /api/v1/rides/{id}/driver-location — rider polls driver position
- [ ] GET /api/v1/rides/history — paginated ride history (rider or driver based on role)

---

## Phase 6: Payment & Ratings

### 6.1 Payment
- [ ] Payment entity (see schema in architecture.md)
- [ ] PaymentRepository
- [ ] PaymentService:
  - createPayment() — called on ride completion, records fare breakdown
  - Payment status: PENDING → COMPLETED (auto, since no real gateway)
- [ ] PaymentController:
  - GET /api/v1/payments/history — paginated payment history

### 6.2 Ratings
- [ ] Rating entity (ride_id, rater_id, ratee_id, score, comment)
- [ ] RatingRepository (unique constraint on ride_id + rater_id)
- [ ] RatingService:
  - submitRating() — validate ride is COMPLETED, rater was part of ride
  - Update running average on DriverProfile or User
- [ ] RatingController:
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
