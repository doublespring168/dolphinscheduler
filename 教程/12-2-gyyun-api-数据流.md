# gyyun-api 数据流分析

本文分析 `gyyun-api` 模块的核心数据流，重点覆盖：

- API Server 启动时做了什么；
- HTTP 请求如何经过拦截器、认证、Controller、Service；
- 登录态、token、权限、审计和异常如何流转；
- 工作流定义保存、工作流启动、调度上线、资源、日志、监控、告警测试等典型链路；
- 本机 IDEA Debug 时应该在哪些类和方法上打断点。

## 1. 总体结论

`gyyun-api` 是系统对 UI 和外部客户端暴露的 REST API 服务，启动类是：

```text
com.gyyun.ds.api.ApiApplicationServer
```

默认 HTTP 地址：

```text
http://127.0.0.1:12345/gyyun/
```

它不是 Master，也不是 Worker。它的核心定位是：

```text
HTTP 网关 + 权限校验 + 参数转换 + 业务编排 + DB/RPC/Storage/Registry 聚合层
```

典型请求流：

```text
浏览器 / curl / UI
  -> Jetty HTTP
  -> Spring MVC
  -> LocaleChangeInterceptor
  -> RateLimitInterceptor
  -> LoginHandlerInterceptor
  -> Controller
  -> Service
  -> DAO / gyyun-service / Registry / RPC / Storage / Quartz
  -> Result<T>
  -> JSON 响应
```

模块中有 35 个 Controller，覆盖项目、工作流、任务、调度、资源、数据源、告警、监控、用户、租户、日志等入口。

## 2. 启动数据流

入口文件：

```text
gyyun-api/src/main/java/com/gyyun/ds/api/ApiApplicationServer.java
```

启动类导入的配置：

```java
@Import({
    DaoConfiguration.class,
    CommonConfiguration.class,
    ServiceConfiguration.class,
    StorageConfiguration.class,
    RegistryConfiguration.class
})
```

启动流程：

```text
ApiApplicationServer.main()
  -> 注册未捕获异常指标
  -> 设置 DefaultUncaughtExceptionHandler
  -> SpringApplication.run(ApiApplicationServer.class)
  -> Spring 容器初始化
    -> DaoConfiguration
    -> CommonConfiguration
    -> ServiceConfiguration
    -> StorageConfiguration
    -> RegistryConfiguration
    -> Controller / Service / Interceptor / Validator Bean
  -> ApplicationReadyEvent
    -> ServerLifeCycleManager.toRunning()
    -> DataSourcePluginManager.loadDataSourcePlugin()
    -> TaskPluginManager.loadTaskPlugin()
```

启动完成后，API 会加载：

| 插件 | 作用 |
| --- | --- |
| `DataSourcePluginManager` | 加载数据源插件，供数据源创建、连接测试、元数据查询使用 |
| `TaskPluginManager` | 加载任务插件，供任务参数校验、任务类型配置和工作流定义保存使用 |

注意：

```text
API Server 启动不会自动同步数据库结构。
数据库初始化/升级仍然要通过 gyyun-tools 单独执行。
```

## 3. 关键配置

配置文件：

```text
gyyun-api/src/main/resources/application.yaml
```

核心配置：

```yaml
server:
  port: 12345
  servlet:
    context-path: /gyyun/

spring:
  application:
    name: api-server
  profiles:
    active: mysql
  quartz:
    auto-startup: false
    jdbc:
      initialize-schema: never

registry:
  type: zookeeper
  zookeeper:
    namespace: gyyun
    connect-string: localhost:4206

security:
  authentication:
    type: PASSWORD

api:
  base-url: http://127.0.0.1:12345/gyyun
  ui-url: http://127.0.0.1:5173
  audit-enable: false
```

几个容易混淆的点：

| 配置 | 说明 |
| --- | --- |
| `server.port` | API HTTP 端口，默认 `12345` |
| `server.servlet.context-path` | 所有 API 的统一前缀，默认 `/gyyun/` |
| `spring.quartz.auto-startup` | API 内部不会自动启动 Quartz 调度线程 |
| `spring.quartz.jdbc.initialize-schema` | 设置为 `never`，不会自动建 Quartz 表 |
| `registry.zookeeper.connect-string` | API 查询 Master/Worker/Alert 节点和发 RPC 的基础 |
| `security.authentication.type` | 选择认证实现，默认 `PASSWORD` |
| `api.audit-enable` | 审计开关，当前配置默认关闭 |

## 4. HTTP 入口层

Web 配置类：

```text
gyyun-api/src/main/java/com/gyyun/ds/api/configuration/AppConfiguration.java
```

主要职责：

| 配置点 | 作用 |
| --- | --- |
| `CorsFilter` | 允许跨域 |
| `LocaleResolver` | 从 `language` cookie 解析语言 |
| `LocaleChangeInterceptor` | 从 header 解析语言 |
| `RateLimitInterceptor` | 全局或租户级限流 |
| `LoginHandlerInterceptor` | 登录态或 token 校验 |
| `/ui/**` 静态资源映射 | 指向本地 `ui/` 目录 |
| `/` 和 `/ui/` 跳转 | 默认跳 UI 首页 |

拦截器注册顺序：

```text
LocaleChangeInterceptor
  -> RateLimitInterceptor
  -> LoginHandlerInterceptor
```

登录拦截排除路径包括：

```text
/login/**
/users/register
/swagger-resources/**
/webjars/**
/v3/api-docs/**
/api-docs/**
/swagger-ui.html
/doc.html
/swagger-ui/**
*.html
/ui/**
/error
/oauth2-provider
/redirect/login/oauth2
/cookies
/oidc-providers
/oauth2/authorization/**
/login/oauth2/code/**
```

## 5. 鉴权数据流

登录入口：

```text
gyyun-api/src/main/java/com/gyyun/ds/api/controller/LoginController.java
```

默认认证配置：

```yaml
security:
  authentication:
    type: PASSWORD
```

认证实现选择：

```text
SecurityConfig.authenticator()
  -> PASSWORD    -> PasswordAuthenticator
  -> LDAP        -> LdapAuthenticator
  -> CASDOOR_SSO -> CasdoorAuthenticator
  -> OIDC        -> OidcAuthenticator
```

### 5.1 用户名密码登录

请求：

```text
POST /gyyun/login
```

核心链路：

```text
LoginController.login(userName, userPassword)
  -> BaseController.getClientIpAddress(request)
  -> Authenticator.authenticate(userName, password, ip)
    -> PasswordAuthenticator.login(...)
      -> UsersService.queryUser(userName, password)
    -> SessionService.createSessionIfAbsent(user)
      -> SessionDao 查询/写入 t_ds_session
    -> 返回 sessionId 和 securityConfigType
  -> response.addCookie(sessionId=...)
  -> Result<Map<String, String>>
```

返回数据里会包含：

```text
sessionId
securityConfigType
```

后续请求可以通过：

```text
Cookie: sessionId=...
```

或：

```text
Header: sessionId: ...
```

携带登录态。

### 5.2 普通请求鉴权

核心类：

```text
gyyun-api/src/main/java/com/gyyun/ds/api/interceptor/LoginHandlerInterceptor.java
```

处理逻辑：

```text
LoginHandlerInterceptor.preHandle(...)
  -> ApiServerMetrics.incApiRequestCount()
  -> 先读 Header: token
     -> 如果 token 不为空：
          UserDao.queryUserByToken(token, now)
     -> 如果 token 为空：
          Authenticator.getAuthUser(request)
            -> 从 Header/Cookie 读取 sessionId
            -> SessionService.getSession(sessionId)
            -> SessionService.isSessionExpire(session)
            -> UsersService.queryUser(session.userId)
  -> 校验 user.state
  -> request.setAttribute(Constants.SESSION_USER, user)
  -> ThreadLocalContext.setTimezone(user.timeZone)
```

因此 API 支持两套身份入口：

| 方式 | 来源 | 说明 |
| --- | --- | --- |
| Session | `sessionId` header/cookie | UI 默认登录态 |
| Access Token | `token` header | 外部接口调用常用 |

鉴权失败会直接返回 HTTP 401，不进入 Controller。

## 6. Controller 和 Result 数据流

Controller 一般继承：

```text
BaseController
```

统一响应对象：

```text
com.gyyun.ds.api.utils.Result<T>
```

结构：

```text
code
msg
data
```

典型 Controller 方法形态：

```java
public Result<Something> api(
    @RequestAttribute(Constants.SESSION_USER) User loginUser,
    @PathVariable long projectCode,
    @RequestParam ...
) {
    return Result.success(service.method(loginUser, ...));
}
```

异常包装：

```text
ApiExceptionHandler
  -> ServiceException -> Result(code, message)
  -> Throwable        -> @ApiException 中声明的 Status 或 INTERNAL_SERVER_ERROR_ARGS
```

一个重要特点：

```text
很多接口即使业务失败，HTTP status 仍可能是 200，
真正的业务状态要看 Result.code。
```

## 7. Service 层职责

`gyyun-api` 的 Service 不是简单透传，它通常负责：

| 职责 | 示例 |
| --- | --- |
| 权限校验 | `ProjectService.checkProjectAndAuthThrowException(...)` |
| DTO 转换 | `TriggerWorkflowRequestTransformer`、资源 RequestTransformer |
| 参数校验 | `TriggerWorkflowDTOValidator`、资源 Validator |
| DB 编排 | 调用 DAO、Mapper、`ProcessService` |
| RPC 编排 | 通过 `Clients.withService(...)` 调 Master/Worker/Alert |
| 存储编排 | 通过 `StorageOperator` 操作资源文件 |
| 调度编排 | 通过 `SchedulerApi` 操作 Quartz |

常见依赖边界：

```text
gyyun-api.service.impl
  -> gyyun-dao repository / mapper
  -> gyyun-service
  -> gyyun-extract-master / worker / alert
  -> gyyun-registry
  -> gyyun-storage-plugin
  -> gyyun-scheduler
```

## 8. 工作流定义保存数据流

入口：

```text
POST /gyyun/projects/{projectCode}/workflow-definition
```

Controller：

```text
WorkflowDefinitionController.createWorkflowDefinition(...)
```

核心链路：

```text
WorkflowDefinitionController.createWorkflowDefinition(...)
  -> WorkflowDefinitionServiceImpl.createWorkflowDefinition(...)
    -> projectDao.queryByCode(projectCode)
    -> projectService.checkHasProjectWritePermissionThrowException(...)
    -> workflowDefinitionDao.verifyByDefineName(...)
    -> globalParamsValidator.validate(globalParams)
    -> generateTaskDefinitionList(taskDefinitionJson)
      -> JSONUtils.toList(...)
      -> 检查任务名重复
      -> TaskPluginManager.checkTaskParameters(taskType, taskParams)
    -> generateTaskRelationList(taskRelationJson, taskDefinitionLogs)
      -> JSONUtils.toList(...)
      -> processService.transformTask(...)
      -> graphHasCycle(...)
    -> CodeGenerateUtils.genCode()
    -> createDagDefine(...)
      -> processService.saveTaskDefine(...)
      -> processService.saveWorkflowDefine(...)
      -> processService.saveTaskRelation(...)
      -> saveWorkflowLineage(...)
        -> workflowLineageService.updateWorkflowLineage(...)
```

这条链路主要写这些表：

```text
t_ds_workflow_definition
t_ds_workflow_definition_log
t_ds_task_definition
t_ds_task_definition_log
t_ds_workflow_task_relation
t_ds_workflow_task_relation_log
t_ds_workflow_task_lineage
```

关键结论：

```text
创建/编辑工作流定义是 API 侧 DB 写链路，不会发给 Master 执行。
真正启动运行时，才会通过 RPC 找 Master。
```

## 9. 工作流启动数据流

入口：

```text
POST /gyyun/projects/{projectCode}/executors/start-workflow-instance
```

Controller：

```text
ExecutorController.triggerWorkflowDefinition(...)
```

核心链路：

```text
ExecutorController.triggerWorkflowDefinition(...)
  -> 构造 WorkflowTriggerRequest
  -> ExecutorServiceImpl.triggerWorkflowDefinition(...)
    -> projectService.checkProjectAndAuthThrowException(...)
    -> TriggerWorkflowRequestTransformer.transform(...)
      -> 查询 workflowDefinition 等上下文
    -> 校验 URL projectCode 和 workflowDefinition.projectCode
    -> TriggerWorkflowDTOValidator.validate(...)
    -> ExecutorClient.triggerWorkflowDefinition().execute(...)
      -> TriggerWorkflowExecutorDelegate.execute(...)
        -> registryClient.getRandomServer(RegistryNodeType.MASTER)
        -> Clients.withService(IWorkflowControlClient.class)
              .withHost(master.host + ":" + master.port)
              .manualTriggerWorkflow(WorkflowManualTriggerRequest)
        -> WorkflowManualTriggerResponse
```

RPC 契约：

```text
gyyun-extract/gyyun-extract-master/src/main/java/com/gyyun/ds/extract/master/IWorkflowControlClient.java
```

Master 接口：

```text
manualTriggerWorkflow(...)
backfillTriggerWorkflow(...)
scheduleTriggerWorkflow(...)
repeatTriggerWorkflowInstance(...)
triggerFromFailureTasks(...)
triggerFromSuspendTasks(...)
pauseWorkflowInstance(...)
stopWorkflowInstance(...)
```

关键结论：

```text
手动启动工作流时，API 不直接创建工作流实例。
API 会选择一个可用 Master，通过 RPC 请求 Master 创建和运行工作流实例。
```

如果注册中心没有 Master：

```text
no master server available
```

## 10. 补数数据流

入口仍然是：

```text
POST /gyyun/projects/{projectCode}/executors/start-workflow-instance
```

但参数：

```text
execType=COMPLEMENT_DATA
```

链路：

```text
ExecutorController.triggerWorkflowDefinition(...)
  -> 构造 WorkflowBackFillRequest
  -> ExecutorServiceImpl.backfillWorkflowDefinition(...)
    -> projectService.checkProjectAndAuthThrowException(...)
    -> BackfillWorkflowRequestTransformer.transform(...)
    -> BackfillWorkflowDTOValidator.validate(...)
    -> ExecutorClient.backfillWorkflowDefinition().execute(...)
      -> BackfillWorkflowExecutorDelegate.execute(...)
        -> 如开启 ALL_DEPENDENT，解析下游依赖工作流
        -> 根据 RunMode 串行或并行拆分补数时间
        -> registryClient.getRandomServer(RegistryNodeType.MASTER)
        -> IWorkflowControlClient.backfillTriggerWorkflow(...)
```

这条链路有一个额外特点：

```text
API 会在发 Master RPC 前处理补数日期列表、依赖扩展和串并行拆分。
```

## 11. 工作流控制数据流

入口：

```text
POST /gyyun/projects/{projectCode}/executors/execute
```

参数：

```text
workflowInstanceId
executeType
```

链路：

```text
ExecutorController.controlWorkflowInstance(...)
  -> ExecutorServiceImpl.controlWorkflowInstance(...)
    -> workflowInstanceDao.queryOptionalById(...)
    -> projectService.checkProjectAndAuthThrowException(...)
    -> 根据 executeType 选择 ExecutorDelegate
```

常见操作：

| executeType | Delegate |
| --- | --- |
| `REPEAT_RUNNING` | `RepeatRunningWorkflowInstanceExecutorDelegate` |
| `START_FAILURE_TASK_PROCESS` | `RecoverFailureTaskInstanceExecutorDelegate` |
| `RECOVER_SUSPENDED_PROCESS` | `RecoverSuspendedWorkflowInstanceExecutorDelegate` |
| `PAUSE` | `PauseWorkflowInstanceExecutorDelegate` |
| `STOP` | `StopWorkflowInstanceExecutorDelegate` |

暂停/停止有两类路径：

```text
如果实例状态允许直接改库：
  -> workflowInstanceDao.updateWorkflowInstanceState(...)
  -> serialCommandDao.deleteByWorkflowInstanceId(...)

否则：
  -> Clients.withService(IWorkflowControlClient.class)
       .withHost(workflowInstance.host)
       .pauseWorkflowInstance(...) / stopWorkflowInstance(...)
```

关键结论：

```text
工作流控制不是永远随机找 Master。
对已运行实例，API 经常会使用 workflowInstance.host 回调负责该实例的 Master。
```

## 12. 调度数据流

入口：

```text
/gyyun/projects/{projectCode}/schedules
```

Controller：

```text
SchedulerController
```

创建调度：

```text
SchedulerController.createSchedule(...)
  -> SchedulerServiceImpl.insertSchedule(...)
    -> projectService.checkProjectAndAuthThrowException(...)
    -> workflowDefinitionDao.queryByCode(...)
    -> executorService.checkWorkflowDefinitionValid(...)
    -> scheduleDao.queryByWorkflowDefinitionCode(...)
    -> JSONUtils.parseObject(schedule, ScheduleParam.class)
    -> CronUtils.isValidExpression(...)
    -> tenantExistValidator.validate(...)
    -> scheduleDao.insert(...)
    -> workflowDefinitionDao.update warningGroupId
```

上线调度：

```text
SchedulerController.publishScheduleOnline(...)
  -> SchedulerServiceImpl.onlineScheduler(...)
    -> scheduleDao.queryById(...)
    -> 校验 workflowDefinition 已 ONLINE
    -> schedule.releaseState = ONLINE
    -> scheduleDao.updateById(...)
    -> schedulerApi.insertOrUpdateScheduleTask(project.id, schedule)
```

下线调度：

```text
SchedulerController.offlineSchedule(...)
  -> SchedulerServiceImpl.offlineScheduler(...)
    -> schedule.releaseState = OFFLINE
    -> scheduleDao.updateById(...)
    -> schedulerApi.deleteScheduleTask(project.id, schedule.id)
```

关键结论：

```text
创建/更新 schedule 主要写 DB。
真正让 Quartz 生效的是 onlineScheduler 中调用 SchedulerApi。
```

## 13. 资源中心数据流

入口：

```text
/gyyun/resources
```

Controller：

```text
ResourcesController
```

Service：

```text
ResourcesServiceImpl
```

核心依赖：

```text
StorageOperator
```

上传文件：

```text
ResourcesController.createFile(...)
  -> 构造 CreateFileRequest
  -> ResourcesServiceImpl.createFile(...)
    -> FileRequestTransformer.transform(...)
    -> CreateFileDtoValidator.validate(...)
    -> copyFileToLocal(MultipartFile)
    -> storageOperator.upload(localTmpPath, targetAbsolutePath, ...)
    -> ApiServerMetrics.recordApiResourceUploadSize(...)
```

在线创建文件：

```text
createFileFromContent(...)
  -> 字符串内容写本地临时文件
  -> storageOperator.upload(...)
```

查询资源树：

```text
queryResourceFiles(...)
  -> tenantDao.queryOptionalById(loginUser.tenantId)
  -> storageOperator.getStorageBaseDirectory(tenantCode, resourceType)
  -> storageOperator.listFileStorageEntityRecursively(...)
  -> ResourceTreeVisitor 组装树
```

查看文件内容：

```text
fetchResourceFileContent(...)
  -> FetchFileContentDtoValidator.validate(...)
  -> storageOperator.fetchFileContent(path, skipLineNum, limit)
```

关键结论：

```text
资源文件内容不主要存数据库。
API 通过 StorageOperator 操作底层存储，DB 更多用于用户、租户、权限等元数据。
```

## 14. 日志查询数据流

入口：

```text
GET /gyyun/log/detail
GET /gyyun/log/download-log
```

Controller：

```text
LoggerController
```

核心链路：

```text
LoggerController.queryLog(...)
  -> LoggerServiceImpl.queryLog(...)
    -> taskInstanceDao.queryById(taskInstanceId)
    -> 校验 taskInstance.host
    -> projectService.checkProjectAndAuthThrowException(...)
    -> LogClientDelegate.getPartLogString(taskInstance, skipLineNum, limit)
      -> checkNodeExists(taskInstance)
        -> logic task 查 MASTER
        -> 普通 task 查 WORKER
      -> LocalLogClient.getPartLog(...)
      -> 如果失败，RemoteLogClient.getPartLog(...)
```

下载全量日志：

```text
LoggerServiceImpl.getLogBytes(...)
  -> LogClientDelegate.getWholeLogBytes(...)
    -> 优先 LocalLogClient
    -> 失败或节点不存在时 RemoteLogClient
```

关键结论：

```text
API 自己不执行任务，也不直接生成任务日志。
API 根据 taskInstance.host 找到对应 Master/Worker 或远程日志存储读取日志。
```

## 15. 监控数据流

入口：

```text
GET /gyyun/monitor/{nodeType}
GET /gyyun/monitor/databases
GET /gyyun/monitor/masters/workflow-executors
GET /gyyun/monitor/workers/task-executors
```

Service：

```text
MonitorServiceImpl
```

节点列表：

```text
MonitorServiceImpl.listServer(nodeType)
  -> registryClient.getServerList(nodeType)
```

数据库指标：

```text
MonitorServiceImpl.queryDatabaseState(...)
  -> databaseMonitor.getDatabaseMetrics()
```

Master 运行中工作流：

```text
queryWorkflowExecutors(loginUser, masterAddress)
  -> 仅 ADMIN_USER
  -> Clients.withService(IWorkflowExecutorQueryClient.class)
       .withHost(masterAddress)
       .queryWorkflowExecutors(...)
```

Worker 运行中任务：

```text
queryTaskExecutors(loginUser, serverAddress)
  -> 仅 ADMIN_USER
  -> Clients.withService(ITaskExecutorQueryClient.class)
       .withHost(serverAddress)
       .queryTaskInstances(...)
```

关键结论：

```text
API 的监控页面数据主要来自注册中心、数据库监控插件和 Master/Worker RPC。
```

## 16. 告警测试发送数据流

入口：

```text
POST /gyyun/alert-plugin-instances/test-send
```

Service：

```text
AlertPluginInstanceServiceImpl
```

核心链路：

```text
AlertPluginInstanceServiceImpl.testSend(...)
  -> getAlertServerAddress()
    -> registryClient.getServerList(RegistryNodeType.ALERT_SERVER)
  -> Clients.withService(IAlertOperator.class)
       .withHost(alertServerAddress)
       .sendTestAlert(AlertTestSendRequest)
  -> AlertSendResponse
```

关键结论：

```text
告警测试发送不由 API 直接调用 webhook/email。
API 找 Alert Server，然后通过 IAlertOperator RPC 让 alert-server 执行插件发送。
```

## 17. 数据源数据流

API 启动时加载：

```text
DataSourcePluginManager.loadDataSourcePlugin()
```

数据源相关入口：

```text
DataSourceController
DataSourceServiceImpl
```

典型职责：

```text
创建/更新数据源
  -> 参数 DTO 校验
  -> 数据源密码等敏感信息处理
  -> 写 t_ds_datasource

连接测试/库表查询
  -> 根据数据源类型找到 datasource plugin
  -> 构造连接参数
  -> 调插件执行 connect/test/list database/list table/list column
```

关键结论：

```text
API 不把不同数据源类型写死在 Controller。
数据源类型能力主要通过 DataSourcePluginManager 和插件实现提供。
```

## 18. 审计数据流

审计注解：

```text
@OperatorLog(auditType = ...)
```

切面：

```text
gyyun-api/src/main/java/com/gyyun/ds/api/audit/OperatorLogAspect.java
```

处理链路：

```text
Controller method with @OperatorLog
  -> OperatorLogAspect.before(...)
    -> 读取 @Operation 描述
    -> OperatorUtils.getParamsMap(...)
    -> OperatorUtils.getUser(...)
    -> 根据 AuditType 找 AuditOperator
    -> 构造 AuditContext 放入 ThreadLocal
  -> Controller / Service 正常执行
  -> OperatorLogAspect.afterReturning(...)
    -> auditContext.operator.recordAudit(...)
```

异常时：

```text
afterThrowing()
  -> auditThreadLocal.remove()
```

注意：

```text
api.audit-enable 默认是 false。
具体是否最终写入审计表，需要结合 AuditOperator / BaseAuditOperator 的开关逻辑继续看。
```

## 19. 权限数据流

API 的权限检查大多发生在 Service 层。

常见方法：

```text
projectService.checkProjectAndAuthThrowException(...)
projectService.checkHasProjectWritePermissionThrowException(...)
ResourcePermissionCheckService
PermissionCheck
```

典型模式：

```text
Controller 从 LoginHandlerInterceptor 拿 loginUser
  -> Service 用 loginUser + projectCode / resourceId / tenantId 做权限校验
  -> 权限通过才继续 DAO/RPC/Storage 操作
```

所以调试权限问题时，不要只看 Controller 参数是否传对，要重点看：

```text
ProjectServiceImpl
ResourcePermissionCheckServiceImpl
各业务 Service 中调用的 check... 方法
```

## 20. Python Gateway 数据流

API 配置中有：

```yaml
api:
  python-gateway:
    enabled: false
```

相关类：

```text
gyyun-api/src/main/java/com/gyyun/ds/api/python/PythonGateway.java
```

当启用时，API 会启动 Py4J gateway，供 Python SDK 调用。

关键结论：

```text
Python SDK 集成不是普通 REST 调用，而是走 Py4J gateway。
本机默认 enabled=false，可以先忽略。
```

## 21. 数据库表视角

API 模块常写或查询的核心表：

| 表 | 典型来源 |
| --- | --- |
| `t_ds_user` | 登录、用户管理、权限判断 |
| `t_ds_session` | 登录态 |
| `t_ds_access_token` | token 调用 |
| `t_ds_project` | 项目管理、权限 |
| `t_ds_workflow_definition` | 工作流定义 |
| `t_ds_workflow_definition_log` | 工作流定义版本 |
| `t_ds_task_definition` | 任务定义 |
| `t_ds_task_definition_log` | 任务定义版本 |
| `t_ds_workflow_task_relation` | DAG 边关系 |
| `t_ds_workflow_task_relation_log` | DAG 边关系版本 |
| `t_ds_workflow_task_lineage` | 依赖任务 lineage |
| `t_ds_workflow_instance` | 工作流实例查询、控制 |
| `t_ds_task_instance` | 任务实例查询、日志定位 |
| `t_ds_schedules` | 调度配置 |
| `t_ds_datasource` | 数据源配置 |
| `t_ds_alertgroup` | 告警组 |
| `t_ds_alert_plugin_instance` | 告警插件实例 |
| `t_ds_audit_log` | 审计日志 |

## 22. API 到外部模块的边界

| 外部模块 | API 访问方式 | 典型类 |
| --- | --- | --- |
| MySQL/PostgreSQL | DAO / Mapper | `WorkflowDefinitionDao`、`ScheduleDao`、`TaskInstanceDao` |
| ZooKeeper Registry | `RegistryClient` | `MonitorServiceImpl`、`TriggerWorkflowExecutorDelegate` |
| Master | RPC `IWorkflowControlClient` 等 | `ExecutorClient` delegates |
| Worker | RPC `ITaskExecutorQueryClient`、日志 RPC | `MonitorServiceImpl`、`LogClientDelegate` |
| Alert Server | RPC `IAlertOperator` | `AlertPluginInstanceServiceImpl` |
| Storage | `StorageOperator` | `ResourcesServiceImpl` |
| Quartz/Scheduler | `SchedulerApi` | `SchedulerServiceImpl` |
| DataSource Plugins | 插件管理器 | `DataSourceServiceImpl` |
| Task Plugins | 插件管理器 | `WorkflowDefinitionServiceImpl` |

## 23. IDEA Debug 推荐断点

### 23.1 启动

| 断点位置 | 看什么 |
| --- | --- |
| `ApiApplicationServer.main()` | 是否进入 API 进程 |
| `ApiApplicationServer.run(ApplicationReadyEvent)` | 是否加载数据源插件和任务插件 |
| `ApiConfig.validate(...)` | API 配置是否按预期加载 |
| `AppConfiguration.addInterceptors(...)` | 拦截器是否注册 |
| `SecurityConfig.authenticator()` | 当前认证实现是哪一个 |

### 23.2 登录与鉴权

| 断点位置 | 看什么 |
| --- | --- |
| `LoginController.login(...)` | 登录参数和 IP |
| `PasswordAuthenticator.login(...)` | 用户名密码是否查到用户 |
| `AbstractAuthenticator.authenticate(...)` | session 创建和返回 cookie |
| `SessionServiceImpl.createSessionIfAbsent(...)` | `t_ds_session` 写入 |
| `LoginHandlerInterceptor.preHandle(...)` | 后续请求的 user 如何解析 |
| `UserDao.queryUserByToken(...)` | token header 路径 |

### 23.3 工作流定义

| 断点位置 | 看什么 |
| --- | --- |
| `WorkflowDefinitionController.createWorkflowDefinition(...)` | 前端传入的任务 JSON、关系 JSON |
| `WorkflowDefinitionServiceImpl.generateTaskDefinitionList(...)` | 任务参数是否通过插件校验 |
| `WorkflowDefinitionServiceImpl.generateTaskRelationList(...)` | DAG 关系是否正确 |
| `WorkflowDefinitionServiceImpl.createDagDefine(...)` | 任务、工作流、关系保存顺序 |
| `ProcessService.saveTaskDefine(...)` | 任务定义实际落库 |
| `ProcessService.saveWorkflowDefine(...)` | 工作流定义实际落库 |
| `WorkflowDefinitionServiceImpl.saveWorkflowLineage(...)` | lineage 是否生成 |

### 23.4 工作流启动和控制

| 断点位置 | 看什么 |
| --- | --- |
| `ExecutorController.triggerWorkflowDefinition(...)` | 请求参数如何转成 DTO |
| `ExecutorServiceImpl.triggerWorkflowDefinition(...)` | 权限、转换、校验 |
| `TriggerWorkflowExecutorDelegate.execute(...)` | 是否从注册中心拿到 Master |
| `Clients.withService(IWorkflowControlClient.class)` | RPC 目标地址 |
| `BackfillWorkflowExecutorDelegate.executeWithDependentExpansion(...)` | 补数依赖扩展 |
| `ExecutorServiceImpl.controlWorkflowInstance(...)` | 控制操作选择哪个 delegate |
| `PauseWorkflowInstanceExecutorDelegate.execute(...)` | 直接改库还是发 Master RPC |
| `StopWorkflowInstanceExecutorDelegate.execute(...)` | 直接改库还是发 Master RPC |

### 23.5 调度

| 断点位置 | 看什么 |
| --- | --- |
| `SchedulerController.createSchedule(...)` | 调度请求参数 |
| `SchedulerServiceImpl.insertSchedule(...)` | cron、租户、工作流上线校验 |
| `SchedulerServiceImpl.doOnlineScheduler(...)` | 上线时是否写 Quartz |
| `SchedulerApi.insertOrUpdateScheduleTask(...)` | Quartz Job/Trigger 是否创建 |
| `SchedulerServiceImpl.doOfflineScheduler(...)` | 下线是否删除 Quartz 任务 |

### 23.6 资源、日志、监控、告警

| 断点位置 | 看什么 |
| --- | --- |
| `ResourcesServiceImpl.createFile(...)` | 上传文件临时路径和目标存储路径 |
| `StorageOperator.upload(...)` | 底层存储实现 |
| `LoggerServiceImpl.queryLog(...)` | taskInstance.host/logPath |
| `LogClientDelegate.getPartLogString(...)` | 本地日志还是远程日志 |
| `MonitorServiceImpl.listServer(...)` | 注册中心节点 |
| `MonitorServiceImpl.queryWorkflowExecutors(...)` | Master RPC |
| `MonitorServiceImpl.queryTaskExecutors(...)` | Worker RPC |
| `AlertPluginInstanceServiceImpl.testSend(...)` | Alert Server RPC |

## 24. 本机排查 SQL

查看登录 session：

```sql
select id, user_id, last_login_time
from t_ds_session
order by last_login_time desc
limit 20;
```

查看 access token：

```sql
select id, user_id, token, expire_time, create_time
from t_ds_access_token
order by id desc
limit 20;
```

查看工作流定义：

```sql
select id, code, name, version, release_state, project_code, user_id, update_time
from t_ds_workflow_definition
order by update_time desc
limit 20;
```

查看任务定义：

```sql
select id, code, name, version, task_type, project_code, update_time
from t_ds_task_definition
order by update_time desc
limit 20;
```

查看 DAG 关系：

```sql
select id, workflow_definition_code, workflow_definition_version, pre_task_code, post_task_code
from t_ds_workflow_task_relation
where workflow_definition_code = ?;
```

查看工作流实例：

```sql
select id, name, workflow_definition_code, workflow_definition_version, state, host, command_type, command_param, start_time, end_time
from t_ds_workflow_instance
order by id desc
limit 20;
```

查看任务实例日志定位：

```sql
select id, name, task_type, workflow_instance_id, host, log_path, state, start_time, end_time
from t_ds_task_instance
where id = ?;
```

查看调度：

```sql
select id, workflow_definition_code, workflow_definition_name, crontab, timezone_id, release_state, start_time, end_time
from t_ds_schedules
order by id desc
limit 20;
```

查看告警插件实例：

```sql
select id, instance_name, plugin_define_id, plugin_instance_params
from t_ds_alert_plugin_instance
order by id desc
limit 20;
```

## 25. 常见问题

### 25.1 接口 401

优先检查：

```text
1. 是否访问了 /gyyun/ 前缀下的接口
2. 是否携带 sessionId cookie/header
3. session 是否存在于 t_ds_session
4. session 是否过期
5. 是否改用 token header
6. 用户 state 是否禁用
```

断点：

```text
LoginHandlerInterceptor.preHandle(...)
AbstractAuthenticator.getAuthUser(...)
SessionServiceImpl.getSession(...)
```

### 25.2 启动工作流报 no master server available

说明 API 从注册中心拿不到 Master。

检查：

```text
1. ZooKeeper 是否启动
2. API 和 Master 的 registry namespace 是否一致
3. Master 是否已启动并注册
4. registry.zookeeper.connect-string 是否一致
```

断点：

```text
TriggerWorkflowExecutorDelegate.execute(...)
registryClient.getRandomServer(RegistryNodeType.MASTER)
```

### 25.3 工作流定义保存失败

重点看：

```text
1. taskDefinitionJson 是否能转成 TaskDefinitionLog
2. taskRelationJson 是否能转成 WorkflowTaskRelationLog
3. taskType 是否有对应插件
4. TaskPluginManager.checkTaskParameters 是否通过
5. DAG 是否有环
6. 项目写权限是否通过
```

断点：

```text
WorkflowDefinitionServiceImpl.generateTaskDefinitionList(...)
WorkflowDefinitionServiceImpl.generateTaskRelationList(...)
WorkflowDefinitionServiceImpl.createDagDefine(...)
```

### 25.4 调度上线后不触发

检查：

```text
1. 工作流定义是否 ONLINE
2. t_ds_schedules.release_state 是否 ONLINE
3. SchedulerServiceImpl.doOnlineScheduler 是否执行
4. SchedulerApi.insertOrUpdateScheduleTask 是否成功
5. Quartz 表是否存在，数据库升级是否执行过
```

注意：

```text
API 配置 spring.quartz.auto-startup=false。
上线调度依赖 SchedulerApi 注册任务，不代表 API 自己会作为调度执行器。
```

### 25.5 日志查不到

检查：

```text
1. t_ds_task_instance.host 是否为空
2. t_ds_task_instance.log_path 是否为空
3. host 对应 Master/Worker 是否仍在注册中心
4. LocalLogClient 是否能取到日志
5. RemoteLogClient 对应远程日志配置是否正确
```

断点：

```text
LoggerServiceImpl.queryLog(...)
LogClientDelegate.checkNodeExists(...)
LogClientDelegate.getPartLogString(...)
```

### 25.6 告警测试发送失败

检查：

```text
1. Alert Server 是否启动
2. Alert Server 是否注册到 RegistryNodeType.ALERT_SERVER
3. API 是否拿到 alert server address
4. 插件实例参数是否合法
5. alert-server 插件是否加载成功
```

断点：

```text
AlertPluginInstanceServiceImpl.getAlertServerAddress(...)
AlertPluginInstanceServiceImpl.testSend(...)
```

## 26. 一句话数据流

普通查询/管理接口：

```text
HTTP -> Interceptor -> Controller -> Service -> DAO/Mapper -> DB -> Result
```

工作流定义保存：

```text
HTTP -> WorkflowDefinitionController -> WorkflowDefinitionServiceImpl
  -> 解析任务和关系 JSON
  -> 任务插件参数校验
  -> ProcessService 保存 definition/task/relation/lineage
  -> DB
```

工作流启动：

```text
HTTP -> ExecutorController -> ExecutorServiceImpl
  -> 权限校验和 DTO 校验
  -> RegistryClient 随机选择 Master
  -> IWorkflowControlClient RPC
  -> Master 创建并运行工作流实例
```

资源中心：

```text
HTTP -> ResourcesController -> ResourcesServiceImpl
  -> Validator/Transformer
  -> StorageOperator
  -> LOCAL/HDFS/OSS/COS/OBS 等底层存储
```

日志查询：

```text
HTTP -> LoggerController -> LoggerServiceImpl
  -> t_ds_task_instance
  -> LogClientDelegate
  -> LocalLogClient 或 RemoteLogClient
```

监控：

```text
HTTP -> MonitorController -> MonitorServiceImpl
  -> RegistryClient / DatabaseMonitor / Master RPC / Worker RPC
```

