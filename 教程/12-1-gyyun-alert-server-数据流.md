# gyyun-alert-server 数据流分析

本文分析 `gyyun-alert/gyyun-alert-server` 模块的核心数据流，重点回答几个问题：

- 告警数据从哪里进入系统；
- `gyyun-alert-server` 启动后做了哪些组件初始化；
- 同步 RPC 发送和数据库异步发送分别怎么流转；
- Alert Server 如何通过 ZooKeeper 做 active/standby；
- 告警插件、告警组、告警实例和数据库表之间是什么关系；
- 本机 IDEA Debug 时应该在哪些类和方法上打断点。

## 1. 总体结论

`gyyun-alert-server` 是一个独立的 Spring Boot 服务，主类是：

```text
com.gyyun.ds.alert.AlertServer
```

它同时承担三类职责：

| 职责 | 说明 |
| --- | --- |
| RPC 服务端 | 暴露 `IAlertOperator`，接收 API 或其他服务发来的测试告警、同步告警请求 |
| 异步告警消费器 | 从 `t_ds_alert` 表轮询 `WAIT_EXECUTION` 状态的告警，调用插件发送，并回写状态 |
| HA 节点 | 通过注册中心参与 Alert HA 选主，只有 active 节点会启动异步告警消费循环 |

需要特别注意：

```text
AlertEventPendingQueue 是内存阻塞队列。
真正保证告警不丢的是 t_ds_alert 表，而不是内存队列。
```

也就是说，异步告警的可靠性来自：

```text
上游服务先把告警写入 t_ds_alert
  -> alert-server active 节点轮询 WAIT_EXECUTION 数据
  -> 发送完成后更新 t_ds_alert.alert_status
```

## 2. 启动数据流

入口类：

```text
gyyun-alert/gyyun-alert-server/src/main/java/com/gyyun/ds/alert/AlertServer.java
```

启动顺序如下：

```text
AlertServer.main()
  -> SpringApplication.run(AlertServer.class, args)
  -> @PostConstruct AlertServer.run()
    -> ServerLifeCycleManager.toRunning()
    -> AlertPluginManager.start()
    -> AlertRpcServer.start()
    -> AlertRegistryClient.start()
    -> AlertHAServer.addServerStatusChangeListener(...)
    -> AlertHAServer.start()
```

其中最关键的顺序是：

```text
先加载告警插件
再启动 RPC 服务
再写注册中心心跳
最后参与 HA 选主
```

源码位置：

| 类 | 作用 |
| --- | --- |
| `AlertServer` | Spring Boot 主类，编排启动和关闭 |
| `AlertPluginManager` | SPI 扫描告警插件，写入 `t_ds_plugin_define` |
| `AlertRpcServer` | Netty RPC 服务端，监听 `alert.port` |
| `AlertRegistryClient` | 写 Alert Server 心跳到注册中心 |
| `AlertHAServer` | 基于注册中心做 Alert HA active/standby |
| `AlertBootstrapService` | active 后启动异步拉取和发送循环 |

## 3. HA 数据流

Alert Server 使用注册中心做 HA。

相关类：

```text
com.gyyun.ds.alert.service.AlertHAServer
com.gyyun.ds.alert.registry.AlertRegistryClient
com.gyyun.ds.alert.registry.AlertHeartbeatTask
```

HA 路径来自：

```text
RegistryNodeType.ALERT_HA_LEADER.getRegistryPath()
```

Alert Server 心跳路径来自：

```text
RegistryNodeType.ALERT_SERVER.getRegistryPath() + "/" + alertServerAddress
```

心跳内容模型是：

```text
com.gyyun.ds.common.model.AlertServerHeartBeat
```

心跳中会包含：

```text
processId
startupTime
reportTime
jvmCpuUsage
cpuUsage
memoryUsage
jvmMemoryUsage
diskUsage
serverStatus
isActive
host
port
```

当节点 HA 状态变化时，`AlertServer.run()` 注册的监听器会触发：

```text
changeToActive()
  -> alertBootstrapService.start()
    -> alertEventFetcher.start()
    -> alertEventLoop.start()

changeToStandBy()
  -> close()
```

因此：

```text
只有 active alert-server 会消费 t_ds_alert 中的待执行告警。
standby alert-server 可以存在，但不会启动异步告警处理循环。
```

## 4. 入口一：API 测试告警 RPC

页面上配置告警插件实例后，通常会有“测试发送”动作。

API 侧入口：

```text
gyyun-api/src/main/java/com/gyyun/ds/api/service/impl/AlertPluginInstanceServiceImpl.java
```

核心调用链：

```text
AlertPluginInstanceServiceImpl.testSend(...)
  -> getAlertServerAddress()
    -> registryClient.getServerList(RegistryNodeType.ALERT_SERVER)
  -> Clients.withService(IAlertOperator.class)
      .withHost(alertServerAddress)
      .sendTestAlert(AlertTestSendRequest)
```

Alert Server 侧接收：

```text
gyyun-alert/gyyun-alert-server/src/main/java/com/gyyun/ds/alert/rpc/AlertOperatorImpl.java
```

处理链路：

```text
AlertOperatorImpl.sendTestAlert(...)
  -> AlertSender.syncTestSend(pluginDefineId, pluginInstanceParams)
    -> AlertPluginManager.getAlertChannel(pluginDefineId)
    -> 构造 AlertData(TEST_TITLE, TEST_CONTENT)
    -> 构造 AlertInfo(alertData, alertParams)
    -> AlertChannel.process(alertInfo)
    -> 返回 AlertSendResponse
```

这条链路的特点：

| 特点 | 说明 |
| --- | --- |
| 是否落 `t_ds_alert` | 不落库 |
| 是否经过异步队列 | 不经过 |
| 是否需要 active 节点 | 代码没有显式要求 active，只要 RPC 打到某个可用 alert-server 即可 |
| 主要用途 | 测试插件实例参数能否正常发送 |

## 5. 入口二：同步发送告警 RPC

RPC 契约来自：

```text
gyyun-extract/gyyun-extract-alert/src/main/java/com/gyyun/ds/extract/alert/IAlertOperator.java
```

接口方法：

```text
AlertSendResponse sendAlert(AlertSendRequest alertSendRequest);
AlertSendResponse sendTestAlert(AlertTestSendRequest alertSendRequest);
```

同步发送入口：

```text
AlertOperatorImpl.sendAlert(AlertSendRequest)
```

处理链路：

```text
AlertOperatorImpl.sendAlert(...)
  -> AlertSender.syncHandler(groupId, title, content)
    -> AlertDao.listInstanceByAlertGroupId(groupId)
      -> t_ds_alertgroup.alert_instance_ids
      -> t_ds_alert_plugin_instance
    -> 构造 AlertData(title, content)
    -> 遍历每个 AlertPluginInstance
      -> AbstractEventSender.doSendEvent(instance, alertData)
        -> AlertPluginManager.getAlertChannel(pluginDefineId)
        -> JSONUtils.toMap(instance.pluginInstanceParams)
        -> AlertChannel.process(AlertInfo)
    -> 聚合每个插件返回的 AlertResult
    -> 返回 AlertSendResponse
```

这条链路的特点：

| 特点 | 说明 |
| --- | --- |
| 是否落 `t_ds_alert` | 不落库 |
| 是否经过异步队列 | 不经过 |
| 发送模式 | RPC 线程同步调用插件 |
| 结果返回 | 直接返回给调用方 |
| 告警组作用 | 用 `groupId` 找到绑定的插件实例列表 |

如果告警组没有绑定插件实例，返回失败：

```text
Alert GroupId ${groupId} send error : not found alert instance
```

## 6. 入口三：业务告警先入库，再异步发送

这是系统内部工作流告警、任务超时告警、容错告警的主链路。

上游写库位置主要在：

```text
gyyun-service/src/main/java/com/gyyun/ds/service/alert/WorkflowAlertManager.java
gyyun-dao/src/main/java/com/gyyun/ds/dao/AlertDao.java
```

典型入口：

| 上游场景 | 写入方法 |
| --- | --- |
| 工作流结束告警 | `WorkflowAlertManager.sendAlertWorkflowInstance(...)` -> `AlertDao.addAlert(...)` |
| 工作流超时 | `WorkflowAlertManager.sendWorkflowTimeoutAlert(...)` -> `AlertDao.sendWorkflowTimeoutAlert(...)` |
| 任务超时 | `WorkflowAlertManager.sendTaskTimeoutAlert(...)` -> `AlertDao.sendTaskTimeoutAlert(...)` |
| Master/Worker 停止容错告警 | `AlertDao.sendServerStoppedAlert(...)` |

写入 `t_ds_alert` 时，关键字段是：

| 字段 | 含义 |
| --- | --- |
| `id` | 告警主键，也是异步轮询 offset |
| `sign` | `sha1(content)`，用于部分场景去重 |
| `title` | 告警标题 |
| `content` | 告警内容，通常是 JSON |
| `alert_status` | 告警执行状态，`0` 表示等待执行 |
| `warning_type` | 成功、失败、全部等告警类型 |
| `alertgroup_id` | 告警组 ID |
| `log` | 发送结果日志，异步发送完成后写回 |
| `project_code` | 项目编码 |
| `workflow_definition_code` | 工作流定义编码 |
| `workflow_instance_id` | 工作流实例 ID |
| `alert_type` | 告警来源类型 |

`AlertStatus` 枚举如下：

| 状态 | code | 含义 |
| --- | ---: | --- |
| `WAIT_EXECUTION` | 0 | 等待执行 |
| `EXECUTION_SUCCESS` | 1 | 全部插件发送成功 |
| `EXECUTION_FAILURE` | 2 | 全部插件发送失败，或没有绑定插件实例 |
| `EXECUTION_PARTIAL_SUCCESS` | 3 | 部分插件发送成功 |

## 7. 异步发送主流程

异步发送只在 active Alert Server 中运行。

启动入口：

```text
AlertBootstrapService.start()
  -> AlertEventFetcher.start()
  -> AlertEventLoop.start()
```

完整数据流：

```text
t_ds_alert(alert_status = WAIT_EXECUTION)
  -> AlertEventFetcher.fetchPendingEvent(eventOffset)
    -> AlertDao.listPendingAlerts(eventOffset)
      -> AlertMapper.listingAlertByStatus(...)
      -> select * from t_ds_alert
         where id > #{minAlertId}
           and alert_status = 0
         order by id asc
         limit 100
  -> AlertEventPendingQueue.put(alert)
  -> AlertEventLoop.take()
  -> CompletableFuture.runAsync(...)
  -> AlertSender.sendEvent(alert)
  -> AbstractEventSender.doSendEvent(...)
  -> AlertChannel.process(alertInfo)
  -> AlertSender.onSuccess/onPartialSuccess/onError
  -> AlertDao.updateAlert(...)
  -> update t_ds_alert set alert_status=?, log=?, update_time=?
```

### 7.1 Fetcher 轮询逻辑

核心类：

```text
com.gyyun.ds.alert.service.AlertEventFetcher
com.gyyun.ds.alert.service.AbstractEventFetcher
```

轮询参数写死在 `AbstractEventFetcher`：

```text
FETCH_SIZE = 100
FETCH_INTERVAL = 5000 ms
eventOffset 初始值 = -1
```

实际 SQL 的 limit 来自 `AlertDao.QUERY_ALERT_THRESHOLD`：

```text
QUERY_ALERT_THRESHOLD = 100
```

轮询逻辑：

```text
while running:
  if 当前节点不是 active:
    sleep 5s
    continue

  pendingEvents = fetchPendingEvent(eventOffset)

  if pendingEvents 为空:
    sleep 5s
    continue

  for alert in pendingEvents:
    eventPendingQueue.put(alert)

  eventOffset = max(eventOffset, max(alert.id))
```

一个重要影响：

```text
eventOffset 只向前移动。
如果某条 id 较小的告警发送失败且 alert_status 仍未被更新，当前进程后续不会再因为轮询 offset 回头捞它。
服务重启后 eventOffset 重新变为 -1，才会再次扫描 WAIT_EXECUTION。
```

但正常发送失败时，代码会把状态更新成 `EXECUTION_FAILURE`，所以不会一直保持 `WAIT_EXECUTION`。

### 7.2 内存队列

核心类：

```text
com.gyyun.ds.alert.service.AlertEventPendingQueue
com.gyyun.ds.alert.service.AbstractEventPendingQueue
```

队列类型：

```text
LinkedBlockingQueue
```

队列容量：

```text
alert.sender-parallelism * 3 + 1
```

默认配置：

```yaml
alert:
  sender-parallelism: 100
```

默认队列容量就是：

```text
100 * 3 + 1 = 301
```

这只是进程内缓冲。Alert Server 重启后，内存队列会丢失，但未更新状态的告警仍可以从 `t_ds_alert` 重新扫描。

### 7.3 EventLoop 并发处理

核心类：

```text
com.gyyun.ds.alert.service.AlertEventLoop
com.gyyun.ds.alert.service.AbstractEventLoop
com.gyyun.ds.alert.service.AlertSenderThreadPoolFactory
```

线程池：

```text
ThreadUtils.newDaemonFixedThreadExecutor("AlertSenderThread", alert.sender-parallelism)
```

处理逻辑：

```text
while running:
  if handlingEventCount >= senderParallelism:
    sleep 1s
    continue

  alert = eventPendingQueue.take()
  handlingEventCount++

  CompletableFuture.runAsync(() -> alertSender.sendEvent(alert), threadPool)
    .whenComplete(...)
      -> handlingEventCount--
```

因此：

```text
alert.sender-parallelism 控制并发发送的告警条数，而不是单条告警内插件并发数。
```

单条告警绑定多个插件实例时，`AbstractEventSender.sendEvent(...)` 是按插件实例顺序循环发送。

## 8. 插件发送数据流

插件加载入口：

```text
com.gyyun.ds.alert.plugin.AlertPluginManager.start()
```

加载流程：

```text
AlertPluginManager.start()
  -> checkAlertPluginExist()
    -> 检查 t_ds_plugin_define 是否存在
  -> installAlertPlugin()
    -> PrioritySPIFactory<AlertChannelFactory>
    -> 扫描所有 AlertChannelFactory
    -> factory.create() 创建 AlertChannel
    -> factory.params() 生成插件参数定义
    -> pluginDao.addOrUpdatePluginDefine(...)
    -> alertPluginMap.put(pluginDefineId, alertChannel)
```

插件接口：

```text
gyyun-alert/gyyun-alert-plugins/gyyun-alert-api/src/main/java/com/gyyun/ds/alert/api/AlertChannel.java
```

```java
public interface AlertChannel {
    AlertResult process(AlertInfo info);
}
```

插件工厂接口：

```text
gyyun-alert/gyyun-alert-plugins/gyyun-alert-api/src/main/java/com/gyyun/ds/alert/api/AlertChannelFactory.java
```

```java
public interface AlertChannelFactory extends PrioritySPI {
    String name();
    AlertChannel create();
    List<PluginParams> params();
}
```

发送时构造的核心对象：

```text
AlertData
  - id
  - title
  - content
  - log
  - alertType

AlertInfo
  - alertParams
  - alertData
  - alertPluginInstanceId

AlertResult
  - success
  - message
```

发送方法：

```text
AbstractEventSender.doSendEvent(AlertPluginInstance instance, AlertData alertData)
```

核心逻辑：

```text
pluginDefineId = instance.pluginDefineId
alertChannel = alertPluginManager.getAlertChannel(pluginDefineId)

alertInfo = AlertInfo.builder()
  .alertData(alertData)
  .alertParams(JSONUtils.toMap(instance.pluginInstanceParams))
  .alertPluginInstanceId(instance.id)
  .build()

if alert.wait-timeout <= 0:
  alertChannel.process(alertInfo)
else:
  CompletableFuture.supplyAsync(() -> alertChannel.process(alertInfo))
    .get(alert.wait-timeout, TimeUnit.MILLISECONDS)
```

默认配置：

```yaml
alert:
  wait-timeout: 0
```

含义：

```text
默认不做超时截断，会一直等待插件返回。
```

## 9. 告警组、插件定义、插件实例的关系

这几个表容易混淆：

| 表 | 作用 |
| --- | --- |
| `t_ds_plugin_define` | 插件定义表，由 `AlertPluginManager` 根据 SPI 自动注册或更新 |
| `t_ds_alert_plugin_instance` | 插件实例表，保存用户在页面上配置的插件参数 |
| `t_ds_alertgroup` | 告警组表，通过 `alert_instance_ids` 绑定多个插件实例 |
| `t_ds_alert` | 待发送和已发送的业务告警表 |
| `t_ds_alert_send_status` | 设计上用于记录单插件发送状态，但当前主发送链路没有调用写入方法 |

关系图：

```text
t_ds_alert.alertgroup_id
  -> t_ds_alertgroup.id
    -> t_ds_alertgroup.alert_instance_ids = "1,2,3"
      -> t_ds_alert_plugin_instance.id
        -> t_ds_alert_plugin_instance.plugin_define_id
          -> t_ds_plugin_define.id
            -> AlertPluginManager.alertPluginMap[pluginDefineId]
              -> AlertChannel.process(...)
```

`AlertDao.listInstanceByAlertGroupId(alertGroupId)` 的逻辑：

```text
select alert_instance_ids from t_ds_alertgroup where id = #{alertGroupId}
  -> 按逗号切分成插件实例 ID 列表
  -> select * from t_ds_alert_plugin_instance where id in (...)
```

所以一次告警能发给哪些渠道，不是看 `t_ds_alert` 本身，而是看：

```text
t_ds_alert.alertgroup_id -> t_ds_alertgroup.alert_instance_ids
```

## 10. 异步发送结果如何回写

异步主链路中，发送结果在 `AbstractEventSender.sendEvent(...)` 聚合。

逻辑：

```text
for each AlertPluginInstance:
  alertResult = doSendEvent(instance, alertData)
  alertSendStatus = {
    alertId,
    alertPluginInstanceId,
    sendStatus,
    log,
    createTime
  }
```

然后统计：

```text
successCount = 成功插件数量
failureCount = 失败插件数量
```

最终状态：

| 条件 | 回写状态 |
| --- | --- |
| `successCount == 0` | `EXECUTION_FAILURE` |
| `successCount > 0 && failureCount > 0` | `EXECUTION_PARTIAL_SUCCESS` |
| `successCount > 0 && failureCount == 0` | `EXECUTION_SUCCESS` |

回写方法：

```text
AlertSender.onError(...)
AlertSender.onPartialSuccess(...)
AlertSender.onSuccess(...)
  -> AlertDao.updateAlert(...)
```

实际更新的是：

```text
t_ds_alert.alert_status
t_ds_alert.log
t_ds_alert.update_time
```

注意：

```text
虽然 AbstractEventSender.sendEvent(...) 内部构造了 List<AlertSendStatus>，
但当前源码没有调用 AlertDao.insertAlertSendStatus(alertSendStatuses)。

也就是说，当前主发送链路的插件级发送结果被序列化后写入 t_ds_alert.log，
不是逐条写入 t_ds_alert_send_status。
```

`t_ds_alert_send_status` 表和 DAO 方法仍然存在：

```text
AlertDao.addAlertSendStatus(...)
AlertDao.insertAlertSendStatus(...)
AlertSendStatusMapper.batchInsert(...)
```

但在当前主流程里没有被调用。

## 11. 配置项

配置文件：

```text
gyyun-alert/gyyun-alert-server/src/main/resources/application.yaml
```

核心配置：

```yaml
server:
  port: 50053

alert:
  port: 50052
  wait-timeout: 0
  max-heartbeat-interval: 60s
  sender-parallelism: 100

registry:
  type: zookeeper
  zookeeper:
    namespace: gyyun
    connect-string: localhost:4206
```

含义：

| 配置 | 说明 |
| --- | --- |
| `server.port` | Spring Boot HTTP/Actuator 端口 |
| `alert.port` | Alert RPC 服务端口 |
| `alert.wait-timeout` | 单次插件发送超时时间，`0` 表示无限等待 |
| `alert.max-heartbeat-interval` | 写注册中心心跳的间隔 |
| `alert.sender-parallelism` | 异步告警并发处理数量 |
| `registry.zookeeper.connect-string` | ZooKeeper 地址 |

MySQL profile 中还配置了：

```yaml
spring:
  profiles:
    active: mysql
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3317/ala-data-ds
    username: ds
    password: 32WO3ad2JZSiCGsl
```

## 12. IDEA Debug 推荐断点

### 12.1 启动和 HA

| 断点位置 | 看什么 |
| --- | --- |
| `AlertServer.run()` | 组件启动顺序 |
| `AlertPluginManager.installAlertPlugin()` | 插件是否被 SPI 扫描到，`pluginDefineId` 是多少 |
| `AlertRpcServer` 构造方法 | RPC 监听端口是否正确 |
| `AlertRegistryClient.start()` | 是否开始写注册中心心跳 |
| `AlertHeartbeatTask.writeHeartBeat(...)` | ZooKeeper 中写入的心跳内容 |
| `AlertHAServer.start()` | 是否参与 HA 选主 |
| `AlertServer.changeToActive()` | 是否成为 active 后启动异步循环 |

### 12.2 测试发送和同步 RPC

| 断点位置 | 看什么 |
| --- | --- |
| `AlertOperatorImpl.sendTestAlert(...)` | API 测试发送是否打到 Alert Server |
| `AlertSender.syncTestSend(...)` | 插件实例参数解析是否正确 |
| `AlertOperatorImpl.sendAlert(...)` | 普通同步告警 RPC 是否进入 |
| `AlertSender.syncHandler(...)` | 告警组是否能查到插件实例 |
| `AbstractEventSender.doSendEvent(...)` | 插件渠道是否存在，`AlertInfo` 是否正确 |
| 具体插件的 `AlertChannel.process(...)` | 最终发送渠道请求参数和返回值 |

### 12.3 异步告警

| 断点位置 | 看什么 |
| --- | --- |
| `WorkflowAlertManager.sendAlertWorkflowInstance(...)` | 工作流结束时是否生成告警 |
| `AlertDao.addAlert(...)` | 是否写入 `t_ds_alert` |
| `AlertEventFetcher.fetchPendingEvent(...)` | 是否能捞到 `WAIT_EXECUTION` 数据 |
| `AbstractEventFetcher.run()` | `eventOffset` 如何变化 |
| `AlertEventPendingQueue.put(...)` | 是否进入内存队列 |
| `AbstractEventLoop.run()` | 是否提交到发送线程池 |
| `AlertSender.sendEvent(...)` | 是否进入异步发送 |
| `AlertSender.onSuccess/onPartialSuccess/onError` | 最终状态如何回写 |
| `AlertDao.updateAlert(...)` | `t_ds_alert.alert_status/log` 最终值 |

## 13. 本机排查 SQL

查看待发送告警：

```sql
select id, title, alert_status, warning_type, alertgroup_id, create_time, update_time
from t_ds_alert
where alert_status = 0
order by id asc
limit 20;
```

查看最近发送结果：

```sql
select id, title, alert_status, alertgroup_id, log, update_time
from t_ds_alert
order by id desc
limit 20;
```

查看告警组绑定了哪些插件实例：

```sql
select id, group_name, alert_instance_ids
from t_ds_alertgroup
order by id;
```

查看插件实例：

```sql
select id, instance_name, plugin_define_id, plugin_instance_params
from t_ds_alert_plugin_instance
order by id;
```

查看插件定义：

```sql
select id, plugin_name, plugin_type, plugin_params
from t_ds_plugin_define
where plugin_type = 'alert'
order by id;
```

## 14. 常见问题

### 14.1 Alert Server 启动了，但异步告警不发送

优先检查：

```text
1. 当前节点是否 active
2. ZooKeeper 是否正常
3. t_ds_alert 中是否有 alert_status = 0 的数据
4. t_ds_alert.alertgroup_id 是否对应真实告警组
5. t_ds_alertgroup.alert_instance_ids 是否为空
6. t_ds_alert_plugin_instance.plugin_define_id 是否能在 AlertPluginManager.alertPluginMap 中找到
```

### 14.2 页面测试发送失败

优先检查：

```text
1. API 是否能从注册中心拿到 ALERT_SERVER
2. alert.port 是否是 RPC 端口，默认 50052
3. 插件实例参数 JSON 是否能被 PluginParamsTransfer 或 JSONUtils 正确解析
4. 具体插件的外部服务地址、token、webhook 是否有效
```

### 14.3 `t_ds_alert_send_status` 为什么没有数据

当前主发送链路中没有调用：

```text
AlertDao.insertAlertSendStatus(...)
```

插件级发送结果会作为 JSON 写入：

```text
t_ds_alert.log
```

因此本机调试时应优先看 `t_ds_alert.log`，不要把 `t_ds_alert_send_status` 当成当前链路的必写审计表。

### 14.4 插件定义没有写入 `t_ds_plugin_define`

检查：

```text
1. AlertPluginManager.start() 是否执行
2. t_ds_plugin_define 表是否存在
3. IDEA classpath 是否包含 gyyun-alert-all 或具体 alert 插件模块
4. 具体插件模块是否提供 AlertChannelFactory SPI 元数据
```

`gyyun-alert-server/pom.xml` 中 `gyyun-alert-all` 是 `provided` scope。生产发行包通常通过 assembly 把插件放入运行环境；IDEA 本机调试时如果插件没有被扫描到，需要确认模块依赖和运行 classpath。

## 15. 一句话数据流

同步 RPC：

```text
调用方 -> IAlertOperator RPC -> AlertOperatorImpl -> AlertSender.syncHandler/syncTestSend -> AlertChannel.process -> AlertSendResponse
```

异步业务告警：

```text
业务服务 -> AlertDao 写 t_ds_alert(WAIT_EXECUTION)
  -> active alert-server 轮询
  -> 内存队列
  -> 发送线程池
  -> 告警组绑定的插件实例
  -> AlertChannel.process
  -> 回写 t_ds_alert.alert_status/log
```

