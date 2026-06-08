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

package com.gyyun.ds.plugin.task.sqoop.generator;

import static com.gyyun.ds.plugin.task.sqoop.SqoopConstants.HANA;
import static com.gyyun.ds.plugin.task.sqoop.SqoopConstants.HDFS;
import static com.gyyun.ds.plugin.task.sqoop.SqoopConstants.HIVE;
import static com.gyyun.ds.plugin.task.sqoop.SqoopConstants.MYSQL;
import static com.gyyun.ds.plugin.task.sqoop.SqoopConstants.ORACLE;
import static com.gyyun.ds.plugin.task.sqoop.SqoopConstants.SQLSERVER;

import com.gyyun.ds.plugin.task.sqoop.SqoopJobType;
import com.gyyun.ds.plugin.task.sqoop.SqoopTaskExecutionContext;
import com.gyyun.ds.plugin.task.sqoop.generator.sources.HanaSourceGenerator;
import com.gyyun.ds.plugin.task.sqoop.generator.sources.HdfsSourceGenerator;
import com.gyyun.ds.plugin.task.sqoop.generator.sources.HiveSourceGenerator;
import com.gyyun.ds.plugin.task.sqoop.generator.sources.MySQLSourceGenerator;
import com.gyyun.ds.plugin.task.sqoop.generator.sources.OracleSourceGenerator;
import com.gyyun.ds.plugin.task.sqoop.generator.sources.SqlServerSourceGenerator;
import com.gyyun.ds.plugin.task.sqoop.generator.targets.HanaTargetGenerator;
import com.gyyun.ds.plugin.task.sqoop.generator.targets.HdfsTargetGenerator;
import com.gyyun.ds.plugin.task.sqoop.generator.targets.HiveTargetGenerator;
import com.gyyun.ds.plugin.task.sqoop.generator.targets.MySQLTargetGenerator;
import com.gyyun.ds.plugin.task.sqoop.generator.targets.OracleTargetGenerator;
import com.gyyun.ds.plugin.task.sqoop.generator.targets.SqlServerTargetGenerator;
import com.gyyun.ds.plugin.task.sqoop.parameter.SqoopParameters;

/**
 * Sqoop Job Scripts Generator
 */
public class SqoopJobGenerator {

    /**
     * target script generator
     */
    private ITargetGenerator targetGenerator;
    /**
     * source script generator
     */
    private ISourceGenerator sourceGenerator;
    /**
     * common script generator
     */
    private final CommonGenerator commonGenerator;

    public SqoopJobGenerator() {
        commonGenerator = new CommonGenerator();
    }

    private void createSqoopJobGenerator(String sourceType, String targetType) {
        sourceGenerator = createSourceGenerator(sourceType);
        targetGenerator = createTargetGenerator(targetType);
    }

    /**
     * get the final sqoop scripts
     *
     * @param sqoopParameters           sqoop params
     * @param sqoopTaskExecutionContext
     * @return sqoop scripts
     */
    public String generateSqoopJob(SqoopParameters sqoopParameters,
                                   SqoopTaskExecutionContext sqoopTaskExecutionContext) {

        String sqoopScripts = "";

        if (SqoopJobType.TEMPLATE.getDescp().equals(sqoopParameters.getJobType())) {
            createSqoopJobGenerator(sqoopParameters.getSourceType(), sqoopParameters.getTargetType());
            if (sourceGenerator == null || targetGenerator == null) {
                throw new RuntimeException("sqoop task source type or target type is null");
            }

            sqoopScripts = String.format("%s%s%s", commonGenerator.generate(sqoopParameters),
                    sourceGenerator.generate(sqoopParameters, sqoopTaskExecutionContext),
                    targetGenerator.generate(sqoopParameters, sqoopTaskExecutionContext));
        } else if (SqoopJobType.CUSTOM.getDescp().equals(sqoopParameters.getJobType())) {
            sqoopScripts = sqoopParameters.getCustomShell().replaceAll("\\r\\n", System.lineSeparator());
        }

        return sqoopScripts;
    }

    /**
     * get the source generator
     *
     * @param sourceType sqoop source type
     * @return sqoop source generator
     */
    private ISourceGenerator createSourceGenerator(String sourceType) {
        switch (sourceType) {
            case MYSQL:
                return new MySQLSourceGenerator();
            case HIVE:
                return new HiveSourceGenerator();
            case HDFS:
                return new HdfsSourceGenerator();
            case ORACLE:
                return new OracleSourceGenerator();
            case HANA:
                return new HanaSourceGenerator();
            case SQLSERVER:
                return new SqlServerSourceGenerator();
            default:
                return null;
        }
    }

    /**
     * get the target generator
     *
     * @param targetType sqoop target type
     * @return sqoop target generator
     */
    private ITargetGenerator createTargetGenerator(String targetType) {
        switch (targetType) {
            case MYSQL:
                return new MySQLTargetGenerator();
            case HIVE:
                return new HiveTargetGenerator();
            case HDFS:
                return new HdfsTargetGenerator();
            case ORACLE:
                return new OracleTargetGenerator();
            case HANA:
                return new HanaTargetGenerator();
            case SQLSERVER:
                return new SqlServerTargetGenerator();
            default:
                return null;
        }
    }
}
