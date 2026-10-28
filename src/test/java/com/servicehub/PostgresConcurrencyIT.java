package com.servicehub;

import com.servicehub.testing.PostgresIntegration;

/** The existing transaction races, executed against an automatically managed PostgreSQL. */
@PostgresIntegration
class PostgresConcurrencyIT extends BusinessConcurrencyIntegrationTests { }
