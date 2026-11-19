import test from 'node:test';
import assert from 'node:assert/strict';
import { guidePlatform, guidePort, linuxCertificateCommands } from '../web/src/captureGuide.ts';

test('按后端平台选指南；无法识别时要求选择，不把手动代理当成 Linux', () => {
  for (const name of ['windows','macos','linux']) assert.equal(guidePlatform(name),name);
  assert.equal(guidePlatform(undefined),undefined);
  assert.equal(guidePlatform(false),undefined);
  assert.equal(guidePlatform('freebsd'),undefined);
});
test('端口来自生效配置，缺失和非法值不回退到写死默认端口', () => {
  assert.equal(guidePort({proxyPort:19901,port:18888}),19901);
  assert.equal(guidePort({port:20001}),20001);
  for (const value of [0,-1,65536,1.5]) assert.equal(guidePort({proxyPort:value}),undefined);
  assert.equal(guidePort({}),undefined);
});
test('Linux 命令按发行版生成，不能使用空路径、Windows 路径或多行命令', () => {
  assert.equal(linuxCertificateCommands('', 'debian'),'');
  assert.equal(linuxCertificateCommands('C:\\Users\\test\\cert.crt','debian'),'');
  assert.equal(linuxCertificateCommands('/tmp/cert\nsudo unexpected','debian'),'');
  const debian=linuxCertificateCommands('/home/测试/Downloads/CA cert.crt','debian');
  assert.ok(debian.includes("'/home/测试/Downloads/CA cert.crt'"));
  assert.ok(debian.includes('/usr/local/share/ca-certificates/protolens.crt'));
  assert.ok(debian.endsWith('sudo update-ca-certificates'));
  const fedora=linuxCertificateCommands('/tmp/CA.crt','fedora');
  assert.ok(fedora.includes('/etc/pki/ca-trust/source/anchors/protolens.crt'));
  assert.ok(fedora.endsWith('sudo update-ca-trust extract'));
});
test('包含引号或 shell 字符的路径仍是一个文件参数', () => {
  const command=linuxCertificateCommands("/tmp/test's $(example).crt",'debian');
  assert.ok(command.includes("'/tmp/test'\\''s $(example).crt'"));
});
