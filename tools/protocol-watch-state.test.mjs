import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import {useProtocolWatch} from '../web/src/useProtocolWatch.ts'
const require=createRequire(new URL('../web/package.json',import.meta.url))
const {effectScope, ref, nextTick}=require('vue')

test('连接元数据刷新不能重置关注已读基线，关注计数不受列表跟随影响',async()=>{
 const original=Object.getOwnPropertyDescriptor(globalThis,'localStorage')
 const values=new Map()
 Object.defineProperty(globalThis,'localStorage',{configurable:true,value:{getItem:key=>values.get(key)??null,setItem:(key,value)=>values.set(key,value)}})
 const scope=effectScope()
 try {
  const capture=ref({id:'a',catalog:{sha256:'version'},connections:[]})
  const activity=ref({captureId:'a',groups:[{token:'id:0',count:1}]})
  const watched=scope.run(()=>useProtocolWatch(()=>capture.value,()=>activity.value))
  watched.toggle({protocolId:0,protocolName:'CgZero'})
  assert.equal(watched.choices.value[0].unread,0)
  capture.value={...capture.value,connections:[{id:'connection',note:'只修改备注'}]}
  await nextTick()
  activity.value={captureId:'a',groups:[{token:'id:0',count:2}]}
  await nextTick()
  assert.equal(watched.choices.value[0].unread,1)
  watched.acknowledge('id:0',2)
  activity.value={captureId:'a',groups:[{token:'id:0',count:4}]}
  await nextTick()
  assert.equal(watched.choices.value[0].unread,2)
  assert.equal(JSON.parse(values.get('analyzer.protocol-watch.v1')).version[0].token,'id:0')
 } finally {scope.stop();if(original)Object.defineProperty(globalThis,'localStorage',original);else delete globalThis.localStorage}
})
