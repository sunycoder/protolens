package com.lashouwan.protolens.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.config.AnalyzerLaunchOptions;
import com.lashouwan.protolens.desktop.RuntimeTools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Node AST 工具的本机进程适配；只读取客户端文本，不执行竞品代码，不保存页面配置。 */
public final class ProtocolExtractor {
    private static final int TOOL_TIMEOUT_SECONDS = 60;
    private final AnalyzerLaunchOptions options;
    private final ObjectMapper mapper;

    public ProtocolExtractor(AnalyzerLaunchOptions options, ObjectMapper mapper) {
        this.options = options;
        this.mapper = mapper;
    }

    /** 发现候选不切换字典，扫描和进程等待只允许在启动线程或 HTTP worker 执行。 */
    public ObjectNode discover(String source) throws Exception {
        String input = source.trim();
        if (input.startsWith("\"") && input.endsWith("\"") && input.length() > 1) {
            input = input.substring(1, input.length() - 1);
        }
        if (input.isBlank()) {
            throw new IllegalArgumentException("请填写客户端目录或 thrift_data.js 文件路径");
        }
        if (!Files.exists(Path.of(input))) {
            throw new IllegalArgumentException("协议来源不存在，请在设置中重新选择：" + input);
        }
        Files.createDirectories(options.dataDirectory());
        Path result =
                Files.createTempFile(options.dataDirectory(), "protocol-candidates-", ".json");
        try {
            run("catalog-discover.log", "--discover", input, result.toString());
            return (ObjectNode) mapper.readTree(result.toFile());
        } finally {
            Files.deleteIfExists(result);
        }
    }

    public void extract(Path source, Path root, Path destination) throws Exception {
        run("catalog-extract.log", source.toString(), destination.toString(), root.toString());
    }

    private void run(String logName, String... arguments) throws Exception {
        Path log = options.dataDirectory().resolve("logs").resolve(logName);
        Files.createDirectories(log.getParent());
        List<String> command = new ArrayList<>();
        command.add(RuntimeTools.node(options));
        command.add(options.moduleRoot().resolve("tools/extract-catalog.mjs").toString());
        command.addAll(List.of(arguments));
        Process process =
                new ProcessBuilder(command)
                        .directory(options.moduleRoot().toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        try {
            if (!process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("协议工具执行超时，查看 " + log);
            }
            if (process.exitValue() != 0) {
                throw new IOException("查找或提取协议失败，请检查输入路径；日志：" + log);
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
