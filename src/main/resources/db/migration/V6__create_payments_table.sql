CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ride_id UUID NOT NULL UNIQUE REFERENCES rides (id),
    rider_id UUID NOT NULL REFERENCES users (id),
    driver_id UUID NOT NULL REFERENCES users (id),
    amount DECIMAL(10, 2) NOT NULL,
    base_fare DECIMAL(10, 2) NOT NULL,
    surge_amount DECIMAL(10, 2) DEFAULT 0.00,
    payment_method VARCHAR(20) NOT NULL DEFAULT 'CASH' CHECK (payment_method IN ('CASH', 'CARD')),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'REFUNDED')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payments_ride_id ON payments (ride_id);
CREATE INDEX idx_payments_rider_id ON payments (rider_id);
CREATE INDEX idx_payments_driver_id ON payments (driver_id);
