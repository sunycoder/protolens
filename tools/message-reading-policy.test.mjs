import test from 'node:test'
import assert from 'node:assert/strict'
import {useMessageSelection} from '../web/src/useMessageSelection.ts'
import {reconcileRows, shouldRefreshMessages} from '../web/src/workspaceRefresh.ts'

test('关闭选择后暂停时列表继续追新，已打开的详情快照不被新行或缓存淘汰替换',async()=>{
 const original=globalThis.fetch
 const capture={id:'a'}
 let following=true
 let pauseOnSelect=false
 let source={index:0,connection:'c',bytes:20,body:{amount:20}}
 globalThis.fetch=async()=>({ok:true,json:async()=>structuredClone(source)})
 try {
  const state=useMessageSelection(()=>capture,()=>{following=false},()=>pauseOnSelect)
  await state.selectDetail(source)
  assert.equal(following,true)
  assert.equal(shouldRefreshMessages('a',['a'],following,false),true)
  source={index:1,connection:'c',bytes:20,body:{amount:21}}
  let rows=reconcileRows([{index:0,connection:'c'}],[source])
  assert.equal(rows[0].index,1)
  assert.equal(state.selected.value.index,0)
  assert.equal(state.selected.value.body.amount,20)
  rows=[] // 列表淘汰不会回写详情所有者。
  assert.equal(state.selected.value.body.amount,20)
  pauseOnSelect=true
  await state.selectDetail(source)
  assert.equal(following,false)
  assert.equal(state.selected.value.index,1)
  assert.equal(shouldRefreshMessages('a',['a'],following,false),false)
 } finally {globalThis.fetch=original}
})
