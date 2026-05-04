ALTER TABLE rides ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE rides ADD COLUMN idempotency_key VARCHAR(64);
CREATE UNIQUE INDEX idx_rides_idempotency_key ON rides (rider_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
