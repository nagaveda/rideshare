CREATE TABLE ride_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ride_id UUID NOT NULL REFERENCES rides (id),
    rider_id UUID NOT NULL REFERENCES users (id),
    driver_id UUID REFERENCES users (id),
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('REQUESTED', 'DRIVER_ASSIGNED', 'DRIVER_EN_ROUTE', 'ARRIVED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ride_events_ride_id ON ride_events (ride_id);
CREATE INDEX idx_ride_events_rider_id ON ride_events (rider_id);
CREATE INDEX idx_ride_events_driver_id ON ride_events (driver_id);
CREATE INDEX idx_ride_events_created_at ON ride_events (created_at);
