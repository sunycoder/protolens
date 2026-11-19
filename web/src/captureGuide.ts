/** 使用后端运行身份选择说明，不能根据自动代理支持与否猜测操作系统。 */
export type GuidePlatform = "windows" | "macos" | "linux";
export const platformLabels: Record<GuidePlatform, string> = {
  windows: "Windows",
  macos: "macOS",
  linux: "Linux",
};
export function guidePlatform(value: unknown): GuidePlatform | undefined {
  return value === "windows" || value === "macos" || value === "linux"
    ? value
    : undefined;
}
export function guidePort(meta: { proxyPort?: number; port?: number }) {
  const port = meta.proxyPort ?? meta.port;
  return Number.isInteger(port) && port! > 0 && port! <= 65535
    ? port
    : undefined;
}
/** 用户提供下载文件的位置后才生成命令；单引号转义防止空格、中文及 shell 字符改变参数。 */
export function linuxCertificateCommands(
  path: string,
  family: "debian" | "fedora",
) {
  const value = path.trim();
  if (!value || !value.startsWith("/") || /[\r\n\u0000]/.test(value)) return "";
  const quoted = "'" + value.replace(/'/g, "'\\''") + "'";
  const target =
    family === "debian"
      ? "/usr/local/share/ca-certificates/protolens.crt"
      : "/etc/pki/ca-trust/source/anchors/protolens.crt";
  return (
    `sudo install -m 0644 -- ${quoted} ${target}\n` +
    (family === "debian"
      ? "sudo update-ca-certificates"
      : "sudo update-ca-trust extract")
  );
}
