package io.github.majianzheng.jarboot.config;

import io.github.majianzheng.jarboot.common.JarbootThreadFactory;
import io.github.majianzheng.jarboot.common.notify.DefaultPublisher;
import io.github.majianzheng.jarboot.common.notify.NotifyReactor;
import io.github.majianzheng.jarboot.ws.MessageSenderSubscriber;
import io.github.majianzheng.jarboot.ws.SendCommandSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import javax.annotation.PostConstruct;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * jarboot配置类
 * @author majianzheng
 */
@Configuration
public class JarBootConfig {
    private static final int MAX_BUFFER_SIZE = 4096;

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(MAX_BUFFER_SIZE);
        container.setMaxTextMessageBufferSize(MAX_BUFFER_SIZE);
        return container;
    }

    @Bean
    public ExecutorService taskExecutorService() {
        return new ThreadPoolExecutor(
                4, 128, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8),
                JarbootThreadFactory.createThreadFactory("task.s-", true));
    }

    @PostConstruct
    public void init() {
        //注册消息发送订阅
        NotifyReactor
                .getInstance()
                .registerSubscriber(
                        new MessageSenderSubscriber(),
                        new DefaultPublisher(32768, "fe.sender.publisher"));
        NotifyReactor
                .getInstance()
                .registerSubscriber(
                        new SendCommandSubscriber(),
                        new DefaultPublisher(16384, "send.command.publisher"));
    }
}
