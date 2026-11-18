package com.lashouwan.protolens.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lashouwan.protolens.config.AnalyzerSettingsStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** 只读访问已配置的竞品源码目录；拒绝目录穿越和链接逃逸，不执行源码和任意命令。 */
public final class ClientSourceReader {
    private static final Set<String> EXTENSIONS = Set.of("js", "ts", "json", "proto");
    private static final Set<String> EXCLUDED = Set.of("node_modules", ".git", ".svn", ".gradle");
    private final AnalyzerSettingsStore settings;
    private final ObjectMapper mapper;

    public ClientSourceReader(AnalyzerSettingsStore settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    /** 目录配置是唯一读取边界；直接指定协议文件时，搜索范围限制为文件所在目录。 */
    private Path root() throws IOException {
        String value = settings.snapshot().path("competitorPath").asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("尚未配置竞品路径，请在工作台设置中加载协议来源");
        }
        Path configured = Path.of(value).toRealPath();
        return Files.isDirectory(configured) ? configured : configured.getParent();
    }

    /** 有界逐行读取，不因一行打包脚本特别长而一次返回整个文件。 */
    public ObjectNode read(String relativePath, int from, int count) throws IOException {
        Path root = root();
        Path file = root.resolve(relativePath).normalize().toRealPath();
        if (!file.startsWith(root) || !Files.isRegularFile(file) || !allowed(file)) {
            throw new IllegalArgumentException("只允许读取竞品目录内的 JS、TS、JSON、proto 普通文件");
        }
        if (Files.size(file) > McpLimits.MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("源码文件超过 8 MiB，请缩小来源");
        }
        ObjectNode result =
                mapper.createObjectNode()
                        .put("root", root.toString())
                        .put("path", root.relativize(file).toString().replace('\\', '/'))
                        .put("fromLine", from)
                        .put("modifiedAt", Files.getLastModifiedTime(file).toString());
        ArrayNode lines = result.putArray("lines");
        int number = 0;
        boolean more = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                number++;
                if (number < from) {
                    continue;
                }
                if (lines.size() == count) {
                    more = true;
                    break;
                }
                lines.addObject()
                        .put("line", number)
                        .put("text", clip(line))
                        .put("lineTruncated", line.length() > McpLimits.MAX_LINE_CHARS);
            }
        }
        result.put("hasMore", more).put("nextLine", from + lines.size());
        return result;
    }

    /** 字面量搜索避免正则灾难回溯；遍历不跟随链接，达到时间/数量上限明确标记未扫描完整。 */
    public ObjectNode search(String query, String pathContains, int offset, int limit)
            throws IOException {
        if (query.isBlank()) {
            throw new IllegalArgumentException("搜索内容不能为空");
        }
        Path root = root();
        String keyword = query.toLowerCase(Locale.ROOT);
        String filename = pathContains.toLowerCase(Locale.ROOT);
        ObjectNode result = mapper.createObjectNode().put("root", root.toString());
        ArrayNode matches = result.putArray("matches");
        int files = 0;
        int matched = 0;
        int skipped = 0;
        boolean truncated = false;
        long deadline = System.nanoTime() + McpLimits.SCAN_MILLIS * 1_000_000;
        try (Stream<Path> stream = Files.walk(root, 32)) {
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                if (++files > McpLimits.MAX_SOURCE_FILES || System.nanoTime() > deadline) {
                    truncated = true;
                    break;
                }
                Path file = iterator.next();
                Path relative = root.relativize(file);
                boolean excluded = false;
                for (Path part : relative) {
                    if (EXCLUDED.contains(part.toString())) {
                        excluded = true;
                        break;
                    }
                }
                if (excluded
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || !allowed(file)
                        || !relative.toString().toLowerCase(Locale.ROOT).contains(filename)) {
                    continue;
                }
                if (!file.toRealPath().startsWith(root)
                        || Files.size(file) > McpLimits.MAX_SOURCE_BYTES) {
                    skipped++;
                    continue;
                }
                try (BufferedReader reader =
                        Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    int number = 0;
                    while ((line = reader.readLine()) != null) {
                        number++;
                        if (System.nanoTime() > deadline) {
                            truncated = true;
                            break;
                        }
                        int position = line.toLowerCase(Locale.ROOT).indexOf(keyword);
                        if (position < 0) {
                            continue;
                        }
                        matched++;
                        if (matched <= offset) {
                            continue;
                        }
                        if (matches.size() == limit) {
                            truncated = true;
                            break;
                        }
                        int start = Math.max(0, position - 160);
                        matches.addObject()
                                .put("path", relative.toString().replace('\\', '/'))
                                .put("line", number)
                                .put("text", clip(line.substring(start)))
                                .put("column", position + 1)
                                .put(
                                        "lineTruncated",
                                        start > 0 || line.length() > McpLimits.MAX_LINE_CHARS);
                    }
                } catch (IOException failure) {
                    skipped++;
                }
                if (truncated) {
                    break;
                }
            }
        }
        return result.put("truncated", truncated)
                .put("scannedEntries", files)
                .put("skippedFiles", skipped)
                .put("offset", offset)
                .put("nextOffset", offset + matches.size())
                .put(
                        "note",
                        "当前磁盘源码可能与抓包版本不同；请核对抓包字典 sourceRoot、sourceFile 和 SHA256。truncated 时请增加"
                                + " pathContains 缩小范围。");
    }

    private boolean allowed(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private String clip(String line) {
        return line.substring(0, Math.min(line.length(), McpLimits.MAX_LINE_CHARS));
    }
}
