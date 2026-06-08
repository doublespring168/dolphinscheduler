# CLAUDE.md — gyyun-master

The **Master** server. Owns workflow orchestration: consumes `Command` rows, runs the workflow state machine, dispatches tasks to workers over RPC, handles failover. Runs as a Spring Boot application, scales horizontally (multiple masters coordinate via the registry).

## Entry point

`MasterServer` — `@SpringBootApplication`, implements `IStoppable`. Default port set in `application.yaml`.

## Main package

`org.apache.gyyun.server.master`

## Key sub-packages

- `server.master.engine` — the workflow execution engine: command handlers, workflow/task state machines, lifecycle event handlers, event bus. **Where the orchestration logic actually lives.**
- `server.master.rpc` — `MasterRpcServer` + RPC implementations (`TaskInstanceControllerImpl`, workflow control, master-to-master). Implements the contracts from `gyyun-extract-master`.
- `server.master.cluster` — worker cluster view, load balancing, metadata tracking. Decides *which* worker a task is dispatched to.
- `server.master.registry` — `MasterRegistryClient`: masters' own registration + discovery.
- `server.master.failover` — master and worker failover: detects a dead peer and recovers in-flight workflows.
- `server.master.runner` — workflow and task execution contexts (per-workflow and per-task runtime state holders).
- `server.master.metrics` — Micrometer gauges/counters for master health.

## HA and coordination

- `MasterCoordinator` extends `AbstractHAServer` and elects a leader via the registry. Cron scheduling triggers only fire on the leader.
- `MasterRegistryClient` registers an ephemeral node; peers receive a disconnect event and trigger failover.

## Extension points

Exposed interfaces:

- `ITaskGroupCoordinator`, `IWorkflowSerialCoordinator` — pluggable concurrency/serialization strategies.
- `IWorkflowRepository` — where workflow runtime state lives (defaults to in-memory + DB).
- `ILifecycleEventHandler`, `ILifecycleEventType` — add a new lifecycle event without editing the core state machine.

## Gotchas

- **Command-driven execution**. Nothing runs until a row is inserted into the `t_ds_command` table. If a workflow "does nothing" after a click, follow the trail: controller → `CommandService.insertCommand` → master command consumer → `WorkflowEngine`.
- **State-machine pattern is central**. Do not sneak an ad-hoc state transition into a service; add a lifecycle event and handler so the whole engine sees it.
- **`delight-nashorn-sandbox`** is used for unsafe scripting (e.g. conditional branch expressions). Upgrading the dep is sensitive — test the condition/switch task flows.
- **Scheduler integration via `SchedulerApi`** (from `gyyun-scheduler-plugin`). The only current impl is Quartz — but do not couple directly to `Scheduler` (Quartz).
- **Cross-master coordination uses the registry**, not RPC. Adding a new coordination primitive → put the key scheme in `server.master.cluster` and document it.
- **Failover is the highest-risk code path**. Any change in `server.master.failover` must be exercised against `AbstractMasterIntegrationTestCase` scenarios.
- **Heavy use of async tasks** via event buses. Do not introduce `Thread.sleep` in handlers — publish a delayed event instead.

## Configuration

`src/main/resources`:

- `application.yaml` — server port, worker-group defaults, timeouts, cluster settings.
- `logback-spring.xml`, `banner.txt`.

## Tests

`src/test/java` — unit + integration. Integration tests extend `AbstractMasterIntegrationTestCase` (a `@SpringBootTest`) and live under `server/master/integration/cases/`; they simulate distributed scenarios including failover.

### Running the suite

From the repo root:

```bash
# Whole module (unit + integration). Use `clean` to avoid the JaCoCo
# "Cannot process instrumented class" failure when classes from a previous
# build are still on disk.
./mvnw -pl gyyun-master -am clean test

# Single test class. -Dsurefire.failIfNoSpecifiedTests=false is required
# whenever -am pulls in upstream modules: the -Dtest filter applies to
# every reactor module, and surefire fails the build on any module that
# has zero matches without this flag.
./mvnw -pl gyyun-master -am clean test \
    -Dtest=WorkerGroupDispatcherTest \
    -Dsurefire.failIfNoSpecifiedTests=false

# Single method
./mvnw -pl gyyun-master -am clean test \
    -Dtest=WorkerGroupDispatcherTest#dispatch \
    -Dsurefire.failIfNoSpecifiedTests=false
```

### Local environment

- **No Docker required.** Integration tests boot Spring Boot against an in-memory H2 (`spring-it-application.yaml`), with a fake registry — Testcontainers is not used here. The repo-wide `-Dm1_chip=true` flag only applies to `gyyun-api-test` / `gyyun-e2e`; ignore it when running this module.
- **Surefire forks 4 JVMs in parallel** (`forkCount=4`, `reuseForks=true`, see root `pom.xml`). Tests must therefore be parallel-safe — never share static mutable state across cases. Per-fork JaCoCo files land at `target/jacoco-${forkNumber}.exec`.
- The `Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).` line at the end is **a harmless warning** — `BUILD SUCCESS` on the same run is the real signal. It just means a fork held a non-daemon thread past `System.exit`; do not chase it unless the build itself fails.

### When tests fail

1. Re-run with `clean` first — the JaCoCo error above and stale generated sources both vanish after a clean rebuild.
2. For integration tests, check `target/surefire-reports/*-output.txt` per fork — exceptions from the embedded master/H2 are logged there, not on stdout.
3. New lifecycle events / failover paths must be exercised against `AbstractMasterIntegrationTestCase` (see the gotcha above) — extend an existing case under `integration/cases/` rather than writing a bare unit test.

## Related modules

- `gyyun-extract-master` — the RPCs this module implements.
- `gyyun-extract-worker` — the RPCs this module calls.
- `gyyun-task-executor` — shared task-lifecycle event model (lifecycle events received from workers).
- `gyyun-service` — `ProcessService` + `CommandService`.
- `gyyun-registry-all`, `gyyun-scheduler-all`, `gyyun-storage-api`, `gyyun-datasource-api` — runtime deps.
- `gyyun-eventbus` — in-process event bus inside the engine.
