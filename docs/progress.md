# Progress

## Phase 1: Project Foundation
- [x] Spring Boot project scaffolding with Maven
- [x] Add dependencies (Spring Web, JPA, Security, Kafka, Redis, PostGIS, Flyway, Lombok, etc.)
- [x] Docker Compose for PostgreSQL/PostGIS, Redis, Kafka (KRaft)
- [x] Application properties (dev profile)
- [x] Flyway migrations for all database tables
- [x] BaseEntity (id, createdAt, updatedAt)
- [x] ApiResponse wrapper class
- [x] GlobalExceptionHandler with custom exceptions
- [x] CORS configuration
- [x] Swagger/OpenAPI configuration

## Phase 2: User & Auth
- [x] User entity and UserRepository
- [x] RegisterRequest / LoginRequest / AuthResponse DTOs
- [x] AuthService (register, login)
- [x] JwtService (generate, validate, extract claims)
- [x] JwtAuthenticationFilter
- [x] SecurityConfig (permit auth endpoints, protect the rest)
- [x] AuthController (POST /register, POST /login)
- [x] DriverProfile entity and DriverProfileRepository
- [x] DriverService + DriverController (profile CRUD, status update)
- [x] Rider profile endpoints

## Phase 3: Location Tracking
- [x] KafkaProducerConfig and KafkaConsumerConfig
- [x] LocationUpdateRequest DTO
- [x] LocationProducer (publish to "driver-location-updates" topic)
- [x] LocationController (POST /drivers/location)
- [x] DriverLocation entity with PostGIS Point geometry
- [x] DriverLocationRepository with spatial query methods
- [x] LocationConsumer (Kafka → write to PostGIS + Redis GEO)
- [x] RedisConfig with GEO operations
- [x] Redis driver TTL management (active driver tracking)

## Phase 4: Pricing Engine
- [x] SurgePricingStrategy interface
- [x] DemandSupplyRatioStrategy (reads from Redis demand/supply counters)
- [x] TimeBasedSurgeStrategy (peak hour multipliers)
- [x] SurgeService (manage Redis demand/supply counters)
- [x] FareCalculator service (base fare + surge)
- [x] FareEstimate DTO
- [x] Fare estimation endpoint (POST /rides/estimate)

## Phase 5: Ride Management
- [x] RideStatus enum
- [x] Ride entity and RideRepository
- [x] RideRoutePoint entity
- [x] RideService (request, cancel, state transitions)
- [x] MatchingService (find nearest driver, retry logic, radius expansion)
- [x] RideEventProducer (publish ride state changes to Kafka)
- [x] RideEvent entity + Flyway migration for ride_events audit log table
- [x] RideEventConsumer (Kafka → write audit log to ride_events table)
- [x] RideController (request, accept, start, complete, cancel, get status)
- [x] Driver location polling endpoint for rider (GET ride driver location)
- [x] Ride history endpoints (rider + driver)

## Phase 6: Payment & Ratings
- [x] Payment entity and PaymentRepository
- [x] PaymentService (create payment on ride completion)
- [x] PaymentController (payment history)
- [x] Rating entity and RatingRepository
- [x] RatingService (submit rating, update running average on profiles)
- [x] RatingController (POST rating after ride)

## Phase 7: Polish & Enhancements
- [ ] Integration tests for critical flows (ride request → complete)
- [ ] WebSocket for real-time ride status + driver location push (stretch)
- [ ] Load testing / performance validation on PostGIS queries
