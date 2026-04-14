# Requirements

## Project Goal
Production-quality Uber-like ride-sharing backend built for learning. Not a toy project — should be scalable, well-engineered, and demonstrate strong backend skills (Spring Boot, Kafka, Redis, PostGIS, design patterns).

## Tech Stack
- **Framework:** Spring Boot (Java)
- **Database:** PostgreSQL with PostGIS extension
- **Cache:** Redis (GEO commands for driver proximity, TTL for active driver tracking)
- **Messaging:** Apache Kafka
- **Migrations:** Flyway
- **Local Infra:** Docker Compose (Postgres/PostGIS, Redis, Kafka + Zookeeper)
- **Auth:** JWT
- **API Docs:** Swagger/OpenAPI

## Functional Requirements

### 1. Authentication & User Management
- JWT-based registration and login
- Two roles: RIDER and DRIVER
- Rider profile (name, phone, email)
- Driver profile (name, phone, email, vehicle details, license, status, rating)

### 2. Driver Location Tracking
- Drivers POST GPS coordinates every 3-5 seconds when online
- Location pipeline: REST → Kafka → Consumer writes to Redis GEO + PostGIS
- Redis GEO holds only active drivers (TTL-based eviction — no update within ~30s = offline)
- PostGIS stores all location history (permanent, source of truth)
- Kafka decouples the REST endpoint from storage writes (async, buffered, resilient)

### 3. Rider-Driver Matching
- When rider requests a ride, system finds nearest available drivers via PostGIS
- Send request to closest driver first (one at a time, not broadcast)
- Driver has a timeout window (~30 seconds) to accept
- If declined or timed out → next closest driver
- If no drivers in current radius → expand radius (e.g., 3km → 5km → 10km)
- After max retries or max radius → return "no drivers available"

### 4. Pricing & Surge (Strategy Pattern)
- Base fare calculation: base_fare + (per_km_rate × distance) + (per_min_rate × duration)
- Strategy Pattern for surge pricing:
  - `SurgePricingStrategy` interface with `calculateMultiplier(zone, metrics)`
  - `DemandSupplyRatioStrategy` — surge based on riders_requesting / drivers_available from Redis
  - `TimeBasedSurgeStrategy` — fixed multipliers during peak hours
- Final fare = base_fare × surge_multiplier
- Fare estimation endpoint (before ride request)

### 5. Ride Lifecycle
- States: REQUESTED → DRIVER_ASSIGNED → DRIVER_EN_ROUTE → ARRIVED → IN_PROGRESS → COMPLETED | CANCELLED
- Rider requests ride → fare estimate → ride created → matching begins
- Driver accepts → en route → arrives → rider picked up → in progress → complete
- Cancellation allowed at any state before IN_PROGRESS, with a reason
- Ride events published to Kafka ("ride-events" topic)

### 6. Payment
- Record-keeping only (no real payment gateway integration)
- Payment record created on ride completion with fare breakdown
- Payment history for riders and drivers

### 7. Ratings
- After ride completion, rider can rate driver (1-5 stars)
- Driver can rate rider (1-5 stars)
- Running average rating stored on profiles

### 8. Real-Time Updates (Rider Read Path)
- Phase 1: REST polling — rider GETs driver location and ride status periodically
- Phase 2 (later): WebSocket — server pushes updates to rider in real-time
- Core pipeline (driver → Kafka → Redis/PostGIS) is the same for both approaches

## Non-Functional Requirements
- API versioned under `/api/v1/`
- Standard JSON response wrapper: `{success, data, error, timestamp}`
- CORS configured for frontend integration
- Pagination on list endpoints
- Swagger/OpenAPI auto-generated documentation
- Docker Compose for local development (all infrastructure)
- Flyway for database migration management
- Clean git history — one logical change per commit
