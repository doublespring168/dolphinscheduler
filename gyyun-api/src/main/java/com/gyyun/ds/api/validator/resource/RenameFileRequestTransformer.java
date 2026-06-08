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

import com.gyyun.ds.api.dto.resources.RenameFileDto;
import com.gyyun.ds.api.dto.resources.RenameFileRequest;
import com.gyyun.ds.api.validator.ITransformer;
import com.gyyun.ds.common.utils.FileUtils;
import com.gyyun.ds.plugin.storage.api.ResourceMetadata;
import com.gyyun.ds.plugin.storage.api.StorageOperator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RenameFileRequestTransformer implements ITransformer<RenameFileRequest, RenameFileDto> {

    @Autowired
    private StorageOperator storageOperator;

    @Override
    public RenameFileDto transform(RenameFileRequest renameFileRequest) {
        ResourceMetadata resourceMetaData =
                storageOperator.getResourceMetaData(renameFileRequest.getFileAbsolutePath());
        return RenameFileDto.builder()
                .loginUser(renameFileRequest.getLoginUser())
                .originFileAbsolutePath(renameFileRequest.getFileAbsolutePath())
                .targetFileAbsolutePath(FileUtils.concatFilePath(resourceMetaData.getResourceParentAbsolutePath(),
                        renameFileRequest.getNewFileName()))
                .build();
    }
}
