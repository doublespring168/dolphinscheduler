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

package com.gyyun.ds.plugin.alert.slack;

import com.gyyun.ds.alert.api.AlertChannel;
import com.gyyun.ds.alert.api.AlertData;
import com.gyyun.ds.alert.api.AlertInfo;
import com.gyyun.ds.alert.api.AlertResult;

import java.util.Map;

public final class SlackAlertChannel implements AlertChannel {

    @Override
    public AlertResult process(AlertInfo alertInfo) {
        AlertData alertData = alertInfo.getAlertData();
        Map<String, String> alertParams = alertInfo.getAlertParams();
        if (alertParams == null || alertParams.isEmpty()) {
            return new AlertResult(false, "Slack alert params is empty");
        }
        SlackSender slackSender = new SlackSender(alertParams);
        String response = slackSender.sendMessage(alertData.getTitle(), alertData.getContent());
        return new AlertResult("ok".equals(response), response);
    }
}
