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

package com.gyyun.ds.server.master.engine.workflow.serial;

import com.gyyun.ds.common.enums.WorkflowExecutionStatus;
import com.gyyun.ds.common.utils.DateUtils;
import com.gyyun.ds.dao.entity.Command;
import com.gyyun.ds.dao.entity.WorkflowInstance;
import com.gyyun.ds.dao.model.SerialCommandDto;
import com.gyyun.ds.dao.repository.CommandDao;
import com.gyyun.ds.dao.repository.SerialCommandDao;
import com.gyyun.ds.dao.repository.WorkflowInstanceDao;
import com.gyyun.ds.extract.base.client.Clients;
import com.gyyun.ds.extract.master.IWorkflowControlClient;
import com.gyyun.ds.extract.master.transportor.workflow.WorkflowInstanceStopRequest;
import com.gyyun.ds.server.master.config.MasterConfig;
import com.gyyun.ds.server.master.metrics.WorkflowInstanceMetrics;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
public abstract class AbstractSerialCommandHandler implements ISerialCommandHandler {

    @Autowired
    protected WorkflowInstanceDao workflowInstanceDao;

    @Autowired
    protected IWorkflowControlClient workflowControlClient;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    @Autowired
    protected MasterConfig masterConfig;

    @Autowired
    protected CommandDao commandDao;

    @Autowired
    protected SerialCommandDao serialCommandDao;

    protected void launchSerialCommand(SerialCommandDto serialCommand) {
        transactionTemplate.execute(new TransactionCallback<Object>() {

            @Override
            public Void doInTransaction(TransactionStatus status) {
                final Command command = serialCommand.getCommand();
                commandDao.insert(command);

                serialCommand.setState(SerialCommandDto.State.LAUNCHED);
                serialCommandDao.updateById(serialCommand.toEntity());
                return null;
            }
        });
    }

    protected void discardSerialCommandAndStopWorkflowInstanceInDB(SerialCommandDto serialCommand) {
        transactionTemplate.execute(new TransactionCallback<Object>() {

            @Override
            public Void doInTransaction(TransactionStatus status) {
                serialCommandDao.deleteById(serialCommand.getId());

                // todo: call api to stop the workflow instance
                final Integer workflowInstanceId = serialCommand.getWorkflowInstanceId();
                final WorkflowInstance workflowInstance = workflowInstanceDao.queryById(workflowInstanceId);
                workflowInstance.setEndTime(DateUtils.getCurrentDate());
                workflowInstance.setState(WorkflowExecutionStatus.STOP);
                workflowInstanceDao.upsertWorkflowInstance(workflowInstance);
                WorkflowInstanceMetrics.recordWorkflowInstanceFinish(
                        WorkflowExecutionStatus.STOP,
                        workflowInstance.getWorkflowDefinitionCode());
                return null;
            }
        });
    }

    protected void stopWorkflowInstanceInMaster(SerialCommandDto serialCommand) {

        // todo: directly call master api
        // todo: call api to stop the workflow instance
        // todo: We might need to set a status discarding in serial command. then we can avoid duplicate stop
        // workflow instance
        final Integer workflowInstanceId = serialCommand.getWorkflowInstanceId();
        final WorkflowInstance workflowInstance = workflowInstanceDao.queryById(workflowInstanceId);
        if (!workflowInstance.getState().isCanStop()) {
            return;
        }

        final String workflowInstanceLaunchedHost = workflowInstance.getHost();
        final WorkflowInstanceStopRequest stopRequest = new WorkflowInstanceStopRequest(workflowInstanceId);
        if (masterConfig.getMasterAddress().equals(workflowInstanceLaunchedHost)) {
            workflowControlClient.stopWorkflowInstance(stopRequest);
        } else {
            Clients.withService(IWorkflowControlClient.class)
                    .withHost(workflowInstanceLaunchedHost)
                    .stopWorkflowInstance(stopRequest);
        }
    }
}
