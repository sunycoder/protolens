import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {spawnSync} from 'node:child_process'

const shell=process.env.PROTOLENS_TEST_SH || (process.platform==='win32' ? '' : 'sh')
const repository=fileURLToPath(new URL('../',import.meta.url))
function run(entry,args=[],fail=false){
  const base=path.resolve(process.env.PROTOLENS_TEST_TMP || os.tmpdir())
  fs.mkdirSync(base,{recursive:true})
  const root=fs.mkdtempSync(path.join(base,'protolens-startup-'))
  try{
    const source=path.join(root,'source root 启动'),outside=path.join(root,'outside')
    fs.mkdirSync(source);fs.mkdirSync(outside)
    for(const script of ['start-protolens.sh','collect-protolens.sh'])fs.copyFileSync(path.join(repository,script),path.join(source,script))
    fs.writeFileSync(path.join(source,'gradlew'),'#!/bin/sh\nprintf "%s\\n" BEGIN "$@" END >> "$PROBE_LOG"\nif [ "$PROBE_FAIL" = 1 ] && [ "$3" = extractCatalog ]; then exit 9; fi\n','utf8')
    const log=path.join(root,'arguments.log')
    const result=spawnSync(shell,[path.join(source,entry).replaceAll('\\','/'),...args],{cwd:outside,encoding:'utf8',env:{...process.env,PROBE_LOG:log.replaceAll('\\','/'),PROBE_FAIL:fail?'1':'0'}})
    assert.ifError(result.error)
    return {status:result.status,log:fs.readFileSync(log,'utf8').replaceAll('\r','')}
  }finally{
    assert.ok(root.startsWith(base+path.sep))
    fs.rmSync(root,{recursive:true,force:true})
  }
}
test('POSIX启动脚本支持从仓库外调用和带空格、中文的目录',{skip:!shell},()=>{
  const result=run('start-protolens.sh')
  assert.equal(result.status,0)
  assert.equal(result.log.split('\n').filter(x=>x==='BEGIN').length,1)
  assert.match(result.log,/source root 启动\nrun\n--console=plain/)
})
test('兼容入口保留整个协议目录参数，先提取再启动',{skip:!shell},()=>{
  const result=run('collect-protolens.sh',['/tmp/client project 中文'])
  assert.equal(result.status,0)
  assert.ok(result.log.includes('-PcompetitorDir=/tmp/client project 中文\n'))
  assert.ok(result.log.indexOf('extractCatalog')<result.log.lastIndexOf('\nrun\n'))
  assert.equal(result.log.split('\n').filter(x=>x==='BEGIN').length,2)
})
test('提取协议失败立即返回原退出码，不继续启动',{skip:!shell},()=>{
  const result=run('start-protolens.sh',['/tmp/client project'],true)
  assert.equal(result.status,9)
  assert.ok(!result.log.includes('\nrun\n'))
})
