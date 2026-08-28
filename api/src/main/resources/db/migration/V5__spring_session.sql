-- Sessions live in Postgres, not in Tomcat's heap.
--
-- Without this, every restart signs out every user: the session *is* the
-- credential here (an httpOnly cookie, no tokens), so an in-memory store means a
-- deploy logs out the household. It also means live sync cannot survive a
-- restart — the socket reconnects and is told to authenticate again.
--
-- The DDL below is Spring Session's own `schema-postgresql.sql`, copied verbatim
-- rather than hand-written, so an upgrade can be diffed against the version in
-- the jar. Two consequences of it being here at all:
--
--  * `spring.session.jdbc.initialize-schema: never` — Flyway owns the schema in
--    this project, and letting Spring Session create tables behind Flyway's back
--    would leave two things believing they own the same two tables.
--  * Hibernate's `ddl-auto: validate` does not care about them: nothing maps
--    these tables to an entity, and validation only checks what is mapped.
--
-- Unquoted identifiers, so Postgres folds them to lower case — which is what
-- Spring Session's own queries expect from this same script.

CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
