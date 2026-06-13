# gyyun-master 数据流分析

本文分析 `gyyun-master` 模块的核心数据流，重点回答几个问题：

- Master 启动后初始化了哪些组件；
- API、Quartz 调度、Worker 回调分别从哪里进入 Master；
- 工作流实例、命令、任务实例如何在数据库和内存状态机之间流转；
- 任务如何选择 Worker 并通过 RPC 下发；
- 多 Master、Master Coordinator、Worker Group、Failover 的职责边界；
- 本机 IDEA Debug 时应该在哪些类和方法上打断点。

## 1. 总体结论

`gyyun-master` 是系统的工作流编排和任务调度服务，启动类是：

```text
com.gyyun.ds.server.master.MasterServer
```

它不是 HTTP API 网关，也不是普通物理任务执行器。它的核心定位是：

```text
工作流触发接收器 + 命令消费器 + DAG 状态机 + 任务分发器 + 逻辑任务执行器 + 集群 failover 协调器
```

最核心的数据流是：

```text
API / Quartz / 子工作流
  -> Master RPC: IWorkflowControlClient
  -> 写 t_ds_workflow_instance
  -> 写 t_ds_command 或 t_ds_serial_command
  -> CommandEngine 按 Master slot 拉取 t_ds_command
  -> WorkflowExecutionFactory 创建 WorkflowExecution
  -> WorkflowEventBus 发布 WorkflowStartLifecycleEvent
  -> 工作流状态机按 DAG 触发 TaskStartLifecycleEvent
  -> 生成 t_ds_task_instance
  -> TaskDispatchLifecycleEvent
  -> WorkerGroupDispatcher 延迟优先队列
  -> TaskExecutorClient 选择逻辑任务或物理任务通道
  -> Worker / Master 本地逻辑任务执行
  -> Worker 或逻辑任务执行器回调 Master
  -> Master 将回调转换成任务生命周期事件
  -> 更新 t_ds_task_instance
  -> 推动后继任务或结束工作流
  -> 更新 t_ds_workflow_instance
  -> 发送工作流告警
```

一句话概括：

```text
Master 是命令驱动 + 事件驱动的 DAG 调度器。
外部触发只负责落 workflow instance 和 command，真正运行由 CommandEngine 和 WorkflowEventBus 推进。
```

## 2. 启动数据流

入口文件：

```text
gyyun-master/src/main/java/com/gyyun/ds/server/master/MasterServer.java
```

`MasterServer.run()` 的启动顺序如下：

```text
ServerLifeCycleManager.toRunning()
  -> MasterRpcServer.start()
  -> TaskPluginManager.loadTaskPlugin()
  -> DataSourcePluginManager.loadDataSourcePlugin()
  -> MasterRegistryClient.start()
  -> masterRegistryClient.setRegistryStoppable(this)
  -> MasterCoordinator.start()
  -> ClusterManager.start()
  -> ClusterStateMonitors.start()
  -> WorkflowEngine.start()
       -> WorkflowEventBusCoordinator.start()
       -> CommandEngine.start()
       -> WorkerGroupDispatcherCoordinator.start()
       -> LogicTaskEngineDelegator.start()
  -> SchedulerApi.start()
  -> SystemEventBus.publish(GlobalMasterFailoverEvent)
  -> SystemEventBusFireWorker.start()
  -> register metrics
```

几个关键点：

| 组件 | 作用 |
| --- | --- |
| `MasterRpcServer` | 启动 Netty RPC，监听 Master RPC 端口 |
| `TaskPluginManager` | 加载任务插件，供任务参数和执行上下文构建使用 |
| `DataSourcePluginManager` | 加载数据源插件，供任务执行和数据源相关能力使用 |
| `MasterRegistryClient` | 将 Master 心跳写入 ZooKeeper |
| `MasterCoordinator` | Master 集群里的 active coordinator，负责 task group、串行工作流、failover 清理 |
| `ClusterManager` | 从注册中心加载并订阅 Master/Worker 节点 |
| `ClusterStateMonitors` | 监听 Master/Worker 下线并发布 failover 事件 |
| `WorkflowEngine` | 启动命令消费、事件总线、任务分发、逻辑任务执行器 |
| `SchedulerApi` | 启动 Quartz 调度器 |
| `SystemEventBusFireWorker` | 消费系统事件，如全局 Master failover、节点 failover |

注意：

```text
Master 启动不会自动同步业务数据库结构。
application.yaml 中 spring.quartz.jdbc.initialize-schema 也是 never。
数据库初始化/升级仍然要通过 gyyun-tools 执行。
```

## 3. 关键配置

配置文件：

```text
gyyun-master/src/main/resources/application.yaml
```

核心配置：

```yaml
server:
  port: 5679

master:
  listen-port: 5678
  workflow-event-bus-fire-thread-count: 10
  max-heartbeat-interval: 10s
  command-fetch-strategy:
    type: ID_SLOT_BASED
    config:
      fetch-size: 10
  worker-load-balancer-configuration-properties:
    type: DYNAMIC_WEIGHTED_ROUND_ROBIN

registry:
  type: zookeeper
  zookeeper:
    namespace: gyyun
    connect-string: localhost:4206

spring:
  profiles:
    active: mysql
  quartz:
    jdbc:
      initialize-schema: never
```

端口和地址含义：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `master.listen-port` | `5678` | Master RPC 端口，API、Worker、Quartz 内部都会通过 RPC 调 Master |
| `server.port` | `5679` | Spring Boot HTTP/actuator 端口 |
| `registry.zookeeper.connect-string` | `localhost:4206` | ZooKeeper 地址 |
| `spring.datasource.driver-class-name` | `com.mysql.cj.jdbc.Driver` | MySQL 驱动类 |
| `spring.datasource.url` | `jdbc:mysql://127.0.0.1:3317/ala-data-ds` | 本地 MySQL 地址 |

`MasterConfig` 会在启动时计算：

```text
masterAddress = host:master.listen-port
masterRegistryPath = /masters/{masterAddress}
```

如果 IDEA 里调试多进程，要保证每个 Master 的 `master.listen-port` 不冲突。

## 4. 外部入口

Master 的主要外部入口都是 RPC。

RPC 服务端：

```text
gyyun-master/src/main/java/com/gyyun/ds/server/master/rpc/MasterRpcServer.java
```

核心接口：

| 接口实现 | 契约 | 数据流入口 |
| --- | --- | --- |
| `WorkflowControlClient` | `IWorkflowControlClient` | API、Quartz、子工作流触发、暂停、停止、重跑、恢复 |
| `TaskExecutorEventListenerImpl` | `ITaskExecutorEventListener` | Worker 或逻辑任务执行器上报任务生命周期 |
| `TaskInstanceControllerImpl` | `ITaskInstanceController` | task group slot 获取成功后唤醒任务 |
| `LogicTaskExecutorOperatorImpl` | `ILogicTaskExecutorOperator` | Master 本地逻辑任务执行入口 |
| `MasterContainerService` | `IMasterContainerService` | 刷新 worker group |
| `LogicTaskExecutorQueryClient` | `ITaskExecutorQueryClient` | 查询 Master 本地逻辑任务执行器 |

### 4.1 API 触发工作流

API 侧典型入口：

```text
gyyun-api/src/main/java/com/gyyun/ds/api/executor/workflow/TriggerWorkflowExecutorDelegate.java
gyyun-api/src/main/java/com/gyyun/ds/api/executor/workflow/BackfillWorkflowExecutorDelegate.java
gyyun-api/src/main/java/com/gyyun/ds/api/executor/workflow/PauseWorkflowInstanceExecutorDelegate.java
gyyun-api/src/main/java/com/gyyun/ds/api/executor/workflow/StopWorkflowInstanceExecutorDelegate.java
```

API 通过注册中心找到 Master 地址后调用：

```text
Clients.withService(IWorkflowControlClient.class)
  .withHost(masterAddress)
  .manualTriggerWorkflow(...)
```

Master 侧接收：

```text
WorkflowControlClient.manualTriggerWorkflow(...)
  -> WorkflowManualTrigger.triggerWorkflow(...)
```

同类入口还有：

| 方法 | 触发器 | 命令类型 |
| --- | --- | --- |
| `manualTriggerWorkflow` | `WorkflowManualTrigger` | `START_PROCESS` |
| `backfillTriggerWorkflow` | `WorkflowBackfillTrigger` | `COMPLEMENT_DATA` |
| `scheduleTriggerWorkflow` | `WorkflowScheduleTrigger` | `SCHEDULER` |
| `repeatTriggerWorkflow` | `WorkflowInstanceRepeatTrigger` | 重跑类命令 |
| `recoverFailureTasks` | `WorkflowInstanceRecoverFailureTaskTrigger` | 失败任务恢复 |
| `recoverSuspendedWorkflow` | `WorkflowInstanceRecoverSuspendTaskTrigger` | 暂停恢复 |
| `pauseWorkflowInstance` | 查内存 `WorkflowCacheRepository` 后发布 pause 事件 | 不新增命令 |
| `stopWorkflowInstance` | 查内存 `WorkflowCacheRepository` 后发布 stop 事件 | 不新增命令 |

## 5. 触发器数据流

触发器基类：

```text
gyyun-master/src/main/java/com/gyyun/ds/server/master/engine/workflow/trigger/AbstractWorkflowTrigger.java
```

触发器方法有事务：

```text
triggerWorkflow(...)
  -> constructWorkflowInstance(...)
  -> constructTriggerCommand(...)
  -> 根据 workflow executionType 分支
```

并行工作流：

```text
insert t_ds_workflow_instance
insert t_ds_command
```

串行工作流：

```text
workflow_instance.state = SERIAL_WAIT
insert t_ds_workflow_instance
insert t_ds_serial_command
```

这意味着：

```text
RPC 触发成功不代表任务已经开始执行。
它只代表 workflow instance 和 command 已经写入数据库，等待 Master 命令消费或串行 coordinator 放行。
```

典型字段：

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `t_ds_workflow_instance` | `id` | 工作流实例 ID，后续 command 和 task instance 都会引用 |
| `t_ds_workflow_instance` | `state` | `SUBMITTED_SUCCESS`、`RUNNING_EXECUTION`、`SERIAL_WAIT`、`SUCCESS`、`FAILURE` 等 |
| `t_ds_workflow_instance` | `host` | 当前调度该 workflow 的 Master 地址 |
| `t_ds_workflow_instance` | `command_param` | 启动节点、启动参数、时区等 |
| `t_ds_command` | `workflow_instance_id` | 对应 workflow instance |
| `t_ds_command` | `command_type` | `START_PROCESS`、`SCHEDULER`、`COMPLEMENT_DATA` 等 |
| `t_ds_serial_command` | `workflow_instance_id` | 串行等待队列 |

## 6. CommandEngine 命令消费

核心类：

```text
gyyun-master/src/main/java/com/gyyun/ds/server/master/engine/command/CommandEngine.java
```

启动于：

```text
WorkflowEngine.start()
  -> commandEngine.start()
```

主循环：

```text
CommandEngine.run()
  -> 读取系统指标
  -> MasterServerLoadProtection.isOverload(...)
  -> ICommandFetcher.fetchCommands()
  -> 多线程 bootstrapCommand(command)
  -> bootstrapWorkflowExecution(workflowExecution)
  -> bootstrapSuccess(command)
```

当前配置使用：

```text
IdSlotBasedCommandFetcher
```

消费逻辑：

```text
MasterSlotManager.checkSlotValid()
  -> currentSlot = 当前 Master 在正常 Master 列表中的下标
  -> totalSlot = 正常 Master 数量
  -> CommandDao.queryCommandByIdSlot(currentSlot, totalSlot, idStep, fetchSize)
```

多 Master 时，每个 Master 只消费自己 slot 范围内的 `t_ds_command`。这就是 Master 水平扩展时避免重复消费命令的核心机制。

命令处理器基类：

```text
gyyun-master/src/main/java/com/gyyun/ds/server/master/engine/command/handler/AbstractCommandHandler.java
```

组装流程：

```text
handleCommand(command)
  -> assembleWorkflowDefinition()
  -> assembleProject()
  -> assembleWorkflowGraph()
  -> assembleWorkflowInstance()
  -> assembleWorkflowInstanceLifecycleListeners()
  -> assembleWorkflowEventBus()
  -> assembleWorkflowExecutionGraph()
  -> new WorkflowExecution(...)
```

普通运行命令：

```text
RunWorkflowCommandHandler
  -> 查询 t_ds_workflow_instance
  -> state 改为 RUNNING_EXECUTION
  -> host 改为当前 Master 地址
  -> 合并 workflow globalParams 和 command params
  -> 根据 DAG 生成 WorkflowExecutionGraph
```

命令消费成功后：

```text
workflowRepository.put(workflowExecution)
workflowEventBusCoordinator.registerWorkflowEventBus(workflowExecution)
workflowExecution.getWorkflowEventBus().publish(WorkflowStartLifecycleEvent.of(workflowExecution))
```

命令消费失败时：

```text
t_ds_workflow_instance.state = FAILURE
t_ds_command -> t_ds_error_command
```

## 7. WorkflowEventBus 和工作流状态机

每个 `WorkflowExecution` 都有自己的事件总线：

```text
gyyun-master/src/main/java/com/gyyun/ds/server/master/engine/WorkflowEventBus.java
```

事件处理线程：

```text
WorkflowEventBusCoordinator
  -> WorkflowEventBusFireWorkers
  -> WorkflowEventBusFireWorker
```

分配规则：

```text
workflowInstanceId % workflowEventBusFireWorkerSize
```

事件处理流程：

```text
WorkflowEventBusFireWorker.fireAllRegisteredEvent()
  -> 找出非空事件总线
  -> poll 一个生命周期事件
  -> 根据 event.getEventType() 找 ILifecycleEventHandler
  -> handler.handle(workflowExecution, event)
  -> handler 内部根据当前状态找到 StateAction
```

工作流启动事件：

```text
WorkflowStartLifecycleEventHandler
  -> 如果配置 workflow timeout，发布 WorkflowTimeoutLifecycleEvent
  -> workflowStateAction.onStartEvent(...)
```

运行态工作流：

```text
WorkflowRunningStateAction.onStartEvent(...)
  -> 获取 DAG start nodes
  -> triggerTasks(workflowExecution, startNodes)
```

触发任务的核心方法：

```text
AbstractWorkflowStateAction.triggerTasks(...)
  -> 过滤满足触发条件的候选任务
  -> markTaskExecutionActive(...)
  -> 跳过或 forbidden 的任务直接发布拓扑推进事件
  -> 普通任务发布 TaskStartLifecycleEvent
```

任务结束后：

```text
TaskSuccess / TaskFailed / TaskKilled / TaskPaused
  -> 发布 WorkflowTopologyLogicalTransitionWithTaskFinishLifecycleEvent
  -> WorkflowRunningStateAction.onTopologyLogicalTransitionEvent(...)
  -> markTaskExecutionInActive(...)
  -> tryToTriggerSuccessorsAfterTaskFinish(...)
  -> 触发后继任务或发布 WorkflowSucceed/WorkflowFailed/WorkflowStopped
```

工作流结束：

```text
workflowFinish(...)
  -> 更新 t_ds_workflow_instance.end_time
  -> 更新 t_ds_workflow_instance.state
  -> 串行工作流删除 t_ds_serial_command
  -> 发布 WorkflowFinalizeLifecycleEvent
```

Finalize 动作：

```text
AbstractWorkflowStateAction.finalizeEventAction(...)
  -> 打印 workflow 详情
  -> workflowCacheRepository.remove(workflowInstanceId)
  -> workflowEventBusCoordinator.unRegisterWorkflowEventBus(workflowExecution)
  -> workflowAlertManager.sendAlertWorkflowInstance(workflowInstance)
```

## 8. TaskExecution 和任务状态机

任务执行对象：

```text
gyyun-master/src/main/java/com/gyyun/ds/server/master/engine/task/execution/TaskExecution.java
```

任务启动事件：

```text
TaskStartLifecycleEventHandler.handle(...)
  -> 如果 taskInstance 未初始化，initializeFirstRunTaskInstance()
  -> 注册任务超时监控事件
  -> 进入任务状态机
```

首次初始化任务实例：

```text
TaskExecution.initializeFirstRunTaskInstance()
  -> TaskInstanceFactories.firstRunTaskInstanceFactory()
  -> insert t_ds_task_instance
  -> 初始状态 SUBMITTED_SUCCESS
```

`SUBMITTED_SUCCESS` 状态动作：

```text
TaskSubmittedStateAction.onStartEvent(...)
  -> 如果 workflow READY_PAUSE，发布 TaskPausedLifecycleEvent
  -> 如果 workflow READY_STOP，发布 TaskKilledLifecycleEvent
  -> 否则 tryToDispatchTask(taskExecution)
```

如果任务需要 task group slot：

```text
acquireTaskGroupSlot(taskInstance, taskDefinition)
  -> slot 获取成功后由 TaskInstanceControllerImpl.taskWakeup(...)
  -> 发布 TaskDispatchLifecycleEvent
```

如果不需要 task group slot：

```text
直接发布 TaskDispatchLifecycleEvent
```

任务派发事件：

```text
TaskSubmittedStateAction.onDispatchEvent(...)
  -> 处理 delayTime
  -> taskExecution.initializeTaskExecutionContext()
  -> WorkerGroupDispatcherCoordinator.dispatchTask(taskExecution, delayTimeMills)
```

`TaskExecutionContext` 包含：

| 信息 | 来源 |
| --- | --- |
| task instance ID/name/type/worker group | `t_ds_task_instance` |
| workflow instance ID/name/project/tenant/scheduleTime | `t_ds_workflow_instance` |
| task params | `t_ds_task_definition_log` |
| global params / startup params / varPool | 工作流实例、命令、前置任务 |
| workflowInstanceHost | 当前 Master 地址 |
| resource / environment / k8s context | service、resource、environment 相关表 |

## 9. Worker 选择和任务下发

分发协调器：

```text
gyyun-master/src/main/java/com/gyyun/ds/server/master/engine/task/dispatcher/WorkerGroupDispatcherCoordinator.java
```

每个 worker group 一个分发线程：

```text
WorkerGroupDispatcher-{workerGroup}
```

分发队列：

```text
TaskDispatchableEventBus
```

特点：

| 特点 | 说明 |
| --- | --- |
| 按 worker group 隔离 | 不同 worker group 有不同 dispatcher |
| 支持延迟执行 | delay task 会带 delayTimeMills 入队 |
| 支持优先级 | `TaskExecution.compareTo()` 按工作流优先级、任务优先级、task group 优先级、提交时间排序 |
| 支持派发重试 | RPC 派发失败后退避重试，1 到 60 秒 |
| 支持派发超时 | 超过 `taskDispatchPolicy.maxTaskDispatchDuration` 后发布 `TaskFailedLifecycleEvent` |

真正下发任务：

```text
WorkerGroupDispatcher.doDispatchTask(...)
  -> taskExecutorClient.dispatch(taskExecution)
```

客户端选择：

```text
TaskExecutorClient.getTaskExecutorClientDelegator(...)
  -> 逻辑任务: LogicTaskExecutorClientDelegator
  -> 物理任务: PhysicalTaskExecutorClientDelegator
```

### 9.1 物理任务

物理任务下发类：

```text
PhysicalTaskExecutorClientDelegator
```

链路：

```text
PhysicalTaskExecutorClientDelegator.dispatch(...)
  -> 检查 worker group 是否存在
  -> IWorkerLoadBalancer.select(workerGroup)
  -> taskExecutionContext.host = workerAddress
  -> taskInstance.host = workerAddress
  -> RPC IPhysicalTaskExecutorOperator.dispatchTask(...)
```

默认负载均衡配置：

```text
DYNAMIC_WEIGHTED_ROUND_ROBIN
```

Worker 列表来源：

```text
ClusterManager.initializeWorkerClusters()
  -> registryClient.getServerList(RegistryNodeType.WORKER)
  -> registryClient.subscribe("/workers", workerClusters)
```

### 9.2 逻辑任务

逻辑任务不是发给 Worker，而是发回当前 Master 自己执行。

相关类：

```text
LogicTaskExecutorClientDelegator
LogicTaskExecutorOperatorImpl
LogicTaskEngineDelegator
LogicTaskExecutor
```

链路：

```text
LogicTaskExecutorClientDelegator.dispatch(...)
  -> logicTaskExecutorAddress = masterConfig.getMasterAddress()
  -> RPC ILogicTaskExecutorOperator.dispatchTask(...)
  -> LogicTaskExecutorOperatorImpl.dispatchTask(...)
  -> LogicTaskEngineDelegator.dispatchLogicTask(...)
  -> TaskEngine.submitTask(...)
  -> LogicTaskExecutor.initializeTaskPlugin()
  -> ILogicTask.start()
```

逻辑任务也会通过任务执行器生命周期上报机制回到 Master，后续仍然走 `TaskExecutorEventListenerImpl` 到任务状态机。

## 10. Worker 回调数据流

Worker 或逻辑任务执行器上报入口：

```text
gyyun-master/src/main/java/com/gyyun/ds/server/master/rpc/TaskExecutorEventListenerImpl.java
```

它实现：

```text
gyyun-extract/gyyun-extract-master/src/main/java/com/gyyun/ds/extract/master/ITaskExecutorEventListener.java
```

回调转换关系：

| Worker 事件 | Master 内部事件 |
| --- | --- |
| dispatched | `TaskDispatchedLifecycleEvent` |
| running | `TaskRunningLifecycleEvent` |
| runtime context changed | `TaskRuntimeContextChangedEvent` |
| success | `TaskSuccessLifecycleEvent` |
| failed | `TaskFailedLifecycleEvent` |
| killed | `TaskKilledLifecycleEvent` |
| paused | `TaskPausedLifecycleEvent` |

处理流程：

```text
TaskExecutorEventListenerImpl
  -> workflowRepository.get(workflowInstanceId)
  -> workflowExecution.getWorkflowExecutionGraph().getTaskExecution(taskInstanceId)
  -> taskExecution.getWorkflowEventBus().publish(...)
```

注意：

```text
Worker 回调不会直接在 RPC 方法里完成 DAG 推进。
RPC 方法只把外部执行器事件转成 Master 内部事件。
真正更新 t_ds_task_instance、重试、触发后继任务、结束工作流，仍然由 WorkflowEventBusFireWorker 消费事件后完成。
```

任务成功时：

```text
AbstractTaskStateAction.onSucceedEvent(...)
  -> releaseTaskInstanceResourcesIfNeeded()
  -> t_ds_task_instance.state = SUCCESS
  -> 合并 task varPool
  -> 合并到 workflow varPool
  -> 发布 WorkflowTopologyLogicalTransitionWithTaskFinishLifecycleEvent
```

任务失败时：

```text
AbstractTaskStateAction.onFailedEvent(...)
  -> releaseTaskInstanceResourcesIfNeeded()
  -> t_ds_task_instance.state = FAILURE
  -> 如果可重试，发布 TaskRetryLifecycleEvent
  -> 否则标记失败链路
  -> 发布 WorkflowTopologyLogicalTransitionWithTaskFinishLifecycleEvent
```

## 11. 调度任务数据流

Master 依赖：

```text
gyyun-scheduler-plugin/gyyun-scheduler-all
```

Quartz 实现：

```text
gyyun-scheduler-plugin/gyyun-scheduler-quartz/src/main/java/com/gyyun/ds/scheduler/quartz/QuartzScheduler.java
```

Master 启动：

```text
MasterServer.run()
  -> schedulerApi.start()
  -> Quartz scheduler.start()
```

Quartz job：

```text
ProcessScheduleTask.executeInternal(...)
  -> 从 Quartz JobData 取 projectId 和 scheduleId
  -> 查 t_ds_schedules
  -> 查 t_ds_workflow_definition
  -> 如果 schedule/workflow 已下线或不存在，删除 Quartz job
  -> 构造 WorkflowScheduleTriggerRequest
  -> workflowInstanceController.scheduleTriggerWorkflow(...)
```

随后回到 Master 触发器：

```text
WorkflowControlClient.scheduleTriggerWorkflow(...)
  -> WorkflowScheduleTrigger.triggerWorkflow(...)
  -> insert t_ds_workflow_instance
  -> insert t_ds_command 或 t_ds_serial_command
```

所以调度触发和手动触发最终都会进入同一条命令消费链路。

## 12. 串行工作流数据流

如果工作流定义的 `executionType` 是串行策略，触发器不会直接写 `t_ds_command`，而是写：

```text
t_ds_serial_command
```

串行协调器：

```text
gyyun-master/src/main/java/com/gyyun/ds/server/master/engine/workflow/serial/WorkflowSerialCoordinator.java
```

启动位置：

```text
MasterCoordinator.changeToActive()
  -> workflowSerialCoordinator.start()
```

也就是说：

```text
只有 active MasterCoordinator 节点负责串行命令放行。
```

串行 coordinator 每 5 秒轮询一次：

```text
SerialCommandDao.fetchSerialCommands(1000)
  -> 按 workflowDefinitionCode + workflowDefinitionVersion 分组
  -> 根据 workflow executionType 选择策略
```

策略：

| executionType | 处理器 | 行为 |
| --- | --- | --- |
| `SERIAL_WAIT` | `SerialCommandWaitHandler` | 只放行队列第一个等待中的实例 |
| `SERIAL_DISCARD` | `SerialCommandDiscardHandler` | 放行第一个，后续等待实例直接置为 STOP 并删除 serial command |
| `SERIAL_PRIORITY` | `SerialCommandPriorityHandler` | 最新实例优先；等待的旧实例丢弃，运行中的旧实例发 stop |

放行动作：

```text
AbstractSerialCommandHandler.launchSerialCommand(...)
  -> insert t_ds_command
  -> t_ds_serial_command.state = LAUNCHED
```

工作流结束时：

```text
AbstractWorkflowStateAction.workflowFinish(...)
  -> 如果 workflow definition executionType.isSerial()
  -> serialCommandDao.deleteByWorkflowInstanceId(...)
```

## 13. Master 集群和注册中心

注册中心客户端：

```text
MasterRegistryClient
MasterHeartBeatTask
```

Master 注册路径：

```text
RegistryNodeType.MASTER.getRegistryPath() + "/" + masterAddress
```

心跳内容：

```text
MasterHeartBeat
  -> startupTime
  -> reportTime
  -> jvmCpuUsage
  -> cpuUsage
  -> memoryUsage
  -> diskUsage
  -> processId
  -> serverStatus
  -> host
  -> port
  -> isCoordinator
```

`serverStatus` 会受 Master 负载保护影响：

```text
MasterServerLoadProtection.isOverload(systemMetrics)
  -> BUSY 或 NORMAL
```

集群管理：

```text
ClusterManager.start()
  -> initializeMasterClusters()
  -> initializeWorkerClusters()
```

Master slot 重平衡：

```text
MasterClusters 变化
  -> MasterSlotChangeListenerAdaptor
  -> MasterSlotManager.doReBalance(normalMasterServers)
  -> currentSlot / totalSlots
```

命令消费依赖这个 slot：

```text
t_ds_command.id 按 currentSlot / totalSlots 分片查询
```

Worker group 刷新：

```text
WorkerClusters 变化
  -> WorkerGroupChangeNotifier
  -> 通知 MasterContainerService / worker group 相关缓存
```

## 14. Failover 数据流

系统事件总线：

```text
SystemEventBus
SystemEventBusFireWorker
```

启动完成后，Master 会发布一次全局 Master failover：

```text
MasterServer.run()
  -> systemEventBus.publish(GlobalMasterFailoverEvent.of(startupTime))
```

节点下线监听：

```text
ClusterStateMonitors.masterRemoved(...)
  -> 30 秒延迟发布 MasterFailoverEvent

ClusterStateMonitors.workerRemoved(...)
  -> 30 秒延迟发布 WorkerFailoverEvent
```

### 14.1 Master failover

核心类：

```text
FailoverCoordinator
WorkflowFailover
WorkflowFailoverCommandHandler
```

流程：

```text
FailoverCoordinator.failoverMaster(...)
  -> 判断 Master 是否已经以同 startupTime 重新上线
  -> 获取注册中心 failover lock
  -> queryNeedFailoverWorkflowInstances(masterAddress)
  -> WorkflowFailover.failoverWorkflow(workflowInstance)
```

`WorkflowFailover.failoverWorkflow(...)` 会：

```text
t_ds_workflow_instance.state = FAILOVER
insert t_ds_command(command_type = RECOVER_TOLERANCE_FAULT_PROCESS)
```

后续仍然由 `CommandEngine` 消费恢复命令：

```text
WorkflowFailoverCommandHandler
  -> 查询原 workflow instance
  -> state 恢复为 failover 前状态
  -> host 改为当前 Master
  -> 从已有 t_ds_task_instance 重建 WorkflowExecutionGraph
  -> 继续通过 WorkflowEventBus 推进
```

### 14.2 Worker failover

核心类：

```text
FailoverCoordinator
TaskFailover
FailoverTaskInstanceFactory
```

流程：

```text
FailoverCoordinator.failoverWorker(...)
  -> 判断 Worker 是否已经以同 startupTime 重新上线
  -> 从 workflowRepository 内存活跃工作流中找任务
  -> 条件：task.host == workerAddress
  -> 条件：task.state in (DISPATCH, RUNNING_EXECUTION)
  -> 条件：submitTime 早于 failover deadline
  -> TaskFailover.failoverTask(taskExecution)
  -> 发布 TaskFailoverLifecycleEvent
```

任务状态机收到 failover 事件后：

```text
TaskExecution.failover()
  -> 先尝试 reassignWorkflowInstanceHost 给原执行器
  -> 成功则不重跑
  -> 失败则 FailoverTaskInstanceFactory 创建新 task instance
```

新旧任务实例变化：

```text
旧 t_ds_task_instance:
  flag = NO
  state = NEED_FAULT_TOLERANCE

新 t_ds_task_instance:
  state = SUBMITTED_SUCCESS
  host = null
  submit_time = now
```

然后新任务实例重新发布 `TaskStartLifecycleEvent`，进入正常派发流程。

## 15. 超时和告警

工作流超时：

```text
WorkflowStartLifecycleEventHandler.workflowTimeoutMonitor(...)
  -> 如果 workflowInstance.timeout > 0
  -> 发布 WorkflowTimeoutLifecycleEvent
```

任务超时：

```text
TaskStartLifecycleEventHandler.taskTimeoutMonitor(...)
  -> 如果 taskDefinition.timeout > 0 且配置了 timeoutNotifyStrategy
  -> 发布 TaskTimeoutLifecycleEvent
  -> 如果系统配置 maxTaskInstanceRuntime > 0
  -> 再发布一个系统级 FAILED 超时事件
```

工作流结束告警：

```text
AbstractWorkflowStateAction.finalizeEventAction(...)
  -> workflowAlertManager.sendAlertWorkflowInstance(workflowInstance)
```

告警不是 Master 自己发插件，而是通过 `gyyun-service` 写告警记录或调用告警服务能力，后续由 `gyyun-alert-server` 处理。

## 16. 核心表

| 表 | Master 中的作用 |
| --- | --- |
| `t_ds_workflow_definition` | 当前工作流定义 |
| `t_ds_workflow_definition_log` | Master 按 code + version 读取定义快照 |
| `t_ds_workflow_instance` | 工作流实例状态、host、参数、开始/结束时间 |
| `t_ds_command` | Master 命令队列，CommandEngine 消费 |
| `t_ds_error_command` | 命令消费失败后的错误队列 |
| `t_ds_serial_command` | 串行工作流等待队列 |
| `t_ds_task_definition` / `t_ds_task_definition_log` | 任务定义和版本快照 |
| `t_ds_task_instance` | 任务实例状态、host、日志路径、varPool、重试次数 |
| `t_ds_schedules` | 调度配置，Quartz job 执行时查询 |
| `QRTZ_*` | Quartz JDBC 表，保存调度 job/trigger 状态 |
| `t_ds_alert` | 工作流结束告警可能写入的告警记录 |

## 17. IDEA Debug 推荐断点

### 17.1 启动和注册中心

```text
MasterServer.run()
MasterRpcServer.start()
MasterRegistryClient.registry()
MasterHeartBeatTask.getHeartBeat()
ClusterManager.initializeMasterClusters()
ClusterManager.initializeWorkerClusters()
MasterSlotManager.doReBalance(...)
```

看点：

```text
masterAddress 是否正确
ZooKeeper 是否能看到 /masters 和 /workers
currentSlot / totalSlots 是否有效
Worker group 是否加载出来
```

### 17.2 手动触发工作流

```text
WorkflowControlClient.manualTriggerWorkflow(...)
WorkflowManualTrigger.constructWorkflowInstance(...)
AbstractWorkflowTrigger.triggerWorkflow(...)
CommandDao.insert(...)
```

看点：

```text
t_ds_workflow_instance 是否插入
t_ds_command 是否插入
串行工作流是否进入 t_ds_serial_command
```

### 17.3 命令消费

```text
CommandEngine.run()
IdSlotBasedCommandFetcher.fetchCommands()
WorkflowExecutionFactory.createWorkflowExecuteRunnable(...)
RunWorkflowCommandHandler.assembleWorkflowInstance(...)
RunWorkflowCommandHandler.assembleWorkflowExecutionGraph(...)
CommandEngine.bootstrapWorkflowExecution(...)
```

看点：

```text
当前 Master slot 是否能拉到 command
workflow instance 是否改成 RUNNING_EXECUTION
WorkflowExecutionGraph 中有哪些节点
WorkflowStartLifecycleEvent 是否发布
```

### 17.4 DAG 推进

```text
WorkflowEventBus.publish(...)
WorkflowEventBusFireWorker.doFireSingleEvent(...)
WorkflowStartLifecycleEventHandler.handle(...)
WorkflowRunningStateAction.onStartEvent(...)
AbstractWorkflowStateAction.triggerTasks(...)
WorkflowRunningStateAction.onTopologyLogicalTransitionEvent(...)
AbstractWorkflowStateAction.workflowFinish(...)
```

看点：

```text
start nodes 是哪些
任务触发条件是否满足
后继节点为什么没触发
工作流为何 SUCCESS / FAILURE / STOP
```

### 17.5 任务派发

```text
TaskStartLifecycleEventHandler.handle(...)
TaskExecution.initializeFirstRunTaskInstance()
TaskSubmittedStateAction.onStartEvent(...)
TaskSubmittedStateAction.onDispatchEvent(...)
TaskExecution.initializeTaskExecutionContext()
WorkerGroupDispatcherCoordinator.dispatchTask(...)
WorkerGroupDispatcher.doDispatchTask(...)
TaskExecutorClient.dispatch(...)
PhysicalTaskExecutorClientDelegator.dispatch(...)
LogicTaskExecutorClientDelegator.dispatch(...)
```

看点：

```text
t_ds_task_instance 是否生成
workerGroup 是否正确
TaskExecutionContext 参数是否完整
物理任务选中了哪个 Worker
逻辑任务是否回到 Master 本地 TaskEngine
```

### 17.6 Worker 回调

```text
TaskExecutorEventListenerImpl.onTaskExecutorDispatched(...)
TaskExecutorEventListenerImpl.onTaskExecutorRunning(...)
TaskExecutorEventListenerImpl.onTaskExecutorSuccess(...)
TaskExecutorEventListenerImpl.onTaskExecutorFailed(...)
AbstractTaskStateAction.onSucceedEvent(...)
AbstractTaskStateAction.onFailedEvent(...)
AbstractTaskStateAction.publishWorkflowInstanceTopologyLogicalTransitionEvent(...)
```

看点：

```text
workflowRepository 是否能找到 workflow instance
taskExecution 是否能按 taskInstanceId 找到
t_ds_task_instance 状态是否更新
是否触发后继任务
```

### 17.7 Quartz 调度

```text
QuartzScheduler.start()
ProcessScheduleTask.executeInternal(...)
WorkflowControlClient.scheduleTriggerWorkflow(...)
WorkflowScheduleTrigger.constructWorkflowInstance(...)
```

看点：

```text
QRTZ_* 表是否有 job/trigger
t_ds_schedules 是否 ONLINE
workflow definition 是否 ONLINE
scheduleTime 是否是 Quartz scheduledFireTime
```

### 17.8 Failover

```text
ClusterStateMonitors.masterRemoved(...)
ClusterStateMonitors.workerRemoved(...)
FailoverCoordinator.globalMasterFailover(...)
FailoverCoordinator.failoverMaster(...)
WorkflowFailover.failoverWorkflow(...)
WorkflowFailoverCommandHandler.assembleWorkflowExecutionGraph(...)
FailoverCoordinator.failoverWorker(...)
TaskFailover.failoverTask(...)
FailoverTaskInstanceFactory.createTaskInstance(...)
```

看点：

```text
是否 30 秒延迟后仍然判定节点下线
Master failover 是否插入 RECOVER_TOLERANCE_FAULT_PROCESS 命令
Worker failover 是否只处理当前 Master 内存中的活跃任务
旧 task instance 是否变成 NEED_FAULT_TOLERANCE
新 task instance 是否重新 SUBMITTED_SUCCESS
```

## 18. SQL 排查

查看最近工作流实例：

```sql
select id, name, workflow_definition_code, workflow_definition_version,
       state, host, command_type, start_time, end_time, schedule_time
from t_ds_workflow_instance
order by id desc
limit 20;
```

查看命令队列：

```sql
select id, command_type, workflow_definition_code, workflow_definition_version,
       workflow_instance_id, create_time, update_time
from t_ds_command
order by id desc
limit 20;
```

查看错误命令：

```sql
select id, command_type, workflow_instance_id, message, create_time
from t_ds_error_command
order by id desc
limit 20;
```

查看串行等待命令：

```sql
select id, workflow_definition_code, workflow_definition_version,
       workflow_instance_id, state, create_time, update_time
from t_ds_serial_command
order by id desc
limit 20;
```

查看某个工作流的任务实例：

```sql
select id, name, task_type, state, host, retry_times, max_retry_times,
       submit_time, start_time, end_time, flag
from t_ds_task_instance
where workflow_instance_id = #{workflowInstanceId}
order by id;
```

查看 Quartz job：

```sql
select sched_name, job_name, job_group, job_class_name
from QRTZ_JOB_DETAILS
order by job_name;
```

查看 Quartz trigger：

```sql
select sched_name, trigger_name, trigger_group, job_name, trigger_state,
       next_fire_time, prev_fire_time
from QRTZ_TRIGGERS
order by next_fire_time;
```

查看调度配置：

```sql
select id, workflow_definition_code, crontab, timezone_id,
       release_state, failure_strategy, worker_group, tenant_code
from t_ds_schedules
order by id desc
limit 20;
```

## 19. 常见问题

### 19.1 API 调用成功，但 Master 没有执行

优先检查：

```text
t_ds_command 是否有记录
MasterSlotManager.currentSlot / totalSlots 是否有效
CommandEngine 是否因为 load protection 跳过消费
Master 是否连接的是同一个 MySQL
```

相关断点：

```text
IdSlotBasedCommandFetcher.fetchCommands()
CommandEngine.bootstrapWorkflowExecution(...)
```

### 19.2 `t_ds_command` 一直不减少

可能原因：

```text
Master 没有启动成功
ZooKeeper 中 Master slot 无效
Master 过载保护触发
命令处理异常后进入 t_ds_error_command
当前命令属于其他 Master slot
```

### 19.3 任务一直 SUBMITTED_SUCCESS

可能原因：

```text
TaskDispatchLifecycleEvent 没有发布
task group slot 未获取
TaskExecutionContext 初始化失败
WorkerGroupDispatcher 没有启动
worker group 不存在
```

相关断点：

```text
TaskSubmittedStateAction.onStartEvent(...)
TaskSubmittedStateAction.onDispatchEvent(...)
TaskInstanceControllerImpl.taskWakeup(...)
```

### 19.4 任务一直 DISPATCH

可能原因：

```text
已经发给 Worker，但 Worker 没有回调 running
Worker RPC 不通
Worker 执行器启动失败
Master 的 TaskExecutorEventListenerImpl 没有收到回调
```

相关断点：

```text
PhysicalTaskExecutorClientDelegator.dispatch(...)
TaskExecutorEventListenerImpl.onTaskExecutorRunning(...)
```

### 19.5 工作流结束了但没有告警

优先检查：

```text
workflow warningType / warningGroupId 是否配置
AbstractWorkflowStateAction.finalizeEventAction(...) 是否执行
WorkflowAlertManager 是否写入 t_ds_alert
gyyun-alert-server active 节点是否启动
```

### 19.6 调度不触发

优先检查：

```text
Master 是否执行 SchedulerApi.start()
QRTZ_JOB_DETAILS / QRTZ_TRIGGERS 是否有记录
t_ds_schedules.release_state 是否 ONLINE
t_ds_workflow_definition.release_state 是否 ONLINE
Quartz 表是否已经初始化
```

相关断点：

```text
QuartzScheduler.start()
ProcessScheduleTask.executeInternal(...)
```

### 19.7 串行工作流一直 SERIAL_WAIT

优先检查：

```text
当前是否有 active MasterCoordinator
WorkflowSerialCoordinator 是否启动
t_ds_serial_command 是否存在 WAITING 记录
前一个串行实例是否还未结束
executionType 是 SERIAL_WAIT / SERIAL_DISCARD / SERIAL_PRIORITY 中哪一种
```

相关断点：

```text
MasterCoordinator.changeToActive()
WorkflowSerialCoordinator.doStart()
AbstractSerialCommandHandler.launchSerialCommand(...)
```

## 20. 本机更接近生产的启动顺序

为了 debug Master 的完整链路，本机建议按下面顺序启动：

```text
1. MySQL
2. ZooKeeper
3. MinIO
4. gyyun-alert-server
5. gyyun-master
6. gyyun-worker
7. gyyun-api
8. 前端 UI
```

Master 至少依赖：

| 依赖 | 原因 |
| --- | --- |
| MySQL | 读取 workflow definition、消费 command、写 workflow/task instance、Quartz JDBC |
| ZooKeeper | 注册 Master 心跳、发现 Worker、Master slot、coordinator 选主、failover |
| Worker | 物理任务下发必须有可用 Worker |
| Alert Server | 工作流结束告警链路需要 alert-server 消费 |
| MinIO | 资源文件、日志或存储相关任务链路可能依赖 |

如果只 debug 逻辑任务或命令消费，可以暂时不启动 Worker；但物理任务会因为找不到 worker group 或无可用 Worker 而派发失败。

## 21. 一句话数据流

```text
gyyun-master 的核心不是 Controller-Service-DAO，而是
RPC 触发写命令 -> CommandEngine 消费命令 -> WorkflowEventBus 驱动 DAG -> TaskStateMachine 派发任务 -> Worker 回调事件 -> DAG 继续推进 -> Workflow finalize。
```

