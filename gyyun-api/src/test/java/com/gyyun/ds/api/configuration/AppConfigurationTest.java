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

package com.gyyun.ds.api.configuration;

import java.io.File;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class AppConfigurationTest {

    @TempDir
    private File dolphinSchedulerHome;

    @Test
    public void testGetUiResourceLocationWithDolphinSchedulerHome() {
        String location = AppConfiguration.getUiResourceLocation(dolphinSchedulerHome.getAbsolutePath());

        Assertions.assertEquals(getFileResourceLocation(new File(dolphinSchedulerHome, "api-server/ui")), location);
    }

    @Test
    public void testGetUiResourceLocationWithoutDolphinSchedulerHome() {
        Assertions.assertThrows(IllegalStateException.class, () -> AppConfiguration.getUiResourceLocation(null));
    }

    private String getFileResourceLocation(File directory) {
        String location = directory.toURI().toString();
        return location.endsWith("/") ? location : location + "/";
    }
}
