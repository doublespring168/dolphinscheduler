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

package org.apache.gyyun.server.worker.rpc;

import org.apache.gyyun.extract.worker.IPhysicalTaskExecutorOperator;
import org.apache.gyyun.plugin.task.api.TaskExecutionContext;
import org.apache.gyyun.server.worker.executor.PhysicalTaskEngineDelegator;
import org.apache.gyyun.task.executor.eventbus.ITaskExecutorLifecycleEventReporter;
import org.apache.gyyun.task.executor.operations.TaskExecutorDispatchRequest;
import org.apache.gyyun.task.executor.operations.TaskExecutorDispatchResponse;
import org.apache.gyyun.task.executor.operations.TaskExecutorKillRequest;
import org.apache.gyyun.task.executor.operations.TaskExecutorKillResponse;
import org.apache.gyyun.task.executor.operations.TaskExecutorPauseRequest;
import org.apache.gyyun.task.executor.operations.TaskExecutorPauseResponse;
import org.apache.gyyun.task.executor.operations.TaskExecutorReassignMasterRequest;
import org.apache.gyyun.task.executor.operations.TaskExecutorReassignMasterResponse;

import org.apache.commons.lang3.exception.ExceptionUtils;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PhysicalTaskExecutorOperatorImpl implements IPhysicalTaskExecutorOperator {

    @Autowired
    private PhysicalTaskEngineDelegator physicalTaskEngineDelegator;

    @Override
    public TaskExecutorDispatchResponse dispatchTask(final TaskExecutorDispatchRequest taskExecutorDispatchRequest) {
        log.info("Receive TaskExecutorDispatchResponse: {}", taskExecutorDispatchRequest);
        final TaskExecutionContext taskExecutionContext = taskExecutorDispatchRequest.getTaskExecutionContext();
        try {
            physicalTaskEngineDelegator.dispatchLogicTask(taskExecutionContext);
            log.info("Handle TaskExecutorDispatchResponse: {} success", taskExecutorDispatchRequest);
            return TaskExecutorDispatchResponse.success();
        } catch (Throwable throwable) {
            log.error("Handle TaskExecutorDispatchResponse: {} failed", taskExecutorDispatchRequest, throwable);
            return TaskExecutorDispatchResponse.failed(ExceptionUtils.getMessage(throwable));
        }
    }

    @Override
    public TaskExecutorKillResponse killTask(final TaskExecutorKillRequest taskExecutorKillRequest) {
        log.info("Receive TaskExecutorKillRequest: {}", taskExecutorKillRequest);
        final int taskInstanceId = taskExecutorKillRequest.getTaskInstanceId();
        try {
            physicalTaskEngineDelegator.killLogicTask(taskInstanceId);
            log.info("Handle TaskExecutorKillRequest: {} success", taskExecutorKillRequest);
            return TaskExecutorKillResponse.success();
        } catch (Throwable throwable) {
            log.error("Handle TaskExecutorKillRequest: {} failed", taskExecutorKillRequest, throwable);
            return TaskExecutorKillResponse.fail(ExceptionUtils.getMessage(throwable));
        }
    }

    @Override
    public TaskExecutorPauseResponse pauseTask(final TaskExecutorPauseRequest taskPauseRequest) {
        log.info("Receive TaskExecutorPauseRequest: {}", taskPauseRequest);
        final int taskInstanceId = taskPauseRequest.getTaskInstanceId();
        try {
            physicalTaskEngineDelegator.pauseLogicTask(taskInstanceId);
            log.info("Handle TaskExecutorPauseRequest: {} success", taskPauseRequest);
            return TaskExecutorPauseResponse.success();
        } catch (Throwable throwable) {
            log.error("Handle TaskExecutorPauseRequest: {} failed", taskPauseRequest, throwable);
            return TaskExecutorPauseResponse.fail(ExceptionUtils.getMessage(throwable));
        }
    }

    @Override
    public TaskExecutorReassignMasterResponse reassignWorkflowInstanceHost(final TaskExecutorReassignMasterRequest taskExecutorReassignMasterRequest) {
        boolean success =
                physicalTaskEngineDelegator.reassignWorkflowInstanceHost(taskExecutorReassignMasterRequest);
        if (success) {
            return TaskExecutorReassignMasterResponse.success();
        }
        return TaskExecutorReassignMasterResponse.failed("Reassign master host failed");
    }

    @Override
    public void ackPhysicalTaskExecutorLifecycleEvent(final ITaskExecutorLifecycleEventReporter.TaskExecutorLifecycleEventAck taskExecutorLifecycleEventAck) {
        log.info("Receive TaskExecutorLifecycleEventAck: {}", taskExecutorLifecycleEventAck);
        physicalTaskEngineDelegator.ackPhysicalTaskExecutorLifecycleEventACK(taskExecutorLifecycleEventAck);
    }
}
