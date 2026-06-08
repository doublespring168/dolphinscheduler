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

package com.gyyun.ds.extract.worker;

import com.gyyun.ds.extract.base.RpcMethod;
import com.gyyun.ds.extract.base.RpcService;
import com.gyyun.ds.task.executor.eventbus.ITaskExecutorLifecycleEventReporter;
import com.gyyun.ds.task.executor.operations.TaskExecutorDispatchRequest;
import com.gyyun.ds.task.executor.operations.TaskExecutorDispatchResponse;
import com.gyyun.ds.task.executor.operations.TaskExecutorKillRequest;
import com.gyyun.ds.task.executor.operations.TaskExecutorKillResponse;
import com.gyyun.ds.task.executor.operations.TaskExecutorPauseRequest;
import com.gyyun.ds.task.executor.operations.TaskExecutorPauseResponse;
import com.gyyun.ds.task.executor.operations.TaskExecutorReassignMasterRequest;
import com.gyyun.ds.task.executor.operations.TaskExecutorReassignMasterResponse;

@RpcService
public interface IPhysicalTaskExecutorOperator {

    @RpcMethod
    TaskExecutorDispatchResponse dispatchTask(final TaskExecutorDispatchRequest taskExecutorDispatchRequest);

    @RpcMethod
    TaskExecutorKillResponse killTask(final TaskExecutorKillRequest taskExecutorKillRequest);

    @RpcMethod
    TaskExecutorPauseResponse pauseTask(final TaskExecutorPauseRequest taskExecutorPauseRequest);

    @RpcMethod
    TaskExecutorReassignMasterResponse reassignWorkflowInstanceHost(final TaskExecutorReassignMasterRequest taskExecutorReassignMasterRequest);

    @RpcMethod
    void ackPhysicalTaskExecutorLifecycleEvent(final ITaskExecutorLifecycleEventReporter.TaskExecutorLifecycleEventAck taskExecutorLifecycleEventAck);

}
