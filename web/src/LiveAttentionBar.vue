<script setup lang="ts">
import type { ActivityDelta } from "./liveReading";
defineProps<{
  running: boolean;
  following: boolean;
  reason: string;
  delta?: ActivityDelta;
  watched: { token: string; label: string; unread: number }[];
  activityError?: string;
  loading?: boolean;
  restartRequired?: boolean;
  updatedAt?: number;
}>();
const emit = defineEmits<{
  latest: [];
  activity: [kind: "all" | "first" | "attention"];
  watch: [token: string];
  unwatch: [token: string];
  retry: [];
}>();
</script>
<template>
  <section
    class="live-attention"
    aria-label="消息阅读状态与关注"
    title="统计当前筛选下、仍在保留范围内可查看的新增消息；不受通知自身临时区间限制"
  >
    <div class="live-reading-state">
      <span :class="{ 'reading-live': running }">{{
        running ? "后台采集中 · 本记录" : "记录阅读"
      }}</span>
      <span>{{ following ? "列表跟随新消息" : `列表暂停：${reason}` }}</span>
      <template v-if="delta?.total">
        <button class="activity-count" @click="emit('activity', 'all')">
          {{ following ? "最近更新" : "待查看" }} {{ delta.total }} 条 ·
          {{ delta.complete ? "" : "至少 " }}{{ delta.protocolCount }} 种协议
        </button>
        <button
          v-if="delta.firstSeen.length"
          class="activity-count"
          @click="emit('activity', 'first')"
        >
          {{ delta.firstSeen.length }} 种本记录首次出现
        </button>
        <button
          v-if="delta.attention"
          class="activity-count activity-warning"
          @click="emit('activity', 'attention')"
        >
          {{ delta.attention }} 条需关注
        </button>
        <span v-if="!delta.complete" class="muted"
          >统计范围不完整，首次出现未推断</span
        >
      </template>
      <span v-else-if="!activityError" class="muted">{{
        loading
          ? "正在统计当前筛选下的新消息"
          : running
            ? "当前筛选下暂无新增消息"
            : "关注协议后可快速查看"
      }}</span>
      <span
        v-if="updatedAt"
        class="muted"
        :title="`上次统计完成：${new Date(updatedAt).toLocaleString()}`"
        >{{ loading ? "更新中，保留上次统计" : "已确认统计"
        }}{{ activityError ? "（刷新失败）" : "" }}</span
      >
      <button
        v-if="running && !following"
        class="activity-count"
        @click="emit('latest')"
      >
        回到最新
      </button>
      <span v-if="activityError" class="muted" :title="activityError"
        >{{
          restartRequired
            ? "后端缺少活动统计接口，请先保存临时抓包并重启工作台"
            : "活动统计暂不可用，新增数量尚未确认"
        }}
        <button class="activity-count" @click="emit('retry')">
          重试
        </button></span
      >
    </div>
    <div
      v-if="watched.length"
      class="protocol-watch-list"
      aria-label="关注协议"
    >
      <span class="muted">已关注</span>
      <span v-for="item in watched" :key="item.token" class="watched-protocol">
        <button
          :title="`查看 ${item.label}；仅改变列表，详情继续固定`"
          @click="emit('watch', item.token)"
        >
          {{ item.label }} <b v-if="item.unread">+{{ item.unread }}</b>
        </button>
        <button
          :aria-label="`取消关注 ${item.label}`"
          @click="emit('unwatch', item.token)"
        >
          ×
        </button>
      </span>
    </div>
  </section>
</template>
<style scoped>
.live-attention {
  flex-shrink: 0;
  border-bottom: 1px solid var(--border);
  padding: 5px 12px;
  background: var(--surface);
}
.live-reading-state {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  min-height: 28px;
  font-size: 12px;
}
.reading-live {
  color: var(--success);
}
.activity-count,
.watched-protocol button {
  border: 0;
  background: transparent;
  color: var(--primary);
  padding: 2px 0;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.activity-warning {
  color: var(--warning);
}
.protocol-watch-list {
  display: flex;
  align-items: center;
  gap: 8px;
  overflow-x: auto;
  padding: 3px 0;
}
.protocol-watch-list > span {
  flex-shrink: 0;
}
.watched-protocol {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 1px 7px;
  border: 1px solid var(--border);
  border-radius: 3px;
}
.watched-protocol button:first-child {
  max-width: 250px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
