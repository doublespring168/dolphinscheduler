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

FROM r.gyykj.com/gyy-base/openeuler-jdk:1.8-amd64 AS gyyun-dist

WORKDIR /tmp/gyyun-dist

COPY ./target/*gyyun-*-bin.tar.gz /tmp/gyyun-bin.tar.gz
RUN tar -zxf /tmp/gyyun-bin.tar.gz -C /tmp/gyyun-dist --strip-components=1 --exclude='*/plugins/*' && \
    rm -f /tmp/gyyun-bin.tar.gz && \
    rm -rf /tmp/gyyun-dist/plugins && \
    mkdir -p \
      /tmp/gyyun-dist/plugins/alert-plugins \
      /tmp/gyyun-dist/plugins/datasource-plugins \
      /tmp/gyyun-dist/plugins/storage-plugins \
      /tmp/gyyun-dist/plugins/task-plugins

FROM r.gyykj.com/gyy-base/openeuler-jdk:1.8-amd64

ENV DOCKER=true
ENV TZ=Asia/Shanghai
ENV DOLPHINSCHEDULER_HOME=/gyy_program

# RUN apk add --no-cache bash sudo tzdata && \
RUN mkdir -p /gyy_workspace /gyy_program

WORKDIR $DOLPHINSCHEDULER_HOME

COPY --from=gyyun-dist /tmp/gyyun-dist/libs ./libs
COPY --from=gyyun-dist /tmp/gyyun-dist/plugins ./plugins
COPY --from=gyyun-dist /tmp/gyyun-dist/api-server ./api-server
COPY --from=gyyun-dist /tmp/gyyun-dist/master-server ./master-server
COPY --from=gyyun-dist /tmp/gyyun-dist/worker-server ./worker-server
COPY --from=gyyun-dist /tmp/gyyun-dist/alert-server ./alert-server
COPY --from=gyyun-dist /tmp/gyyun-dist/tools ./tools
COPY --from=gyyun-dist /tmp/gyyun-dist/standalone-server ./standalone-server

RUN chown -R gyy /gyy_workspace && \
    chgrp -R gyy /gyy_workspace && \
    chown -R gyy /gyy_program && \
    chgrp -R gyy /gyy_program && \
    chmod +x -R /gyy_program/standalone-server/bin

USER gyy

EXPOSE 12345 25333

CMD [ "/bin/sh", "/gyy_program/standalone-server/bin/start.sh" ]
