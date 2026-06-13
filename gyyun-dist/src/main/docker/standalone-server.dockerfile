#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

FROM r.gyykj.com/gyy-base/openeuler-jdk:1.8-amd64

ENV DOCKER=true
ENV TZ=Asia/Shanghai
ENV DOLPHINSCHEDULER_HOME=/gyy_program

# RUN apk add --no-cache bash sudo tzdata && \
RUN mkdir -p /gyy_workspace /gyy_program

WORKDIR $DOLPHINSCHEDULER_HOME

COPY ./target/gyyun-*-bin.tar.gz $DOLPHINSCHEDULER_HOME
RUN tar -zxvf gyyun-*-bin.tar.gz --strip-components=1 && \
    rm -f gyyun-*-bin.tar.gz && \
    chown -R gyy /gyy_workspace && \
    chgrp -R gyy /gyy_workspace && \
    chown -R gyy /gyy_program && \
    chgrp -R gyy /gyy_program && \
    chmod +x -R /gyy_program/standalone-server/bin

USER gyy

EXPOSE 12345 25333

CMD [ "/bin/sh", "/gyy_program/standalone-server/bin/start.sh" ]
