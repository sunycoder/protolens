import { onScopeDispose, ref, watch } from "vue";

/** 同口径只运行一个请求；数据继续到达时合并为一次尾随刷新，不取消已运行计算。换记录/筛选才作废。 */
export function useStableRefresh<T, R>(options: {
  scope: () => string;
  input: () => T | undefined;
  signature: () => string;
  load: (input: T, signal: AbortSignal) => Promise<R>;
  publish: (result: R, input: T) => void;
  reset: () => void;
  delay?: number;
}) {
  const loading = ref(false),
    error = ref(""),
    updatedAt = ref(0);
  let generation = 0,
    running = false,
    dirty = false;
  let controller: AbortController | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  function schedule() {
    dirty = true;
    if (running || timer) return;
    timer = setTimeout(() => {
      timer = undefined;
      void run();
    }, options.delay ?? 150);
  }
  async function run() {
    const input = options.input();
    if (input === undefined) {
      dirty = false;
      return;
    }
    const version = generation;
    controller = new AbortController();
    running = true;
    dirty = false;
    loading.value = true;
    try {
      const result = await options.load(input, controller.signal);
      if (version !== generation) return;
      options.publish(result, input);
      error.value = "";
      updatedAt.value = Date.now();
    } catch (cause) {
      if (version === generation)
        error.value = cause instanceof Error ? cause.message : String(cause);
    } finally {
      if (version === generation) {
        running = false;
        loading.value = false;
        if (dirty) schedule();
      }
    }
  }
  function invalidate() {
    generation++;
    controller?.abort();
    if (timer) clearTimeout(timer);
    timer = undefined;
    running = false;
    dirty = false;
    loading.value = false;
    error.value = "";
    updatedAt.value = 0;
    options.reset();
    schedule();
  }
  watch(options.scope, invalidate, { immediate: true, flush: "sync" });
  watch(options.signature, schedule);
  onScopeDispose(() => {
    generation++;
    controller?.abort();
    if (timer) clearTimeout(timer);
  });
  return { loading, error, updatedAt, refresh: schedule };
}
