import test from 'node:test'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
import {useCaptureActivity} from '../web/src/useCaptureActivity.ts'
import {useInlineCopy} from '../web/src/useInlineCopy.ts'
const require=createRequire(new URL('../web/package.json',import.meta.url))
const {effectScope,ref,nextTick}=require('vue')
const snapshot=(total,clearVersion=0)=>({captureId:'a',mode:'lifetime',clearVersion,total,attention:0,lastIndex:total-1,firstRetained:0,complete:true,groups:[{token:'id:0',name:'Zero',count:total,firstIndex:0,lastIndex:total-1}]})

test('迟到列表只确认该列表的消息，不吞掉后来到达的数据，也不能倒退已读或清空代次',()=>{
 const scope=effectScope()
 try {
  const activity=scope.run(()=>useCaptureActivity(()=>({id:'a'})))
  activity.publish(snapshot(10))
  activity.publish(snapshot(15))
  assert.equal(activity.unread.value.total,5)
  activity.publish(snapshot(12),true)
  assert.equal(activity.latest.value.total,15)
  assert.equal(activity.unread.value.total,3)
  activity.acknowledge()
  activity.publish(snapshot(12),true)
  assert.equal(activity.baseline.value.total,15)
  activity.publish(snapshot(15,1))
  activity.publish(snapshot(20,0))
  assert.equal(activity.latest.value.clearVersion,1)
  assert.equal(activity.latest.value.total,15)
 } finally {scope.stop()}
})
test('切换记录后旧活动请求不能发布到新记录',async()=>{
 const original=globalThis.fetch, scope=effectScope()
 let release
 globalThis.fetch=()=>new Promise(resolve=>release=resolve)
 try {
  const capture=ref({id:'a'})
  const activity=scope.run(()=>useCaptureActivity(()=>capture.value))
  const pending=activity.refresh()
  capture.value={id:'b'}; await nextTick()
  release({ok:true,json:async()=>snapshot(10)})
  await pending
  assert.equal(activity.latest.value,undefined)
  assert.equal(activity.baseline.value,undefined)
 } finally {scope.stop();globalThis.fetch=original}
})
test('复制成功和失败在原位反馈，值切换或组件关闭后清理旧提示',async()=>{
 const original=Object.getOwnPropertyDescriptor(globalThis,'navigator'),scope=effectScope()
 const written=[]
 Object.defineProperty(globalThis,'navigator',{configurable:true,value:{clipboard:{writeText:async text=>written.push(text)}}})
 try {
  const copy=scope.run(()=>useInlineCopy())
  await copy.copy('9007199254740993')
  assert.equal(copy.feedback.value,'已复制'); assert.deepEqual(written,['9007199254740993'])
  navigator.clipboard.writeText=async()=>{throw new Error('denied')}
  await copy.copy('next')
  assert.equal(copy.feedback.value,'复制失败')
  copy.reset(); assert.equal(copy.feedback.value,'')
 } finally {scope.stop();if(original)Object.defineProperty(globalThis,'navigator',original);else delete globalThis.navigator}
})
