CREATE TABLE ratings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ride_id UUID NOT NULL REFERENCES rides (id),
    rater_id UUID NOT NULL REFERENCES users (id),
    ratee_id UUID NOT NULL REFERENCES users (id),
    score INT NOT NULL CHECK (score >= 1 AND score <= 5),
    comment VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ratings_ride_rater UNIQUE (ride_id, rater_id)
);

CREATE INDEX idx_ratings_ride_id ON ratings (ride_id);
CREATE INDEX idx_ratings_ratee_id ON ratings (ratee_id);
