# Progress

## Phase 1: Project Foundation
- [ ] Spring Boot project scaffolding with Maven
- [ ] Add dependencies (Spring Web, JPA, Security, Kafka, Redis, PostGIS, Flyway, Lombok, etc.)
- [ ] Docker Compose for PostgreSQL/PostGIS, Redis, Kafka + Zookeeper
- [ ] Application properties (dev profile)
- [ ] Flyway migrations for all database tables
- [ ] BaseEntity (id, createdAt, updatedAt)
- [ ] ApiResponse wrapper class
- [ ] GlobalExceptionHandler with custom exceptions
- [ ] CORS configuration
- [ ] Swagger/OpenAPI configuration

## Phase 2: User & Auth
- [ ] User entity and UserRepository
- [ ] RegisterRequest / LoginRequest / AuthResponse DTOs
- [ ] AuthService (register, login)
- [ ] JwtService (generate, validate, extract claims)
- [ ] JwtAuthenticationFilter
- [ ] SecurityConfig (permit auth endpoints, protect the rest)
- [ ] AuthController (POST /register, POST /login)
- [ ] DriverProfile entity and DriverProfileRepository
- [ ] DriverService + DriverController (profile CRUD, status update)
- [ ] Rider profile endpoints

## Phase 3: Location Tracking
- [ ] KafkaProducerConfig and KafkaConsumerConfig
- [ ] LocationUpdateRequest DTO
- [ ] LocationProducer (publish to "driver-location-updates" topic)
- [ ] LocationController (POST /drivers/location)
- [ ] DriverLocation entity with PostGIS Point geometry
- [ ] DriverLocationRepository with spatial query methods
- [ ] LocationConsumer (Kafka → write to PostGIS + Redis GEO)
- [ ] RedisConfig with GEO operations
- [ ] Redis driver TTL management (active driver tracking)

## Phase 4: Pricing Engine
- [ ] SurgePricingStrategy interface
- [ ] DemandSupplyRatioStrategy (reads from Redis demand/supply counters)
- [ ] TimeBasedSurgeStrategy (peak hour multipliers)
- [ ] SurgeService (manage Redis demand/supply counters)
- [ ] FareCalculator service (base fare + surge)
- [ ] FareEstimate DTO
- [ ] Fare estimation endpoint (POST /rides/estimate)

## Phase 5: Ride Management
- [ ] RideStatus enum
- [ ] Ride entity and RideRepository
- [ ] RideRoutePoint entity
- [ ] RideService (request, cancel, state transitions)
- [ ] MatchingService (find nearest driver, retry logic, radius expansion)
- [ ] RideEventProducer (publish ride state changes to Kafka)
- [ ] RideController (request, accept, start, complete, cancel, get status)
- [ ] Driver location polling endpoint for rider (GET ride driver location)
- [ ] Ride history endpoints (rider + driver)

## Phase 6: Payment & Ratings
- [ ] Payment entity and PaymentRepository
- [ ] PaymentService (create payment on ride completion)
- [ ] PaymentController (payment history)
- [ ] Rating entity and RatingRepository
- [ ] RatingService (submit rating, update running average on profiles)
- [ ] RatingController (POST rating after ride)

## Phase 7: Polish & Enhancements
- [ ] Integration tests for critical flows (ride request → complete)
- [ ] WebSocket for real-time ride status + driver location push (stretch)
- [ ] Load testing / performance validation on PostGIS queries
