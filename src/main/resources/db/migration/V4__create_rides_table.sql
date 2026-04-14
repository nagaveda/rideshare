CREATE TABLE rides (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rider_id UUID NOT NULL REFERENCES users (id),
    driver_id UUID REFERENCES users (id),
    status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED', 'DRIVER_ASSIGNED', 'DRIVER_EN_ROUTE', 'ARRIVED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    pickup_location GEOMETRY(Point, 4326) NOT NULL,
    dropoff_location GEOMETRY(Point, 4326) NOT NULL,
    pickup_address VARCHAR(500),
    dropoff_address VARCHAR(500),
    estimated_fare DECIMAL(10, 2),
    actual_fare DECIMAL(10, 2),
    surge_multiplier DECIMAL(4, 2) DEFAULT 1.00,
    distance_km DECIMAL(8, 2),
    duration_minutes DECIMAL(8, 2),
    requested_at TIMESTAMP NOT NULL DEFAULT NOW(),
    accepted_at TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    cancellation_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rides_rider_id ON rides (rider_id);
CREATE INDEX idx_rides_driver_id ON rides (driver_id);
CREATE INDEX idx_rides_status ON rides (status);
CREATE INDEX idx_rides_pickup_spatial ON rides USING GIST (pickup_location);
