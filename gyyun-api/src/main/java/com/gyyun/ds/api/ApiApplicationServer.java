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

package com.gyyun.ds.api;

import com.gyyun.ds.api.metrics.ApiServerMetrics;
import com.gyyun.ds.common.CommonConfiguration;
import com.gyyun.ds.common.lifecycle.ServerLifeCycleManager;
import com.gyyun.ds.common.thread.DefaultUncaughtExceptionHandler;
import com.gyyun.ds.dao.DaoConfiguration;
import com.gyyun.ds.plugin.datasource.api.plugin.DataSourcePluginManager;
import com.gyyun.ds.plugin.storage.api.StorageConfiguration;
import com.gyyun.ds.plugin.task.api.TaskPluginManager;
import com.gyyun.ds.registry.api.RegistryConfiguration;
import com.gyyun.ds.service.ServiceConfiguration;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

@Slf4j
@Import({DaoConfiguration.class,
        CommonConfiguration.class,
        ServiceConfiguration.class,
        StorageConfiguration.class,
        RegistryConfiguration.class})
@ServletComponentScan
@SpringBootApplication
public class ApiApplicationServer {

    public static void main(String[] args) {
        ApiServerMetrics.registerUncachedException(DefaultUncaughtExceptionHandler::getUncaughtExceptionCount);
        Thread.setDefaultUncaughtExceptionHandler(DefaultUncaughtExceptionHandler.getInstance());
        SpringApplication.run(ApiApplicationServer.class);
    }

    @EventListener
    public void run(ApplicationReadyEvent readyEvent) {
        ServerLifeCycleManager.toRunning();
        log.info("Received spring application context ready event will load taskPlugin and write to DB");
        DataSourcePluginManager.loadDataSourcePlugin();
        TaskPluginManager.loadTaskPlugin();
    }
}
