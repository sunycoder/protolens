import test from 'node:test'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
import {useActivityWindow} from '../web/src/useActivityWindow.ts'
import {defaultQuery} from '../web/src/workspaceInteractions.ts'
const require=createRequire(new URL('../web/package.json',import.meta.url))
const {effectScope,ref,nextTick}=require('vue')
const wait=()=>new Promise(resolve=>setTimeout(resolve,150))
const flush=()=>new Promise(resolve=>setImmediate(resolve))

test('通知统计切换筛选立即撤下旧入口，拒绝迟到结果，确认游标不吞掉稍后消息',async()=>{
 const originalFetch=globalThis.fetch, scope=effectScope(), requests=[]
 globalThis.fetch=(url)=>new Promise(resolve=>requests.push({url,resolve}))
 const capture=ref({id:'a',marks:[],firstIndex:0,clearVersion:0})
 const query=ref({...defaultQuery})
 const delta=ref({from:641,to:658,total:18,protocolCount:1,firstSeen:[],attention:0,complete:true})
 const snapshot=ref({captureId:'a',lastIndex:658,clearVersion:0})
 const result=(total)=>({captureId:'a',clearVersion:0,total,attention:0,complete:true,groups:total?[{token:'id:7'}]:[]})
 try {
  const state=scope.run(()=>useActivityWindow(()=>capture.value,()=>delta.value,()=>query.value,()=>snapshot.value))
  await wait();assert.equal(requests.length,1)
  assert.match(requests[0].url,/hideHeartbeat=true/)
  query.value={...query.value,hideHeartbeat:false};await nextTick()
  assert.equal(state.value.value,undefined)
  await wait();assert.equal(requests.length,2)
  assert.match(requests[1].url,/hideHeartbeat=false/)
  // 请求发出后又到达的新消息，不得被本次通知确认。
  snapshot.value={...snapshot.value,lastIndex:660}
  requests[1].resolve({ok:true,json:async()=>result(1)})
  await flush();assert.equal(state.value.value.total,1);assert.equal(state.source.value.lastIndex,658)
  requests[0].resolve({ok:true,json:async()=>result(18)})
  await flush();assert.equal(state.value.value.total,1)
  query.value={...query.value,hideHeartbeat:true};await nextTick();await wait()
  requests[2].resolve({ok:true,json:async()=>result(0)})
  await flush();assert.equal(state.value.value.total,0)
  // 备注更新与通知自身区间都不重新发起同口径统计。
  capture.value={...capture.value,name:'备注'}
  query.value={...query.value,range:'recent:641:658'}
  await nextTick();await wait();assert.equal(requests.length,3)
  capture.value={...capture.value,id:'b'};await nextTick()
  assert.equal(state.value.value,undefined)
 } finally {scope.stop();globalThis.fetch=originalFetch}
})
