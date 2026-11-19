/** 排除按协议种类选择；同一协议出现多条消息时只计一项。 */
export interface ProtocolChoice {
  key: string;
  token: string;
  label: string;
}
export function protocolChoice(row: {
  protocolId?: number;
  protocolName?: string;
}): ProtocolChoice | undefined {
  const name = row.protocolName?.trim();
  if (row.protocolId === undefined && !name) return;
  const token =
    row.protocolId === undefined ? `name:${name}` : `id:${row.protocolId}`;
  return {
    key: row.protocolId === undefined ? `name:${name}` : `id:${row.protocolId}`,
    token,
    label: name
      ? `${name}${row.protocolId === undefined ? "" : ` (${row.protocolId})`}`
      : `协议 ${row.protocolId}`,
  };
}
export function mergeProtocolExclusions(
  existing: string,
  choices: ProtocolChoice[],
): string {
  return [
    ...new Set([
      ...existing.split(/[,，\s]+/).filter(Boolean),
      ...choices.map((item) => item.token).filter(Boolean),
    ]),
  ].join(",");
}

/** 再次勾选同协议的另一条消息时也取消该协议，选择跨页保留且不修改原数组。 */
export function toggleProtocolChoices(
  current: ProtocolChoice[],
  row: { protocolId?: number; protocolName?: string },
): ProtocolChoice[] {
  const choice = protocolChoice(row);
  if (!choice) return current;
  return current.some((item) => item.key === choice.key)
    ? current.filter((item) => item.key !== choice.key)
    : [...current, choice];
}
