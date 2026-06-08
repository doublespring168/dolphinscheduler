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

package com.gyyun.ds.server.master.rpc;

import com.gyyun.ds.extract.master.ILogicTaskExecutorOperator;
import com.gyyun.ds.plugin.task.api.TaskExecutionContext;
import com.gyyun.ds.server.master.engine.executor.LogicTaskEngineDelegator;
import com.gyyun.ds.task.executor.eventbus.ITaskExecutorLifecycleEventReporter;
import com.gyyun.ds.task.executor.log.TaskExecutorMDCUtils;
import com.gyyun.ds.task.executor.operations.TaskExecutorDispatchRequest;
import com.gyyun.ds.task.executor.operations.TaskExecutorDispatchResponse;
import com.gyyun.ds.task.executor.operations.TaskExecutorKillRequest;
import com.gyyun.ds.task.executor.operations.TaskExecutorKillResponse;
import com.gyyun.ds.task.executor.operations.TaskExecutorPauseRequest;
import com.gyyun.ds.task.executor.operations.TaskExecutorPauseResponse;

import org.apache.commons.lang3.exception.ExceptionUtils;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LogicTaskExecutorOperatorImpl implements ILogicTaskExecutorOperator {

    @Autowired
    private LogicTaskEngineDelegator logicTaskEngineDelegator;

    @Override
    public TaskExecutorDispatchResponse dispatchTask(final TaskExecutorDispatchRequest taskExecutorDispatchRequest) {
        final TaskExecutionContext taskExecutionContext = taskExecutorDispatchRequest.getTaskExecutionContext();
        try (
                final TaskExecutorMDCUtils.MDCAutoClosable ignore =
                        TaskExecutorMDCUtils.logWithMDC(taskExecutionContext.getTaskInstanceId())) {
            log.info("Receive  {}", taskExecutorDispatchRequest);
            try {
                logicTaskEngineDelegator.dispatchLogicTask(taskExecutionContext);
                log.info("Handle {} success", taskExecutorDispatchRequest);
                return TaskExecutorDispatchResponse.success();
            } catch (Throwable throwable) {
                log.error("Handle {} failed", taskExecutorDispatchRequest, throwable);
                return TaskExecutorDispatchResponse.failed(ExceptionUtils.getMessage(throwable));
            }
        }
    }

    @Override
    public TaskExecutorPauseResponse pauseTask(final TaskExecutorPauseRequest taskPauseRequest) {
        final int taskInstanceId = taskPauseRequest.getTaskInstanceId();
        try (final TaskExecutorMDCUtils.MDCAutoClosable ignore = TaskExecutorMDCUtils.logWithMDC(taskInstanceId)) {
            log.info("Receive {}", taskPauseRequest);
            try {
                logicTaskEngineDelegator.pauseLogicTask(taskInstanceId);
                log.info("Handle {} success", taskPauseRequest);
                return TaskExecutorPauseResponse.success();
            } catch (Throwable throwable) {
                log.error("Handle {} failed", taskPauseRequest, throwable);
                return TaskExecutorPauseResponse.fail(ExceptionUtils.getMessage(throwable));
            }
        }
    }

    @Override
    public void ackTaskExecutorLifecycleEvent(final ITaskExecutorLifecycleEventReporter.TaskExecutorLifecycleEventAck taskExecutorLifecycleEventAck) {
        final int taskExecutorId = taskExecutorLifecycleEventAck.getTaskExecutorId();
        try (final TaskExecutorMDCUtils.MDCAutoClosable ignore = TaskExecutorMDCUtils.logWithMDC(taskExecutorId)) {
            log.info("Receive : {}", taskExecutorLifecycleEventAck);
            logicTaskEngineDelegator.ackLogicTaskExecutionEvent(taskExecutorLifecycleEventAck);
        }
    }

    @Override
    public TaskExecutorKillResponse killTask(final TaskExecutorKillRequest taskKillRequest) {
        final int taskInstanceId = taskKillRequest.getTaskInstanceId();
        try (final TaskExecutorMDCUtils.MDCAutoClosable ignore = TaskExecutorMDCUtils.logWithMDC(taskInstanceId)) {
            log.info("Receive  {}", taskKillRequest);
            try {
                logicTaskEngineDelegator.killLogicTask(taskInstanceId);
                log.info("Handle  {} success", taskKillRequest);
                return TaskExecutorKillResponse.success();
            } catch (Throwable throwable) {
                log.error("Handle  {} failed", taskKillRequest, throwable);
                return TaskExecutorKillResponse.fail(ExceptionUtils.getMessage(throwable));
            }
        }
    }

}
