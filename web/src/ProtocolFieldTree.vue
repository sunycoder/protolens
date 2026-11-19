<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from "vue";
import { api } from "./types";
import {
  definitionUrl,
  fieldPathLabel,
  definitionPathRemainder,
  type ProtocolDefinition,
  type ProtocolField,
  type ProtocolQueryContext,
} from "./protocolQuery";

const props = withDefaults(
  defineProps<{
    fields: ProtocolField[];
    context: ProtocolQueryContext;
    lineage: string[];
    basePath?: string;
    matchPath?: string;
  }>(),
  { basePath: "", matchPath: "" },
);
const emit = defineEmits<{ open: [key: string]; copy: [value: string] }>();
const children = ref<Record<string, ProtocolDefinition>>({});
const expanded = ref<Record<string, boolean>>({});
const errors = ref<Record<string, string>>({});
const loading = ref<Record<string, boolean>>({});
let alive = true;
const controller = new AbortController();
onBeforeUnmount(() => {
  alive = false;
  controller.abort();
});
function childId(field: ProtocolField, key: string, suffix: string) {
  return `${field.id}:${key}:${suffix}`;
}
function path(field: ProtocolField) {
  return props.basePath + field.path;
}
async function expand(
  field: ProtocolField,
  structure: ProtocolField["structures"][number],
  auto = false,
) {
  const id = childId(field, structure.key, structure.pathSuffix);
  if (loading.value[id]) return;
  if (expanded.value[id] && !auto) {
    expanded.value[id] = false;
    return;
  }
  expanded.value[id] = true;
  if (
    children.value[id] ||
    props.lineage.includes(structure.key) ||
    props.lineage.length >= 16
  )
    return;
  loading.value[id] = true;
  errors.value[id] = "";
  try {
    const relative =
      definitionPathRemainder(
        path(field) + structure.pathSuffix,
        props.matchPath,
      ) || "";
    const value = await api<ProtocolDefinition>(
      definitionUrl(props.context, structure.key, 0, relative),
      { signal: controller.signal },
    );
    if (alive) children.value[id] = value;
  } catch (error) {
    if (alive) errors.value[id] = String(error);
  } finally {
    if (alive) loading.value[id] = false;
  }
}
watch(
  () => [props.fields, props.matchPath],
  () => {
    for (const field of props.fields) {
      for (const structure of field.structures) {
        const remaining = definitionPathRemainder(
          path(field) + structure.pathSuffix,
          props.matchPath,
        );
        if (remaining) void expand(field, structure, true);
      }
    }
  },
  { immediate: true },
);
</script>
<template>
  <div class="protocol-fields" role="list" aria-label="协议字段定义">
    <div
      v-for="field in fields"
      :key="field.id"
      class="protocol-field"
      :class="{
        hit:
          !!matchPath && definitionPathRemainder(path(field), matchPath) === '',
      }"
      role="listitem"
    >
      <div class="field-row">
        <span class="field-number">{{ field.id }}</span
        ><code>{{ field.name }}</code
        ><span class="muted">{{ field.type }}</span>
        <el-button text size="small" @click="emit('copy', path(field))"
          >复制路径</el-button
        >
      </div>
      <div
        v-for="structure in field.structures"
        :key="structure.key + structure.pathSuffix"
        class="field-structure"
      >
        <el-button text size="small" @click="expand(field, structure)"
          >{{
            expanded[childId(field, structure.key, structure.pathSuffix)]
              ? "收起"
              : "展开"
          }}
          {{ structure.name
          }}{{
            structure.pathSuffix
              ? `（${fieldPathLabel(structure.pathSuffix)}）`
              : ""
          }}</el-button
        >
        <el-button text size="small" @click="emit('open', structure.key)"
          >查看结构体</el-button
        >
        <template
          v-if="expanded[childId(field, structure.key, structure.pathSuffix)]"
        >
          <p v-if="lineage.includes(structure.key)" class="muted">
            循环引用 {{ structure.name }}；可点击“查看结构体”独立浏览。
          </p>
          <p v-else-if="lineage.length >= 16" class="muted">
            已展开 16 层，可独立查看下一层结构体。
          </p>
          <p
            v-else-if="
              loading[childId(field, structure.key, structure.pathSuffix)]
            "
            class="muted"
            role="status"
          >
            读取结构体…
          </p>
          <p
            v-else-if="
              errors[childId(field, structure.key, structure.pathSuffix)]
            "
            class="error-bar"
          >
            {{ errors[childId(field, structure.key, structure.pathSuffix)] }}
            <el-button text @click="expand(field, structure, true)"
              >重试</el-button
            >
          </p>
          <template
            v-else-if="
              children[childId(field, structure.key, structure.pathSuffix)]
            "
          >
            <ProtocolFieldTree
              :fields="
                children[childId(field, structure.key, structure.pathSuffix)]!
                  .fields
              "
              :context="context"
              :lineage="[...lineage, structure.key]"
              :base-path="path(field) + structure.pathSuffix"
              :match-path="matchPath"
              @open="emit('open', $event)"
              @copy="emit('copy', $event)"
            />
            <el-button
              v-if="
                children[childId(field, structure.key, structure.pathSuffix)]!
                  .fieldCount >
                children[childId(field, structure.key, structure.pathSuffix)]!
                  .fields.length
              "
              text
              @click="emit('open', structure.key)"
              >当前仅展示部分字段，独立查看全部字段</el-button
            >
          </template>
        </template>
      </div>
    </div>
    <p v-if="!fields.length" class="muted">此定义没有字段。</p>
  </div>
</template>
<style scoped>
.protocol-field {
  padding: 8px 4px;
  border-bottom: 1px solid var(--border);
}
.field-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
.field-row code {
  overflow-wrap: anywhere;
}
.field-number {
  color: var(--muted);
  width: 30px;
  flex-shrink: 0;
}
.field-structure {
  margin-left: 24px;
  border-left: 2px solid var(--border);
  padding-left: 10px;
}
.hit {
  background: var(--soft);
  outline: 1px solid var(--primary);
}
</style>
