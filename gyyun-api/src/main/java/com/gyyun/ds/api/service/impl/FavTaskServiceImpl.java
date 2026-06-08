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

import com.gyyun.ds.api.configuration.TaskTypeConfiguration;
import com.gyyun.ds.api.dto.FavTaskDto;
import com.gyyun.ds.api.service.FavTaskService;
import com.gyyun.ds.dao.entity.FavTask;
import com.gyyun.ds.dao.entity.User;
import com.gyyun.ds.dao.mapper.FavTaskMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

@Service
public class FavTaskServiceImpl extends BaseServiceImpl implements FavTaskService {

    @Resource
    private TaskTypeConfiguration taskTypeConfiguration;
    @Resource
    private FavTaskMapper favMapper;

    @Override
    public List<FavTaskDto> getFavTaskList(User loginUser) {
        Set<String> userFavTaskTypes = favMapper.getUserFavTaskTypes(loginUser.getId());

        List<FavTaskDto> defaultTaskTypes = taskTypeConfiguration.getDefaultTaskTypes();
        List<FavTaskDto> result = new ArrayList<>();
        // clone default list and modify fav task type flag
        defaultTaskTypes.forEach(e -> {
            try {
                FavTaskDto clone = (FavTaskDto) e.clone();
                if (userFavTaskTypes.contains(clone.getTaskType())) {
                    clone.setCollection(true);
                }
                result.add(clone);
            } catch (CloneNotSupportedException ex) {
                throw new RuntimeException(ex);
            }
        });
        return result;
    }

    @Override
    public boolean deleteFavTask(User loginUser, String taskType) {
        return favMapper.deleteUserFavTask(loginUser.getId(), taskType);
    }

    @Override
    public int addFavTask(User loginUser, String taskType) {
        favMapper.deleteUserFavTask(loginUser.getId(), taskType);
        return favMapper.insert(new FavTask(null, taskType, loginUser.getId()));
    }
}
