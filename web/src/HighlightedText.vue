<script setup lang="ts">
import { computed } from "vue";
const props = defineProps<{ text?: string; query?: string }>();
const pieces = computed(() => {
  const text = props.text || "",
    query = props.query?.trim() || "";
  if (!query) return [{ text, match: false }];
  const result: { text: string; match: boolean }[] = [];
  const lower = text.toLowerCase(),
    keyword = query.toLowerCase();
  let offset = 0;
  for (
    let position = lower.indexOf(keyword);
    position >= 0;
    position = lower.indexOf(keyword, offset)
  ) {
    if (position > offset)
      result.push({ text: text.slice(offset, position), match: false });
    result.push({
      text: text.slice(position, position + query.length),
      match: true,
    });
    offset = position + query.length;
  }
  if (offset < text.length)
    result.push({ text: text.slice(offset), match: false });
  return result;
});
</script>
<template>
  <template v-for="(piece, index) in pieces" :key="index"
    ><mark v-if="piece.match" class="search-hit">{{ piece.text }}</mark
    ><template v-else>{{ piece.text }}</template></template
  >
</template>
