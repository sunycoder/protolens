/** 消息分析使用有界遍历；不把大型消息展开成无上限的 DOM 或对比结果。 */
export interface FieldMatch {
  path: string;
  value: string;
}
export interface FieldChange {
  path: string;
  before: string;
  after: string;
  kind: "新增" | "移除" | "修改";
}
export function pointerChild(path: string, key: string): string {
  return `${path}/${key.replace(/~/g, "~0").replace(/\//g, "~1")}`;
}
export function displayValue(value: unknown): string {
  if (value === undefined) return "（不存在）";
  if (Array.isArray(value)) return `[${value.length} 项]`;
  if (value !== null && typeof value === "object")
    return `{${Object.keys(value).length} 个字段}`;
  const text = JSON.stringify(value) ?? String(value);
  return text.length > 500 ? text.slice(0, 500) + "…" : text;
}
export function findFieldMatches(value: unknown, query: string) {
  const keyword = query.trim().toLowerCase();
  const matches: FieldMatch[] = [];
  let visited = 0;
  let truncated = false;
  function visit(node: unknown, path: string, key: string, depth: number) {
    if (++visited > 10000 || matches.length >= 50 || depth > 32) {
      truncated = true;
      return;
    }
    const container = node !== null && typeof node === "object";
    if (
      key.toLowerCase().includes(keyword) ||
      (!container && String(node).toLowerCase().includes(keyword))
    )
      matches.push({ path, value: displayValue(node) });
    if (container)
      for (const childKey in node as Record<string, unknown>) {
        if (!Object.hasOwn(node, childKey)) continue;
        if (visited >= 10000 || matches.length >= 50) {
          truncated = true;
          break;
        }
        visit(
          (node as Record<string, unknown>)[childKey],
          pointerChild(path, childKey),
          childKey,
          depth + 1,
        );
      }
  }
  if (keyword && value !== undefined) visit(value, "", "", 0);
  return { matches, truncated };
}

/** 对比只表达实际字段变化，不据此推断请求、响应或广播关系。 */
export function compareFields(before: unknown, after: unknown, arrayKey = "") {
  const changes: FieldChange[] = [];
  const warnings: string[] = [];
  let visited = 0;
  let truncated = false;
  function visit(a: unknown, b: unknown, path: string, depth: number) {
    if (++visited > 20000 || changes.length >= 500 || depth > 32) {
      truncated = true;
      return;
    }
    if (Object.is(a, b)) return;
    const aObject = a !== null && typeof a === "object";
    const bObject = b !== null && typeof b === "object";
    if (arrayKey && Array.isArray(a) && Array.isArray(b)) {
      if (a.length + b.length > 20000 - visited) {
        truncated = true;
        return;
      }
      function index(items: unknown[]) {
        const result = new Map<string, unknown>();
        for (const item of items) {
          visited++;
          if (
            !item ||
            typeof item !== "object" ||
            !Object.hasOwn(item, arrayKey)
          )
            return;
          const value = (item as Record<string, unknown>)[arrayKey];
          if (
            (typeof value !== "number" && typeof value !== "string") ||
            (typeof value === "number" && !Number.isFinite(value))
          )
            return;
          const key = `${typeof value}:${String(value)}`;
          if (result.has(key)) return;
          result.set(key, item);
        }
        return result;
      }
      const left = index(a),
        right = index(b);
      if (left && right) {
        for (const [key, value] of left) {
          if (visited >= 20000 || changes.length >= 500) {
            truncated = true;
            break;
          }
          visit(
            value,
            right.get(key),
            pointerChild(path, `${arrayKey}=${key}`),
            depth + 1,
          );
        }
        for (const [key, value] of right) {
          if (left.has(key)) continue;
          if (visited >= 20000 || changes.length >= 500) {
            truncated = true;
            break;
          }
          visit(
            undefined,
            value,
            pointerChild(path, `${arrayKey}=${key}`),
            depth + 1,
          );
        }
        return;
      }
      if (warnings.length < 10)
        warnings.push(
          `${path || "/"}：对齐字段缺失、重复或不是字符串/数字，已回退按位置比较`,
        );
    }
    if (aObject && bObject && Array.isArray(a) === Array.isArray(b)) {
      const left = a as Record<string, unknown>;
      const right = b as Record<string, unknown>;
      for (const key in left) {
        if (!Object.hasOwn(left, key)) continue;
        if (visited >= 20000 || changes.length >= 500) {
          truncated = true;
          break;
        }
        visit(left[key], right[key], pointerChild(path, key), depth + 1);
      }
      for (const key in right) {
        if (!Object.hasOwn(right, key) || Object.hasOwn(left, key)) continue;
        if (visited >= 20000 || changes.length >= 500) {
          truncated = true;
          break;
        }
        visit(undefined, right[key], pointerChild(path, key), depth + 1);
      }
      return;
    }
    changes.push({
      path: path || "/",
      before: displayValue(a),
      after: displayValue(b),
      kind: a === undefined ? "新增" : b === undefined ? "移除" : "修改",
    });
  }
  visit(before, after, "", 0);
  return { changes, truncated, warnings };
}

/** 页码依据实际匹配条数，不依赖原始消息编号是否连续。 */
export function pageContaining(precedingCount: number, pageSize = 100) {
  return Math.max(1, Math.ceil(precedingCount / pageSize));
}

export function neighbourPage(
  page: number,
  position: number,
  rowCount: number,
  total: number,
  delta: number,
  pageSize = 100,
  offset = (page - 1) * pageSize,
) {
  if (position < 0 || rowCount === 0) return undefined;
  const target = position + delta;
  const absolute = offset + target;
  if (absolute < 0 || absolute >= total) return undefined;
  if (target >= 0 && target < rowCount) return { page, position: target };
  const nextPage = Math.floor(absolute / pageSize) + 1;
  const nextPosition = absolute % pageSize;
  return {
    page: nextPage,
    position: nextPosition === pageSize - 1 ? -1 : nextPosition,
  };
}
