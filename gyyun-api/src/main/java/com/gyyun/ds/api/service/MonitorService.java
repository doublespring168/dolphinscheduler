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

import com.gyyun.ds.common.model.Server;
import com.gyyun.ds.dao.entity.User;
import com.gyyun.ds.dao.plugin.api.monitor.DatabaseMetrics;
import com.gyyun.ds.extract.master.dto.WorkflowExecutorDTO;
import com.gyyun.ds.registry.api.enums.RegistryNodeType;
import com.gyyun.ds.task.executor.dto.TaskExecutorDTO;

import java.util.List;

public interface MonitorService {

    /**
     * query database state
     *
     * @param loginUser login user
     * @return data base state
     */
    List<DatabaseMetrics> queryDatabaseState(User loginUser);

    /**
     * query server list
     *
     * @param nodeType RegistryNodeType
     * @return server information list
     */
    List<Server> listServer(RegistryNodeType nodeType);

    List<WorkflowExecutorDTO> queryWorkflowExecutors(User loginUser, String masterAddress);

    List<TaskExecutorDTO> queryTaskExecutors(User loginUser, String serverAddress);
}
