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

package org.apache.gyyun.server.master.registry;

import org.apache.gyyun.common.enums.ServerStatus;
import org.apache.gyyun.common.lifecycle.ServerLifeCycleManager;
import org.apache.gyyun.common.model.BaseHeartBeatTask;
import org.apache.gyyun.common.model.MasterHeartBeat;
import org.apache.gyyun.common.utils.JSONUtils;
import org.apache.gyyun.common.utils.NetUtils;
import org.apache.gyyun.common.utils.OSUtils;
import org.apache.gyyun.meter.metrics.MetricsProvider;
import org.apache.gyyun.meter.metrics.SystemMetrics;
import org.apache.gyyun.registry.api.RegistryClient;
import org.apache.gyyun.registry.api.utils.RegistryUtils;
import org.apache.gyyun.server.master.config.MasterConfig;
import org.apache.gyyun.server.master.config.MasterServerLoadProtection;
import org.apache.gyyun.server.master.engine.MasterCoordinator;
import org.apache.gyyun.server.master.metrics.MasterServerMetrics;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MasterHeartBeatTask extends BaseHeartBeatTask<MasterHeartBeat> {

    private final MasterConfig masterConfig;

    private MasterServerLoadProtection masterServerLoadProtection;

    private final MetricsProvider metricsProvider;

    private final RegistryClient registryClient;

    private final MasterCoordinator masterCoordinator;

    private final String heartBeatPath;

    private final int processId;

    public MasterHeartBeatTask(@NonNull MasterConfig masterConfig,
                               @NonNull MasterServerLoadProtection masterServerLoadProtection,
                               @NonNull MetricsProvider metricsProvider,
                               @NonNull RegistryClient registryClient,
                               @NonNull MasterCoordinator masterCoordinator) {
        super("MasterHeartBeatTask", masterConfig.getMaxHeartbeatInterval().toMillis());
        this.masterConfig = masterConfig;
        this.masterServerLoadProtection = masterServerLoadProtection;
        this.metricsProvider = metricsProvider;
        this.registryClient = registryClient;
        this.masterCoordinator = masterCoordinator;
        this.heartBeatPath = masterConfig.getMasterRegistryPath();
        this.processId = OSUtils.getProcessID();
    }

    @Override
    public MasterHeartBeat getHeartBeat() {
        SystemMetrics systemMetrics = metricsProvider.getSystemMetrics();
        return MasterHeartBeat.builder()
                .startupTime(ServerLifeCycleManager.getServerStartupTime())
                .reportTime(System.currentTimeMillis())
                .jvmCpuUsage(systemMetrics.getJvmCpuUsagePercentage())
                .cpuUsage(systemMetrics.getSystemCpuUsagePercentage())
                .jvmMemoryUsage(systemMetrics.getJvmMemoryUsedPercentage())
                .jvmHeapUsed(systemMetrics.getJvmHeapUsed())
                .jvmHeapMax(systemMetrics.getJvmHeapMax())
                .jvmNonHeapUsed(systemMetrics.getJvmNonHeapUsed())
                .jvmNonHeapMax(systemMetrics.getJvmNonHeapMax())
                .memoryUsage(systemMetrics.getSystemMemoryUsedPercentage())
                .diskUsage(systemMetrics.getDiskUsedPercentage())
                .processId(processId)
                .serverStatus(
                        masterServerLoadProtection.isOverload(systemMetrics) ? ServerStatus.BUSY : ServerStatus.NORMAL)
                .host(NetUtils.getHost())
                .port(masterConfig.getListenPort())
                .isCoordinator(masterCoordinator.isActive())
                .build();
    }

    @Override
    public void writeHeartBeat(final MasterHeartBeat masterHeartBeat) {
        final String failoverNodePath = RegistryUtils.getFailoveredNodePath(masterHeartBeat);
        if (registryClient.exists(failoverNodePath)) {
            log.warn("The master: {} is under {}, means it has been failover will close myself",
                    masterHeartBeat,
                    failoverNodePath);
            registryClient
                    .getStoppable()
                    .stop("The master exist: " + failoverNodePath + ", means it has been failover will close myself");
            return;
        }
        String masterHeartBeatJson = JSONUtils.toJsonString(masterHeartBeat);
        registryClient.persistEphemeral(heartBeatPath, masterHeartBeatJson);
        MasterServerMetrics.incMasterHeartbeatCount();
        log.debug("Success write master heartBeatInfo into registry, masterRegistryPath: {}, heartBeatInfo: {}",
                heartBeatPath,
                masterHeartBeatJson);
    }

}
