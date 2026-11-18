import test from 'node:test'
import assert from 'node:assert/strict'
import { extractCatalog, discoverProtocolFiles } from './extract-catalog.mjs'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

test('静态提取常量引用、链式赋值和嵌套结构，不执行函数', () => {
  const catalog = extractCatalog(`System.register('sample', [], function(e) {
    return {execute:function(){ var field='groupId', struct='PsSample', i;
      e('thriftData', ((i={2364:{1:[field,8]}}).PsSample={1:['large',10]}, i[2365]={1:['info',[12,struct]]}, i));
      e('messageData', [['CgSample',2364],['GcSample',2365]]);
      e('structData', [struct]);
    }};
  });`)
  assert.deepEqual(catalog.definitions[2364][1], ['groupId', 8])
  assert.equal(catalog.messages[2365].direction, 'SC')
  assert.equal(catalog.definitions.PsSample[1][1], 10)
})
test('动态表达式与不完整引用明确拒绝，不能执行客户端代码补齐', () => {
  assert.throws(() => extractCatalog(`function run(e){ e('thriftData', evil()); }`), /无法静态解析/)
  assert.throws(() => extractCatalog(`function run(e){ var i; e('thriftData',i={1:{1:['x',[12,'Missing']]}}); e('messageData',[['CgX',1]]); e('structData',[]); }`), /结构体引用缺失/)
})

test('新版 StringKind 二进制定义保留，未知子类型拒绝', () => {
  const source = `function sample(e){e('thriftData',{1:{1:['payload',[11,1]],2:['text',[11,0]]}});e('messageData',[['CgBinary',1]]);e('structData',[]);}`
  assert.deepEqual(extractCatalog(source).definitions[1][1], ['payload',[11,1]])
  assert.throws(()=>extractCatalog(source.replace('[11,1]', '[11,2]')), /STRING 子类型/)
})

test('根目录、任意分包子目录及带引号的文件路径共用发现规则', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), '协议 路径-'))
  t.after(() => fs.rmSync(root, {recursive:true, force:true}))
  const directory = path.join(root, 'script/subpackages/scripts___4')
  fs.mkdirSync(directory, {recursive:true})
  const file = path.join(directory, 'thrift_data.js')
  fs.writeFileSync(file, 'not executed', 'utf8')
  const result = discoverProtocolFiles(root)
  assert.equal(result.candidates.length, 1)
  assert.equal(result.candidates[0].relativePath, 'script/subpackages/scripts___4/thrift_data.js')
  assert.equal(discoverProtocolFiles(directory).candidates[0].path, file)
  assert.equal(discoverProtocolFiles(`"${file}"`).candidates[0].path, file)
  assert.ok(result.candidates[0].sizeBytes > 0)
})

test('多个候选全部列出，排除依赖目录；空目录及无效文件有明确结果', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'protocol-source-'))
  t.after(() => fs.rmSync(root, {recursive:true, force:true}))
  assert.deepEqual(discoverProtocolFiles(root).candidates, [])
  for (const folder of ['a', 'b', 'node_modules/ignored']) {
    fs.mkdirSync(path.join(root, folder), {recursive:true})
    fs.writeFileSync(path.join(root, folder, 'thrift_data.js'), '', 'utf8')
  }
  assert.deepEqual(discoverProtocolFiles(root).candidates.map(item=>item.relativePath), ['a/thrift_data.js', 'b/thrift_data.js'])
  const invalid = path.join(root, 'unrelated.js')
  fs.writeFileSync(invalid, '', 'utf8')
  assert.throws(() => discoverProtocolFiles(invalid), /thrift_data.js/)
  assert.throws(() => discoverProtocolFiles(''), /请填写/)
})
