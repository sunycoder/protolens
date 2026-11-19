/** 大 JSON/HEX 格式化移出主线程；页面只渲染用户请求的文本段。 */
self.onmessage = (event: MessageEvent<{ value: unknown; format: string }>) => {
  try {
    const { value, format } = event.data;
    if (format !== "hex") {
      self.postMessage({ text: JSON.stringify(value, null, 2) ?? "" });
      return;
    }
    const hex = typeof value === "string" ? value : "";
    const lines: string[] = [];
    for (let offset = 0; offset < hex.length; offset += 32)
      lines.push(
        (offset / 2).toString(16).padStart(8, "0") +
          "  " +
          (hex.slice(offset, offset + 32).match(/.{1,2}/g) || [])
            .join(" ")
            .toUpperCase(),
      );
    self.postMessage({ text: lines.join("\n") });
  } catch (error) {
    self.postMessage({ error: String(error) });
  }
};
export {};
