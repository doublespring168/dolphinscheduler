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

import com.gyyun.ds.api.enums.Status;
import com.gyyun.ds.api.exceptions.ServiceException;
import com.gyyun.ds.api.service.MonitorService;
import com.gyyun.ds.common.enums.UserType;
import com.gyyun.ds.common.model.Server;
import com.gyyun.ds.dao.entity.User;
import com.gyyun.ds.dao.plugin.api.monitor.DatabaseMetrics;
import com.gyyun.ds.dao.plugin.api.monitor.DatabaseMonitor;
import com.gyyun.ds.extract.base.client.Clients;
import com.gyyun.ds.extract.master.IWorkflowExecutorQueryClient;
import com.gyyun.ds.extract.master.dto.WorkflowExecutorDTO;
import com.gyyun.ds.extract.master.transportor.WorkflowExecutorQueryRequest;
import com.gyyun.ds.extract.master.transportor.WorkflowExecutorQueryResponse;
import com.gyyun.ds.extract.worker.ITaskExecutorQueryClient;
import com.gyyun.ds.extract.worker.transportor.TaskExecutorQueryRequest;
import com.gyyun.ds.extract.worker.transportor.TaskExecutorQueryResponse;
import com.gyyun.ds.registry.api.RegistryClient;
import com.gyyun.ds.registry.api.enums.RegistryNodeType;
import com.gyyun.ds.task.executor.dto.TaskExecutorDTO;

import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.common.collect.Lists;

@Service
@Slf4j
public class MonitorServiceImpl extends BaseServiceImpl implements MonitorService {

    @Autowired
    private DatabaseMonitor databaseMonitor;

    @Autowired
    private RegistryClient registryClient;

    /**
     * query database state
     *
     * @param loginUser login user
     * @return data base state
     */
    @Override
    public List<DatabaseMetrics> queryDatabaseState(User loginUser) {
        return Lists.newArrayList(databaseMonitor.getDatabaseMetrics());
    }

    @Override
    public List<Server> listServer(RegistryNodeType nodeType) {
        return registryClient.getServerList(nodeType);
    }

    @Override
    public List<WorkflowExecutorDTO> queryWorkflowExecutors(User loginUser, String masterAddress) {
        if (!loginUser.getUserType().equals(UserType.ADMIN_USER)) {
            throw new ServiceException(Status.NO_CURRENT_OPERATING_PERMISSION);
        }

        WorkflowExecutorQueryResponse response = Clients
                .withService(IWorkflowExecutorQueryClient.class)
                .withHost(masterAddress)
                .queryWorkflowExecutors(new WorkflowExecutorQueryRequest());
        if (!response.isSuccess()) {
            throw new ServiceException(response.getMessage());
        }
        return response.getWorkflowExecutors();
    }

    @Override
    public List<TaskExecutorDTO> queryTaskExecutors(User loginUser, String serverAddress) {
        if (!loginUser.getUserType().equals(UserType.ADMIN_USER)) {
            throw new ServiceException(Status.NO_CURRENT_OPERATING_PERMISSION);
        }
        TaskExecutorQueryResponse response = Clients
                .withService(ITaskExecutorQueryClient.class)
                .withHost(serverAddress)
                .queryTaskInstances(new TaskExecutorQueryRequest());
        if (!response.isSuccess()) {
            throw new ServiceException(response.getMessage());
        }
        return response.getTaskExecutors();
    }

}
