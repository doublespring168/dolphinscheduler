# CLAUDE.md — gyyun-authentication

Parent POM grouping two unrelated authentication helpers. They share no code — they're bundled here because both are about injecting security behavior into the server stack.

**This directory is a Maven parent POM.**

## Sub-modules

- **`gyyun-actuator-authentication`** — Secures Spring Boot Actuator endpoints (`/actuator/**` and `/gyyun/actuator/**`) with HTTP Basic auth. Ships `ActuatorAuthenticationAutoConfiguration` + `ActuatorSecurityProperties` (`management.security.*` properties). Enabled when `management.security.enabled=true`. Excludable endpoints (health/info) via config list.
- **`gyyun-aws-authentication`** — AWS credential provider abstraction used by AWS-based datasource/task plugins (EMR, S3, SageMaker, DMS, DataSync, etc.). Exposes `AWSCredentialsProviderFactor` with two strategies: `STATIC` (access key + secret) and `INSTANCE_PROFILE` (EC2/EKS IAM role).

## Gotchas

- **These two sub-modules are unrelated.** Don't treat "authentication" as a coherent module — the actuator one is about operator access; the AWS one is about cloud-task credentials. They don't share any class.
- **API login auth is NOT here**. Session/password/LDAP/OIDC/CASDOOR login for the main API lives in `gyyun-api/src/main/java/org/apache/gyyun/api/security/`. If a user says "auth", check which one they mean first.
- Actuator: the sample config uses `{noop}` password encoder. This is **development-only**; production must switch to `{bcrypt}` or an external IdP.
- AWS: `STATIC` credentials sit in plaintext config. Always prefer `INSTANCE_PROFILE` on real infrastructure. When reading code that takes an `AwsConfig` map, expect either style.

## Tests

Minimal — auto-config wiring only. End-to-end auth is covered by `gyyun-api-test`.

## Related modules

- `gyyun-meter` — exposes the actuator endpoints that `-actuator-authentication` secures.
- `gyyun-task-plugin` / `-datasource-plugin` AWS members — consume `-aws-authentication`.
- `gyyun-api` — for the real user-login code path (separate from this module).
