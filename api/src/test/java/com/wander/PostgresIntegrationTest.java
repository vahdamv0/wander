package com.wander;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * Tests run against real Postgres in a container, not H2.
 *
 * The migrations use Postgres-specific SQL (identity columns, a functional
 * unique index on LOWER(email)), and a test DB that cannot run them would be
 * testing a schema that does not exist in production.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = PostgresContainerInitializer.class)
@ActiveProfiles("test")
public @interface PostgresIntegrationTest {
}
