package com.lashouwan.protolens.capture;

import com.lashouwan.protolens.constant.AnalyzerLimits;
import com.lashouwan.protolens.enums.MessageOrder;

/** 一次消息查询的不可变条件；只影响展示，不改变采集、持久化或解压上下文。 */
public final class MessageQuery {
    /** 协议名、编号或解码字段的内容搜索。 */
    private final String query;

    /** 发送/接收方向，空值表示不限。 */
    private final String direction;

    /** 解码状态筛选，空值表示不限。 */
    private final String status;

    /** 连接 ID 筛选，空值表示不限。 */
    private final String connection;

    /** 是否仅在展示中隐藏心跳，不影响采集。 */
    private final boolean hideHeartbeat;

    /** 稳定消息编号下界，包含本值。 */
    private final int from;

    /** 稳定消息编号上界，包含本值。 */
    private final int to;

    /** -1 表示按排序方向选取最新一页。 */
    private final int offset;

    /** 单页消息数量，不超过查询资源上限。 */
    private final int limit;

    /** 稳定消息编号排序方向。 */
    private final MessageOrder order;

    /** 协议名称或编号包含条件，多个条件任一命中。 */
    private final String include;

    /** 协议排除条件，优先于包含条件。 */
    private final String exclude;

    public MessageQuery(
            String query,
            String direction,
            String status,
            String connection,
            boolean hideHeartbeat,
            int from,
            int to,
            int offset,
            int limit,
            String order,
            String include,
            String exclude) {
        if (from < 0
                || to < from
                || offset < -1
                || limit < 1
                || limit > AnalyzerLimits.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("消息范围或分页参数无效");
        }

        this.query = query;
        this.direction = direction;
        this.status = status;
        this.connection = connection;
        this.hideHeartbeat = hideHeartbeat;
        this.from = from;
        this.to = to;
        this.offset = offset;
        this.limit = limit;
        this.order = MessageOrder.fromCode(order);
        this.include = include;
        this.exclude = exclude;
    }

    public String query() {
        return query;
    }

    public String direction() {
        return direction;
    }

    public String status() {
        return status;
    }

    public String connection() {
        return connection;
    }

    public String include() {
        return include;
    }

    public String exclude() {
        return exclude;
    }

    public boolean hideHeartbeat() {
        return hideHeartbeat;
    }

    public int from() {
        return from;
    }

    public int to() {
        return to;
    }

    public int offset() {
        return offset;
    }

    public int limit() {
        return limit;
    }

    public MessageOrder order() {
        return order;
    }
}
