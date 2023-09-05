package io.github.majianzheng.jarboot.base;

import io.github.majianzheng.jarboot.api.event.JarbootEvent;
import io.github.majianzheng.jarboot.api.event.Subscriber;
import io.github.majianzheng.jarboot.api.pojo.JvmProcess;
import io.github.majianzheng.jarboot.api.pojo.ServiceSetting;
import io.github.majianzheng.jarboot.common.AnsiLog;
import io.github.majianzheng.jarboot.common.notify.DefaultPublisher;
import io.github.majianzheng.jarboot.common.notify.NotifyReactor;
import io.github.majianzheng.jarboot.api.constant.CommonConst;
import io.github.majianzheng.jarboot.common.protocol.CommandConst;
import io.github.majianzheng.jarboot.common.protocol.CommandResponse;
import io.github.majianzheng.jarboot.common.protocol.ResponseType;
import io.github.majianzheng.jarboot.common.utils.StringUtils;
import io.github.majianzheng.jarboot.task.AttachStatus;
import io.github.majianzheng.jarboot.utils.MessageUtils;
import io.github.majianzheng.jarboot.utils.PropertyFileUtils;
import io.github.majianzheng.jarboot.utils.SettingUtils;
import io.github.majianzheng.jarboot.utils.TaskUtils;
import io.github.majianzheng.jarboot.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.websocket.Session;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author majianzheng
 */
@SuppressWarnings({"squid:S2274", "PrimitiveArrayArgumentToVarargsMethod"})
public class AgentManager {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    /** 客户端Map */
    private final ConcurrentHashMap<String, AgentOperator> clientMap = new ConcurrentHashMap<>(16);
    /** 启动中的latch */
    private final ConcurrentHashMap<String, CountDownLatch> startingLatchMap = new ConcurrentHashMap<>(16);
    /** 本地进程的pid列表 */
    private final ConcurrentHashMap<String, String> localServices = new ConcurrentHashMap<>(16);
    /** 远程进程列表 */
    private final ConcurrentHashMap<String, JvmProcess> remoteProcesses = new ConcurrentHashMap<>(16);

    /** 写日志方法 */
    private java.lang.reflect.Method writeBytes = null;
    /** 当前默认的日志Appender */
    private ch.qos.logback.core.OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = null;

    /**
     * 单例获取
     * @return 单例
     */
    public static AgentManager getInstance() {
        return AgentManagerHolder.INSTANCE;
    }

    /**
     * 进程上线
     * @param userDir 用户目录
     * @param serviceName 服务名
     * @param session 会话
     * @param sid sid服务唯一id
     */
    public void online(String userDir, String serviceName, Session session, String sid) {
        //目标进程上线
        AgentOperator client = clientMap.compute(sid, (k,v) -> new AgentOperator(serviceName, sid, session));
        CountDownLatch latch = startingLatchMap.getOrDefault(sid, null);
        if (null == latch) {
            client.setState(ClientState.ONLINE);
            MessageUtils.upgradeStatus(sid, CommonConst.RUNNING);
        } else {
            latch.countDown();
        }
        String pid = TaskUtils.getPid(sid);
        if (pid.isEmpty()) {
            //非受管理的本地进程，通知前端Attach成功
            if (sid.startsWith(CommonConst.REMOTE_SID_PREFIX)) {
                this.remoteJvm(client);
            } else {
                //本地进程默认受信任
                client.setTrusted(true);
                MessageUtils.upgradeStatus(sid, AttachStatus.ATTACHED);
            }
        } else {
            //属于受管理的服务
            localServices.put(pid, sid);
            client.setPid(pid);
            client.setTrusted(true);
        }
        ServiceSetting setting = PropertyFileUtils.getServiceSetting(userDir, serviceName);
        if (Objects.equals(sid, setting.getSid())) {
            client.setTrusted(true);
            client.setSetting(setting);
            ServiceOnlineEvent event = new ServiceOnlineEvent(setting);
            NotifyReactor.getInstance().publishEvent(event);
        }
    }

    /**
     * 进程下线
     * @param sid sid服务唯一id
     */
    public void offline(String sid) {
        final AgentOperator client = clientMap.remove(sid);
        if (null == client) {
            return;
        }
        String pid = client.getPid();
        if (pid.isEmpty()) {
            if (sid.startsWith(CommonConst.REMOTE_SID_PREFIX)) {
                remoteProcesses.remove(sid);
            }
            //非受管理的本地进程，通知前端进程已经离线
            MessageUtils.upgradeStatus(sid, AttachStatus.EXITED);
        } else {
            localServices.remove(pid);
        }
        String msg = String.format("\033[1;96m%s\033[0m 下线！", client.getName());
        MessageUtils.console(sid, msg);
        synchronized (client) {
            //发送了退出执行，唤醒killClient或waitServerStarted线程
            try {
                client.notifyAll();
            } catch (Exception e) {
                logger.warn(e.getMessage(), e);
            } finally {
                //先移除，防止再次点击终止时，会去执行已经关闭的会话
                //此时属于异常退出，发布异常退出事件，通知任务守护服务
                if (null != client.getSetting()) {
                    ServiceOfflineEvent event = new ServiceOfflineEvent(client.getSetting());
                    NotifyReactor.getInstance().publishEvent(event);
                }
                client.setState(ClientState.OFFLINE);
                MessageUtils.upgradeStatus(sid, CommonConst.STOPPED);
            }
        }
    }

    /**
     * 检查服务是否在线
     * @param sid 服务id
     * @return 是否在线
     */
    public boolean isOnline(String sid) {
        final AgentOperator client = clientMap.getOrDefault(sid, null);
        if (null == client) {
            return false;
        }
        if (!client.isOpen()) {
            logger.warn("{} websocket session 已经关闭！", sid);
            clientMap.remove(sid);
            return false;
        }
        synchronized (client) {
            return ClientState.ONLINE.equals(client.getState());
        }
    }

    /**
     * 获取远程服务列表
     * @param list 远程服务列表
     */
    public void remoteProcess(List<JvmProcess> list) {
        if (!remoteProcesses.isEmpty()) {
            list.addAll(remoteProcesses.values());
        }
    }

    /**
     * 优雅退出客户端
     * @param sid 服务唯一id
     * @return 是否成功
     */
    public boolean gracefulExit(String sid) {
        final AgentOperator client = clientMap.getOrDefault(sid, null);
        if (null == client) {
            return false;
        }
        synchronized (client) {
            long startTime = System.currentTimeMillis();
            client.setState(ClientState.EXITING);
            sendInternalCommand(sid, CommandConst.EXIT_CMD, StringUtils.EMPTY);
            //等目标进程发送offline信息时执行notify唤醒当前线程
            try {
                client.wait(SettingUtils.getSystemSetting().getMaxExitTime());
            } catch (InterruptedException e) {
                //ignore
                Thread.currentThread().interrupt();
            }
            long costTime = System.currentTimeMillis() - startTime;
            if (clientMap.containsKey(sid)) {
                logger.warn("未能成功退出！{}, 耗时:{}", sid, costTime);
                //失败
                return false;
            } else {
                client.setState(ClientState.OFFLINE);
                MessageUtils.console(sid, "进程优雅退出成功！");
            }
        }
        return true;
    }

    /**
     * 判断进程pid是否为受Jarboot管理的进程，即由Jarboot所启动的进程
     * @param pid 进程pid
     * @return 是否为受Jarboot管理的进程
     */
    public boolean isLocalService(String pid) {
        return localServices.containsKey(pid);
    }

    /**
     * 等待服务启动完成
     * @param setting 服务配置
     * @param millis 时间
     */
    public void waitServiceStarted(ServiceSetting setting, int millis) {
        String sid = setting.getSid();
        AgentOperator client = clientMap.getOrDefault(sid, null);
        if (null == client) {
            CountDownLatch latch = startingLatchMap.computeIfAbsent(sid, k -> new CountDownLatch(1));
            try {
                if (!latch.await(CommonConst.MAX_AGENT_CONNECT_TIME, TimeUnit.SECONDS)) {
                    logger.error("Wait service connect timeout, sid:{}", sid);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                startingLatchMap.remove(sid);
            }
            client = clientMap.getOrDefault(sid, null);
            if (null == client) {
                String msg = formatErrorMsg(sid, "connect timeout!");
                MessageUtils.console(sid, msg);
                return;
            }
        }

        synchronized (client) {
            if (!ClientState.STARTING.equals(client.getState())) {
                logger.info("Current service({}) is not starting now, wait service started error. statue:{}",
                        client.getName(), client.getState());
                MessageUtils.console(sid,
                        client.getName() + " is not starting, wait started error. status:" + client.getState());
                return;
            }
            try {
                client.wait(millis);
            } catch (InterruptedException e) {
                //ignore
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 添加信任的服务
     * @param host 地址
     */
    public void addTrustedHost(String host) {
        remoteProcesses.forEach((k, v) -> {
            if (java.util.Objects.equals(v.getRemote(), host)) {
                AgentOperator client = clientMap.getOrDefault(k, null);
                if (null != client) {
                    client.setTrusted(true);
                    v.setTrusted(true);
                    MessageUtils.upgradeStatus(k, AttachStatus.TRUSTED);
                }
            }
        });
    }

    /**
     * 来自远程服务器的连接
     * @param client 远程服务sid，格式：prefix + pid + name + uuid
     */
    private void remoteJvm(final AgentOperator client) {
        String sid = client.getSid();
        JvmProcess process = new JvmProcess();
        int index = sid.lastIndexOf(',');
        if (-1 == index) {
            return;
        }
        String str = sid.substring(0, index);
        final int limit = 4;
        String[] s = str.split(CommonConst.COMMA_SPLIT, limit);
        if (s.length != limit) {
            return;
        }
        String pid = s[1];
        String remoteIp = s[2];
        String name = s[3];
        process.setAttached(true);
        process.setPid(pid);
        process.setName(name);
        process.setSid(sid);
        process.setRemote(remoteIp);
        boolean isTrusted = SettingUtils.isTrustedHost(remoteIp);
        client.setTrusted(isTrusted);
        remoteProcesses.put(sid, process);
        MessageUtils.upgradeStatus(sid, AttachStatus.ATTACHED);
    }

    /**
     * 发送命令
     * @param sid 唯一id
     * @param command 命令
     * @param sessionId 会话id
     * @param col 宽
     * @param row 高
     */
    private void sendCommand(String sid, String command, String sessionId, int col, int row) {
        if (StringUtils.isEmpty(sid) || StringUtils.isEmpty(command)) {
            return;
        }
        AgentOperator client = clientMap.getOrDefault(sid, null);
        if (null == client) {
            if (TaskUtils.getPid(sid).isEmpty()) {
                String msg = formatErrorMsg(StringUtils.EMPTY, "未在线，无法执行命令");
                //未在线，进程不存在
                MessageUtils.commandFailed(sid, sessionId, msg);
            } else {
                //如果进程仍然存活，尝试使用attach重新连接
                tryReConnect(sid, sessionId);

                client = clientMap.getOrDefault(sid, null);
                if (null == client) {
                    String msg = formatErrorMsg(sid, "连接断开，重连失败，请稍后重试");
                    MessageUtils.commandFailed(sid, sessionId, msg);
                }
            }
        }
        if (null != client) {
            if (client.isTrusted()) {
                client.sendCommand(command, sessionId, row, col);
            } else {
                String msg = formatErrorMsg(StringUtils.EMPTY, "not trusted!");
                MessageUtils.commandFailed(sid, sessionId, msg);
                MessageUtils.upgradeStatus(sid, AttachStatus.NOT_TRUSTED);
            }
        }
    }

    /**
     * 尝试重新连接
     * @param sid 服务唯一id
     * @param sessionId 会话id
     */
    private void tryReConnect(String sid, String sessionId) {
        CountDownLatch latch = startingLatchMap.computeIfAbsent(sid, k -> new CountDownLatch(1));
        try {
            TaskUtils.attach(sid);
            MessageUtils.console(sid, sessionId, "连接断开，重连中...");
            if (!latch.await(CommonConst.MAX_AGENT_CONNECT_TIME, TimeUnit.SECONDS)) {
                logger.error("Attach and wait service connect timeout，{}", sid);
                MessageUtils.console(sid, sessionId, "Attach重连超时！");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            startingLatchMap.remove(sid);
        }
    }

    /**
     * 发送内部指令
     * @param sid 服务唯一id
     * @param command 命令
     * @param sessionId 会话id
     */
    private void sendInternalCommand(String sid, String command, String sessionId) {
        if (StringUtils.isEmpty(sid) || StringUtils.isEmpty(command)) {
            MessageUtils.commandFailed(sid, sessionId, StringUtils.EMPTY);
            return;
        }
        AgentOperator client = clientMap.getOrDefault(sid, null);
        if (null == client) {
            MessageUtils.commandFailed(sid, sessionId, StringUtils.EMPTY);
            return;
        }
        client.sendInternalCommand(command, sessionId);
    }

    /**
     * Agent客户端的响应内容处理
     * @param event
     */
    private void onResponse(AgentResponseEvent event) {
        CommandResponse resp = event.getResponse();
        ResponseType type = resp.getResponseType();
        switch (type) {
            case HEARTBEAT:
                doHeartbeat(event.getUserDir(), event.getServiceName(), event.getSid(), event.getSession());
                break;
            case STD_PRINT:
                //启动中的控制台消息
                MessageUtils.stdPrint(event.getSid(), resp.getBody());
                break;
            case LOG_APPENDER:
                onAgentLog(event.getSid(), resp.getBody());
                break;
            case NOTIFY:
                this.onNotify(resp, event.getSid());
                break;
            default:
                //ignore
                break;
        }
    }

    private void trustOnce(String sid) {
        AgentOperator client = clientMap.getOrDefault(sid, null);
        if (null != client && !client.isTrusted()) {
            client.setTrusted(true);
            MessageUtils.upgradeStatus(sid, AttachStatus.TRUSTED);
        }
    }

    private boolean checkNotTrusted(String sid) {
        AgentOperator client = clientMap.getOrDefault(sid, null);
        if (null == client) {
            return true;
        }
        if (client.isTrusted()) {
            return false;
        }
        JvmProcess jvm = remoteProcesses.getOrDefault(sid, null);
        if (null == jvm) {
            return true;
        }
        if (Boolean.TRUE.equals(jvm.getTrusted())) {
            client.setTrusted(true);
            return false;
        }
        if (SettingUtils.isTrustedHost(jvm.getRemote())) {
            jvm.setTrusted(true);
            client.setTrusted(true);
            return false;
        }
        return true;
    }

    private void onAgentLog(String sid, String msg) {
        if (checkNotTrusted(sid)) {
            return;
        }
        if (null != writeBytes) {
            try {
                writeBytes.invoke(appender, msg.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                //ignore
            }
        }
    }

    private void doHeartbeat(String userDir, String serviceName, String sid, Session session) {
        if (null == session) {
            return;
        }
        AgentOperator client = clientMap.getOrDefault(sid, null);
        if (null == client || !ClientState.ONLINE.equals(client.getState())) {
            this.online(userDir, serviceName, session, sid);
            this.onServiceStarted(sid);
            MessageUtils.console(sid, "reconnected by heartbeat!");
            AnsiLog.debug("reconnected by heartbeat {}, {}", serviceName, sid);
            MessageUtils.upgradeStatus(sid, CommonConst.RUNNING);
            client = clientMap.getOrDefault(sid, null);
            if (null != client) {
                client.heartbeat();
            }
        } else {
            client.heartbeat();
        }
    }

    /**
     * 进程启动完成事件响应
     * @param sid 服务唯一id
     */
    private void onServiceStarted(final String sid) {
        AgentOperator client = clientMap.getOrDefault(sid, null);
        if (null == client) {
            return;
        }
        synchronized (client) {
            //发送启动成功，唤醒waitServerStarted线程
            try {
                client.notifyAll();
            } catch (Exception e) {
                logger.warn(e.getMessage(), e);
            } finally {
                client.setState(ClientState.ONLINE);
            }
        }
    }

    /**
     * 浏览器刷新或关闭，重置进程会话
     * @param sessionId 会话id
     */
    private void releaseAgentSession(String sessionId) {
        //向所有在线的agent客户端发送会话失效命令
        clientMap.forEach((k, v) -> sendInternalCommand(v.getSid(), CommandConst.CANCEL_CMD, sessionId));
    }

    private void onNotify(CommandResponse resp, String sid) {
        if (checkNotTrusted(sid)) {
            return;
        }
        final String sessionId = resp.getSessionId();
        if (StringUtils.isEmpty(sessionId)) {
            MessageUtils.notify(sid, resp.getSuccess(), resp.getBody());
        } else {
            MessageUtils.notify(sid, sessionId, resp.getSuccess(), resp.getBody());
        }
    }

    private String formatErrorMsg(String serviceName, String msg) {
        return String.format("\033[96m%s\033[0m \033[31m%s\033[0m", serviceName, msg);
    }

    private static class AgentManagerHolder {
        static final AgentManager INSTANCE = new AgentManager();
    }

    private void onFuncReceivedEvent(FuncReceivedEvent event) {
        final String sid = event.getSid();
        switch (event.funcCode()) {
            case CMD_FUNC:
                sendCommand(sid, event.getBody(), event.getSessionId(), event.getCols(), event.getRows());
                break;
            case CANCEL_FUNC:
                sendInternalCommand(sid, CommandConst.CANCEL_CMD, event.getSessionId());
                break;
            case TRUST_ONCE_FUNC:
                trustOnce(sid);
                break;
            case CHECK_TRUSTED_FUNC:
                if (checkNotTrusted(sid)) {
                    MessageUtils.upgradeStatus(sid, AttachStatus.NOT_TRUSTED);
                }
                break;
            case DETACH_FUNC:
                sendInternalCommand(sid, CommandConst.SHUTDOWN, event.getSessionId());
                break;
            case SESSION_CLOSED_FUNC:
                releaseAgentSession(event.getSessionId());
                break;
            default:
                logger.debug("Unknown func, func:{}", event.funcCode());
                break;
        }
    }

    private void initSubscriber() {
        //Agent客户端响应事件
        NotifyReactor.getInstance().registerSubscriber(new Subscriber<AgentResponseEvent>() {
            @Override
            public void onEvent(AgentResponseEvent event) {
                onResponse(event);
            }

            @Override
            public Class<? extends JarbootEvent> subscribeType() {
                return AgentResponseEvent.class;
            }
        }, new DefaultPublisher(32768, "agent.resp.publisher"));

        //服务启动完成事件
        NotifyReactor.getInstance().registerSubscriber(new Subscriber<ServiceStartedEvent>() {
            @Override
            public void onEvent(ServiceStartedEvent event) {
                onServiceStarted(event.getSid());
            }

            @Override
            public Class<? extends JarbootEvent> subscribeType() {
                return ServiceStartedEvent.class;
            }
        });

        //前端调用事件
        NotifyReactor.getInstance().registerSubscriber(new Subscriber<FuncReceivedEvent>() {
            @Override
            public void onEvent(FuncReceivedEvent event) {
                onFuncReceivedEvent(event);
            }

            @Override
            public Class<? extends JarbootEvent> subscribeType() {
                return FuncReceivedEvent.class;
            }
        }, new DefaultPublisher(16384, "func.req.publisher"));
    }

    @SuppressWarnings("java:S3011")
    private AgentManager() {
        //获取日志的appender，当宿主服务发来日志时，使用当前服务的日志系统记录
        //这里解读了logback日志源码，反射获取写日志文件的类方法
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger("ROOT");
        appender = (ch.qos.logback.core.OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent>) root
                .getAppender("FILE");
        try {
            writeBytes = ch.qos.logback.core.OutputStreamAppender.class
                    .getDeclaredMethod("writeBytes", byte[].class);
            writeBytes.setAccessible(true);
        } catch (Exception e) {
            //ignore
        }

        //注册事件订阅
        this.initSubscriber();
    }
}