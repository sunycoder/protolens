package com.lashouwan.protolens.protocol;

import com.lashouwan.protolens.constant.AnalyzerLimits;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** 每条连接、每个方向分别持有 WebSocket 分片与 deflate 上下文，不能跨方向共用字典。 */
public final class WebSocketStreamDecoder implements AutoCloseable {
    private final Inflater inflater = new Inflater(true);
    private final boolean noContextTakeover;
    private final ByteArrayOutputStream fragments = new ByteArrayOutputStream();

    /** 当前未结束消息的 opcode，0 表示没有分片消息。 */
    private int messageOpcode;

    private boolean compressed;

    /** 压缩流损坏后拒绝继续复用，直到下一次独立消息能够重置上下文。 */
    private boolean compressionBroken;

    public WebSocketStreamDecoder(boolean noContextTakeover) {
        this.noContextTakeover = noContextTakeover;
    }

    /** 消费一个保存于 SAZ 的完整 wire 帧；控制帧和未完成分片返回 null。 */
    public byte[] accept(byte[] frame) throws DataFormatException {
        if (frame.length < 2) {
            throw new IllegalArgumentException("WebSocket 帧头截断");
        }
        int first = frame[0] & 255;
        int second = frame[1] & 255;
        boolean fin = (first & 128) != 0;
        int opcode = first & 15;
        if ((first & 48) != 0) {
            throw new IllegalArgumentException("WebSocket RSV2/RSV3 非零");
        }
        int cursor = 2;
        long length = second & 127;
        if (length == 126) {
            if (frame.length < 4) {
                throw new IllegalArgumentException("扩展帧长截断");
            }
            length = ((frame[2] & 255) << 8) | (frame[3] & 255);
            cursor = 4;
        } else if (length == 127) {
            if (frame.length < 10 || frame[2] < 0) {
                throw new IllegalArgumentException("扩展帧长无效");
            }
            length = 0;
            for (int index = 2; index < 10; index++) {
                length = (length << 8) | (frame[index] & 255);
            }
            cursor = 10;
        }
        if (length > AnalyzerLimits.MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("WebSocket 帧超限");
        }
        boolean masked = (second & 128) != 0;
        int maskOffset = cursor;
        if (masked) {
            cursor += 4;
        }
        if (length != frame.length - cursor) {
            throw new IllegalArgumentException("WebSocket 帧长度不匹配");
        }
        byte[] payload = Arrays.copyOfRange(frame, cursor, frame.length);
        if (masked) {
            for (int index = 0; index < payload.length; index++) {
                payload[index] ^= frame[maskOffset + (index & 3)];
            }
        }
        if (opcode >= 8) {
            if (!fin || length > 125 || (first & 64) != 0 || opcode > 10) {
                throw new IllegalArgumentException("控制帧格式无效");
            }
            return null;
        }
        if (opcode == 1 || opcode == 2) {
            if (messageOpcode != 0) {
                throw new IllegalArgumentException("上一条分片消息尚未结束");
            }
            messageOpcode = opcode;
            compressed = (first & 64) != 0;
        } else if (opcode != 0 || messageOpcode == 0 || (first & 64) != 0) {
            throw new IllegalArgumentException("孤立或非法 WebSocket continuation");
        }
        if (fragments.size() + payload.length > AnalyzerLimits.MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("分片消息累计超限");
        }
        fragments.writeBytes(payload);
        if (!fin) {
            return null;
        }
        byte[] message = fragments.toByteArray();
        fragments.reset();
        int completedOpcode = messageOpcode;
        messageOpcode = 0;
        if (compressed) {
            message = inflate(message);
        }
        if (completedOpcode != 2) {
            throw new IllegalArgumentException("当前业务适配器仅解码二进制消息");
        }
        return message;
    }

    private byte[] inflate(byte[] input) throws DataFormatException {
        if (noContextTakeover) {
            inflater.reset();
            compressionBroken = false;
        }
        if (compressionBroken) {
            throw new DataFormatException("之前的压缩上下文已损坏，请从连接建立时重新导入");
        }
        byte[] terminated = Arrays.copyOf(input, input.length + 4);
        terminated[terminated.length - 2] = (byte) 255;
        terminated[terminated.length - 1] = (byte) 255;
        inflater.setInput(terminated);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try {
            while (!inflater.needsInput()) {
                int read = inflater.inflate(chunk);
                if (output.size() + read > AnalyzerLimits.MAX_MESSAGE_BYTES) {
                    throw new DataFormatException("解压消息超过 16 MiB");
                }
                output.write(chunk, 0, read);
                if (read == 0 && !inflater.needsInput()) {
                    throw new DataFormatException("压缩流未继续推进");
                }
            }
            return output.toByteArray();
        } catch (DataFormatException failure) {
            compressionBroken = true;
            throw failure;
        }
    }

    @Override
    public void close() {
        inflater.end();
    }
}
