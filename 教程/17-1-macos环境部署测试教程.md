# 0、打包

```

# 运行

git ls-files -z | rsync -avz --from0 --files-from=- ./ dp:/home/darcy/Downloads/ala-ds

ssh dp

cd ~/Downloads/ala-ds

./mvnw clean package -Pstaging -DskipUT=true

cd gyyun-dist

# 打包
sh src/main/docker/docker-push.sh r.gyykj.com/gyy-base 3.4.3-SNAPSHOT ala-ds

# 下载
ssh macos
scp -r dp:/home/darcy/Downloads/ala-ds/gyyun-dist/docker-images ./

cd docker-images
scp -r dp:/home/darcy/Downloads/ala-ds/gyyun-dist/target/gyyun-3.4.3-SNAPSHOT-bin.tar.gz ./

tar -zxvf gyyun-3.4.3-SNAPSHOT-bin.tar.gz

rm -rf plugins
mv gyyun-3.4.3-SNAPSHOT-bin/plugins ./

ll plugins/alert-plugins

rm -rf gyyun-3.4.3-SNAPSHOT-bin

```

# 1、清数据

```

# 运行
docker rm -f gyyun-api gyyun-master gyyun-worker gyyun-alert
mkdir -p /Users/darcy/ala-ds
cd /Users/darcy/ala-ds
rm -rf ./*
ll

```

# 2、初始化目录

```

# 运行

export HUB='r.gyykj.com/gyy-base'
export TAG='3.4.3-SNAPSHOT'
export GYY_HOME=/gyy_program
export GYY_HOST_LOGS="/Users/darcy/ala-ds/logs"
export GYY_HOST_SOFT="/Users/darcy/ala-ds/soft"
export GYY_HOST_RESOURCE="/Users/darcy/ala-ds/resource"
export GYY_HOST_PLUGINS="/Users/darcy/ala-ds/plugins"
export GYY_HOST_WORKER_DATA="/Users/darcy/ala-ds/worker-data"
export GYY_DATABASE=mysql
export GYY_MYSQL_HOST=10.211.55.2
export GYY_MYSQL_PORT=3317
export GYY_MYSQL_DATABASE=ala-data-ds
export GYY_MYSQL_USER=gyy
export GYY_MYSQL_PASSWORD=32WO3ad2JZSiCGsl
export GYY_MYSQL_URL="jdbc:mysql://${GYY_MYSQL_HOST}:${GYY_MYSQL_PORT}/${GYY_MYSQL_DATABASE}?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
export GYY_ZK_CONNECT=10.211.55.2:4206

# 注册到 ZK、供 api/master/worker/alert 内部 RPC 调用的宿主机业务 IP。
# 下方命令采用 1:1 端口映射，因此只需要指定 IP。
# 如果宿主机端口不是 1:1 映射，请改用 MASTER_MASTER_ADDRESS、WORKER_WORKER_ADDRESS、ALERT_ALERT_SERVER_ADDRESS 指定完整的 ip:外部端口。
export GYY_ADVERTISED_HOST=10.211.55.2


mkdir -p \
  "${GYY_HOST_LOGS}" \
  "${GYY_HOST_SOFT}" \
  "${GYY_HOST_RESOURCE}" \
  "${GYY_HOST_PLUGINS}" \
  "${GYY_HOST_WORKER_DATA}"
  

# 复制plugins

cp -r /Users/darcy/docker-images/plugins /Users/darcy/ala-ds/
ll /Users/darcy/ala-ds/plugins
ll /Users/darcy/ala-ds/plugins/alert-plugins

```

# 3、部署MySQL、ZK

# 4、初始化数据库

```

# 复用开发环境库

```

# 5、部署gyyun-alert

```

# 运行
docker rm -f gyyun-alert 2>/dev/null || true

docker run -d \
  --name gyyun-alert \
  --restart unless-stopped \
  -p 50052:50052 \
  -p 50053:50053 \
  -e TZ=Asia/Shanghai \
  -e DATABASE=mysql \
  -e SPRING_PROFILES_ACTIVE=mysql \
  -e SPRING_DATASOURCE_URL="${GYY_MYSQL_URL}" \
  -e SPRING_DATASOURCE_USERNAME="${GYY_MYSQL_USER}" \
  -e SPRING_DATASOURCE_PASSWORD="${GYY_MYSQL_PASSWORD}" \
  -e SPRING_JACKSON_TIME_ZONE=UTC \
  -e MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true \
  -e REGISTRY_ZOOKEEPER_CONNECT_STRING="${GYY_ZK_CONNECT}" \
  -e DOLPHINSCHEDULER_HOME="${GYY_HOME}" \
  -e SUDO_ENABLE=false \
  -e ALERT_ADVERTISED_HOST="${GYY_ADVERTISED_HOST}" \
  -v "${GYY_HOST_PLUGINS}:${GYY_HOME}/plugins" \
  -v "${GYY_HOST_LOGS}:${GYY_HOME}/logs" \
  --health-cmd='curl -fsS http://127.0.0.1:50053/actuator/health/liveness >/dev/null || exit 1' \
  --health-start-period=60s \
  --health-interval=30s \
  --health-timeout=5s \
  --health-retries=3 \
  "${HUB}/ala-ds-alert-server:${TAG}"

# 查看日志
docker logs -f gyyun-alert

```

# 6、部署gyyun-worker

```

# 运行
docker rm -f gyyun-worker 2>/dev/null || true

docker run -d \
  --name gyyun-worker \
  --restart unless-stopped \
  -p 1234:1234 \
  -p 1235:1235 \
  -e TZ=Asia/Shanghai \
  -e DATABASE=mysql \
  -e SPRING_JACKSON_TIME_ZONE=UTC \
  -e MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true \
  -e REGISTRY_ZOOKEEPER_CONNECT_STRING="${GYY_ZK_CONNECT}" \
  -e DOLPHINSCHEDULER_HOME="${GYY_HOME}" \
  -e SUDO_ENABLE=false \
  -e DATA_BASEDIR_PATH=/tmp/gyyun \
  -e RESOURCE_STORAGE_TYPE=LOCAL \
  -e RESOURCE_STORAGE_UPLOAD_BASE_PATH=/gyyun \
  -e WORKER_TENANT_CONFIG_AUTO_CREATE_TENANT_ENABLED=true \
  -e WORKER_TENANT_CONFIG_DEFAULT_TENANT_ENABLED=true \
  -e WORKER_ADVERTISED_HOST="${GYY_ADVERTISED_HOST}" \
  -v "${GYY_HOST_PLUGINS}:${GYY_HOME}/plugins" \
  -v "${GYY_HOST_WORKER_DATA}:/tmp/gyyun" \
  -v "${GYY_HOST_LOGS}:${GYY_HOME}/logs" \
  -v "${GYY_HOST_SOFT}:/opt/soft" \
  -v "${GYY_HOST_RESOURCE}:/gyyun" \
  --health-cmd='curl -fsS http://127.0.0.1:1235/actuator/health/liveness >/dev/null || exit 1' \
  --health-start-period=60s \
  --health-interval=30s \
  --health-timeout=5s \
  --health-retries=3 \
  "${HUB}/ala-ds-worker:${TAG}"

# 查看日志
docker logs -f gyyun-worker

```

# 7、部署gyyun-master

```

# 运行
docker rm -f gyyun-master 2>/dev/null || true

docker run -d \
  --name gyyun-master \
  --restart unless-stopped \
  -p 5678:5678 \
  -p 5679:5679 \
  -e TZ=Asia/Shanghai \
  -e DATABASE=mysql \
  -e SPRING_PROFILES_ACTIVE=mysql \
  -e SPRING_DATASOURCE_URL="${GYY_MYSQL_URL}" \
  -e SPRING_DATASOURCE_USERNAME="${GYY_MYSQL_USER}" \
  -e SPRING_DATASOURCE_PASSWORD="${GYY_MYSQL_PASSWORD}" \
  -e SPRING_JACKSON_TIME_ZONE=UTC \
  -e MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true \
  -e REGISTRY_ZOOKEEPER_CONNECT_STRING="${GYY_ZK_CONNECT}" \
  -e DOLPHINSCHEDULER_HOME="${GYY_HOME}" \
  -e SUDO_ENABLE=false \
  -e RESOURCE_STORAGE_TYPE=LOCAL \
  -e RESOURCE_STORAGE_UPLOAD_BASE_PATH=/gyyun \
  -e MASTER_ADVERTISED_HOST="${GYY_ADVERTISED_HOST}" \
  -v "${GYY_HOST_PLUGINS}:${GYY_HOME}/plugins" \
  -v "${GYY_HOST_LOGS}:${GYY_HOME}/logs" \
  -v "${GYY_HOST_SOFT}:/opt/soft" \
  -v "${GYY_HOST_RESOURCE}:/gyyun" \
  --health-cmd='curl -fsS http://127.0.0.1:5679/actuator/health/liveness >/dev/null || exit 1' \
  --health-start-period=60s \
  --health-interval=30s \
  --health-timeout=5s \
  --health-retries=3 \
  "${HUB}/ala-ds-master:${TAG}"

# 查看日志
docker logs -f gyyun-master

```

# 8、部署gyyun-api

```

# 运行
docker rm -f gyyun-api 2>/dev/null || true

docker run -d \
  --name gyyun-api \
  --restart unless-stopped \
  -p 12345:12345 \
  -p 25333:25333 \
  -e TZ=Asia/Shanghai \
  -e DATABASE=mysql \
  -e SPRING_PROFILES_ACTIVE=mysql \
  -e SPRING_DATASOURCE_URL="${GYY_MYSQL_URL}" \
  -e SPRING_DATASOURCE_USERNAME="${GYY_MYSQL_USER}" \
  -e SPRING_DATASOURCE_PASSWORD="${GYY_MYSQL_PASSWORD}" \
  -e SPRING_JACKSON_TIME_ZONE=UTC \
  -e MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true \
  -e REGISTRY_ZOOKEEPER_CONNECT_STRING="${GYY_ZK_CONNECT}" \
  -e DOLPHINSCHEDULER_HOME="${GYY_HOME}" \
  -e SUDO_ENABLE=false \
  -e RESOURCE_STORAGE_TYPE=LOCAL \
  -e RESOURCE_STORAGE_UPLOAD_BASE_PATH=/gyyun \
  -v "${GYY_HOST_PLUGINS}:${GYY_HOME}/plugins" \
  -v "${GYY_HOST_LOGS}:${GYY_HOME}/logs" \
  -v "${GYY_HOST_SOFT}:/opt/soft" \
  -v "${GYY_HOST_RESOURCE}:/gyyun" \
  --health-cmd='curl -fsS http://127.0.0.1:12345/gyyun/actuator/health/liveness >/dev/null || exit 1' \
  --health-start-period=60s \
  --health-interval=30s \
  --health-timeout=5s \
  --health-retries=3 \
  "${HUB}/ala-ds-api:${TAG}"

# 查看日志
docker logs -f gyyun-api

```

# 9、访问
    http://127.0.0.1:12345/gyyun/ui
    admin - dolphinscheduler123