CREATE TABLE ride_route_points (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ride_id UUID NOT NULL REFERENCES rides (id),
    location GEOMETRY(Point, 4326) NOT NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ride_route_points_ride_id ON ride_route_points (ride_id);
CREATE INDEX idx_ride_route_points_spatial ON ride_route_points USING GIST (location);
