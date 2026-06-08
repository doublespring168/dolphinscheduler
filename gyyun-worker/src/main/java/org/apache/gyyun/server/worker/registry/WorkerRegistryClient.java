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

package org.apache.gyyun.server.worker.registry;

import static org.apache.gyyun.common.constants.Constants.SLEEP_TIME_MILLIS;

import org.apache.gyyun.common.IStoppable;
import org.apache.gyyun.common.model.Server;
import org.apache.gyyun.common.thread.ThreadUtils;
import org.apache.gyyun.common.utils.JSONUtils;
import org.apache.gyyun.extract.base.utils.Host;
import org.apache.gyyun.meter.metrics.MetricsProvider;
import org.apache.gyyun.registry.api.RegistryClient;
import org.apache.gyyun.registry.api.RegistryException;
import org.apache.gyyun.registry.api.enums.RegistryNodeType;
import org.apache.gyyun.server.worker.config.WorkerConfig;
import org.apache.gyyun.server.worker.config.WorkerServerLoadProtection;
import org.apache.gyyun.server.worker.executor.PhysicalTaskExecutorContainerProvider;
import org.apache.gyyun.server.worker.task.WorkerHeartBeatTask;

import org.apache.commons.collections4.CollectionUtils;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import javax.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class WorkerRegistryClient implements AutoCloseable {

    @Autowired
    private WorkerConfig workerConfig;

    @Autowired
    private WorkerServerLoadProtection workerServerLoadProtection;

    @Autowired
    private PhysicalTaskExecutorContainerProvider physicalTaskExecutorContainerDelegator;

    @Autowired
    private RegistryClient registryClient;

    @Autowired
    private MetricsProvider metricsProvider;

    private WorkerHeartBeatTask workerHeartBeatTask;

    @PostConstruct
    public void initWorkRegistry() {
        this.workerHeartBeatTask = new WorkerHeartBeatTask(
                workerConfig,
                workerServerLoadProtection,
                metricsProvider,
                registryClient,
                physicalTaskExecutorContainerDelegator.getExecutorContainer());
    }

    public void start() {
        try {
            registry();
            registryClient.addConnectionStateListener(new WorkerConnectionStateListener(registryClient));
        } catch (Exception ex) {
            throw new RegistryException("Worker registry client start up error", ex);
        }
    }

    private void registry() {
        String workerRegistryPath = workerConfig.getWorkerRegistryPath();
        // remove before persist
        registryClient.remove(workerRegistryPath);
        registryClient.persistEphemeral(workerRegistryPath, JSONUtils.toJsonString(workerHeartBeatTask.getHeartBeat()));
        log.info("Worker node: {} registry to registry center {} successfully", workerConfig.getWorkerAddress(),
                workerRegistryPath);

        while (!registryClient.checkNodeExists(workerConfig.getWorkerAddress(), RegistryNodeType.WORKER)) {
            ThreadUtils.sleep(SLEEP_TIME_MILLIS);
        }

        workerHeartBeatTask.start();
        log.info("Worker node: {} registry finished", workerConfig.getWorkerAddress());
    }

    public Optional<Host> getAlertServerAddress() {
        List<Server> serverList = registryClient.getServerList(RegistryNodeType.ALERT_SERVER);
        if (CollectionUtils.isEmpty(serverList)) {
            return Optional.empty();
        }
        Server server = serverList.get(0);
        return Optional.of(new Host(server.getHost(), server.getPort()));
    }

    public void setRegistryStoppable(IStoppable stoppable) {
        registryClient.setStoppable(stoppable);
    }

    @Override
    public void close() throws IOException {
        if (workerHeartBeatTask != null) {
            workerHeartBeatTask.shutdown();
        }
        registryClient.close();
        log.info("Closed WorkerRegistryClient");
    }

    public boolean isAvailable() {
        return registryClient.isConnected();
    }
}
