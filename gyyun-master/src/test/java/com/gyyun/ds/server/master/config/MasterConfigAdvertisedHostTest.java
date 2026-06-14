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

package com.gyyun.ds.server.master.config;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;

class MasterConfigAdvertisedHostTest {

    @Test
    void validateShouldUseAdvertisedHostWhenMasterAddressIsEmpty() {
        MasterConfig config = new MasterConfig();
        config.setAdvertisedHost("10.20.30.40");

        config.validate(config, new BeanPropertyBindingResult(config, "masterConfig"));

        assertThat(config.getMasterAddress()).isEqualTo("10.20.30.40:5678");
        assertThat(config.getMasterRegistryPath()).isEqualTo("/nodes/master/10.20.30.40:5678");
    }

    @Test
    void validateShouldKeepMasterAddressWhenConfigured() {
        MasterConfig config = new MasterConfig();
        config.setAdvertisedHost("10.20.30.40");
        config.setMasterAddress("10.20.30.41:15678");

        config.validate(config, new BeanPropertyBindingResult(config, "masterConfig"));

        assertThat(config.getMasterAddress()).isEqualTo("10.20.30.41:15678");
        assertThat(config.getMasterRegistryPath()).isEqualTo("/nodes/master/10.20.30.41:15678");
    }
}
