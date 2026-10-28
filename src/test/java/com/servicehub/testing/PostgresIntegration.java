package com.servicehub.testing;

import java.lang.annotation.*;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag("postgres")
@Import(PostgresContainerConfiguration.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect")
public @interface PostgresIntegration { }
