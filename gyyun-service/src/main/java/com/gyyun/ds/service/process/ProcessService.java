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

package com.gyyun.ds.service.process;

import com.gyyun.ds.common.enums.AuthorizationType;
import com.gyyun.ds.common.graph.DAG;
import com.gyyun.ds.common.model.TaskNodeRelation;
import com.gyyun.ds.dao.entity.DagData;
import com.gyyun.ds.dao.entity.DataSource;
import com.gyyun.ds.dao.entity.Schedule;
import com.gyyun.ds.dao.entity.TaskDefinitionLog;
import com.gyyun.ds.dao.entity.TaskInstance;
import com.gyyun.ds.dao.entity.User;
import com.gyyun.ds.dao.entity.WorkflowDefinition;
import com.gyyun.ds.dao.entity.WorkflowDefinitionLog;
import com.gyyun.ds.dao.entity.WorkflowInstance;
import com.gyyun.ds.dao.entity.WorkflowTaskRelation;
import com.gyyun.ds.dao.entity.WorkflowTaskRelationLog;
import com.gyyun.ds.service.model.TaskNode;

import java.util.List;
import java.util.Optional;

public interface ProcessService {

    Optional<WorkflowInstance> findWorkflowInstanceDetailById(int workflowInstanceId);

    WorkflowInstance findWorkflowInstanceById(int workflowInstanceId);

    WorkflowDefinition findWorkflowDefinition(Long workflowDefinitionCode, int workflowDefinitionVersion);

    int deleteWorkflowInstanceById(int workflowInstanceId);

    List<Long> findAllSubWorkflowDefinitionCode(long workflowDefinitionCode);

    String getTenantForWorkflow(String tenantCode, int userId);

    WorkflowInstance findSubWorkflowInstance(Integer parentWorkflowInstanceId, Integer parentTaskId);

    WorkflowInstance findParentWorkflowInstance(Integer subWorkflowInstanceId);

    List<Schedule> queryReleaseSchedulerListByWorkflowDefinitionCode(long workflowDefinitionCode);

    DataSource findDataSourceById(int id);

    <T> List<T> listUnauthorized(int userId, T[] needChecks, AuthorizationType authorizationType);

    User getUserById(int userId);

    int switchVersion(WorkflowDefinition workflowDefinition, WorkflowDefinitionLog workflowDefinitionLog);

    int switchWorkflowTaskRelationVersion(WorkflowDefinition workflowDefinition);

    int switchTaskDefinitionVersion(long taskCode, int taskVersion);

    int saveTaskDefine(User operator, long projectCode, List<TaskDefinitionLog> taskDefinitionLogs, Boolean syncDefine);

    int saveWorkflowDefine(User operator, WorkflowDefinition workflowDefinition, Boolean syncDefine,
                           Boolean isFromWorkflowDefinition);

    int saveTaskRelation(User operator, long projectCode, long workflowDefinitionCode, int workflowDefinitionVersion,
                         List<WorkflowTaskRelationLog> taskRelationList, List<TaskDefinitionLog> taskDefinitionLogs,
                         Boolean syncDefine);

    boolean isTaskOnline(long taskCode);

    DAG<Long, TaskNode, TaskNodeRelation> genDagGraph(WorkflowDefinition workflowDefinition);

    DagData genDagData(WorkflowDefinition workflowDefinition);

    List<WorkflowTaskRelation> findRelationByCode(long workflowDefinitionCode, int workflowDefinitionVersion);

    List<TaskNode> transformTask(List<WorkflowTaskRelation> taskRelationList,
                                 List<TaskDefinitionLog> taskDefinitionLogs);

    String findConfigYamlByName(String clusterName);

    void forceWorkflowInstanceSuccessByTaskInstanceId(TaskInstance taskInstance);

}
