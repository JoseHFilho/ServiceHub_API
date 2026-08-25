CREATE TABLE reviews (
    id          BIGSERIAL PRIMARY KEY,
    request_id  BIGINT NOT NULL UNIQUE REFERENCES service_requests(id),
    reviewer_id BIGINT NOT NULL REFERENCES users(id),
    rating      SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment     TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
