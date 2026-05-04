# API Documentation

Base URL: `/api/v1`

**Authentication:** All endpoints except `/auth/register` and `/auth/login` require a JWT bearer token:
```
Authorization: Bearer <token>
```

All responses follow the standard wrapper:
```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "timestamp": "2026-04-13T10:30:00Z"
}
```

---

## Authentication

### POST /auth/register
Register a new user (rider or driver).

**Request:**
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

**Response:** `201 Created`
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "userId": "uuid",
  "email": "john@example.com",
  "role": "RIDER"
}
```

### POST /auth/login
Authenticate and receive JWT.

**Request:**
```json
{
  "email": "john@example.com",
  "password": "securePassword123"
}
```

**Response:** `200 OK`
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "userId": "uuid",
  "email": "john@example.com",
  "role": "DRIVER"
}
```

---

## Driver

All driver endpoints require JWT with role `DRIVER`.

### GET /drivers/profile
Get authenticated driver's profile.

### PUT /drivers/profile
Update driver profile (vehicle details, license).

**Request:**
```json
{
  "vehicleMake": "Toyota",
  "vehicleModel": "Camry",
  "vehicleYear": 2022,
  "vehicleColor": "Black",
  "licensePlate": "ABC1234",
  "licenseNumber": "DL123456"
}
```

### PUT /drivers/status
Go online or offline.

**Request:**
```json
{
  "status": "AVAILABLE"
}
```

### POST /drivers/location
Update driver's current GPS location. Called every 3-5 seconds by the driver app.

**Request:**
```json
{
  "latitude": 37.7749,
  "longitude": -122.4194,
  "heading": 180.0,
  "speed": 35.5
}
```

**Response:** `200 OK`

---

## Rider

All rider endpoints require JWT with role `RIDER`.

### GET /riders/profile
Get authenticated rider's profile.

### PUT /riders/profile
Update rider profile (name, phone).

---

## Rides

### POST /rides/estimate
Get fare estimate before requesting a ride. Requires role `RIDER`.

**Request:**
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

### POST /rides/request
Request a new ride. Requires role `RIDER`.

**Headers:**
| Header | Required | Description |
|---|---|---|
| `Idempotency-Key` | No | Client-generated unique key (max 64 chars). If provided and a ride with this key already exists for the rider, the original ride is returned instead of creating a new one. Use to safely retry on network failures. |

**Request:**
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

**Response:** `201 Created`
```json
{
  "rideId": "uuid",
  "status": "REQUESTED",
  "estimatedFare": 18.75,
  "surgeMultiplier": 1.5,
  "pickupAddress": "123 Market St, SF",
  "dropoffAddress": "456 Mission St, SF"
}
```

### GET /rides/{id}
Get ride details. Accessible by the ride's rider or assigned driver.

**Response:**
```json
{
  "rideId": "uuid",
  "status": "DRIVER_EN_ROUTE",
  "riderId": "uuid",
  "driverId": "uuid",
  "driverName": "Jane Smith",
  "vehicleInfo": "Black Toyota Camry - ABC1234",
  "pickupAddress": "123 Market St, SF",
  "dropoffAddress": "456 Mission St, SF",
  "estimatedFare": 18.75,
  "surgeMultiplier": 1.5,
  "requestedAt": "2026-04-13T10:30:00Z",
  "acceptedAt": "2026-04-13T10:30:45Z"
}
```

### PUT /rides/{id}/accept
Driver accepts a ride request. Requires role `DRIVER`.

### PUT /rides/{id}/en-route
Driver starts heading to pickup. Requires role `DRIVER`.

### PUT /rides/{id}/arrived
Driver arrived at pickup location. Requires role `DRIVER`.

### PUT /rides/{id}/start
Ride begins (rider picked up). Requires role `DRIVER`.

### PUT /rides/{id}/complete
Ride completed at destination. Requires role `DRIVER`.

**Response:** includes final fare breakdown.

### PUT /rides/{id}/cancel
Cancel a ride. Allowed by rider (before IN_PROGRESS) or driver (before IN_PROGRESS).

**Request:**
```json
{
  "reason": "Changed plans"
}
```

### GET /rides/{id}/driver-location
Rider polls driver's current location during an active ride.

**Response:**
```json
{
  "latitude": 37.7760,
  "longitude": -122.4180,
  "heading": 90.0,
  "speed": 25.0,
  "updatedAt": "2026-04-13T10:31:00Z"
}
```

### GET /rides/history
Paginated ride history. Returns rides for the authenticated user (rider or driver).

**Query params:** `page` (default 0), `size` (default 20)

---

## Payments

### GET /payments/history
Paginated payment history for the authenticated user. Returns rides they paid for (rider) or earned on (driver).

**Query params:** `page` (default 0), `size` (default 20)

**Response:** `200 OK`
```json
{
  "content": [
    {
      "paymentId": "uuid",
      "rideId": "uuid",
      "riderId": "uuid",
      "driverId": "uuid",
      "amount": 18.75,
      "baseFare": 12.50,
      "surgeAmount": 6.25,
      "paymentMethod": "CASH",
      "status": "COMPLETED",
      "createdAt": "2026-04-13T10:45:00"
    }
  ],
  "totalElements": 42,
  "totalPages": 3,
  "number": 0,
  "size": 20
}
```

---

## Ratings

### POST /rides/{id}/rate
Submit a rating after ride completion. Both rider and driver can rate each other. The ratee is determined automatically from ride membership — caller does not specify who they are rating.

**Request:**
```json
{
  "score": 5,
  "comment": "Great ride!"
}
```

**Response:** `201 Created`
```json
{
  "ratingId": "uuid",
  "rideId": "uuid",
  "raterId": "uuid",
  "rateeId": "uuid",
  "score": 5,
  "comment": "Great ride!",
  "createdAt": "2026-04-13T10:50:00"
}
```
