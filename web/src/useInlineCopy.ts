import { ref, onScopeDispose } from "vue";

/** 复制反馈留在触发位置；重复点击复用同一提示，不堆叠全局通知。 */
export function useInlineCopy() {
  const feedback = ref("");
  let timer: ReturnType<typeof setTimeout> | undefined;
  let generation = 0;
  async function copy(text: string) {
    const request = ++generation;
    if (timer) clearTimeout(timer);
    try {
      await navigator.clipboard.writeText(text);
      if (request === generation) feedback.value = "已复制";
    } catch {
      if (request === generation) feedback.value = "复制失败";
    }
    if (request === generation)
      timer = setTimeout(() => (feedback.value = ""), 1800);
  }
  function reset() {
    generation++;
    if (timer) clearTimeout(timer);
    feedback.value = "";
  }
  onScopeDispose(reset);
  return { feedback, copy, reset };
}
