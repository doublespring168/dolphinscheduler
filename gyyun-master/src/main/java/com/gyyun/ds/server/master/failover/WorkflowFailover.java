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

package com.gyyun.ds.server.master.failover;

import com.gyyun.ds.common.enums.CommandType;
import com.gyyun.ds.common.enums.WorkflowExecutionStatus;
import com.gyyun.ds.common.utils.JSONUtils;
import com.gyyun.ds.dao.entity.Command;
import com.gyyun.ds.dao.entity.WorkflowInstance;
import com.gyyun.ds.dao.repository.CommandDao;
import com.gyyun.ds.dao.repository.WorkflowInstanceDao;
import com.gyyun.ds.extract.master.command.WorkflowFailoverCommandParam;
import com.gyyun.ds.server.master.metrics.WorkflowInstanceMetrics;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
public class WorkflowFailover {

    @Autowired
    private WorkflowInstanceDao workflowInstanceDao;

    @Autowired
    private CommandDao commandDao;

    @Transactional
    public void failoverWorkflow(final WorkflowInstance workflowInstance) {
        workflowInstanceDao.updateWorkflowInstanceState(
                workflowInstance.getId(),
                workflowInstance.getState(),
                WorkflowExecutionStatus.FAILOVER);
        WorkflowInstanceMetrics.recordWorkflowInstanceFailover(workflowInstance.getWorkflowDefinitionCode());

        final WorkflowFailoverCommandParam failoverWorkflowCommandParam = WorkflowFailoverCommandParam.builder()
                .workflowExecutionStatus(workflowInstance.getState())
                .build();

        final Command failoverCommand = Command.builder()
                .commandParam(JSONUtils.toJsonString(failoverWorkflowCommandParam))
                .commandType(CommandType.RECOVER_TOLERANCE_FAULT_PROCESS)
                .workflowDefinitionCode(workflowInstance.getWorkflowDefinitionCode())
                .workflowDefinitionVersion(workflowInstance.getWorkflowDefinitionVersion())
                .workflowInstanceId(workflowInstance.getId())
                .build();
        commandDao.insert(failoverCommand);
        log.info("Success failover workflowInstance: [id={}, name={}, state={}]",
                workflowInstance.getId(),
                workflowInstance.getName(),
                workflowInstance.getState().name());
    }

}
