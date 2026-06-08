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

package com.gyyun.ds.api.service;

import static com.gyyun.ds.api.AssertionsHelper.assertDoesNotThrow;
import static com.gyyun.ds.api.AssertionsHelper.assertThrowsServiceException;
import static com.gyyun.ds.api.constants.ApiFuncIdentificationConstant.WORKER_GROUP_CREATE;
import static com.gyyun.ds.api.constants.ApiFuncIdentificationConstant.WORKER_GROUP_DELETE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.gyyun.ds.api.enums.Status;
import com.gyyun.ds.api.permission.ResourcePermissionCheckService;
import com.gyyun.ds.api.service.impl.BaseServiceImpl;
import com.gyyun.ds.api.service.impl.WorkerGroupServiceImpl;
import com.gyyun.ds.api.utils.Result;
import com.gyyun.ds.common.enums.AuthorizationType;
import com.gyyun.ds.common.enums.UserType;
import com.gyyun.ds.common.enums.WorkflowExecutionStatus;
import com.gyyun.ds.dao.entity.User;
import com.gyyun.ds.dao.entity.WorkerGroup;
import com.gyyun.ds.dao.entity.WorkflowInstance;
import com.gyyun.ds.dao.mapper.EnvironmentWorkerGroupRelationMapper;
import com.gyyun.ds.dao.repository.ScheduleDao;
import com.gyyun.ds.dao.repository.TaskDefinitionDao;
import com.gyyun.ds.dao.repository.WorkerGroupDao;
import com.gyyun.ds.dao.repository.WorkflowInstanceDao;
import com.gyyun.ds.registry.api.RegistryClient;
import com.gyyun.ds.registry.api.enums.RegistryNodeType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class WorkerGroupServiceTest {

    private static final Logger baseServiceLogger = LoggerFactory.getLogger(BaseServiceImpl.class);

    private static final Logger serviceLogger = LoggerFactory.getLogger(WorkerGroupService.class);

    @InjectMocks
    private WorkerGroupServiceImpl workerGroupService;

    @Mock
    private WorkerGroupDao workerGroupDao;

    @Mock
    private WorkflowInstanceDao workflowInstanceDao;

    @Mock
    private RegistryClient registryClient;

    @Mock
    private ResourcePermissionCheckService resourcePermissionCheckService;

    @Mock
    private EnvironmentWorkerGroupRelationMapper environmentWorkerGroupRelationMapper;

    @Mock
    private TaskDefinitionDao taskDefinitionDao;

    @Mock
    private ScheduleDao scheduleDao;

    private final String GROUP_NAME = "testWorkerGroup";

    private User getLoginUser() {
        User loginUser = new User();
        loginUser.setUserType(UserType.GENERAL_USER);
        loginUser.setUserName("workerGroupTestUser");
        loginUser.setId(1);
        return loginUser;
    }

    @Test
    public void giveNoPermission_whenSaveWorkerGroup_expectNoOperation() {
        User loginUser = getLoginUser();
        when(resourcePermissionCheckService.operationPermissionCheck(AuthorizationType.WORKER_GROUP, 1,
                WORKER_GROUP_CREATE, baseServiceLogger)).thenReturn(false);
        when(resourcePermissionCheckService.resourcePermissionCheck(AuthorizationType.WORKER_GROUP, null, 1,
                baseServiceLogger)).thenReturn(false);
        assertThrowsServiceException(Status.USER_NO_OPERATION_PERM, () -> {
            workerGroupService.saveWorkerGroup(loginUser, 1, GROUP_NAME, "localhost:0000", "test group");
        });
    }

    @Test
    public void giveNullName_whenSaveWorkerGroup_expectNAME_NULL() {
        User loginUser = getLoginUser();
        when(resourcePermissionCheckService.operationPermissionCheck(AuthorizationType.WORKER_GROUP, 1,
                WORKER_GROUP_CREATE, baseServiceLogger)).thenReturn(true);
        when(resourcePermissionCheckService.resourcePermissionCheck(AuthorizationType.WORKER_GROUP, null, 1,
                baseServiceLogger)).thenReturn(true);
        assertThrowsServiceException(Status.NAME_NULL, () -> {
            workerGroupService.saveWorkerGroup(loginUser, 1, "", "localhost:0000", "test group");
        });
    }

    @Test
    public void giveSameUserName_whenSaveWorkerGroup_expectNAME_EXIST() {
        User loginUser = getLoginUser();
        when(resourcePermissionCheckService.operationPermissionCheck(AuthorizationType.WORKER_GROUP, 1,
                WORKER_GROUP_CREATE, baseServiceLogger)).thenReturn(true);
        when(resourcePermissionCheckService.resourcePermissionCheck(AuthorizationType.WORKER_GROUP, null, 1,
                baseServiceLogger)).thenReturn(true);

        Map<String, String> serverMaps = new HashMap<>();
        serverMaps.put("localhost:0000", "");

        when(registryClient.getServerMaps(RegistryNodeType.WORKER)).thenReturn(serverMaps);
        when(workerGroupDao.insert(Mockito.any())).thenThrow(DuplicateKeyException.class);
        assertThrowsServiceException(Status.NAME_EXIST, () -> {
            workerGroupService.saveWorkerGroup(loginUser, 0, GROUP_NAME, "localhost:0000", "test group");
        });
    }

    @Test
    public void giveInvalidAddress_whenSaveWorkerGroup_expectADDRESS_INVALID() {
        User loginUser = getLoginUser();
        when(resourcePermissionCheckService.operationPermissionCheck(AuthorizationType.WORKER_GROUP, 1,
                WORKER_GROUP_CREATE, baseServiceLogger)).thenReturn(true);
        when(resourcePermissionCheckService.resourcePermissionCheck(AuthorizationType.WORKER_GROUP, null, 1,
                baseServiceLogger)).thenReturn(true);
        when(workerGroupDao.queryById(1)).thenReturn(null);
        when(workerGroupDao.queryWorkerGroupByName(GROUP_NAME)).thenReturn(null);
        Map<String, String> serverMaps = new HashMap<>();
        serverMaps.put("localhost1:0000", "");
        when(registryClient.getServerMaps(RegistryNodeType.WORKER)).thenReturn(serverMaps);
        assertThrowsServiceException(Status.WORKER_ADDRESS_INVALID, () -> {
            workerGroupService.saveWorkerGroup(loginUser, 1, GROUP_NAME, "localhost:0000", "test group");
        });
    }

    @Test
    public void giveValidWorkerGroup_whenSaveWorkerGroup_expectSuccess() {
        User loginUser = getLoginUser();
        when(resourcePermissionCheckService.operationPermissionCheck(AuthorizationType.WORKER_GROUP, 1,
                WORKER_GROUP_CREATE, baseServiceLogger)).thenReturn(true);
        when(resourcePermissionCheckService.resourcePermissionCheck(AuthorizationType.WORKER_GROUP, null, 1,
                baseServiceLogger)).thenReturn(true);

        when(workerGroupDao.queryWorkerGroupByName(GROUP_NAME)).thenReturn(null);
        Map<String, String> serverMaps = new HashMap<>();
        serverMaps.put("localhost:0000", "");
        when(registryClient.getServerMaps(RegistryNodeType.WORKER)).thenReturn(serverMaps);
        when(workerGroupDao.insert(any())).thenReturn(1);
        assertDoesNotThrow(() -> {
            workerGroupService.saveWorkerGroup(loginUser, 0, GROUP_NAME, "localhost:0000", "test group");
        });
    }

    @Test
    public void giveValidParams_whenQueryAllGroupPaging_expectSuccess() {
        User loginUser = getLoginUser();
        Set<Integer> ids = new HashSet<>();
        ids.add(1);
        List<WorkerGroup> workerGroups = new ArrayList<>();
        workerGroups.add(getWorkerGroup(1));
        when(resourcePermissionCheckService.userOwnedResourceIdsAcquisition(AuthorizationType.WORKER_GROUP,
                loginUser.getId(), serviceLogger)).thenReturn(ids);
        when(workerGroupDao.queryByIds(ids)).thenReturn(workerGroups);
        Set<String> activeWorkerNodes = new HashSet<>();
        activeWorkerNodes.add("localhost:12345");
        activeWorkerNodes.add("localhost:23456");
        when(registryClient.getServerNodeSet(RegistryNodeType.WORKER)).thenReturn(activeWorkerNodes);

        Result result = workerGroupService.queryAllGroupPaging(loginUser, 1, 1, null);
        Assertions.assertEquals(result.getCode(), Status.SUCCESS.getCode());
    }

    @Test
    public void testQueryAllGroup() {
        List<String> workerGroups = workerGroupService.queryAllGroup(getLoginUser());
        Assertions.assertEquals(0, workerGroups.size());
    }

    @Test
    public void giveNotExistsWorkerGroup_whenDeleteWorkerGroupById_expectNotExists() {
        User loginUser = getLoginUser();
        when(resourcePermissionCheckService.operationPermissionCheck(AuthorizationType.WORKER_GROUP, 1,
                WORKER_GROUP_DELETE, baseServiceLogger)).thenReturn(true);
        when(resourcePermissionCheckService.resourcePermissionCheck(AuthorizationType.WORKER_GROUP, null, 1,
                baseServiceLogger)).thenReturn(true);
        when(workerGroupDao.queryById(1)).thenReturn(null);

        assertThrowsServiceException(Status.DELETE_WORKER_GROUP_NOT_EXIST,
                () -> workerGroupService.deleteWorkerGroupById(loginUser, 1));
    }

    @Test
    public void giveRunningProcess_whenDeleteWorkerGroupById_expectFailed() {
        User loginUser = getLoginUser();
        when(resourcePermissionCheckService.operationPermissionCheck(AuthorizationType.WORKER_GROUP, 1,
                WORKER_GROUP_DELETE, baseServiceLogger)).thenReturn(true);
        when(resourcePermissionCheckService.resourcePermissionCheck(AuthorizationType.WORKER_GROUP, null, 1,
                baseServiceLogger)).thenReturn(true);
        WorkerGroup workerGroup = getWorkerGroup(1);
        when(workerGroupDao.queryById(1)).thenReturn(workerGroup);
        WorkflowInstance workflowInstance = new WorkflowInstance();
        workflowInstance.setId(1);
        List<WorkflowInstance> workflowInstances = new ArrayList<WorkflowInstance>();
        workflowInstances.add(workflowInstance);
        when(workflowInstanceDao.queryByWorkerGroupNameAndStatus(workerGroup.getName(),
                WorkflowExecutionStatus.NOT_TERMINAL_STATES))
                        .thenReturn(workflowInstances);

        assertThrowsServiceException(Status.DELETE_WORKER_GROUP_BY_ID_FAIL,
                () -> workerGroupService.deleteWorkerGroupById(loginUser, 1));
    }

    @Test
    public void giveValidParams_whenDeleteWorkerGroupById_expectSuccess() {
        User loginUser = getLoginUser();
        when(resourcePermissionCheckService.operationPermissionCheck(AuthorizationType.WORKER_GROUP, 1,
                WORKER_GROUP_DELETE, baseServiceLogger)).thenReturn(true);
        when(resourcePermissionCheckService.resourcePermissionCheck(AuthorizationType.WORKER_GROUP, null, 1,
                baseServiceLogger)).thenReturn(true);
        WorkerGroup workerGroup = getWorkerGroup(1);
        when(workerGroupDao.queryById(1)).thenReturn(workerGroup);
        when(workflowInstanceDao.queryByWorkerGroupNameAndStatus(workerGroup.getName(),
                WorkflowExecutionStatus.NOT_TERMINAL_STATES)).thenReturn(null);

        when(taskDefinitionDao.queryByWorkerGroup(Mockito.any())).thenReturn(null);

        when(scheduleDao.queryScheduleByWorkerGroup(Mockito.any())).thenReturn(null);

        assertDoesNotThrow(() -> workerGroupService.deleteWorkerGroupById(loginUser, 1));
    }

    @Test
    public void testQueryAllGroupWithDefault() {
        List<String> workerGroups = workerGroupService.queryAllGroup(getLoginUser());
        Assertions.assertEquals(0, workerGroups.size());
    }

    /**
     * get Group
     */
    private WorkerGroup getWorkerGroup(int id) {
        WorkerGroup workerGroup = new WorkerGroup();
        workerGroup.setName(GROUP_NAME);
        workerGroup.setId(id);
        return workerGroup;
    }

}
