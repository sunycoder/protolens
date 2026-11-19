package com.lashouwan.protolens.enums;

/** MCP 使用的 JSON-RPC 标准错误码；HTTP 状态与 RPC 错误码分别表达传输和调用失败。 */
public enum JsonRpcError {
    PARSE_ERROR(-32700),
    INVALID_REQUEST(-32600),
    METHOD_NOT_FOUND(-32601),
    INVALID_PARAMS(-32602),
    INTERNAL_ERROR(-32603);

    private final int code;

    JsonRpcError(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
