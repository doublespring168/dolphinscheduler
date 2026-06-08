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

package com.gyyun.ds.plugin.datasource.azuresql;

import com.gyyun.ds.plugin.datasource.api.client.BasePooledDataSourceClient;
import com.gyyun.ds.plugin.datasource.azuresql.param.AzureSQLAuthMode;
import com.gyyun.ds.plugin.datasource.azuresql.param.AzureSQLConnectionParam;
import com.gyyun.ds.plugin.datasource.azuresql.param.AzureSQLDataSourceProcessor;
import com.gyyun.ds.spi.datasource.BaseConnectionParam;
import com.gyyun.ds.spi.enums.DbType;

import java.sql.Connection;
import java.sql.SQLException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AzureSQLPooledDataSourceClient extends BasePooledDataSourceClient {

    public AzureSQLPooledDataSourceClient(BaseConnectionParam baseConnectionParam, DbType dbType) {
        super(baseConnectionParam, dbType);
    }

    @Override
    public Connection getConnection() throws SQLException {
        AzureSQLConnectionParam connectionParam = (AzureSQLConnectionParam) this.baseConnectionParam;
        if (!connectionParam.getMode().equals(AzureSQLAuthMode.ACCESSTOKEN)) {
            return super.getConnection();
        }
        return AzureSQLDataSourceProcessor.tokenGetConnection(connectionParam);
    }

}
