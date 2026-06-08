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

package com.gyyun.ds.api.validator.resource;

import com.gyyun.ds.api.dto.resources.DeleteResourceDto;
import com.gyyun.ds.dao.entity.User;
import com.gyyun.ds.dao.repository.TenantDao;
import com.gyyun.ds.plugin.storage.api.StorageOperator;

import org.springframework.stereotype.Component;

@Component
public class DeleteResourceDtoValidator extends AbstractResourceValidator<DeleteResourceDto> {

    public DeleteResourceDtoValidator(StorageOperator storageOperator, TenantDao tenantDao) {
        super(storageOperator, tenantDao);
    }

    @Override
    public void validate(DeleteResourceDto deleteResourceDto) {
        String resourceAbsolutePath = deleteResourceDto.getResourceAbsolutePath();
        User loginUser = deleteResourceDto.getLoginUser();

        exceptionResourceAbsolutePathInvalidated(resourceAbsolutePath);
        exceptionResourceNotExists(resourceAbsolutePath);
        exceptionUserNoResourcePermission(loginUser, resourceAbsolutePath);
    }
}
