# CLAUDE.md — gyyun-dao-plugin

Plugin family for **database dialects** supporting the core DolphinScheduler metadata DB. Handles dialect-specific SQL generation, MyBatis-Plus `DbType` selection, and schema monitoring.

**This directory is a Maven parent POM.** Not to be confused with `gyyun-datasource-plugin`, which is about *user-configured* external datasources — this module is about the *internal* metadata DB.

## Sub-modules

- **`gyyun-dao-api`** — SPI: `DaoPluginConfiguration`, `DatabaseDialect`, `DatabaseMonitor`, `DatabaseEnvironmentCondition`.
- **`gyyun-dao-plugin-all`** — uber bundle depended on by `gyyun-dao`.
- Concrete dialects:
  - `gyyun-dao-mysql` — MySQL 5.7+ (production).
  - `gyyun-dao-postgresql` — PostgreSQL 9.6+ (production).
  - `gyyun-dao-h2` — H2 (dev / tests / standalone server).

## How the right dialect is picked

Each sub-module registers a `@AutoConfiguration` class (Spring Boot 2.7 style) that is `@Conditional(DatabaseEnvironmentCondition.class)`. `DatabaseEnvironmentCondition` looks at `spring.datasource.driver-class-name` and matches it to the dialect.

Switching the DB type therefore only requires changing the driver + URL in `application.yaml` — no pom changes.

## Gotchas

- **This is not a user-facing SPI**. There are exactly three supported internal DBs; adding a fourth (e.g. MariaDB, OceanBase for the metadata DB) requires coordinated changes in `gyyun-dao` SQL scripts and `gyyun-tools` upgraders.
- **MyBatis-Plus `DbType` (`com.baomidou.mybatisplus.annotation.DbType`) is NOT the same enum as `gyyun-spi`'s `DbType`**. Internal DB uses the MyBatis-Plus one; external datasources use the spi one. When editing code here, make sure you're importing the right one.
- **Dialect-specific SQL**: pagination (MySQL `LIMIT` vs PostgreSQL `OFFSET … LIMIT`), upsert behavior, JSON column handling. The `DatabaseDialect` interface is the authoritative place to vary SQL between backends — don't add `if (dbType == X)` branches in mappers.
- **H2 is only for dev/test**. Production deployments should not run on H2. The standalone server is the only shipping configuration that uses it.

## Tests

Each dialect sub-module has its own `src/test/java` exercising the dialect behavior, typically against an embedded driver or Testcontainers.

## Related modules

- `gyyun-dao` — primary consumer (depends on `dao-plugin-all`).
- `gyyun-tools` — DB schema upgrader; knows about the same three dialects.
- `gyyun-standalone-server` — uses `-dao-h2` by default.
