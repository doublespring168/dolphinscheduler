/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gyyun.ds.server.worker.executor;

import com.gyyun.ds.common.constants.Constants;
import com.gyyun.ds.common.utils.JSONUtils;
import com.gyyun.ds.common.utils.PropertyUtils;
import com.gyyun.ds.plugin.storage.api.StorageOperator;
import com.gyyun.ds.plugin.task.api.AbstractTask;
import com.gyyun.ds.plugin.task.api.TaskCallBack;
import com.gyyun.ds.plugin.task.api.log.TaskLogMarkers;
import com.gyyun.ds.plugin.task.api.model.ApplicationInfo;
import com.gyyun.ds.plugin.task.api.resource.ResourceContext;
import com.gyyun.ds.server.worker.config.WorkerConfig;
import com.gyyun.ds.server.worker.utils.TaskExecutionContextUtils;
import com.gyyun.ds.server.worker.utils.TenantUtils;
import com.gyyun.ds.task.executor.AbstractTaskExecutor;
import com.gyyun.ds.task.executor.ITaskExecutor;
import com.gyyun.ds.task.executor.TaskExecutorState;
import com.gyyun.ds.task.executor.TaskExecutorStateMappings;
import com.gyyun.ds.task.executor.events.TaskExecutorRuntimeContextChangedLifecycleEvent;

import java.util.ArrayList;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PhysicalTaskExecutor extends AbstractTaskExecutor {

    private final WorkerConfig workerConfig;

    private final StorageOperator storageOperator;

    @Getter
    private AbstractTask physicalTask;

    private final PhysicalTaskPluginFactory physicalTaskPluginFactory;

    public PhysicalTaskExecutor(final PhysicalTaskExecutorBuilder physicalTaskExecutorBuilder) {
        super(physicalTaskExecutorBuilder.getTaskExecutionContext(),
                physicalTaskExecutorBuilder.getTaskExecutorEventBus());
        this.workerConfig = physicalTaskExecutorBuilder.getWorkerConfig();
        this.storageOperator = physicalTaskExecutorBuilder.getStorageOperator();
        this.physicalTaskPluginFactory = physicalTaskExecutorBuilder.getPhysicalTaskPluginFactory();
    }

    @Override
    protected void initializeTaskPlugin() {
        this.physicalTask = physicalTaskPluginFactory.createPhysicalTask(this);
        log.info("Initialized physicalTask: {} successfully", taskExecutionContext.getTaskType());

        this.physicalTask.init();

        this.physicalTask.getParameters().setVarPool(new ArrayList<>());
        log.info("Set taskVarPool: {} successfully", taskExecutionContext.getVarPool());
    }

    @Override
    protected void doTriggerTaskPlugin() {
        final ITaskExecutor taskExecutor = this;
        physicalTask.handle(new TaskCallBack() {

            @Override
            public void updateRemoteApplicationInfo(final int taskInstanceId, final ApplicationInfo applicationInfo) {
                taskExecutionContext.setAppIds(applicationInfo.getAppIds());
                taskExecutorEventBus.publish(TaskExecutorRuntimeContextChangedLifecycleEvent.of(taskExecutor));
            }

            @Override
            public void updateTaskInstanceInfo(final int taskInstanceId) {
                taskExecutorEventBus.publish(TaskExecutorRuntimeContextChangedLifecycleEvent.of(taskExecutor));
            }
        });
    }

    @Override
    protected TaskExecutorState doTrackTaskPluginStatus() {
        return TaskExecutorStateMappings.mapState(physicalTask.getExitStatus());
    }

    @Override
    public void pause() {
        log.warn("The physical doesn't support pause");
    }

    @Override
    public void kill() {
        if (physicalTask != null) {
            physicalTask.cancel();
        }
    }

    @Override
    public void finalizeTask() {
        clearTaskInstanceWorkingDirectoryIfNeeded();
    }

    private void clearTaskInstanceWorkingDirectoryIfNeeded() {
        boolean isDevelopment = PropertyUtils.getBoolean(Constants.DEVELOPMENT_STATE, true);
        if (!isDevelopment) {
            TaskExecutionContextUtils.clearTaskInstanceWorkingDirectory(taskExecutionContext);
        }
    }

    @Override
    protected void initializeTaskContext() {
        super.initializeTaskContext();

        taskExecutionContext.setTaskAppId(String.valueOf(taskExecutionContext.getTaskInstanceId()));

        taskExecutionContext.setTenantCode(TenantUtils.getOrCreateActualTenant(workerConfig, taskExecutionContext));
        log.info("TenantCode: {} check successfully", taskExecutionContext.getTenantCode());

        TaskExecutionContextUtils.createTaskInstanceWorkingDirectory(taskExecutionContext);
        log.info("TaskInstance working directory: {} create successfully", taskExecutionContext.getExecutePath());

        final ResourceContext resourceContext = TaskExecutionContextUtils.downloadResourcesIfNeeded(
                physicalTaskPluginFactory.getTaskChannel(this),
                storageOperator,
                taskExecutionContext);
        taskExecutionContext.setResourceContext(resourceContext);
        log.info("Download resources successfully: {}", taskExecutionContext.getResourceContext());

        log.info(TaskLogMarkers.excludeInTaskLog(), "Initialized Task Context{}",
                JSONUtils.toPrettyJsonString(taskExecutionContext));

    }

    @Override
    public String toString() {
        return "PhysicalTaskExecutor{" +
                "id=" + taskExecutionContext.getTaskInstanceId() +
                ", name=" + taskExecutionContext.getTaskName() +
                ", stat=" + taskExecutorState.get() +
                '}';
    }

}
