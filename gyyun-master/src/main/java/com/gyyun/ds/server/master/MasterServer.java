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

package com.gyyun.ds.server.master;

import com.gyyun.ds.common.CommonConfiguration;
import com.gyyun.ds.common.IStoppable;
import com.gyyun.ds.common.constants.Constants;
import com.gyyun.ds.common.lifecycle.ServerLifeCycleManager;
import com.gyyun.ds.common.thread.DefaultUncaughtExceptionHandler;
import com.gyyun.ds.common.thread.ThreadUtils;
import com.gyyun.ds.dao.DaoConfiguration;
import com.gyyun.ds.meter.metrics.MetricsProvider;
import com.gyyun.ds.meter.metrics.SystemMetrics;
import com.gyyun.ds.plugin.datasource.api.plugin.DataSourcePluginManager;
import com.gyyun.ds.plugin.storage.api.StorageConfiguration;
import com.gyyun.ds.plugin.task.api.TaskPluginManager;
import com.gyyun.ds.registry.api.RegistryConfiguration;
import com.gyyun.ds.scheduler.api.SchedulerApi;
import com.gyyun.ds.server.master.cluster.ClusterManager;
import com.gyyun.ds.server.master.cluster.ClusterStateMonitors;
import com.gyyun.ds.server.master.engine.MasterCoordinator;
import com.gyyun.ds.server.master.engine.WorkflowEngine;
import com.gyyun.ds.server.master.engine.system.SystemEventBus;
import com.gyyun.ds.server.master.engine.system.SystemEventBusFireWorker;
import com.gyyun.ds.server.master.engine.system.event.GlobalMasterFailoverEvent;
import com.gyyun.ds.server.master.engine.task.dispatcher.WorkerGroupDispatcherCoordinator;
import com.gyyun.ds.server.master.metrics.MasterServerMetrics;
import com.gyyun.ds.server.master.registry.MasterRegistryClient;
import com.gyyun.ds.server.master.rpc.MasterRpcServer;
import com.gyyun.ds.server.master.utils.MasterThreadFactory;
import com.gyyun.ds.service.ServiceConfiguration;
import com.gyyun.ds.service.bean.SpringApplicationContext;

import java.util.Date;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@Slf4j
@Import({DaoConfiguration.class,
        ServiceConfiguration.class,
        CommonConfiguration.class,
        StorageConfiguration.class,
        RegistryConfiguration.class})
@SpringBootApplication
public class MasterServer implements IStoppable {

    @Autowired
    private SpringApplicationContext springApplicationContext;

    @Autowired
    private MasterRegistryClient masterRegistryClient;

    @Autowired
    private WorkflowEngine workflowEngine;

    @Autowired
    private SchedulerApi schedulerApi;

    @Autowired
    private MasterRpcServer masterRPCServer;

    @Autowired
    private MetricsProvider metricsProvider;

    @Autowired
    private ClusterStateMonitors clusterStateMonitors;

    @Autowired
    private ClusterManager clusterManager;

    @Autowired
    private SystemEventBus systemEventBus;

    @Autowired
    private SystemEventBusFireWorker systemEventBusFireWorker;

    @Autowired
    private MasterCoordinator masterCoordinator;

    @Autowired
    private WorkerGroupDispatcherCoordinator workerGroupDispatcherCoordinator;

    public static void main(String[] args) {
        MasterServerMetrics.registerUncachedException(DefaultUncaughtExceptionHandler::getUncaughtExceptionCount);

        Thread.setDefaultUncaughtExceptionHandler(DefaultUncaughtExceptionHandler.getInstance());
        Thread.currentThread().setName(Constants.THREAD_NAME_MASTER_SERVER);
        SpringApplication.run(MasterServer.class);
    }

    /**
     * run master server
     */
    @PostConstruct
    public void initialized() {
        ServerLifeCycleManager.toRunning();

        // init rpc server
        this.masterRPCServer.start();

        // install task plugin
        TaskPluginManager.loadTaskPlugin();
        DataSourcePluginManager.loadDataSourcePlugin();

        // self tolerant
        this.masterRegistryClient.start();
        this.masterRegistryClient.setRegistryStoppable(this);

        this.masterCoordinator.start();

        this.clusterManager.start();

        this.clusterStateMonitors.start();

        this.workflowEngine.start();

        this.schedulerApi.start();

        this.systemEventBus
                .publish(GlobalMasterFailoverEvent.of(new Date(ServerLifeCycleManager.getServerStartupTime())));
        this.systemEventBusFireWorker.start();

        MasterServerMetrics.registerMasterCpuUsageGauge(() -> {
            SystemMetrics systemMetrics = metricsProvider.getSystemMetrics();
            return systemMetrics.getSystemCpuUsagePercentage();
        });
        MasterServerMetrics.registerMasterMemoryAvailableGauge(() -> {
            SystemMetrics systemMetrics = metricsProvider.getSystemMetrics();
            return (systemMetrics.getSystemMemoryMax() - systemMetrics.getSystemMemoryUsed()) / 1024.0 / 1024 / 1024;
        });
        MasterServerMetrics.registerMasterMemoryUsageGauge(() -> {
            SystemMetrics systemMetrics = metricsProvider.getSystemMetrics();
            return systemMetrics.getJvmMemoryUsedPercentage();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!ServerLifeCycleManager.isStopped()) {
                close("MasterServer shutdownHook");
            }
        }));
        log.info("MasterServer initialized successfully in {} ms",
                System.currentTimeMillis() - ServerLifeCycleManager.getServerStartupTime());
    }

    @PreDestroy
    public void shutdown() {
        close("MasterServer shutdown");
    }

    public void close(String cause) {
        // set stop signal is true
        // execute only once
        if (!ServerLifeCycleManager.toStopped()) {
            log.warn("MasterServer is already stopped, current cause: {}", cause);
            return;
        }
        // thread sleep 3 seconds for thread quietly stop
        ThreadUtils.sleep(Constants.SERVER_CLOSE_WAIT_TIME.toMillis());
        MasterThreadFactory.getDefaultSchedulerThreadExecutor().shutdownNow();
        try (
                SystemEventBusFireWorker systemEventBusFireWorker1 = systemEventBusFireWorker;
                WorkflowEngine workflowEngine1 = workflowEngine;
                SchedulerApi closedSchedulerApi = schedulerApi;
                MasterRpcServer closedRpcServer = masterRPCServer;
                MasterCoordinator closeMasterCoordinator = masterCoordinator;
                MasterRegistryClient closedMasterRegistryClient = masterRegistryClient;
                // close spring Context and will invoke method with @PreDestroy annotation to destroy beans.
                // like ServerNodeManager,HostManager,TaskResponseService,CuratorZookeeperClient,etc
                SpringApplicationContext closedSpringContext = springApplicationContext;
                WorkerGroupDispatcherCoordinator closeWorkerGroupDispatcherCoordinator =
                        workerGroupDispatcherCoordinator) {

            log.info("MasterServer is stopping, current cause : {}", cause);
        } catch (Exception e) {
            log.error("MasterServer stop failed, current cause: {}", cause, e);
            return;
        }
        log.info("MasterServer stopped, current cause: {}", cause);
    }

    @Override
    public void stop(String cause) {
        close(cause);

        // make sure exit after server closed, don't call System.exit in close logic, will cause deadlock if close
        // multiple times at the same time
        System.exit(1);
    }
}
