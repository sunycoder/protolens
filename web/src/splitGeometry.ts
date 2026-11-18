/** 分隔条两侧的最小尺寸不可同时满足时，让容器滚动，而不是把编辑器压到不可操作。单位CSS像素。 */
export function constrainSplit(
  total: number,
  primary: number,
  minimumPrimary: number,
  minimumSecondary: number,
) {
  const available = Math.max(total, minimumPrimary + minimumSecondary);
  return Math.max(
    minimumPrimary,
    Math.min(available - minimumSecondary, primary),
  );
}
