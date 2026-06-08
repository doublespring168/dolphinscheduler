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

package com.gyyun.ds.api.service.impl;

import com.gyyun.ds.api.service.TaskDefinitionLogService;
import com.gyyun.ds.dao.entity.WorkflowTaskRelation;
import com.gyyun.ds.dao.entity.WorkflowTaskRelationLog;
import com.gyyun.ds.dao.repository.TaskDefinitionLogDao;
import com.gyyun.ds.dao.repository.WorkflowTaskRelationLogDao;

import org.apache.commons.collections4.CollectionUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TaskDefinitionLogServiceImpl implements TaskDefinitionLogService {

    @Autowired
    private WorkflowTaskRelationLogDao workflowTaskRelationLogDao;

    @Autowired
    private TaskDefinitionLogDao taskDefinitionLogDao;

    @Override
    public void deleteTaskByWorkflowDefinitionCode(long workflowDefinitionCode) {
        List<WorkflowTaskRelationLog> workflowTaskRelationLogList =
                workflowTaskRelationLogDao.queryByWorkflowDefinitionCode(workflowDefinitionCode);
        if (CollectionUtils.isEmpty(workflowTaskRelationLogList)) {
            return;
        }
        // delete task definition
        Set<Long> needToDeleteTaskDefinitionCodes = new HashSet<>();
        for (WorkflowTaskRelation workflowTaskRelation : workflowTaskRelationLogList) {
            needToDeleteTaskDefinitionCodes.add(workflowTaskRelation.getPreTaskCode());
            needToDeleteTaskDefinitionCodes.add(workflowTaskRelation.getPostTaskCode());
        }
        taskDefinitionLogDao.deleteByTaskDefinitionCodes(needToDeleteTaskDefinitionCodes);
        // delete task workflow relation
        workflowTaskRelationLogDao.deleteByWorkflowDefinitionCode(workflowDefinitionCode);
    }
}
