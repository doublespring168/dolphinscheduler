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

package com.gyyun.ds.alert;

import com.gyyun.ds.alert.metrics.AlertServerMetrics;
import com.gyyun.ds.alert.plugin.AlertPluginManager;
import com.gyyun.ds.alert.registry.AlertRegistryClient;
import com.gyyun.ds.alert.rpc.AlertRpcServer;
import com.gyyun.ds.alert.service.AlertBootstrapService;
import com.gyyun.ds.alert.service.AlertHAServer;
import com.gyyun.ds.common.CommonConfiguration;
import com.gyyun.ds.common.constants.Constants;
import com.gyyun.ds.common.lifecycle.ServerLifeCycleManager;
import com.gyyun.ds.common.thread.DefaultUncaughtExceptionHandler;
import com.gyyun.ds.common.thread.ThreadUtils;
import com.gyyun.ds.dao.DaoConfiguration;
import com.gyyun.ds.registry.api.RegistryConfiguration;
import com.gyyun.ds.registry.api.ha.AbstractServerStatusChangeListener;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@Slf4j
@Import({CommonConfiguration.class,
        DaoConfiguration.class,
        RegistryConfiguration.class})
@SpringBootApplication
public class AlertServer {

    @Autowired
    private AlertRpcServer alertRpcServer;

    @Autowired
    private AlertPluginManager alertPluginManager;

    @Autowired
    private AlertRegistryClient alertRegistryClient;

    @Autowired
    private AlertHAServer alertHAServer;

    @Autowired
    private AlertBootstrapService alertBootstrapService;

    public static void main(String[] args) {
        AlertServerMetrics.registerUncachedException(DefaultUncaughtExceptionHandler::getUncaughtExceptionCount);
        Thread.setDefaultUncaughtExceptionHandler(DefaultUncaughtExceptionHandler.getInstance());
        Thread.currentThread().setName(Constants.THREAD_NAME_ALERT_SERVER);
        SpringApplication.run(AlertServer.class, args);
    }

    @PostConstruct
    public void run() {
        ServerLifeCycleManager.toRunning();
        log.info("AlertServer is staring ...");
        alertPluginManager.start();
        alertRpcServer.start();
        alertRegistryClient.start();

        alertHAServer.addServerStatusChangeListener(new AbstractServerStatusChangeListener() {

            @Override
            public void changeToActive() {
                alertBootstrapService.start();
            }

            @Override
            public void changeToStandBy() {
                close();
            }
        });

        alertHAServer.start();

        log.info("AlertServer is started ...");
    }

    @PreDestroy
    public void close() {
        String cause = "AlertServer destroy";
        try {
            // set stop signal is true
            // execute only once
            if (!ServerLifeCycleManager.toStopped()) {
                log.warn("AlterServer is already stopped");
                return;
            }
            log.info("AlertServer is stopping, cause: {}", cause);
            try (
                    final AlertRpcServer ignore = alertRpcServer;
                    final AlertRegistryClient ignore1 = alertRegistryClient;
                    final AlertHAServer ignore2 = alertHAServer;
                    final AlertBootstrapService ignore3 = alertBootstrapService;) {
            }
            // thread sleep 3 seconds for thread quietly stop
            ThreadUtils.sleep(Constants.SERVER_CLOSE_WAIT_TIME.toMillis());
            log.info("AlertServer stopped, cause: {}", cause);
        } catch (Exception e) {
            log.error("AlertServer stop failed, cause: {}", cause, e);
        }
    }

}
