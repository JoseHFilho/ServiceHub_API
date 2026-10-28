package com.servicehub;

import com.servicehub.testing.PostgresIntegration;

/** Includes real JWT, all GraphQL operations, REST interoperability and SQL-count assertions. */
@PostgresIntegration
class PostgresGraphQlIT extends GraphQlIntegrationTests { }
