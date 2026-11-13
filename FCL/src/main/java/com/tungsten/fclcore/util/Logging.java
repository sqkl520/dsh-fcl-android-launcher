/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tungsten.fclcore.util;

import com.tungsten.fclcore.util.io.IOUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.logging.*;

public final class Logging {
    private Logging() {
    }

    public static final Logger LOG = Logger.getLogger("FCL");
    private static final ByteArrayOutputStream storedLogs = new ByteArrayOutputStream(IOUtils.DEFAULT_BUFFER_SIZE);

    private static volatile String[] accessTokens = new String[0];

    public static synchronized void registerAccessToken(String token) {
        final String[] oldAccessTokens = accessTokens;
        final String[] newAccessTokens = Arrays.copyOf(oldAccessTokens, oldAccessTokens.length + 1);

        newAccessTokens[oldAccessTokens.length] = token;

        accessTokens = newAccessTokens;
    }

    public static String filterForbiddenToken(String message) {
        if (message == null) return "";
        for (String token : accessTokens)
            message = message.replace(token, "<access token>");
        return message;
    }

    /**
     * 启动日志：内存流 + 控制台 + （尽力而为的）文件流。
     *
     * ★ R31 / s10-eh-06：本方法原先返回 {@code void}，`FileHandler` 创建失败只
     *   {@code System.err.println} 一句。Android 上 {@code System.err} 仅进 logcat 环形缓冲，
     *   **进程崩溃重启后取不回** —— 而"崩了要取证"正是要文件日志的时刻
     *   （见 FCLPath 里 [M-02] 记录的原始事故）。调用方因此无法判断磁盘日志是否真的可用，
     *   带着"以为有日志"的状态继续启动。
     *
     * 现在把结果**返回**给调用方：null = 磁盘日志可用；非 null = 不可用的原因（已含路径与异常类）。
     * 返回值是唯一的判断依据 —— 失败原因也会打一份到 {@code System.err}（此时 LOG 还一个 handler
     * 都没有，且 {@code setUseParentHandlers(false)} 已关掉父级，写 LOG 等于丢进黑洞），
     * 但**调用方不得依赖 stderr**：那正是本缺陷原来的形态。
     *
     * **不抛异常**：本方法在 `FCLApp.onCreate` / `SplashActivity` 冷启动路径上，
     * 抛 = 闪退循环（SplashActivity 版本更糟：抛 = 停在启动页）。
     * 所以除了 IOException，还要挡住 `logFolder == null`（`Files.isRegularFile(null)` 抛 NPE，
     * 而 {@code catch (IOException)} 接不住）和路径非法时的 `InvalidPathException`/`SecurityException`。
     *
     * @param logFolder 日志目录；null 或不可创建时降级为"仅内存/控制台日志"
     * @return 磁盘日志不可用的原因；可写时为 null
     */
    public static String start(Path logFolder) {
        LOG.setLevel(Level.ALL);
        LOG.setUseParentHandlers(false);
        LOG.setFilter(record -> {
            record.setMessage(format(record));
            return true;
        });

        DefaultFormatter formatter = new DefaultFormatter();
        String fileFailure = null;
        if (logFolder == null) {
            // FCLPath.LOG_DIR 是可空静态字段（loadPaths 失败分支里就是 null）；这里不能让它 NPE
            fileFailure = "log folder is null; file logging disabled";
        } else {
            try {
                if (Files.isRegularFile(logFolder))
                    Files.delete(logFolder);

                Files.createDirectories(logFolder);
                FileHandler fileHandler = new FileHandler(logFolder.resolve("fcl.log").toAbsolutePath().toString());
                fileHandler.setLevel(Level.FINEST);
                fileHandler.setFormatter(formatter);
                fileHandler.setEncoding("UTF-8");
                LOG.addHandler(fileHandler);
            } catch (IOException | RuntimeException e) {
                // RuntimeException 一并接住：InvalidPathException / SecurityException 都不是
                // IOException，原先会直接穿出本方法（冷启动路径上抛 = 闪退）。
                fileFailure = "unable to create " + logFolder.resolve("fcl.log").toAbsolutePath()
                        + " (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")";
                System.err.println("Unable to create fcl.log\n" + StringUtils.getStackTrace(e));
            }
        }

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(formatter);
        consoleHandler.setLevel(Level.FINER);
        LOG.addHandler(consoleHandler);

        StreamHandler streamHandler = new StreamHandler(storedLogs, formatter) {
            @Override
            public synchronized void publish(LogRecord record) {
                super.publish(record);
                flush();
            }
        };
        try {
            streamHandler.setEncoding("UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        streamHandler.setLevel(Level.ALL);
        LOG.addHandler(streamHandler);

        // 返回给调用方的是"磁盘日志可用吗"，不是"start 跑完了吗" —— 内存/控制台 handler
        // 上面已经挂好（磁盘失败不影响它们），但调用方要判断的恰恰是磁盘那一份。
        return fileFailure;
    }

    public static void initForTest() {
        LOG.setLevel(Level.ALL);
        LOG.setUseParentHandlers(false);

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new DefaultFormatter());
        consoleHandler.setLevel(Level.FINER);
        LOG.addHandler(consoleHandler);
    }

    public static byte[] getRawLogs() {
        return storedLogs.toByteArray();
    }

    public static String getLogs() {
        try {
            return storedLogs.toString("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new InternalError(e);
        }
    }

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static String format(LogRecord record) {
        String message = filterForbiddenToken(record.getMessage());

        StringBuilder builder = new StringBuilder(128 + message.length());
        builder.append('[');
        TIME_FORMATTER.formatTo(Instant.ofEpochMilli(record.getMillis()), builder);
        builder.append(']');

        builder.append(" [")
                .append(record.getSourceClassName())
                .append('.')
                .append(record.getSourceMethodName())
                .append('/')
                .append(record.getLevel().getName())
                .append("] ")
                .append(message)
                .append('\n');


        Throwable thrown = record.getThrown();
        if (thrown == null) {
            return builder.toString();
        } else {
            StringWriter writer = new StringWriter(builder.length() + 2048);
            writer.getBuffer().append(builder);
            try (PrintWriter printWriter = new PrintWriter(writer)) {
                thrown.printStackTrace(printWriter);
            }

            return writer.toString();
        }
    }

    private static final class DefaultFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            return record.getMessage();
        }
    }
}
