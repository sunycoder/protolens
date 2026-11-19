package com.lashouwan.protolens.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.IDN;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 采集目标值对象：域名包含其子域名，路径按前缀匹配；代理解密与消息筛选共用一份配置。 */
public final class CaptureTargets {
    private static final List<String> DEFAULT_HOSTS = List.of("bjxuejing.cn", "tuyoo.com");
    private static final List<String> DEFAULT_PATHS = List.of("/websocket");

    private static final Pattern IPV4 = Pattern.compile("[0-9]+(?:\\.[0-9]+){3}");

    /** 规范化为小写 ASCII 的域名或 IPv4 地址；不包含协议、端口和通配符。 */
    private final List<String> hosts;

    /** 区分大小写的 WebSocket 路径前缀；不包含查询参数。 */
    private final List<String> pathPrefixes;

    private CaptureTargets(List<String> hosts, List<String> pathPrefixes) {
        this.hosts = List.copyOf(hosts);
        this.pathPrefixes = List.copyOf(pathPrefixes);
    }

    /** 缺省字段兼容已有配置；显式空数组拒绝，避免意外扩大为捕获所有流量。 */
    public static CaptureTargets from(JsonNode input) {
        List<String> hosts = strings(input, "captureHosts", DEFAULT_HOSTS);
        List<String> normalized = new ArrayList<>();
        for (String host : hosts) {
            String ascii;
            try {
                ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("采集域名格式无效：" + host);
            }
            if (ascii.length() > 253
                    || ascii.endsWith(".")
                    || ascii.contains(":")
                    || ascii.contains("/")
                    || ascii.contains("*")
                    || ascii.isBlank()) {
                throw new IllegalArgumentException("采集目标仅填写域名或 IPv4，不含协议、端口、路径和通配符");
            }
            if (IPV4.matcher(ascii).matches()) {
                for (String octet : ascii.split("\\.")) {
                    if (octet.length() > 3 || Integer.parseInt(octet) > 255) {
                        throw new IllegalArgumentException("IPv4 地址格式无效：" + host);
                    }
                }
            }
            normalized.add(ascii);
        }
        List<String> paths = strings(input, "capturePathPrefixes", DEFAULT_PATHS);
        for (String path : paths) {
            if (!path.startsWith("/")
                    || path.length() > 512
                    || path.contains("?")
                    || path.contains("#")
                    || path.chars().anyMatch(Character::isWhitespace)
                    || path.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("采集路径须以 / 开头，不含空白、查询参数或锚点");
            }
        }
        return new CaptureTargets(new ArrayList<>(new LinkedHashSet<>(normalized)), paths);
    }

    private static List<String> strings(JsonNode input, String key, List<String> defaults) {
        if (!input.has(key)) {
            return defaults;
        }
        JsonNode values = input.get(key);
        if (!values.isArray() || values.isEmpty() || values.size() > 32) {
            throw new IllegalArgumentException(key + " 须为包含 1～32 项的字符串数组");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual()
                    || value.asText().isBlank()
                    || value.asText().chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(key + " 不能包含空值、非字符串或控制字符");
            }
            result.add(value.asText().trim());
        }
        return List.copyOf(result);
    }

    /** mitmproxy 对 host:port 做搜索；使用完整域名边界，点号转义兼容 Java 和 Python 正则，不使用 Java 专有的引用语法。 */
    public String allowHostsPattern() {
        List<String> patterns = new ArrayList<>();
        for (String host : hosts) {
            boolean ipv4 = IPV4.matcher(host).matches();
            patterns.add((ipv4 ? "^" : "(?:^|\\.)") + host.replace(".", "\\.") + ":[0-9]+$");
        }
        return "(?i)(?:" + String.join("|", patterns) + ")";
    }

    public List<String> hosts() {
        return hosts;
    }

    public List<String> pathPrefixes() {
        return pathPrefixes;
    }
}
