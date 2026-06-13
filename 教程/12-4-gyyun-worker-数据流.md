# gyyun-worker 数据流分析

本文分析 `gyyun-worker` 模块的核心数据流，重点回答几个问题：

- Worker 启动后初始化了哪些组件；
- Worker 如何注册到注册中心，Master 如何看到 Worker 的负载；
- Master 如何把物理任务下发到 Worker；
- Worker 内部如何创建执行器、准备上下文、加载插件并执行任务；
- 任务生命周期事件如何回传 Master，ACK 后如何释放 Worker 资源；
- Kill、Pause、Master 迁移、日志查询、流任务 savepoint 分别走什么通道；
- 本机 IDEA Debug 时应该在哪些类和方法上打断点。

## 1. 总体结论

`gyyun-worker` 是系统的物理任务执行服务，启动类是：

```text
com.gyyun.ds.server.worker.WorkerServer
```

它不是 HTTP API 网关，也不负责编排 DAG。它的核心定位是：

```text
Worker RPC 服务 + Worker 注册心跳 + 物理任务执行引擎 + 任务插件运行时 + 生命周期事件上报器
```

最核心的数据流是：

```text
Master 选择 Worker
  -> RPC: IPhysicalTaskExecutorOperator.dispatchTask(...)
  -> Worker 接收 TaskExecutionContext
  -> PhysicalTaskExecutorFactory 创建 PhysicalTaskExecutor
  -> TaskEngine.submitTask(...)
  -> ExclusiveThreadTaskExecutorContainer 分配执行槽
  -> TaskExecutorEventBus 发布 DISPATCHED
  -> TaskExecutorWorker 启动执行器
  -> 初始化任务上下文、tenant、工作目录、资源文件
  -> TaskPluginManager 根据 taskType 创建 AbstractTask
  -> AbstractTask.handle(...) 真正执行任务
  -> 定时 track 插件状态
  -> 发布 RUNNING / RUNTIME_CONTEXT_CHANGE / SUCCESS / FAILED / KILLED / PAUSED
  -> PhysicalTaskExecutorLifecycleEventReporter 上报 Master
  -> Master 处理事件并 ACK Worker
  -> Worker 收到终态 ACK 后发布 FINALIZE
  -> 移除执行器、释放执行槽、推送远程日志、清理工作目录
```

一句话概括：

```text
Worker 只执行 Master 已经编排好的物理任务；任务状态不由 Worker 直接写数据库，而是通过生命周期事件回传 Master，再由 Master 更新任务实例和推动 DAG。
```

## 2. 启动数据流

入口文件：

```text
gyyun-worker/src/main/java/com/gyyun/ds/server/worker/WorkerServer.java
```

`WorkerServer.run()` 的启动顺序如下：

```text
ServerLifeCycleManager.toRunning()
  -> WorkerRpcServer.start()
  -> TaskPluginManager.loadTaskPlugin()
  -> DataSourcePluginManager.loadDataSourcePlugin()
  -> WorkerRegistryClient.setRegistryStoppable(this)
  -> WorkerRegistryClient.start()
       -> 删除旧 worker 临时节点
       -> 写入新的 worker 临时节点和初始心跳
       -> 添加注册中心连接状态监听
       -> WorkerHeartBeatTask.start()
  -> PhysicalTaskEngineDelegator.start()
       -> TaskEngine.start()
       -> PhysicalTaskExecutorLifecycleEventReporter.start()
  -> 注册 Worker metrics
  -> 注册 shutdown hook
```

几个关键点：

| 组件 | 作用 |
| --- | --- |
| `WorkerRpcServer` | 启动 Netty RPC，监听 Worker RPC 端口 |
| `TaskPluginManager` | 加载任务插件，Worker 创建 `AbstractTask` 时依赖它 |
| `DataSourcePluginManager` | 加载数据源插件，任务插件运行时可能使用 |
| `WorkerRegistryClient` | 将 Worker 注册为 ZooKeeper 临时节点，并启动心跳线程 |
| `WorkerHeartBeatTask` | 周期性写入 Worker 心跳和负载信息 |
| `PhysicalTaskEngineDelegator` | Worker 物理任务引擎的门面 |
| `TaskEngine` | 通用任务执行引擎，负责 submit、kill、pause、query |
| `PhysicalTaskExecutorLifecycleEventReporter` | 将 Worker 本地生命周期事件远程上报给 Master |

Worker 停止时：

```text
WorkerServer.close(...)
  -> ServerLifeCycleManager.toStopped()
  -> PhysicalTaskEngineDelegator.close()
  -> WorkerRpcServer.close()
  -> WorkerRegistryClient.close()
       -> WorkerHeartBeatTask.shutdown()
       -> registryClient.close()
```

## 3. 关键配置

配置文件：

```text
gyyun-worker/src/main/resources/application.yaml
```

核心配置：

```yaml
server:
  port: 1235

worker:
  listen-port: 1234
  max-heartbeat-interval: 10s
  host-weight: 100
  group: default
  server-load-protection:
    enabled: true
    max-system-cpu-usage-percentage-thresholds: 0.7
    max-jvm-cpu-usage-percentage-thresholds: 0.7
    max-system-memory-usage-percentage-thresholds: 0.7
    max-disk-usage-percentage-thresholds: 0.7
  physical-task-config:
    task-executor-thread-size: 100
  tenant-config:
    auto-create-tenant-enabled: true
    default-tenant-enabled: false

registry:
  type: zookeeper
  zookeeper:
    namespace: gyyun
    connect-string: localhost:4206
```

端口和地址含义：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `worker.listen-port` | `1234` | Worker RPC 端口，Master 通过它下发、停止、查询任务 |
| `server.port` | `1235` | Spring Boot HTTP/actuator 端口 |
| `worker.group` | `default` | Worker 所属分组，Master 按任务的 workerGroup 选择 Worker |
| `worker.host-weight` | `100` | Worker 权重，写入心跳，供 Master 负载均衡使用 |
| `worker.max-heartbeat-interval` | `10s` | 心跳最大写入间隔 |
| `worker.physical-task-config.task-executor-thread-size` | `CPU * 2 + 1`，配置中覆盖为 `100` | 本 Worker 可同时执行的物理任务槽位数 |

`WorkerConfig` 启动校验时会计算：

```text
workerAddress = host:worker.listen-port
workerRegistryPath = /workers/{workerAddress}
```

如果 IDEA 里调试多 Worker，要保证每个 Worker 的 `worker.listen-port` 不冲突。

注意：

```text
gyyun-worker 排除了 DataSourceAutoConfiguration。
Worker 自身不是业务数据库访问服务，任务实例状态更新主要由 Master 完成。
Worker 加载 DataSourcePlugin 是为了任务插件运行时访问外部数据源。
```

## 4. 注册中心和心跳数据流

注册入口：

```text
gyyun-worker/src/main/java/com/gyyun/ds/server/worker/registry/WorkerRegistryClient.java
```

注册流程：

```text
WorkerRegistryClient.start()
  -> registry()
       -> registryClient.remove(workerRegistryPath)
       -> registryClient.persistEphemeral(workerRegistryPath, firstHeartBeatJson)
       -> 等待注册中心确认节点存在
       -> WorkerHeartBeatTask.start()
  -> registryClient.addConnectionStateListener(new WorkerConnectionStateListener(...))
```

心跳生成：

```text
WorkerHeartBeatTask.getHeartBeat()
  -> MetricsProvider.getSystemMetrics()
  -> 读取 CPU / JVM CPU / JVM 内存 / 系统内存 / 磁盘
  -> 读取 taskExecutorContainer.slotUsage()
  -> WorkerServerLoadProtection.isOverload(...)
  -> 生成 WorkerHeartBeat
```

心跳写入：

```text
WorkerHeartBeatTask.writeHeartBeat(...)
  -> 如果 failover 节点存在，说明当前 Worker 已被 failover，主动 stop
  -> JSON 序列化 WorkerHeartBeat
  -> registryClient.persistEphemeral(workerRegistryPath, workerHeartBeatJson)
```

心跳里的关键字段：

| 字段 | 来源 | 作用 |
| --- | --- | --- |
| `host` / `port` | `NetUtils.getHost()` / `worker.listen-port` | Master 通过它得到 Worker RPC 地址 |
| `workerGroup` | `worker.group` | Master 只在指定 worker group 内选择 Worker |
| `workerHostWeight` | `worker.host-weight` | 参与 Master 侧负载均衡 |
| `threadPoolUsage` | `taskExecutorContainer.slotUsage()` | 当前执行槽占用率 |
| `serverStatus` | `WorkerServerLoadProtection` | `NORMAL` 或 `BUSY` |
| `cpuUsage` / `memoryUsage` / `diskUsage` | `MetricsProvider` | Master 判断 Worker 可用性和负载 |
| `processId` | `OSUtils.getProcessID()` | 运维定位进程 |

负载保护入口：

```text
gyyun-worker/src/main/java/com/gyyun/ds/server/worker/config/WorkerServerLoadProtection.java
```

判断逻辑：

```text
server-load-protection.enabled = false
  -> 永远不标记 BUSY

enabled = true
  -> 系统 CPU / JVM CPU / 系统内存 / 磁盘超过阈值，标记 BUSY
  -> 或 taskExecutorContainer.slotUsage() == 1，标记 BUSY
```

这意味着：

```text
Worker 已满槽时，即使 CPU/内存不高，心跳也会变成 BUSY。
Master 侧调度是否继续选择它，取决于 Master 的 Worker 负载均衡和过滤逻辑。
```

## 5. Worker 对外 RPC 入口

RPC 服务端：

```text
gyyun-worker/src/main/java/com/gyyun/ds/server/worker/rpc/WorkerRpcServer.java
```

`WorkerRpcServer` 继承 `SpringServerMethodInvokerDiscovery`，会把 Spring 容器里的 RPC 服务实现暴露出去。

主要 RPC 实现：

| 实现类 | 契约 | 作用 |
| --- | --- | --- |
| `PhysicalTaskExecutorOperatorImpl` | `IPhysicalTaskExecutorOperator` | Master 下发、停止、暂停、迁移、ACK 物理任务 |
| `PhysicalTaskExecutorQueryClient` | `ITaskExecutorQueryClient` | 查询当前 Worker 正在运行的执行器 |
| `WorkerLogServiceImpl` | `ILogService` | 分页读取、下载、删除本机任务日志 |
| `StreamingTaskInstanceOperatorImpl` | `IStreamingTaskInstanceOperator` | 对流任务触发 savepoint |

最重要的接口是：

```text
gyyun-extract/gyyun-extract-worker/src/main/java/com/gyyun/ds/extract/worker/IPhysicalTaskExecutorOperator.java
```

方法含义：

| 方法 | 调用方 | Worker 行为 |
| --- | --- | --- |
| `dispatchTask(TaskExecutorDispatchRequest)` | Master | 接收 `TaskExecutionContext`，创建并提交 `PhysicalTaskExecutor` |
| `killTask(TaskExecutorKillRequest)` | Master | 按 taskInstanceId 找到执行器，发布 KILL 事件 |
| `pauseTask(TaskExecutorPauseRequest)` | Master | 按 taskInstanceId 找到执行器，发布 PAUSE 事件 |
| `reassignWorkflowInstanceHost(TaskExecutorReassignMasterRequest)` | 新 Master | 更新执行器里的 `workflowInstanceHost`，后续事件上报给新 Master |
| `ackPhysicalTaskExecutorLifecycleEvent(TaskExecutorLifecycleEventAck)` | Master | ACK 某个生命周期事件，Worker 移除待 ACK 事件 |

## 6. Master 下发任务到 Worker

Master 侧发起点：

```text
gyyun-master/src/main/java/com/gyyun/ds/server/master/engine/task/client/PhysicalTaskExecutorClientDelegator.java
```

下发流程：

```text
WorkerGroupDispatcher
  -> TaskExecutorClient.dispatch(taskExecution)
  -> PhysicalTaskExecutorClientDelegator.dispatch(taskExecution)
       -> 从 taskExecutionContext 取 workerGroup
       -> ClusterManager 判断 workerGroup 是否存在
       -> IWorkerLoadBalancer.select(workerGroup) 选择 Worker
       -> taskExecutionContext.setHost(workerAddress)
       -> taskInstance.setHost(workerAddress)
       -> Clients.withService(IPhysicalTaskExecutorOperator.class)
             .withHost(workerAddress)
             .dispatchTask(TaskExecutorDispatchRequest.of(taskExecutionContext))
```

Worker 侧接收点：

```text
gyyun-worker/src/main/java/com/gyyun/ds/server/worker/rpc/PhysicalTaskExecutorOperatorImpl.java
```

Worker 处理流程：

```text
PhysicalTaskExecutorOperatorImpl.dispatchTask(...)
  -> request.getTaskExecutionContext()
  -> physicalTaskEngineDelegator.dispatchLogicTask(taskExecutionContext)
  -> 返回 TaskExecutorDispatchResponse.success()
```

这里的方法名叫 `dispatchLogicTask`，但在 Worker 模块中实际提交的是物理任务执行器：

```text
PhysicalTaskEngineDelegator.dispatchLogicTask(...)
  -> physicalTaskExecutorFactory.createTaskExecutor(taskExecutionContext)
  -> taskEngine.submitTask(taskExecutor)
```

下发失败时：

```text
WorkerGroupDispatcher.doDispatchTask(...)
  -> 捕获 dispatch 异常
  -> dispatchFailTimes + 1
  -> 按 1s 到 60s 递增延迟重新入队
  -> 如果启用 dispatch timeout 并超过最大时长，发布 TaskFailedLifecycleEvent
```

所以：

```text
Master RPC 调 Worker 成功，只代表 Worker 已接受任务并提交到本地执行引擎。
任务是否真正完成，要看 Worker 后续回传的生命周期事件。
```

## 7. Worker 本地执行器创建

工厂入口：

```text
gyyun-worker/src/main/java/com/gyyun/ds/server/worker/executor/PhysicalTaskExecutorFactory.java
```

创建流程：

```text
PhysicalTaskExecutorFactory.createTaskExecutor(taskExecutionContext)
  -> assemblyTaskLogPath(taskExecutionContext)
       -> taskExecutionContext.setLogPath(LogUtils.getTaskInstanceLogFullPath(...))
  -> PhysicalTaskExecutorBuilder.builder()
       .taskExecutionContext(taskExecutionContext)
       .workerConfig(workerConfig)
       .storageOperator(storageOperator)
       .physicalTaskPluginFactory(physicalTaskPluginFactory)
       .taskExecutorEventBus(new TaskExecutorEventBus())
  -> new PhysicalTaskExecutor(...)
```

`TaskExecutionContext` 是 Worker 运行时最核心的数据载体。Master 下发时已经包含任务定义、任务实例、工作流实例、参数、workerGroup、tenant、workflowInstanceHost 等信息；Worker 会继续补充：

| 字段 | Worker 写入位置 | 说明 |
| --- | --- | --- |
| `host` | Master 下发前写入 | 实际执行该任务的 Worker 地址 |
| `logPath` | `PhysicalTaskExecutorFactory` | 本地任务日志路径 |
| `startTime` | `AbstractTaskExecutor.initializeTaskContext()` | 执行器开始时间 |
| `taskAppId` | `PhysicalTaskExecutor.initializeTaskContext()` | 默认写为 taskInstanceId |
| `tenantCode` | `TenantUtils.getOrCreateActualTenant(...)` | 最终执行任务的系统用户 |
| `executePath` | `TaskExecutionContextUtils.createTaskInstanceWorkingDirectory(...)` | 本地任务工作目录 |
| `appInfoPath` | `TaskExecutionContextUtils.createTaskInstanceWorkingDirectory(...)` | 应用信息文件路径 |
| `resourceContext` | `TaskExecutionContextUtils.downloadResourcesIfNeeded(...)` | 下载后的资源文件映射 |
| `appIds` | 任务插件回调 | Yarn/Flink 等外部应用 ID |
| `endTime` | `TaskExecutorWorker` 发现终态时写入 | 任务结束时间 |

## 8. 执行槽和 TaskEngine 数据流

Worker 使用的物理任务引擎：

```text
gyyun-worker/src/main/java/com/gyyun/ds/server/worker/executor/PhysicalTaskEngineFactory.java
```

创建出来的是通用 `TaskEngine`：

```text
TaskEngineBuilder
  -> engineName = PhysicalTaskEngine
  -> taskExecutorRepository = PhysicalTaskExecutorRepository
  -> taskExecutorContainerDelegator = PhysicalTaskExecutorContainerProvider
  -> taskExecutorEventBusCoordinator = PhysicalTaskExecutorEventBusCoordinator
```

执行容器：

```text
gyyun-worker/src/main/java/com/gyyun/ds/server/worker/executor/PhysicalTaskExecutorContainerProvider.java
```

它创建的是：

```text
ExclusiveThreadTaskExecutorContainer
```

含义是：

```text
每个 PhysicalTaskExecutor 独占一个 TaskExecutorWorker。
可并发任务数 = worker.physical-task-config.task-executor-thread-size。
```

提交任务：

```text
TaskEngine.submitTask(taskExecutor)
  -> executorContainer.dispatch(taskExecutor)
       -> selectIdleWorker()
       -> worker.registerTaskExecutor(taskExecutor)
       -> assignmentTable.registerTaskExecutor(...)
  -> taskExecutorRepository.put(taskExecutor)
  -> taskExecutor.eventBus.publish(DISPATCHED)
  -> executorContainer.start(taskExecutor)
       -> worker.fireTaskExecutor(taskExecutor)
```

如果没有空闲 worker：

```text
ExclusiveThreadTaskExecutorContainer.selectIdleWorker() 返回 empty
  -> 抛出 TaskExecutorRuntimeException("All ExclusiveThreadTaskExecutorWorker are busy")
  -> dispatchTask 返回失败
  -> Master 侧按分发失败重试
```

`TaskExecutorWorker` 的循环：

```text
while true:
  for activeTaskExecutors:
    if executor 未 start:
       executor.start()
    if 到达状态检查时间:
       executor.trackTaskExecutorState()
       根据状态发布 SUCCESS / FAILED / KILLED / PAUSED
  没任务时等待，有任务时按下一次 track 时间等待
```

默认状态检查间隔：

```text
AbstractTaskExecutor.DEFAULT_TRACK_INTERVAL = 10_000ms
```

## 9. 任务上下文准备数据流

执行器真正开始运行时入口：

```text
gyyun-task-executor/src/main/java/com/gyyun/ds/task/executor/AbstractTaskExecutor.java
```

`start()` 流程：

```text
AbstractTaskExecutor.start()
  -> initializeTaskContext()
  -> publishTaskRunningEvent()
  -> initializeTaskPlugin()
  -> 如果 dryRun，直接置为 SUCCEEDED
  -> doTriggerTaskPlugin()
```

Worker 的上下文准备在：

```text
gyyun-worker/src/main/java/com/gyyun/ds/server/worker/executor/PhysicalTaskExecutor.java
```

具体流程：

```text
PhysicalTaskExecutor.initializeTaskContext()
  -> super.initializeTaskContext()
       -> taskExecutionContext.setStartTime(now)
  -> taskExecutionContext.setTaskAppId(taskInstanceId)
  -> TenantUtils.getOrCreateActualTenant(workerConfig, taskExecutionContext)
  -> TaskExecutionContextUtils.createTaskInstanceWorkingDirectory(...)
  -> TaskExecutionContextUtils.downloadResourcesIfNeeded(...)
  -> taskExecutionContext.setResourceContext(resourceContext)
```

tenant 处理：

```text
TenantUtils.getOrCreateActualTenant(...)
  -> 如果 sudo 未启用，使用 bootstrap 用户
  -> 如果是 default tenant 且 defaultTenantEnabled=true，使用 bootstrap 用户
  -> 如果 autoCreateTenantEnabled=true，自动创建系统用户
  -> 如果最终 tenant 不存在，抛 TaskException
```

工作目录处理：

```text
TaskExecutionContextUtils.createTaskInstanceWorkingDirectory(...)
  -> FileUtils.getTaskInstanceWorkingDirectory(taskInstanceId)
  -> 如果目录已存在，先删除再创建
  -> 创建 775 权限目录
  -> setExecutePath(...)
  -> setAppInfoPath(...)
```

资源下载：

```text
downloadResourcesIfNeeded(taskChannel, storageOperator, taskExecutionContext)
  -> taskChannel.parseParameters(taskParams)
  -> 读取 parameters.resourceFilesList
  -> storageOperator.getResourceMetaData(resourceName)
  -> storageOperator.download(storagePath, localPath, true)
  -> chmod 755
  -> 记录下载耗时、大小、成功/失败 metrics
  -> 返回 ResourceContext
```

这意味着：

```text
任务插件看到的 TaskExecutionContext 已经包含本地工作目录和资源文件本地路径。
资源文件不是 Master 下发二进制内容，而是 Worker 按资源名从 StorageOperator 下载。
```

## 10. 任务插件执行数据流

插件创建入口：

```text
gyyun-worker/src/main/java/com/gyyun/ds/server/worker/executor/PhysicalTaskPluginFactory.java
```

创建流程：

```text
PhysicalTaskExecutor.initializeTaskPlugin()
  -> physicalTaskPluginFactory.createPhysicalTask(this)
       -> TaskPluginManager.getTaskChannel(taskType)
       -> taskChannel.createTask(taskExecutionContext)
  -> physicalTask.init()
  -> physicalTask.getParameters().setVarPool(new ArrayList<>())
```

触发执行：

```text
PhysicalTaskExecutor.doTriggerTaskPlugin()
  -> physicalTask.handle(new TaskCallBack() {...})
```

插件通过回调把运行时信息写回执行器：

```text
TaskCallBack.updateRemoteApplicationInfo(...)
  -> taskExecutionContext.setAppIds(applicationInfo.getAppIds())
  -> 发布 RUNTIME_CONTEXT_CHANGE

TaskCallBack.updateTaskInstanceInfo(...)
  -> 发布 RUNTIME_CONTEXT_CHANGE
```

状态跟踪：

```text
PhysicalTaskExecutor.doTrackTaskPluginStatus()
  -> physicalTask.getExitStatus()
  -> TaskExecutorStateMappings.mapState(...)
```

状态映射：

| 插件 `TaskExecutionStatus` | Worker `TaskExecutorState` |
| --- | --- |
| `RUNNING_EXECUTION` | `RUNNING` |
| `SUCCESS` | `SUCCEEDED` |
| `FAILURE` | `FAILED` |
| `KILL` | `KILLED` |
| `PAUSE` | `PAUSED` |
| 其他 | `INITIALIZED` |

Kill 处理：

```text
PhysicalTaskExecutor.kill()
  -> physicalTask.cancel()
```

Pause 处理：

```text
PhysicalTaskExecutor.pause()
  -> 当前实现只打印 "The physical doesn't support pause"
```

所以：

```text
Worker RPC 支持 pauseTask，但物理任务执行器本身默认不真正暂停。
除非具体任务执行器/插件后续扩展 pause 行为，否则 pause 对物理任务主要不会产生实际中断效果。
```

## 11. 生命周期事件回传数据流

Worker 本地事件总线：

```text
gyyun-worker/src/main/java/com/gyyun/ds/server/worker/executor/PhysicalTaskExecutorEventBusCoordinator.java
```

它继承：

```text
gyyun-task-executor/src/main/java/com/gyyun/ds/task/executor/eventbus/TaskExecutorEventBusCoordinator.java
```

事件分发流程：

```text
TaskExecutorEventBusCoordinator.start()
  -> 每 50ms 扫描 taskExecutorRepository
  -> 对每个执行器 poll 一个 eventBus 头部事件
  -> 根据事件类型调用 TaskExecutorLifecycleEventListener
```

事件类型：

```text
DISPATCHED
RUNNING
RUNTIME_CONTEXT_CHANGE
PAUSE
PAUSED
KILL
KILLED
SUCCESS
FAILED
FINALIZE
```

Listener 行为：

```text
TaskExecutorLifecycleEventListener
  -> DISPATCHED / RUNNING / RUNTIME_CONTEXT_CHANGE / PAUSED / KILLED / SUCCESS / FAILED:
       reportTaskExecutorLifecycleEventToMaster(...)
  -> PAUSE:
       taskExecutor.pause()
  -> KILL:
       taskExecutor.kill()
  -> FINALIZE:
       repository.remove(taskExecutorId)
       executorContainer.finalize(taskExecutor)
```

上报器：

```text
gyyun-worker/src/main/java/com/gyyun/ds/server/worker/executor/PhysicalTaskExecutorLifecycleEventReporter.java
```

它继承：

```text
gyyun-task-executor/src/main/java/com/gyyun/ds/task/executor/eventbus/TaskExecutorLifecycleEventRemoteReporter.java
```

上报队列模型：

```text
reportTaskExecutorLifecycleEvent(event)
  -> eventChannels[taskInstanceId].add(event)
  -> 唤醒 reporter 线程

reporter 线程:
  -> 遍历每个 taskInstanceId 的 channel
  -> 取队首事件
  -> 从 taskExecutionContext.workflowInstanceHost 读取 Master 地址
  -> taskExecutorEventRemoteReporterClient.reportTaskExecutionEventToMaster(masterAddress, event)
  -> 等待 Master ACK
  -> 未 ACK 时每 3 分钟重发
```

远程客户端：

```text
gyyun-extract/gyyun-extract-master/src/main/java/com/gyyun/ds/extract/master/TaskExecutorEventRemoteReporterClient.java
```

调用 Master 的接口：

```text
Clients.withService(ITaskExecutorEventListener.class)
  .withHost(masterAddress)
  .onTaskExecutorRunning(...)
```

事件与 Master RPC 方法对应：

| Worker 事件 | Master RPC 方法 |
| --- | --- |
| `DISPATCHED` | `onTaskExecutorDispatched(...)` |
| `RUNNING` | `onTaskExecutorRunning(...)` |
| `RUNTIME_CONTEXT_CHANGE` | `onTaskExecutorRuntimeContextChanged(...)` |
| `SUCCESS` | `onTaskExecutorSuccess(...)` |
| `FAILED` | `onTaskExecutorFailed(...)` |
| `KILLED` | `onTaskExecutorKilled(...)` |
| `PAUSED` | `onTaskExecutorPaused(...)` |

Master 接收点：

```text
gyyun-master/src/main/java/com/gyyun/ds/server/master/rpc/TaskExecutorEventListenerImpl.java
```

Master 处理流程：

```text
TaskExecutorEventListenerImpl.onTaskExecutorRunning(...)
  -> 根据 workflowInstanceId 找到 IWorkflowExecution
  -> 根据 taskInstanceId 找到 ITaskExecution
  -> 转换成 Master 内部 TaskRunningLifecycleEvent
  -> publish 到 workflowEventBus
```

Master 的任务生命周期 handler 更新状态后，会回 ACK：

```text
TaskDispatchedLifecycleEventHandler
TaskRunningLifecycleEventHandler
TaskRuntimeContextChangedLifecycleEventHandler
TaskSuccessLifecycleEventHandler
TaskFailedLifecycleEventHandler
TaskKilledLifecycleEventHandler
TaskPausedLifecycleEventHandler
  -> taskExecutorClient.ackTaskExecutorLifecycleEvent(...)
  -> PhysicalTaskExecutorClientDelegator.ackTaskExecutorLifecycleEvent(...)
  -> RPC: IPhysicalTaskExecutorOperator.ackPhysicalTaskExecutorLifecycleEvent(...)
```

Worker 收到 ACK：

```text
PhysicalTaskExecutorOperatorImpl.ackPhysicalTaskExecutorLifecycleEvent(...)
  -> PhysicalTaskEngineDelegator.ackPhysicalTaskExecutorLifecycleEventACK(...)
  -> PhysicalTaskExecutorLifecycleEventReporter.receiveTaskExecutorLifecycleEventACK(...)
```

ACK 后处理：

```text
receiveTaskExecutorLifecycleEventACK(ack)
  -> 找到 eventChannels[taskInstanceId]
  -> 按事件类型移除已 ACK 事件
  -> 如果 channel 为空:
       -> 如果移除的是终态事件 SUCCESS / FAILED / KILLED / PAUSED:
            finalizeTaskExecutor(taskInstanceId)
       -> eventChannels.remove(taskInstanceId)
```

终态 ACK 后发布：

```text
TaskExecutorFinalizeLifecycleEvent
```

最终释放资源：

```text
TaskExecutorLifecycleEventListener.onTaskExecutorFinalizeLifecycleEvent(...)
  -> taskExecutorRepository.remove(taskExecutorId)
  -> executorContainer.finalize(taskExecutor)
       -> worker.unRegisterTaskExecutor(taskExecutor)
       -> assignmentTable.unregisterTaskExecutor(taskExecutor)
       -> pushTaskExecutorLogToRemote(taskExecutor)
       -> taskExecutor.finalizeTask()
            -> 非 development 状态下删除 executePath
```

关键结论：

```text
Worker 本地执行器的生命周期会延长到 Master ACK 完终态事件之后。
这样可以避免任务刚结束就释放本地执行器，导致终态事件尚未被 Master 可靠处理。
```

## 12. 主要生命周期字段

Worker 上报给 Master 的生命周期事件里，常用字段如下：

| 事件 | 关键字段 | 数据含义 |
| --- | --- | --- |
| `DISPATCHED` | `workflowInstanceId`、`taskInstanceId`、`taskInstanceHost` | Worker 已接收任务 |
| `RUNNING` | `startTime`、`logPath`、`executePath` | 任务已开始执行，Master 可保存日志路径 |
| `RUNTIME_CONTEXT_CHANGE` | `appIds` | Yarn/Flink 等外部运行应用 ID 变化 |
| `SUCCESS` | `endTime`、`varPool` | 任务成功，并回传变量池 |
| `FAILED` | `endTime` | 任务失败 |
| `KILLED` | `endTime` | 任务被杀死 |
| `PAUSED` | `taskInstanceId` | 任务暂停 |

其中：

```text
logPath 是 Worker 本机日志文件路径。
API 查看日志时，会根据 taskInstance.host 调对应 Master/Worker 的 ILogService。
```

## 13. Kill、Pause、Master 迁移数据流

### 13.1 Kill

Master 发起：

```text
PhysicalTaskExecutorClientDelegator.kill(taskExecution)
  -> Clients.withService(IPhysicalTaskExecutorOperator.class)
       .withHost(taskInstance.host)
       .killTask(TaskExecutorKillRequest.of(taskInstanceId))
```

Worker 接收：

```text
PhysicalTaskExecutorOperatorImpl.killTask(...)
  -> physicalTaskEngineDelegator.killLogicTask(taskInstanceId)
  -> TaskEngine.killTask(taskInstanceId)
  -> taskExecutor.eventBus.publish(KILL)
  -> TaskExecutorLifecycleEventListener.onTaskExecutorKillLifecycleEvent(...)
  -> taskExecutor.kill()
  -> PhysicalTaskExecutor.kill()
  -> physicalTask.cancel()
```

后续：

```text
TaskExecutorWorker.trackTaskExecutorState(...)
  -> 插件状态变成 KILL
  -> 发布 KILLED
  -> 上报 Master
  -> Master ACK
  -> Worker FINALIZE
```

### 13.2 Pause

Master 发起：

```text
PhysicalTaskExecutorClientDelegator.pause(taskExecution)
  -> IPhysicalTaskExecutorOperator.pauseTask(...)
```

Worker 接收：

```text
PhysicalTaskExecutorOperatorImpl.pauseTask(...)
  -> TaskEngine.pauseTask(taskInstanceId)
  -> taskExecutor.eventBus.publish(PAUSE)
  -> TaskExecutorLifecycleEventListener.onTaskExecutorPauseLifecycleEvent(...)
  -> taskExecutor.pause()
```

当前物理执行器实现：

```text
PhysicalTaskExecutor.pause()
  -> log.warn("The physical doesn't support pause")
```

所以排查 pause 不生效时，要先确认具体任务插件是否支持暂停语义。

### 13.3 Master 迁移

Master failover 或接管时，新 Master 会通知 Worker 更新回调地址：

```text
PhysicalTaskExecutorClientDelegator.reassignMasterHost(taskExecution)
  -> IPhysicalTaskExecutorOperator.reassignWorkflowInstanceHost(...)
```

Worker 处理：

```text
PhysicalTaskEngineDelegator.reassignWorkflowInstanceHost(...)
  -> repository.get(taskInstanceId)
  -> taskExecutor.getTaskExecutionContext().setWorkflowInstanceHost(newMasterAddress)
  -> physicalTaskExecutorEventReporter.onWorkflowInstanceHostChanged(taskInstanceId)
```

`onWorkflowInstanceHostChanged` 会把该任务 channel 里的待上报事件 `latestReportTime` 置空：

```text
event.setLatestReportTime(null)
```

这样 reporter 会尽快把未 ACK 事件重新发给新的 Master。

## 14. 日志查询数据流

Worker 暴露日志服务：

```text
gyyun-worker/src/main/java/com/gyyun/ds/server/worker/rpc/WorkerLogServiceImpl.java
```

它继承通用实现：

```text
gyyun-extract/gyyun-extract-common/src/main/java/com/gyyun/ds/extract/common/service/impl/LogServiceImpl.java
```

RPC 契约：

```text
gyyun-extract/gyyun-extract-common/src/main/java/com/gyyun/ds/extract/common/ILogService.java
```

方法：

| 方法 | 作用 |
| --- | --- |
| `pageQueryTaskInstanceLog(...)` | 按 skipLineNum 和 limit 分页读取本机日志文件 |
| `getTaskInstanceWholeLogFileBytes(...)` | 下载完整日志文件 |
| `removeTaskInstanceLog(...)` | 删除本机日志文件 |

日志路径来源：

```text
PhysicalTaskExecutorFactory.assemblyTaskLogPath(...)
  -> taskExecutionContext.setLogPath(LogUtils.getTaskInstanceLogFullPath(taskExecutionContext))

RUNNING 事件上报 Master
  -> Master 保存 taskInstance.logPath
```

API 查日志时的整体链路：

```text
前端
  -> gyyun-api LoggerController
  -> LoggerService / LocalLogClient
  -> 根据 taskInstance.host 找到执行节点
  -> RPC: ILogService.pageQueryTaskInstanceLog(...)
  -> WorkerLogServiceImpl 读取本地 logPath
```

注意：

```text
日志文件本体默认在执行节点本地。
如果启用了 RemoteLogUtils 远程日志，Worker finalize 时会尝试把日志推到远程存储。
```

## 15. 流任务 savepoint 数据流

Worker 暴露流任务操作：

```text
gyyun-worker/src/main/java/com/gyyun/ds/server/worker/rpc/StreamingTaskInstanceOperatorImpl.java
```

RPC 契约：

```text
gyyun-extract/gyyun-extract-worker/src/main/java/com/gyyun/ds/extract/worker/IStreamingTaskInstanceOperator.java
```

处理流程：

```text
triggerSavepoint(request)
  -> taskInstanceId = request.getTaskInstanceId()
  -> physicalTaskExecutorRepository.get(taskInstanceId)
  -> 转成 PhysicalTaskExecutor
  -> taskExecutor.getPhysicalTask()
  -> 判断 physicalTask instanceof StreamTask
  -> ((StreamTask) physicalTask).savePoint()
  -> 返回 success / fail
```

失败场景：

| 场景 | 返回 |
| --- | --- |
| 找不到执行器 | `Cannot find TaskExecutionContext` |
| 执行器还没有创建出插件任务 | `Cannot find StreamTask` |
| 插件不是 `StreamTask` | `The taskInstance is not StreamTask` |
| `savePoint()` 抛异常 | `StreamTask call savePoint error: ...` |

## 16. 查询运行中任务数据流

Worker 暴露查询服务：

```text
gyyun-worker/src/main/java/com/gyyun/ds/server/worker/rpc/PhysicalTaskExecutorQueryClient.java
```

处理流程：

```text
queryTaskInstances(...)
  -> physicalTaskEngineDelegator.queryTaskExecutors()
  -> TaskEngine.queryTaskExecutors()
  -> taskExecutorRepository.getAll()
  -> 转成 TaskExecutorDTO
```

返回字段来自 `TaskExecutionContext`：

```text
taskInstanceId
taskName
taskType
projectCode
workflowInstanceId
workflowInstanceName
startTime
```

这个接口只查当前 Worker 内存中的执行器，不查数据库。

## 17. 与其他模块的边界

Worker 与 Master 的边界：

| 数据/职责 | 所属模块 | 说明 |
| --- | --- | --- |
| DAG 编排 | `gyyun-master` | Worker 不计算后继任务 |
| workerGroup 选择 | `gyyun-master` | Worker 只在心跳里声明自己的 group |
| 任务实例 DB 状态 | `gyyun-master` | Worker 通过生命周期事件回传，由 Master 更新 |
| 任务插件执行 | `gyyun-worker` | Worker 根据 taskType 加载 `TaskChannel` 并运行 `AbstractTask` |
| 日志文件写入 | `gyyun-worker` | 物理任务日志在执行 Worker 本地生成 |
| 资源下载 | `gyyun-worker` | Worker 从 StorageOperator 下载任务资源到本地工作目录 |
| 资源定义、任务定义、工作流定义 | `gyyun-api` / `gyyun-dao` / `gyyun-master` | Worker 只消费 Master 下发的运行上下文 |

Worker 与插件的边界：

| 数据/职责 | 所属模块 | 说明 |
| --- | --- | --- |
| `TaskExecutionContext` | `gyyun-task-plugin-api` | Worker 和插件共享的运行上下文 |
| `TaskChannel` | 任务插件 | 负责参数解析和创建具体 `AbstractTask` |
| `AbstractTask.handle(...)` | 任务插件 | 真正执行 Shell、SQL、Flink、Spark 等任务 |
| `TaskCallBack` | Worker 提供给插件 | 插件用它回写 appIds、运行时上下文变化 |
| `AbstractTask.cancel()` | 任务插件 | Worker kill 时调用 |

## 18. 典型端到端时序

### 18.1 成功任务

```text
Master:
  WorkerGroupDispatcher 取出任务
  -> 选择 Worker 127.0.0.1:1234
  -> RPC dispatchTask(TaskExecutionContext)

Worker:
  PhysicalTaskExecutorOperatorImpl.dispatchTask
  -> PhysicalTaskEngineDelegator.dispatchLogicTask
  -> PhysicalTaskExecutorFactory.createTaskExecutor
  -> TaskEngine.submitTask
  -> 发布 DISPATCHED
  -> TaskExecutorWorker.start executor
  -> initializeTaskContext
  -> 发布 RUNNING
  -> initializeTaskPlugin
  -> physicalTask.handle
  -> 插件返回 SUCCESS
  -> trackTaskExecutorState 发布 SUCCESS

Worker Reporter:
  -> 上报 DISPATCHED 给 Master
  -> 等 ACK
  -> 上报 RUNNING 给 Master
  -> 等 ACK
  -> 上报 SUCCESS 给 Master
  -> 等 ACK
  -> 发布 FINALIZE

Master:
  -> 接收 Worker 事件
  -> 转成 Master 内部任务生命周期事件
  -> 更新 t_ds_task_instance
  -> ACK Worker
  -> SUCCESS 后推动后继任务或结束工作流

Worker:
  -> FINALIZE
  -> repository.remove
  -> 释放执行槽
  -> 推送远程日志
  -> 清理工作目录
```

### 18.2 任务被 kill

```text
Master:
  -> RPC killTask(taskInstanceId)

Worker:
  -> TaskEngine.killTask
  -> 发布 KILL
  -> listener 调 physicalTask.cancel()
  -> 插件状态变 KILL
  -> TaskExecutorWorker 发布 KILLED
  -> Reporter 上报 KILLED
  -> Master ACK KILLED
  -> Worker FINALIZE
```

### 18.3 Worker 已满槽

```text
Master:
  -> RPC dispatchTask(...)

Worker:
  -> ExclusiveThreadTaskExecutorContainer.dispatch(...)
  -> selectIdleWorker() 为空
  -> 抛 TaskExecutorRuntimeException
  -> dispatchTask 返回失败

Master:
  -> WorkerGroupDispatcher 捕获失败
  -> dispatchFailTimes + 1
  -> 延迟重试
```

同时 Worker 心跳里：

```text
threadPoolUsage = 1
serverStatus = BUSY
```

## 19. 排查问题的关键断点

启动和注册：

| 类 | 方法 | 看什么 |
| --- | --- | --- |
| `WorkerServer` | `run()` | 启动顺序是否完整 |
| `WorkerConfig` | `validate(...)` | `workerAddress`、`workerRegistryPath`、`group` 是否正确 |
| `WorkerRegistryClient` | `registry()` | Worker 临时节点是否写入成功 |
| `WorkerHeartBeatTask` | `getHeartBeat()` / `writeHeartBeat(...)` | 心跳内容、BUSY/NORMAL、slotUsage |

任务下发：

| 类 | 方法 | 看什么 |
| --- | --- | --- |
| `PhysicalTaskExecutorOperatorImpl` | `dispatchTask(...)` | Worker 是否收到 Master 下发 |
| `PhysicalTaskEngineDelegator` | `dispatchLogicTask(...)` | 是否创建并提交执行器 |
| `PhysicalTaskExecutorFactory` | `createTaskExecutor(...)` | `logPath` 和 builder 参数 |
| `TaskEngine` | `submitTask(...)` | 分配执行槽、写 repository、发布 DISPATCHED |
| `AbstractTaskExecutorContainer` | `dispatch(...)` | 是否有空闲 worker 槽 |

任务执行：

| 类 | 方法 | 看什么 |
| --- | --- | --- |
| `TaskExecutorWorker` | `start()` | 执行器是否真正 start，状态是否被 track |
| `PhysicalTaskExecutor` | `initializeTaskContext()` | tenant、executePath、resourceContext |
| `TaskExecutionContextUtils` | `downloadResourcesIfNeeded(...)` | 资源下载路径和异常 |
| `PhysicalTaskPluginFactory` | `createPhysicalTask(...)` | taskType 是否能找到 TaskChannel |
| `PhysicalTaskExecutor` | `doTriggerTaskPlugin()` | `physicalTask.handle(...)` 是否进入插件 |

事件回传：

| 类 | 方法 | 看什么 |
| --- | --- | --- |
| `TaskExecutorEventBusCoordinator` | `doFireTaskExecutorEventBus(...)` | 本地事件是否被 listener 消费 |
| `TaskExecutorLifecycleEventListener` | `reportTaskExecutorLifecycleEventToMaster(...)` | 哪些事件会上报 |
| `TaskExecutorLifecycleEventRemoteReporter` | `handleTaskExecutionEventChannel(...)` | 事件是否发给正确 Master，是否在等 ACK |
| `TaskExecutorEventRemoteReporterClient` | `reportTaskExecutionEventToMaster(...)` | RPC 到 Master 是否成功 |
| `PhysicalTaskExecutorOperatorImpl` | `ackPhysicalTaskExecutorLifecycleEvent(...)` | Worker 是否收到 Master ACK |
| `TaskExecutorLifecycleEventRemoteReporter` | `receiveTaskExecutorLifecycleEventACK(...)` | ACK 是否移除事件，终态是否触发 FINALIZE |

日志和流任务：

| 类 | 方法 | 看什么 |
| --- | --- | --- |
| `WorkerLogServiceImpl` / `LogServiceImpl` | `pageQueryTaskInstanceLog(...)` | API 查日志时 Worker 是否读到本地文件 |
| `LogServiceImpl` | `getTaskInstanceWholeLogFileBytes(...)` | 下载日志是否使用正确路径 |
| `StreamingTaskInstanceOperatorImpl` | `triggerSavepoint(...)` | 执行器是否存在，插件是否是 `StreamTask` |

## 20. 常见误区

### 20.1 Worker 不负责直接更新任务实例表

Worker 上报的是生命周期事件：

```text
DISPATCHED / RUNNING / SUCCESS / FAILED / KILLED / PAUSED
```

Master 接到事件后，才更新 `t_ds_task_instance` 并推动工作流。

### 20.2 Worker 接收 dispatch 成功不等于任务成功

`dispatchTask` 成功只表示：

```text
Worker 已经把 PhysicalTaskExecutor 提交到本地 TaskEngine。
```

任务结果要看后续 `SUCCESS`、`FAILED`、`KILLED`、`PAUSED` 事件。

### 20.3 Worker 的执行槽满了会同时影响 dispatch 和心跳

执行槽满时：

```text
dispatchTask 可能因为没有 idle worker 失败；
WorkerHeartBeatTask 会把 serverStatus 标为 BUSY。
```

### 20.4 终态事件 ACK 前执行器不会立刻释放

Worker 必须等 Master ACK 终态事件：

```text
SUCCESS / FAILED / KILLED / PAUSED
```

之后才发布 `FINALIZE` 并释放执行槽。

### 20.5 pauseTask 有接口，但物理执行器默认不支持真实暂停

当前实现：

```text
PhysicalTaskExecutor.pause()
  -> 只打印 warning
```

排查暂停逻辑时要重点看具体任务插件是否有额外支持。

### 20.6 日志路径是 Worker 本地路径

`logPath` 由 Worker 生成并通过 RUNNING 事件上报 Master。API 查日志时需要调到实际执行节点的 `ILogService`，否则会读不到文件。

