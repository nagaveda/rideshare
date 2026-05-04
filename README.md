# RideShare — Distributed Ride-Sharing Backend

A production-quality Uber-like ride-sharing backend built with Spring Boot, demonstrating distributed systems design with Kafka, Redis, and PostGIS.

---

## Table of Contents

- [Overview](#overview)
- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
- [Features](#features)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [API Reference](#api-reference)
- [Database Schema](#database-schema)
- [Design Patterns](#design-patterns)

---

## Overview

RideShare is a backend system that handles the core flows of a ride-sharing platform:

- JWT-based authentication with two roles: **Rider** and **Driver**
- Real-time driver GPS tracking via a **Kafka pipeline** (REST → Kafka → Redis GEO + PostGIS)
- Geospatial **rider-driver matching** using PostGIS proximity queries with Redis as a fast-path cache
- **Dynamic surge pricing** via the Strategy Pattern, backed by Redis demand/supply counters
- Full **ride lifecycle management** with a state machine and Kafka-based audit log
- Payment recording and mutual post-ride ratings

---

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.4.4 (Java 21) |
| Database | PostgreSQL 15 + PostGIS 3.4 |
| Cache | Redis 7 (GEO commands + TTL tracking) |
| Messaging | Apache Kafka 3.9 (KRaft mode) |
| Migrations | Flyway |
| Auth | JWT (JJWT library) |
| API Docs | Swagger / OpenAPI (springdoc) |
| Infrastructure | Docker Compose |

---

## Architecture

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

### Kafka Topics

| Topic | Producer | Consumer | Key |
|---|---|---|---|
| `driver-location-updates` | `LocationController` | `LocationConsumer` → Redis GEO + PostGIS | `driverId` |
| `ride-events` | `RideService` (on every state change) | `RideEventConsumer` → audit log table | `rideId` |

### Redis Usage

| Key Pattern | Purpose |
|---|---|
| `drivers:active` | GEO set of active drivers — used for fast proximity matching |
| `driver:{id}:active` | TTL key (30s) — expiry triggers driver eviction from GEO set |
| `surge:zone:{zoneId}:demand` | Rolling demand counter (60s TTL) for surge calculation |
| `surge:zone:{zoneId}:supply` | Available driver count per zone |
| `ride:active:{rideId}` | Cached ride status for fast lookups |

---

## Features

### Authentication & User Management
- Registration and login with BCrypt password hashing
- JWT tokens (24-hour expiry, configurable)
- Role-based access control: `RIDER` and `DRIVER`
- Rider profile endpoints (view and update)
- Driver profile endpoints (vehicle details, license, status)

### Driver Location Tracking
- Drivers POST GPS coordinates every 3-5 seconds
- Async pipeline: REST endpoint → Kafka topic → consumer writes to both Redis GEO and PostGIS
- Redis GEO provides sub-millisecond proximity lookups for active drivers
- PostGIS is the durable source of truth; also serves as fallback when Redis is unavailable
- Drivers auto-expire from the active set after 30 seconds with no update

### Rider-Driver Matching
- Finds the nearest available driver using Redis GEO (fast path) or PostGIS `ST_DWithin` (fallback)
- Sends requests one driver at a time, closest first
- 30-second accept timeout per driver before moving to the next
- Radius expansion: 3 km → 5 km → 10 km if no drivers respond
- Returns "no drivers available" after max retries at max radius

### Surge Pricing (Strategy Pattern)
- `SurgePricingStrategy` interface with two implementations:
  - `DemandSupplyRatioStrategy` — reads demand/supply counters from Redis; surge = demand ÷ supply (capped at 3x)
  - `TimeBasedSurgeStrategy` — fixed multipliers during configured peak hours
- `FareCalculator`: `baseFare = BASE_RATE + (PER_KM × distance) + (PER_MIN × duration)`
- Final fare = `baseFare × surgeMultiplier`
- Fare estimate endpoint available before requesting a ride

### Ride Lifecycle

```
REQUESTED → DRIVER_ASSIGNED → DRIVER_EN_ROUTE → ARRIVED → IN_PROGRESS → COMPLETED
                                                                        ↘ CANCELLED (from any pre-IN_PROGRESS state)
```

Every state transition publishes an event to Kafka, which is consumed and written to the `ride_events` audit log table.

### Payment & Ratings
- Payment record created automatically on ride completion (no external gateway — record-keeping only)
- Fare breakdown stored: base fare, surge amount, total
- Both rider and driver can rate each other (1–5 stars) after completion
- Running average rating updated on driver and rider profiles
- One rating per person per ride (enforced by DB unique constraint)

---

## Project Structure

```
src/main/java/com/rideshare/
├── config/                    # Security, Kafka, Redis, CORS, Swagger
├── common/                    # ApiResponse wrapper, exceptions, BaseEntity
├── user/                      # Auth (register/login), JWT, rider profile
├── driver/                    # Driver profile, status, vehicle info
├── location/                  # GPS ingestion, Kafka producer/consumer, Redis GEO
├── matching/                  # Nearest-driver matching with retry/radius expansion
├── pricing/                   # Surge strategies, FareCalculator, SurgeService
├── ride/                      # Ride lifecycle, state machine, Kafka audit events
├── payment/                   # Payment records and history
└── rating/                    # Post-ride ratings, running average updates

src/main/resources/
├── application.yml            # All configuration (DB, Redis, Kafka, JWT, pricing)
└── db/migration/              # Flyway migrations V1–V8
```

---

## Prerequisites

- **Java 21**
- **Maven 3.9+** (or use the included `./mvnw` wrapper)
- **Docker & Docker Compose** (for PostgreSQL/PostGIS, Redis, Kafka)

---

## Getting Started

### 1. Clone the repository

```bash
git clone <repo-url>
cd rideShare
```

### 2. Start infrastructure

```bash
docker compose up -d
```

This starts:
- **PostgreSQL + PostGIS** on port `5432`
- **Redis** on port `6379`
- **Kafka** (KRaft, no Zookeeper) on port `9092`
- **pgAdmin** on port `5050` (optional DB UI)

Wait ~10 seconds for all services to be healthy before starting the app.

### 3. Run the application

```bash
./mvnw spring-boot:run
```

Flyway will automatically run all database migrations on startup.

The API is available at `http://localhost:8080/api/v1`.

### 4. Explore the API

Swagger UI: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

OpenAPI JSON: [http://localhost:8080/api-docs](http://localhost:8080/api-docs)

### 5. (Optional) pgAdmin

Access at [http://localhost:5050](http://localhost:5050)

- Email: `admin@rideshare.com`
- Password: `admin`
- Connect to host `postgres`, port `5432`, database `rideshare`, user `rideshare`, password `rideshare`

### Stopping everything

```bash
docker compose down
```

To also remove persisted data volumes:

```bash
docker compose down -v
```

---

## Configuration

All configuration lives in `src/main/resources/application.yml`.

| Property | Default | Description |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/rideshare` | PostgreSQL connection |
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | Kafka broker |
| `jwt.secret` | (dev default) | **Change in production.** Must be 256+ bits. |
| `jwt.expiration-ms` | `86400000` | JWT TTL (24 hours) |
| `pricing.base-rate` | `2.50` | Base fare in USD |
| `pricing.per-km` | `1.50` | Per-km rate |
| `pricing.per-minute` | `0.25` | Per-minute rate |
| `pricing.minimum-fare` | `5.00` | Minimum fare floor |
| `pricing.surge.max-multiplier` | `3.0` | Surge price cap |

To override any value without editing the file, set the corresponding environment variable, e.g.:

```bash
JWT_SECRET=your-production-secret ./mvnw spring-boot:run
```

---

## API Reference

Base URL: `http://localhost:8080/api/v1`

All responses use the standard wrapper:
```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "timestamp": "2026-04-13T10:30:00Z"
}
```

All endpoints except `/auth/register` and `/auth/login` require:
```
Authorization: Bearer <jwt-token>
```

---

### Authentication

#### `POST /auth/register`
Register a new user.

```json
{
  "email": "john@example.com",
  "password": "securePassword123",
  "firstName": "John",
  "lastName": "Doe",
  "phone": "+1234567890",
  "role": "RIDER"
}
```
`role` must be `RIDER` or `DRIVER`. Registering as `DRIVER` auto-creates a driver profile.

**Response `201`:**
```json
{
  "token": "eyJhbGci...",
  "userId": "uuid",
  "email": "john@example.com",
  "role": "RIDER"
}
```

#### `POST /auth/login`
```json
{
  "email": "john@example.com",
  "password": "securePassword123"
}
```

---

### Driver

> Requires role `DRIVER`

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/drivers/profile` | Get driver profile |
| `PUT` | `/drivers/profile` | Update vehicle details and license |
| `PUT` | `/drivers/status` | Set status (`OFFLINE`, `AVAILABLE`, `BUSY`) |
| `POST` | `/drivers/location` | Push current GPS coordinates |

**Location update (call every 3–5 seconds when online):**
```json
{
  "latitude": 37.7749,
  "longitude": -122.4194,
  "heading": 180.0,
  "speed": 35.5
}
```

---

### Rider

> Requires role `RIDER`

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/riders/profile` | Get rider profile |
| `PUT` | `/riders/profile` | Update name and phone |

---

### Rides

#### `POST /rides/estimate` — Fare estimate (RIDER)
```json
{
  "pickupLatitude": 37.7749,
  "pickupLongitude": -122.4194,
  "dropoffLatitude": 37.7849,
  "dropoffLongitude": -122.4094
}
```
**Response:**
```json
{
  "baseFare": 12.50,
  "surgeMultiplier": 1.5,
  "estimatedTotal": 18.75,
  "estimatedDistanceKm": 3.2,
  "estimatedDurationMinutes": 12
}
```

#### `POST /rides/request` — Request a ride (RIDER)
```json
{
  "pickupLatitude": 37.7749,
  "pickupLongitude": -122.4194,
  "pickupAddress": "123 Market St, SF",
  "dropoffLatitude": 37.7849,
  "dropoffLongitude": -122.4094,
  "dropoffAddress": "456 Mission St, SF",
  "paymentMethod": "CARD"
}
```

#### Ride state transitions (DRIVER)

| Method | Endpoint | Transition |
|---|---|---|
| `PUT` | `/rides/{id}/accept` | `REQUESTED` → `DRIVER_ASSIGNED` |
| `PUT` | `/rides/{id}/en-route` | `DRIVER_ASSIGNED` → `DRIVER_EN_ROUTE` |
| `PUT` | `/rides/{id}/arrived` | `DRIVER_EN_ROUTE` → `ARRIVED` |
| `PUT` | `/rides/{id}/start` | `ARRIVED` → `IN_PROGRESS` |
| `PUT` | `/rides/{id}/complete` | `IN_PROGRESS` → `COMPLETED` |

#### Other ride endpoints

| Method | Endpoint | Role | Description |
|---|---|---|---|
| `PUT` | `/rides/{id}/cancel` | RIDER or DRIVER | Cancel (allowed before `IN_PROGRESS`) |
| `GET` | `/rides/{id}` | Either | Ride details |
| `GET` | `/rides/{id}/driver-location` | RIDER | Poll driver's current position |
| `GET` | `/rides/history` | Either | Paginated ride history (`?page=0&size=20`) |

---

### Payments

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/payments/history` | Paginated payment history (`?page=0&size=20`) |

---

### Ratings

#### `POST /rides/{id}/rate`
Submit a rating after the ride is `COMPLETED`. The system determines who you are rating based on your role in the ride.

```json
{
  "score": 5,
  "comment": "Great ride!"
}
```

`score` must be 1–5. One rating per person per ride.

---

## Database Schema

### Core Tables

| Table | Description |
|---|---|
| `users` | All users (riders and drivers) |
| `driver_profiles` | Vehicle details, license, status, rating, total rides |
| `driver_locations` | Current driver position (one row per driver, upserted) — PostGIS `GEOMETRY(Point, 4326)` with GiST index |
| `rides` | Ride records with pickup/dropoff geometry, fare, timestamps |
| `ride_route_points` | GPS waypoints recorded during active rides |
| `payments` | Fare breakdown per completed ride |
| `ratings` | Post-ride ratings with unique constraint on `(ride_id, rater_id)` |
| `ride_events` | Kafka-fed audit log of every ride state transition |

Migrations are in `src/main/resources/db/migration/` (Flyway, V1–V8).

---

## Design Patterns

### Strategy Pattern — Surge Pricing

```
SurgePricingStrategy (interface)
├── DemandSupplyRatioStrategy   ← reads Redis counters, surge = demand / supply
└── TimeBasedSurgeStrategy      ← fixed multipliers by hour-of-day config
```

`FareCalculator` delegates to the active strategy at runtime. The strategy can be swapped or composed (e.g., take the higher of both) without changing `FareCalculator`.

### Dual-Write + Fallback — Location Pipeline

Every driver location update writes to both Redis GEO and PostGIS. Redis is the fast path (~sub-millisecond GEOSEARCH); PostGIS is the durable fallback (~5–10ms with GiST index). `MatchingService` automatically falls back to PostGIS if Redis is unavailable.

### State Machine — Ride Lifecycle

State transitions are validated in `RideService` — invalid transitions throw a `BadRequestException`. Every valid transition publishes an event to Kafka for async audit logging.
