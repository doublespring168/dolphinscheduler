# CLAUDE.md — gyyun-task-plugin

Plugin family for **task types** — shell, SQL, Spark, Flink, Python, HTTP, K8s, EMR, DataX, … — executed by the worker. The runtime `gyyun-task-executor` drives lifecycle; each plugin here supplies the "what to actually do" part.

**This directory is a Maven parent POM.**

## Sub-modules

### Framework

- **`gyyun-task-api`** — the SPI and shared types. `TaskChannelFactory`, `TaskChannel`, `AbstractTask`, `TaskExecutionContext`, parameter DTOs. **Plugin authors depend on this**.
- **`gyyun-task-all`** — uber module aggregating every plugin; depended on by `gyyun-worker` so the worker can run any task type.

### Concrete plugins (one sub-module each)

Shell family: `task-shell`, `task-remoteshell`, `task-python`, `task-java`, `task-sql`, `task-procedure`.

Big-data: `task-spark`, `task-flink`, `task-flink-stream`, `task-mr`, `task-hivecli`, `task-seatunnel`, `task-datax`, `task-chunjun`, `task-sqoop`, `task-linkis`.

Cloud: `task-k8s`, `task-kubeflow`, `task-emr`, `task-emr-serverless`, `task-sagemaker`, `task-dms`, `task-datasync`, `task-datafactory`, `task-aliyunserverlessspark`.

ML/Notebook: `task-jupyter`, `task-zeppelin`, `task-dinky`, `task-mlflow`, `task-openmldb`, `task-dvc`.

Network: `task-http`, `task-grpc`.

(See the directory listing for the complete live set.)

## SPI contract

Each plugin ships:

1. A `TaskChannelFactory` implementation annotated with `@AutoService(TaskChannelFactory.class)`. The annotation processor generates `META-INF/services/com.gyyun.ds.plugin.task.api.TaskChannelFactory` at compile time.
2. A `TaskChannel` implementation that returns a concrete `AbstractTask` subclass given an `ITaskExecutionContext`.
3. A parameter DTO + UI-form description (via `PluginParamsTransfer`, inherited from `gyyun-spi`).

## How plugins are discovered

`TaskPluginManager` (in `task-api`) runs a `PrioritySPIFactory<TaskChannelFactory>` at startup; factories register themselves in a `Map<taskType, factory>`. Startup happens inside **`gyyun-api`** (for form metadata) and **`gyyun-worker`** (for actual execution).

## Gotchas

- **`@AutoService`** requires the Google `auto-service` annotation processor configured in the plugin's `pom.xml`. Copy a working sibling exactly.
- **`TaskExecutionContext` carries secrets** (passwords, tokens, access keys). Never log it raw. Every plugin has its own redaction discipline — mirror the neighbors.
- **Plugin naming**: the `name()` a factory returns becomes the task type as stored in the DB. Changing it is a data-migration event.
- **Classpath isolation is not implemented**: every plugin shares the worker's classloader. Version conflicts between plugins (e.g. two plugins pulling different Jackson) must be resolved at the parent `pom.xml`.
- **Do not create a new plugin outside this directory.** The build scripts + `task-all` rely on the directory pattern.
- **Each plugin's `pom.xml` declares its own external deps with precise versions.** Prefer `gyyun-bom` for versions shared with the core; large client libraries (Spark, Flink, Hadoop) are plugin-local.

## Tests

Each plugin has `src/test/java` with mocked clients (e.g., mocked `SparkSubmit`, mocked `HttpClient`). End-to-end task runs are exercised by `gyyun-e2e` with real backends when possible.

## Related modules

- `gyyun-spi` — base SPI.
- `gyyun-task-executor` — the lifecycle framework plugins execute *inside*.
- `gyyun-worker` — loads `task-all` at runtime.
- `gyyun-api` — loads plugin metadata for UI forms (no execution).
- `gyyun-datasource-plugin` — SQL / big-data tasks consume datasource plugins.
- `gyyun-storage-plugin` — artifact storage for task inputs/outputs.
