package com.cwc.codesandbox;

import cn.hutool.core.date.StopWatch;
import cn.hutool.core.util.ArrayUtil;
import com.cwc.codesandbox.resultcallback.MyExecStartResultCallback;
import com.cwc.codesandbox.resultcallback.MyResultCallback;
import com.cwc.model.ExecuteMessage;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class JavaDockerCodeSandbox extends JavaCodeSandboxTemplate {

    private static final long TIME_OUT = 5000;

    public static final Boolean FIRST_INIT = true;

    public static final String VOLUME_PATH = "/app";

    private DockerClient dockerClient;

    @Override
    public List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList) {
        // 当前路径
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        // 设置配置
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient dockerHttpClient = new ApacheDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(3000)
                .build();
        // 创建容器，把文件复制到容器中
        dockerClient = DockerClientBuilder.getInstance(config).withDockerHttpClient(dockerHttpClient).build();

        // 拉取镜像
        String image = "openjdk:8-alpine";
        if (FIRST_INIT) {
            PullImageCmd pullImageCmd = dockerClient.pullImageCmd(image);
            PullImageResultCallback pullImageResultCallback = new PullImageResultCallback() {
                @Override
                public void onNext(PullResponseItem item) {
                    log.info("download images: {}", item.getStatus());
                    super.onNext(item);
                }

                @Override
                public void onComplete() {
                    log.info("download success!");
                    super.onComplete();
                }
            };
            try {
                pullImageCmd.exec(pullImageResultCallback).awaitCompletion();
            } catch (InterruptedException e) {
                log.info("download error: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        }

        // 创建容器
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image);
        HostConfig hostConfig = new HostConfig();
        hostConfig.withMemory(100 * 1024 * 1024L);
        hostConfig.withMemorySwap(0L);
        hostConfig.withCpuCount(1L);
        // hostConfig.withSecurityOpts(Arrays.asList("seccomp=安全你管理配置JSON字符串"));
        hostConfig.setBinds(new Bind(userCodeParentPath, new Volume(VOLUME_PATH)));

        CreateContainerResponse createContainerResponse = containerCmd.withHostConfig(hostConfig)
                .withAttachStdin(true)     // 开启输入输出流
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withNetworkDisabled(true)
                .withTty(true)             // 交互式
                .exec();

        String containerId = createContainerResponse.getId();

        // 启动容器
        dockerClient.startContainerCmd(containerId).exec();

        // 执行命令并获取输出
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String input : inputList) {
            StopWatch stopWatch = new StopWatch();
            // docker exec [container] java -cp /app Main
            String[] cmdArray = ArrayUtil.append(new String[]{"java", "-cp", VOLUME_PATH, "Main"});
            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmdArray)
                    .withAttachStdin(true)
                    .withAttachStderr(true)
                    .withAttachStdout(true)
                    .exec();

            // 返回信息
            ExecuteMessage executeMessage = new ExecuteMessage();
            long time = 0;
            final long[] maxMemory = {0};
            final boolean[] timeOut = {true};

            String execId = execCreateCmdResponse.getId();
            // 获取输出
            ExecStartResultCallback execStartResultCallback = new MyExecStartResultCallback(timeOut, executeMessage);
            // 获取占用内存
            StatsCmd statsCmd = dockerClient.statsCmd(containerId);
            ResultCallback<Statistics> resultCallback = new MyResultCallback(maxMemory);
            statsCmd.exec(resultCallback);

            // 创建输入流
            byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
            InputStream inputStream = new ByteArrayInputStream(inputBytes);

            try {
                stopWatch.start();
                dockerClient.execStartCmd(execId)
                        .withStdIn(inputStream)
                        .exec(execStartResultCallback)
                        .awaitCompletion(TIME_OUT, TimeUnit.MILLISECONDS);
                stopWatch.stop();
                time = Math.max(time, stopWatch.getLastTaskTimeMillis());
                statsCmd.close();
            } catch (InterruptedException e) {
                log.error("run code error, message = {}", e.getMessage());
                throw new RuntimeException(e);
            }
            // 正常结束
            executeMessage.setTime(time);
            executeMessage.setMemory(maxMemory[0]);
            executeMessageList.add(executeMessage);
        }
        // 删除容器
        boolean destroyStatus = destroyContainer(containerId);
        if (!destroyStatus) {
            log.error("delete container error!");
        }
        log.info("run success: {}", executeMessageList);
        return executeMessageList;
    }

    private boolean destroyContainer(String containerId) {
        try {
            // 停止容器
            StopContainerCmd stopContainerCmd = dockerClient.stopContainerCmd(containerId);
            stopContainerCmd.exec();
            // 删除容器
            RemoveContainerCmd removeContainerCmd = dockerClient.removeContainerCmd(containerId);
            removeContainerCmd.exec();
            return true;
        } catch (Exception e) {
            log.error("remove container fail, message = {}", e.getMessage());
            return false;
        }
    }
}
