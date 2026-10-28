-- Synthetic data only, loaded exclusively by PostgresQueryBenchmark in its own container.
-- Fixed timestamps and arithmetic distributions make before/after results comparable.
INSERT INTO users(id,full_name,email,password_hash,role,is_active,created_at,updated_at)
SELECT g, 'Synthetic ' || g, 'synthetic-' || g || '@benchmark.invalid',
       'not-a-login-hash', CASE WHEN g <= 3000 THEN 'PROVIDER' ELSE 'CLIENT' END,
       g % 20 <> 0, TIMESTAMP '2025-01-01', TIMESTAMP '2025-01-01'
FROM generate_series(1,30000) g;

INSERT INTO services(id,title,description,price,category,provider_id,is_active,created_at,updated_at)
SELECT g, 'Synthetic service ' || g, repeat('Synthetic description. ',5),
       50 + (g % 450),
       CASE WHEN g % 100 = 1 THEN 'CONSULTORIA_TI'
            WHEN g % 100 BETWEEN 2 AND 10 THEN 'ELETRICA' ELSE 'LIMPEZA' END,
       CASE WHEN g <= 3000 THEN 1 ELSE 2 + ((g * 37) % 2999) END,
       g % 5 <> 0, TIMESTAMP '2025-01-01', TIMESTAMP '2025-01-01'
FROM generate_series(1::bigint,60000) g;

INSERT INTO service_requests(id,service_id,client_id,status,total_price,notes,created_at,updated_at)
SELECT g, 1 + ((g * 7919 + (g / 60000) * 104729) % 60000),
       3001 + ((g * 1543 + (g / 26998) * 193) % 26998),
       CASE WHEN g % 100 < 10 THEN 'PENDING'
            WHEN g % 100 < 15 THEN 'ACCEPTED'
            WHEN g % 100 < 20 THEN 'IN_PROGRESS'
            WHEN g % 100 < 80 THEN 'COMPLETED' ELSE 'CANCELLED' END,
       50 + (g % 450), 'Synthetic historical request',
       TIMESTAMP '2025-01-01' + (g % 365) * INTERVAL '1 day',
       TIMESTAMP '2025-01-01' + (g % 365) * INTERVAL '1 day'
FROM generate_series(1::bigint,600000) g;

INSERT INTO reviews(id,request_id,reviewer_id,rating,comment,created_at)
SELECT row_number() OVER (ORDER BY id), id, client_id,
       3 + (id % 3), 'Synthetic review', created_at
FROM service_requests WHERE status = 'COMPLETED' AND id % 2 = 0;
