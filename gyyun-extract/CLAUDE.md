# CLAUDE.md — gyyun-extract

RPC **interface** definitions for inter-server calls (master ↔ worker ↔ alert ↔ api). Contains contracts only — the concrete Spring `@RpcService` implementations live in the caller's module.

**This directory is a Maven parent POM.**

## Sub-modules

- **`gyyun-extract-base`** — RPC transport, framing, serialization; `@RpcService` annotation; `IRpcRequest` / `IRpcResponse`; client/server bootstrap. Everything else depends on this.
- **`gyyun-extract-common`** — contracts usable by every role (e.g. `ILogService` for log fetching).
- **`gyyun-extract-master`** — interfaces callers use to talk **to** master (`ITaskInstanceController`, `IWorkflowControlClient`, `IMasterContainerService`, `IWorkflowMetricService`). Implemented inside `gyyun-master/rpc`.
- **`gyyun-extract-worker`** — interfaces to talk **to** worker (`IPhysicalTaskExecutorOperator`, `IStreamingTaskInstanceOperator`, `ITaskExecutorQueryClient`). Implemented inside `gyyun-worker/rpc`.
- **`gyyun-extract-alert`** — interfaces to talk **to** the alert server. Implemented inside `gyyun-alert-server`.

## The rule

```
A server depends on extract-<otherRole> to CALL that role.
A server depends on extract-<ownRole> to IMPLEMENT its own RPC surface.
```

Example: master depends on `extract-worker` (to dispatch tasks) **and** on `extract-master` (to declare what it implements).

## Gotchas

- **Interface-only**: do not put implementation helpers in `-master`/`-worker`/`-alert`. If a helper is shared, it goes in `-base` or `-common`.
- **Serialization is in `-base`**: changing method signatures in a sub-interface is a wire-protocol change, not just a Java change. Bump carefully — rolling upgrades exist in the wild.
- **`@RpcService` bean discovery**: only Spring beans annotated with `@RpcService` are exported. If a new method doesn't appear on the remote side, check the caller's server module registered the bean.
- Don't add `gyyun-common` dependencies to `-base` — it's kept lean on purpose (like `-spi`).

## Tests

Per sub-module `src/test/java`. Integration-style RPC tests live in the implementing modules (master/worker/alert), not here.

## Related modules

- `gyyun-master` / `-worker` / `-alert-server` — the implementers.
- `gyyun-api` / `-service` — callers (API reaches into master/worker for runtime control).
- `gyyun-microbench` — RPC benchmarks against `extract-base`.
