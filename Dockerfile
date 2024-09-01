# 基础镜像
FROM openjdk:8-jdk-alpine
  
# 指定工作目录
WORKDIR /app
  
# 将 jar 包添加到工作目录，比如 target/coj-backend-user-service-0.0.1-SNAPSHOT.jar
ADD target/coj-code-sandbox-0.0.1-SNAPSHOT.jar .

# 暴露端口
EXPOSE 8090
  
# 启动命令
ENTRYPOINT ["java", "-Xms200M", "-Xmx200M", "-jar", "/app/coj-code-sandbox-0.0.1-SNAPSHOT.jar"]