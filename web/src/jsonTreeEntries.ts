/** 只读取已展示的子项和搜索命中的子项，避免展开百条前先复制整个大数组。 */
export function visibleJsonKeys(
  value: object,
  path: string,
  limit: number,
  matchPaths: string[] = [],
  objectKeys?: string[],
): string[] {
  const array = Array.isArray(value);
  const keys = array ? undefined : (objectKeys ?? Object.keys(value));
  const count = array ? value.length : keys!.length;
  const visible = new Set<string>();
  for (let index = 0; index < Math.min(limit, count); index++)
    visible.add(array ? String(index) : keys![index]!);
  const prefix = path + "/";
  for (const match of matchPaths) {
    if (!match.startsWith(prefix)) continue;
    const key = match
      .slice(prefix.length)
      .split("/")[0]!
      .replace(/~1/g, "/")
      .replace(/~0/g, "~");
    if (Object.hasOwn(value, key) && (!array || /^(0|[1-9]\d*)$/.test(key)))
      visible.add(key);
  }
  return array
    ? [...visible].sort((a, b) => Number(a) - Number(b))
    : [...visible];
}
