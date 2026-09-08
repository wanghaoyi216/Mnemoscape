# syntax=docker/dockerfile:1.7
# ============================================================
#  Mnemoscape backend · 通用微服务镜像 (distroless-style)
#
#  Builder  : maven:3.9-eclipse-temurin-17      (~1.5 GB, 丢弃)
#  Runtime  : eclipse-temurin:17-jre-alpine     (~170 MB, 含 tini+curl+tzdata)
#
#  镜像特征：
#    · 多阶段构建，builder 阶段只产 JAR 文件，不进 runtime 镜像；
#    · non-root（uid/gid=10001，避开 K8s securityContext 冲突区）；
#    · tini 作 PID 1（-g 收孤儿进程，-s 让 JVM 自己 graceful shutdown）；
#    · HEALTHCHECK 探 /actuator/health/liveness，K8s probe 直接复用；
#    · OCI labels + ENV 注入 SERVER_PORT/HEALTH_PATH，每个模块都可用同一镜像；
#    · JVM Container Awareness（MaxRAMPercentage 与 K8s resources.limits 对齐）。
#
#  与子模块已有的轻量 Dockerfile 区别：那些只是
#    "FROM eclipse-temurin:17-jre-alpine + COPY target/*.jar + ENTRYPOINT"
#  的玩具版本，没有多阶段、没有 HEALTHCHECK、没有 tini、没有非 root。
#  这个是生产级版本，所有 7 个子模块共用。
#
#  Build:
#    docker build \
#      --build-arg SERVICE_MODULE=auth-service \
#      --build-arg SERVER_PORT=8081 \
#      -t ghcr.io/mnemoscape/auth-service:latest .
#
#    docker build \
#      --build-arg SERVICE_MODULE=api-gateway \
#      --build-arg SERVER_PORT=8080 \
#      -t ghcr.io/mnemoscape/api-gateway:latest .
#
#    （artifact 名默认取 SERVICE_MODULE 同名，特殊路径可通过 ARTIFACT_ID 覆盖）
# ============================================================

# ---------- 1. Builder ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
LABEL stage=builder

ENV MAVEN_OPTS="-Xmx2g -Xms512m -XX:+UseG1GC" \
    MAVEN_CLI_ARGS="-B -ntp -T 1C -DskipTests" \
    TZ=Asia/Shanghai

WORKDIR /workspace

# ---- Layer 1: 父 POM + 所有子模块 POM（变动频率极低，最大化层缓存）----
COPY pom.xml ./
COPY common/pom.xml           common/
COPY api-gateway/pom.xml      api-gateway/
COPY auth-service/pom.xml     auth-service/
COPY memory-service/pom.xml   memory-service/
COPY ai-service/pom.xml       ai-service/
COPY resonance-service/pom.xml resonance-service/
COPY asset-service/pom.xml    asset-service/

# 预下载依赖；构建机网络抖动时 || true 保证不会因为单次失败重头再来
RUN mvn -B -ntp -q -T 1C -DskipTests \
        -pl "${SERVICE_MODULE}" -am \
        -DincludeScope=test \
        -Dmaven.javadoc.skip=true \
        dependency:go-offline || true

# ---- Layer 2: 全部源码（任一模块改动都会使本层失效，但上面依赖层仍命中）----
COPY common/           common/
COPY api-gateway/      api-gateway/
COPY auth-service/     auth-service/
COPY memory-service/   memory-service/
COPY ai-service/       ai-service/
COPY resonance-service/ resonance-service/
COPY asset-service/    asset-service/

# 编译目标模块
ARG SERVICE_MODULE
ARG ARTIFACT_ID
RUN SERVICE_MODULE="${SERVICE_MODULE}" \
    ARTIFACT_ID="${ARTIFACT_ID:-${SERVICE_MODULE##*/}}" \
    && echo "==> Building module: ${SERVICE_MODULE} (artifact=${ARTIFACT_ID})" \
    && mvn ${MAVEN_CLI_ARGS} \
        -pl "${SERVICE_MODULE}" -am \
        clean package \
    && cp "${SERVICE_MODULE}/target/${ARTIFACT_ID}-*.jar" /tmp/app.jar \
    && ls -lh /tmp/app.jar

# ---------- 2. Runtime ----------
FROM eclipse-temurin:17-jre-alpine AS runtime

# OCI 标准元数据 —— 镜像仓库、CI 扫描、依赖审计都会读这些
LABEL org.opencontainers.image.title="Mnemoscape microservice" \
      org.opencontainers.image.description="Distroless-style Spring Boot 3.x service for Mnemoscape AI memory platform" \
      org.opencontainers.image.source="https://github.com/x/Mnemoscape" \
      org.opencontainers.image.licenses="Proprietary" \
      org.opencontainers.image.vendor="Mnemoscape Team" \
      maintainer="Mnemoscape <dev@mnemoscape.cn>"

# Alpine 一次性装齐：tini(PID1) + curl(探针) + tzdata(CST) + bash(entrypoint 兜底)
RUN apk add --no-cache bash tini tzdata curl \
    && ln -snf /usr/share/zoneinfo/${TZ} /etc/localtime \
    && echo "${TZ}" > /etc/timezone

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    # MaxRAMPercentage=75 与 K8s resources.limits.memory 配合；JVM 自己按 cgroup 上限算堆
    JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+UseStringDeduplication -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    # 默认 8080；每个模块 build 时通过 --build-arg SERVER_PORT=8081 等覆盖
    SERVER_PORT=8080 \
    # 探针路径：默认 Spring Boot Actuator liveness（K8s liveness probe 复用同一个）
    HEALTH_PATH=/actuator/health/liveness \
    SPRING_PROFILES_ACTIVE=prod

# non-root：UID/GID 10001 避开 K8s 安全上下文冲突区间（0~1000 通常被 runAsNonRoot 拒绝）
RUN addgroup -S -g 10001 app \
    && adduser  -S -u 10001 -G app -h /app -s /sbin/nologin app \
    && mkdir -p /app/logs /tmp \
    && chown -R app:app /app /tmp

WORKDIR /app
USER app

# 拷贝 fat-jar（--chown 让非 root 也能直接读取）
COPY --from=builder --chown=app:app /tmp/app.jar /app/app.jar

# 自包含健康探针脚本：避免在 CMD 里写长长一坨 curl
#   - 用 curl 探 HEALTH_PATH（默认 liveness）
#   - 任意非 2xx 即 fail
#   - curl 不在时退到 bash /dev/tcp（极小兜底）
COPY --chown=app:app <<'EOF' /healthcheck.sh
#!/bin/sh
# healthcheck.sh — Spring Boot service liveness probe
set -eu
PORT="${SERVER_PORT:-8080}"
PATH_URL="${HEALTH_PATH:-/actuator/health/liveness}"
URL="http://127.0.0.1:${PORT}${PATH_URL}"
TIMEOUT="${HEALTH_TIMEOUT:-4}"

if command -v curl >/dev/null 2>&1; then
  code=$(curl -fsS -o /dev/null -w '%{http_code}' --max-time "$TIMEOUT" "$URL" 2>/dev/null || echo 000)
  [ "$code" = "200" ] || exit 1
else
  exec 3<>/dev/tcp/127.0.0.1/"$PORT" || exit 1
  printf 'GET %s HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n' "$PATH_URL" >&3
  head -n1 <&3 | grep -q '200' || exit 1
fi
exit 0
EOF
RUN chmod +x /healthcheck.sh

EXPOSE 8080

# K8s probe：start-period 留 60s 给 Spring Boot 启动 + Hikari 预热 + Nacos 注册
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD /healthcheck.sh || exit 1

# tini 全面接管 PID 1：
#   -g : 子进程组（孤儿）也归 tini 收割
#   -s : 收到 SIGTERM 时直接转发给 JVM，不再二次 kill，依赖 server.shutdown=graceful
#   -v : 启动时打印 tini 版本，便于排障
ENTRYPOINT ["/sbin/tini","-g","-s","-v","--"]
CMD ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
