#!/bin/bash
###
 # @Author: darcy.zhang , tech.darcy.zhang@outlook.com
 # @Date: 2026-06-08 19:14:37
 # @LastEditors: darcy.zhang , tech.darcy.zhang@outlook.com
 # @LastEditTime: 2026-06-14 10:24:05
 # @FilePath: /ala-ds/gyyun-dist/src/main/docker/docker-push.sh
 # @Description: 
 # 
 # Copyright (c) 2026 by 【 tech.darcy.zhang@outlook.com 】, All Rights Reserved. 
### 
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
set -xeo pipefail

DOCKER_HUB=$1
DOCKER_TAG=$2
DOCKER_REPO_BASE=$3

CURRENT_HOME=$(dirname $(readlink -f "$0"))
DOCKER_IMAGES_HOME=${CURRENT_HOME}/../../../docker-images

mkdir -p "$DOCKER_IMAGES_HOME"

save_image() {
  local image_name=$1
  local archive_name=$2

  docker pull --platform linux/amd64 "$image_name"
  docker save "$image_name" | gzip -c > "$DOCKER_IMAGES_HOME/$archive_name.tgz"
}

docker rmi $DOCKER_HUB/$DOCKER_REPO_BASE-api:$DOCKER_TAG 
docker buildx build --push --no-cache --platform linux/amd64 -t $DOCKER_HUB/$DOCKER_REPO_BASE-api:$DOCKER_TAG -f ${CURRENT_HOME}/api-server.dockerfile .
save_image "$DOCKER_HUB/$DOCKER_REPO_BASE-api:$DOCKER_TAG" "$DOCKER_REPO_BASE-api"

docker rmi $DOCKER_HUB/$DOCKER_REPO_BASE-master:$DOCKER_TAG 
docker buildx build --push --platform linux/amd64 -t $DOCKER_HUB/$DOCKER_REPO_BASE-master:$DOCKER_TAG -f ${CURRENT_HOME}/master-server.dockerfile .
save_image "$DOCKER_HUB/$DOCKER_REPO_BASE-master:$DOCKER_TAG" "$DOCKER_REPO_BASE-master"

docker rmi $DOCKER_HUB/$DOCKER_REPO_BASE-worker:$DOCKER_TAG 
docker buildx build --push --platform linux/amd64 -t $DOCKER_HUB/$DOCKER_REPO_BASE-worker:$DOCKER_TAG -f ${CURRENT_HOME}/worker-server.dockerfile .
save_image "$DOCKER_HUB/$DOCKER_REPO_BASE-worker:$DOCKER_TAG" "$DOCKER_REPO_BASE-worker"

docker rmi $DOCKER_HUB/$DOCKER_REPO_BASE-alert-server:$DOCKER_TAG 
docker buildx build --push --platform linux/amd64 -t $DOCKER_HUB/$DOCKER_REPO_BASE-alert-server:$DOCKER_TAG -f ${CURRENT_HOME}/alert-server.dockerfile .
save_image "$DOCKER_HUB/$DOCKER_REPO_BASE-alert-server:$DOCKER_TAG" "$DOCKER_REPO_BASE-alert-server"

docker rmi $DOCKER_HUB/$DOCKER_REPO_BASE-tools:$DOCKER_TAG 
docker buildx build --push --platform linux/amd64 -t $DOCKER_HUB/$DOCKER_REPO_BASE-tools:$DOCKER_TAG -f ${CURRENT_HOME}/tools.dockerfile .
save_image "$DOCKER_HUB/$DOCKER_REPO_BASE-tools:$DOCKER_TAG" "$DOCKER_REPO_BASE-tools"
