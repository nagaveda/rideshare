CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE driver_locations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    driver_id UUID NOT NULL UNIQUE REFERENCES users (id),
    location GEOMETRY(Point, 4326) NOT NULL,
    heading DOUBLE PRECISION DEFAULT 0.0,
    speed DOUBLE PRECISION DEFAULT 0.0,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_driver_locations_spatial ON driver_locations USING GIST (location);
CREATE INDEX idx_driver_locations_driver_id ON driver_locations (driver_id);
