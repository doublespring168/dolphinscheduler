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
import com.gyyun.ds.alert.api.AlertChannelFactory;
import com.gyyun.ds.alert.api.AlertInputTips;
import com.gyyun.ds.common.utils.JSONUtils;
import com.gyyun.ds.spi.params.base.PluginParams;
import com.gyyun.ds.spi.params.base.Validate;
import com.gyyun.ds.spi.params.input.InputParam;

import java.util.LinkedList;
import java.util.List;

import com.google.auto.service.AutoService;

@AutoService(AlertChannelFactory.class)
public final class SlackAlertChannelFactory implements AlertChannelFactory {

    @Override
    public String name() {
        return "Slack";
    }

    @Override
    public List<PluginParams> params() {
        List<PluginParams> paramsList = new LinkedList<>();

        InputParam webHookParam = InputParam
                .newBuilder(SlackParamsConstants.SLACK_WEB_HOOK_URL_NAME, SlackParamsConstants.SLACK_WEB_HOOK_URL)
                .addValidate(Validate.newBuilder()
                        .setRequired(true)
                        .build())
                .setPlaceholder(JSONUtils.toJsonString(AlertInputTips.getAllMsg(AlertInputTips.WEBHOOK)))
                .build();

        InputParam botName = InputParam.newBuilder(SlackParamsConstants.SLACK_BOT_NAME, SlackParamsConstants.SLACK_BOT)
                .addValidate(Validate.newBuilder()
                        .setRequired(true)
                        .build())
                .setPlaceholder(JSONUtils.toJsonString(AlertInputTips.getAllMsg(AlertInputTips.BOT_NAME)))
                .build();

        paramsList.add(webHookParam);
        paramsList.add(botName);
        return paramsList;
    }

    @Override
    public AlertChannel create() {
        return new SlackAlertChannel();
    }
}
