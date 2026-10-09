package com.bbmurloc.victoriaeconomics.server;

import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerEconomyRuntime {

    private static final Map<MinecraftServer, ServerEconomyContext> CONTEXTS =
            new ConcurrentHashMap<>();

    private ServerEconomyRuntime() {
    }

    /**
     * 在逻辑服务器启动时创建经济运行上下文。
     */
    public static synchronized void start(MinecraftServer server) {
        Objects.requireNonNull(server, "server");

        if (CONTEXTS.containsKey(server)) {
            throw new IllegalStateException("Economy context already exists for this server");
        }

        ServerEconomyContext context = new ServerEconomyContext(server);

        ServerEconomyContext existing = CONTEXTS.putIfAbsent(server, context);

        if (existing != null) {
            context.close();
            throw new IllegalStateException(
                    "Economy context already exists for this server"
            );
        }
    }

    /**
     * 获取当前服务器对应的经济运行上下文。
     */
    public static ServerEconomyContext get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");

        ServerEconomyContext context = CONTEXTS.get(server);

        if (context == null) {
            throw new IllegalStateException(
                    "Economy context has not been started for this server"
            );
        }

        return context;
    }

    /**
     * 在服务器停止时删除对应的经济运行上下文。
     */
    public static void stop(MinecraftServer server) {
        Objects.requireNonNull(server, "server");

        ServerEconomyContext context = CONTEXTS.remove(server);

        if (context != null) {
            context.close();
        }
    }

    /**
     * 用于检查某个服务器的经济系统是否已经初始化。
     */
    public static boolean isStarted(MinecraftServer server) {
        return CONTEXTS.containsKey(server);
    }
}
