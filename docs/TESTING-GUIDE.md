# Local Testing Guide

All testing is done via curl or Swagger UI. No test containers or frameworks needed.

**Swagger UI:** http://localhost:8080/swagger-ui.html  
**Base URL:** http://localhost:8080/api/v1

---

## Prerequisites

Start infrastructure and the app:

```bash
docker compose up -d
mvn spring-boot:run
```

Wait ~10 seconds for Kafka and Postgres to be fully ready before hitting any endpoints.

---

## Flow 1 — Happy Path (Full Ride End-to-End)

This is the primary flow. Walk through it first.

### Step 1 — Register a driver

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "driver@test.com",
    "password": "password123",
    "firstName": "Jane",
    "lastName": "Smith",
    "phone": "+11234567890",
    "role": "DRIVER"
  }' | jq .
```

Save the `token` from the response → `DRIVER_TOKEN`

### Step 2 — Register a rider

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "rider@test.com",
    "password": "password123",
    "firstName": "John",
    "lastName": "Doe",
    "phone": "+10987654321",
    "role": "RIDER"
  }' | jq .
```

Save the `token` → `RIDER_TOKEN`

### Step 3 — Set driver online

```bash
curl -s -X PUT http://localhost:8080/api/v1/drivers/status \
  -H "Authorization: Bearer $DRIVER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"status": "AVAILABLE"}' | jq .
```

### Step 4 — Start the driver location heartbeat (keep this running in a separate terminal)

This is critical. The driver must be actively posting location for Redis GEO to have a live entry. Open a new terminal and run:

```bash
DRIVER_TOKEN="paste_driver_token_here"

while true; do
  curl -s -X POST http://localhost:8080/api/v1/drivers/location \
    -H "Authorization: Bearer $DRIVER_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "latitude": 37.7749,
      "longitude": -122.4194,
      "heading": 90.0,
      "speed": 30.0
    }' > /dev/null
  echo "Location posted at $(date +%T)"
  sleep 4
done
```

You should see "Location posted at HH:MM:SS" every 4 seconds. Leave this running for all subsequent steps.

### Step 5 — Get a fare estimate

```bash
curl -s -X POST http://localhost:8080/api/v1/rides/estimate \
  -H "Authorization: Bearer $RIDER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "pickupLatitude": 37.7749,
    "pickupLongitude": -122.4194,
    "dropoffLatitude": 37.7849,
    "dropoffLongitude": -122.4094
  }' | jq .
```

**Expected:** `baseFare`, `surgeMultiplier`, `estimatedTotal`, distance, duration. Surge should be 1.0 (no demand yet).

### Step 6 — Request a ride

```bash
curl -s -X POST http://localhost:8080/api/v1/rides/request \
  -H "Authorization: Bearer $RIDER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "pickupLatitude": 37.7749,
    "pickupLongitude": -122.4194,
    "pickupAddress": "123 Market St, SF",
    "dropoffLatitude": 37.7849,
    "dropoffLongitude": -122.4094,
    "dropoffAddress": "456 Mission St, SF"
  }' | jq .
```

**Expected:** `status: DRIVER_ASSIGNED` — driver was found and matched.  
Save the `rideId` → `RIDE_ID`

> If you get `status: REQUESTED` with no driver assigned, the driver location wasn't in Redis or PostGIS yet. Make sure Step 4 ran at least once before this step.

### Step 7 — Driver walks through the ride lifecycle

```bash
# Accept
curl -s -X PUT http://localhost:8080/api/v1/rides/$RIDE_ID/accept \
  -H "Authorization: Bearer $DRIVER_TOKEN" | jq .

# En route to pickup
curl -s -X PUT http://localhost:8080/api/v1/rides/$RIDE_ID/en-route \
  -H "Authorization: Bearer $DRIVER_TOKEN" | jq .

# Arrived at pickup
curl -s -X PUT http://localhost:8080/api/v1/rides/$RIDE_ID/arrived \
  -H "Authorization: Bearer $DRIVER_TOKEN" | jq .

# Start ride
curl -s -X PUT http://localhost:8080/api/v1/rides/$RIDE_ID/start \
  -H "Authorization: Bearer $DRIVER_TOKEN" | jq .

# Complete ride
curl -s -X PUT http://localhost:8080/api/v1/rides/$RIDE_ID/complete \
  -H "Authorization: Bearer $DRIVER_TOKEN" | jq .
```

**Expected after complete:** `status: COMPLETED`, `actualFare` populated.

### Step 8 — Poll driver location during ride (rider side)

Run this while the ride is in progress (between start and complete):

```bash
curl -s -X GET http://localhost:8080/api/v1/rides/$RIDE_ID/driver-location \
  -H "Authorization: Bearer $RIDER_TOKEN" | jq .
```

**Expected:** driver's current lat/lng, heading, speed.

### Step 9 — Check payment was auto-created

```bash
curl -s -X GET "http://localhost:8080/api/v1/payments/history" \
  -H "Authorization: Bearer $RIDER_TOKEN" | jq .
```

**Expected:** one payment record with `status: COMPLETED`, fare breakdown.

### Step 10 — Submit ratings

```bash
# Rider rates driver
curl -s -X POST http://localhost:8080/api/v1/rides/$RIDE_ID/rate \
  -H "Authorization: Bearer $RIDER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"score": 5, "comment": "Great ride!"}' | jq .

# Driver rates rider
curl -s -X POST http://localhost:8080/api/v1/rides/$RIDE_ID/rate \
  -H "Authorization: Bearer $DRIVER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"score": 4, "comment": "Good passenger"}' | jq .
```

**Expected:** both return `201` with rating details. Trying a third rating on the same ride should return `400`.

### Step 11 — Check ride history

```bash
curl -s -X GET "http://localhost:8080/api/v1/rides/history" \
  -H "Authorization: Bearer $RIDER_TOKEN" | jq .
```

---

## Flow 2 — Idempotency Key (Retry Safety)

Proves that double-tapping the request button doesn't create two rides.

```bash
# Send the same ride request twice with the same Idempotency-Key
# First call — creates the ride
curl -s -X POST http://localhost:8080/api/v1/rides/request \
  -H "Authorization: Bearer $RIDER_TOKEN" \
  -H "Idempotency-Key: test-key-001" \
  -H "Content-Type: application/json" \
  -d '{
    "pickupLatitude": 37.7749,
    "pickupLongitude": -122.4194,
    "pickupAddress": "123 Market St, SF",
    "dropoffLatitude": 37.7849,
    "dropoffLongitude": -122.4094,
    "dropoffAddress": "456 Mission St, SF"
  }' | jq .

# Second call — same key, returns the exact same ride (no new ride created)
curl -s -X POST http://localhost:8080/api/v1/rides/request \
  -H "Authorization: Bearer $RIDER_TOKEN" \
  -H "Idempotency-Key: test-key-001" \
  -H "Content-Type: application/json" \
  -d '{
    "pickupLatitude": 37.7749,
    "pickupLongitude": -122.4194,
    "pickupAddress": "123 Market St, SF",
    "dropoffLatitude": 37.7849,
    "dropoffLongitude": -122.4094,
    "dropoffAddress": "456 Mission St, SF"
  }' | jq .
```

**Expected:** both responses return the same `rideId`. No duplicate ride in the DB.

> **Note:** Complete or cancel the previous ride before running this — a rider can't have two active rides.

---

## Flow 3 — State Machine Validation

Proves invalid transitions are rejected.

```bash
# Try to complete a ride that hasn't started yet
curl -s -X PUT http://localhost:8080/api/v1/rides/$RIDE_ID/complete \
  -H "Authorization: Bearer $DRIVER_TOKEN" | jq .
```

**Expected:** `400 Bad Request` — "Invalid state transition".

```bash
# Try to cancel a completed ride
curl -s -X PUT http://localhost:8080/api/v1/rides/$RIDE_ID/cancel \
  -H "Authorization: Bearer $RIDER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Changed my mind"}' | jq .
```

**Expected:** `400 Bad Request` — cannot cancel a completed ride.

---

## Flow 4 — Cancellation

```bash
# Register fresh users, start location heartbeat, request a ride, then cancel it
curl -s -X PUT http://localhost:8080/api/v1/rides/$RIDE_ID/cancel \
  -H "Authorization: Bearer $RIDER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason": "Changed plans"}' | jq .
```

**Expected:** `status: CANCELLED`, `cancellationReason` populated, driver status back to `AVAILABLE`.

---

## Flow 5 — Redis Fast Path vs PostGIS Fallback

This verifies that matching uses Redis when the driver is active, and falls back to PostGIS when evicted.

### Redis fast path (driver is live)

Make sure the location heartbeat from Step 4 is running. Request a ride immediately — matching will use Redis GEO.

Verify in Redis:

```bash
docker exec -it rideshare-redis redis-cli
> GEOSEARCH drivers:active FROMLONLAT -122.4194 37.7749 BYRADIUS 5 km ASC
```

**Expected:** your driver's UUID appears in the results.

### PostGIS fallback (driver evicted from Redis)

1. Stop the location heartbeat script (Ctrl+C)
2. Wait 35 seconds for the TTL to expire
3. Check Redis — driver should be gone:

```bash
docker exec -it rideshare-redis redis-cli
> GEOSEARCH drivers:active FROMLONLAT -122.4194 37.7749 BYRADIUS 5 km ASC
```

**Expected:** empty result.

4. Request a ride — matching falls back to PostGIS (driver location still in DB):

```bash
curl -s -X POST http://localhost:8080/api/v1/rides/request \
  -H "Authorization: Bearer $RIDER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "pickupLatitude": 37.7749,
    "pickupLongitude": -122.4194,
    "pickupAddress": "123 Market St, SF",
    "dropoffLatitude": 37.7849,
    "dropoffLongitude": -122.4094,
    "dropoffAddress": "456 Mission St, SF"
  }' | jq .
```

**Expected:** ride still gets matched (`DRIVER_ASSIGNED`) — PostGIS fallback worked.  
You'll also see a warn log in the app: `"Redis GEO query failed, falling back to PostGIS"`.

---

## Flow 6 — Surge Pricing Trigger

Surge kicks in when demand (ride requests) outpaces supply (available drivers) in a zone.

Make several ride requests in quick succession from the same area (within 60 seconds, before Redis counters expire):

```bash
for i in {1..5}; do
  curl -s -X POST http://localhost:8080/api/v1/rides/estimate \
    -H "Authorization: Bearer $RIDER_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "pickupLatitude": 37.7749,
      "pickupLongitude": -122.4194,
      "dropoffLatitude": 37.7849,
      "dropoffLongitude": -122.4094
    }' | jq '.data.surgeMultiplier'
  sleep 1
done
```

Then check Redis directly to see the demand counter:

```bash
docker exec -it rideshare-redis redis-cli
> KEYS surge:*
> GET surge:zone:-12242:3777:demand
```

**Expected:** demand counter increments with each request. `surgeMultiplier` rises above 1.0 as demand accumulates.

---

## Flow 7 — PostGIS Spatial Index Verification (EXPLAIN ANALYZE)

This validates the sub-10ms geospatial claim. Requires seed data first.

### Step 1 — Seed 500 driver locations

Connect to Postgres:

```bash
docker exec -it rideshare-postgres psql -U rideshare -d rideshare
```

First, insert 500 fake users (needed for FK constraint):

```sql
INSERT INTO users (id, email, password_hash, first_name, last_name, phone, role, created_at, updated_at)
SELECT
  gen_random_uuid(),
  'driver_seed_' || i || '@test.com',
  'hash',
  'Driver',
  i::text,
  '+1000000' || LPAD(i::text, 4, '0'),
  'DRIVER',
  NOW(),
  NOW()
FROM generate_series(1, 500) AS i;
```

Then seed their driver locations scattered around San Francisco:

```sql
INSERT INTO driver_locations (id, driver_id, location, heading, speed, updated_at)
SELECT
  gen_random_uuid(),
  u.id,
  ST_SetSRID(
    ST_MakePoint(
      -122.4194 + (random() - 0.5) * 0.2,
      37.7749  + (random() - 0.5) * 0.2
    ),
    4326
  ),
  random() * 360,
  random() * 60,
  NOW()
FROM users u
WHERE u.email LIKE 'driver_seed_%'
ON CONFLICT (driver_id) DO NOTHING;
```

Also insert driver profiles so the status filter works:

```sql
INSERT INTO driver_profiles (id, user_id, status, rating, total_rides, created_at, updated_at)
SELECT
  gen_random_uuid(),
  u.id,
  'AVAILABLE',
  4.5,
  10,
  NOW(),
  NOW()
FROM users u
WHERE u.email LIKE 'driver_seed_%'
ON CONFLICT DO NOTHING;
```

### Step 2 — Run EXPLAIN ANALYZE

```sql
EXPLAIN ANALYZE
SELECT dl.* FROM driver_locations dl
JOIN driver_profiles dp ON dp.user_id = dl.driver_id
WHERE ST_DWithin(
  dl.location::geography,
  ST_MakePoint(-122.4194, 37.7749)::geography,
  3000
)
AND dp.status = 'AVAILABLE'
ORDER BY ST_Distance(
  dl.location::geography,
  ST_MakePoint(-122.4194, 37.7749)::geography
);
```

**What to look for in the output:**
- `Index Scan using idx_driver_locations_spatial` — confirms GiST index is being used
- `Execution Time: X ms` — should be well under 10ms with 500 rows

### Step 3 — Cleanup seed data (optional)

```sql
DELETE FROM driver_locations WHERE driver_id IN (
  SELECT id FROM users WHERE email LIKE 'driver_seed_%'
);
DELETE FROM driver_profiles WHERE user_id IN (
  SELECT id FROM users WHERE email LIKE 'driver_seed_%'
);
DELETE FROM users WHERE email LIKE 'driver_seed_%';
```

---

## Quick Reference — What Each Flow Proves

| Flow | What It Validates |
|---|---|
| Flow 1 | Full end-to-end: Kafka pipeline, matching, state machine, payment, ratings |
| Flow 2 | Idempotency key prevents duplicate rides on retry |
| Flow 3 | State machine rejects invalid transitions |
| Flow 4 | Cancellation releases driver and updates ride correctly |
| Flow 5 | Redis GEO fast path + automatic PostGIS fallback on eviction |
| Flow 6 | Surge pricing triggers based on real-time demand counters |
| Flow 7 | GiST spatial index used for proximity queries, sub-10ms confirmed |
