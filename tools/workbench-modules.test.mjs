import test from 'node:test'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
import {useWorkspaceFilters} from '../web/src/useWorkspaceFilters.ts'
import {useMessageLoader} from '../web/src/useMessageLoader.ts'
import {useCaptureFeed} from '../web/src/useCaptureFeed.ts'
import {useQueryResults} from '../web/src/useQueryResults.ts'
import {defaultQuery} from '../web/src/workspaceInteractions.ts'
const require=createRequire(new URL('../web/package.json',import.meta.url))
const {effectScope,ref,shallowRef,nextTick}=require('vue')
const storage=new Map()
globalThis.localStorage={getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,String(value))}
const record=id=>({id,name:id,messageCount:1,connections:[],marks:[],counts:{},createdAt:'2026-11-20'})
const packet=index=>({index,protocolName:'GcExample',protocolId:10,direction:'SC',connection:'c',status:'DECODED',time:'',ordinal:index,wireBytes:1,frameId:'f',compressed:false})

test('筛选模块保留未提交草稿，生效标签只反映成功结果，回到最新仍保留心跳条件',()=>{
 const scope=effectScope(),capture=shallowRef(record('a')),shown=shallowRef({...defaultQuery,q:'已生效',hideHeartbeat:true})
 try{
  const state=scope.run(()=>useWorkspaceFilters({capture:()=>capture.value,query:()=>shown.value}))
  state.search.value='未提交';state.include.value='GcExample'
  assert.equal(state.draftQuery().q,'未提交');assert.equal(state.hasDraft.value,true)
  assert.equal(state.activeFilters.value.find(item=>item.key==='search').label,'搜索：已生效')
  state.applyQuery({...shown.value,range:''})
  assert.equal(state.hideHeartbeat.value,true);assert.equal(state.search.value,'已生效')
 }finally{scope.stop()}
})

test('拆分后的查询拒绝迟到响应，失败与取消均保留上次成功结果和筛选',async()=>{
 const original=globalThis.fetch,scope=effectScope(),requests=[]
 globalThis.fetch=(url,options)=>new Promise((resolve,reject)=>requests.push({url,options,resolve,reject}))
 try{
  const capture=shallowRef(record('a')),query=useQueryResults(),autoFollow=ref(true),browsingHistory=ref(false)
  const filters=scope.run(()=>useWorkspaceFilters({capture:()=>capture.value,query:()=>query.displayedQuery.value}))
  const loader=scope.run(()=>useMessageLoader({capture,query,filters,activity:{publish:()=>{}},supportsAnalysis:()=>false,fieldColumns:()=>[],grouping:()=> 'none',autoFollow,browsingHistory,viewingTail:ref(false),unread:shallowRef({}),table:()=>undefined,revealRow:async()=>{}}))
  const first=loader.loadMessages(false,false,{query:{...defaultQuery,q:'A'}})
  capture.value=record('b')
  const second=loader.loadMessages(false,false,{query:{...defaultQuery,q:'B'}})
  assert.equal(requests[0].options.signal.aborted,true)
  requests[1].resolve({ok:true,json:async()=>({capture:record('b'),items:[packet(20)],total:1})});await second
  requests[0].resolve({ok:true,json:async()=>({capture:record('a'),items:[packet(1)],total:1})});await first
  assert.equal(query.rows.value[0].index,20);assert.equal(query.displayedQuery.value.q,'B');assert.equal(capture.value.id,'b')
  const failed=loader.loadMessages(false,false,{query:{...defaultQuery,q:'失败'}})
  requests[2].reject(new Error('网络中断'));await failed
  assert.equal(query.rows.value[0].index,20);assert.equal(query.displayedQuery.value.q,'B');assert.match(query.queryError.value,/网络中断/)
  const cancelled=loader.loadMessages(false,false,{query:{...defaultQuery,q:'取消'}})
  loader.cancelQuery();requests[3].resolve({ok:true,json:async()=>({capture:record('b'),items:[],total:0})});await cancelled
  assert.equal(query.rows.value[0].index,20);assert.equal(query.displayedQuery.value.q,'B');assert.equal(loader.loading.value,false)
  const live=loader.loadMessages(true,true)
  requests[4].resolve({ok:true,json:async()=>({capture:record('b'),items:[packet(21),packet(20)],total:2})});await live
  assert.deepEqual([...loader.recentRows.value],[21])
  const history=loader.loadMessages(false,false)
  requests[5].resolve({ok:true,json:async()=>({capture:record('b'),items:[packet(21),packet(20)],total:2})});await history
  assert.equal(loader.recentRows.value.size,0)
 }finally{scope.stop();globalThis.fetch=original}
})

test('实时摘要模块收到其他连接消息只更新未读数，跟随与暂停仍由当前记录决定',async()=>{
 const capture=shallowRef(record('a')),captures=shallowRef([record('a'),record('b')]),unread=shallowRef({}),autoFollow=ref(true),browsingHistory=ref(false)
 let loads=0,refreshes=0
 const state=useCaptureFeed({capture,captures,unread,autoFollow,browsingHistory,loader:{loading:ref(false),loadMessages:async()=>loads++,queueLive:()=>{}},activity:{refresh:async()=>refreshes++},reloadCaptures:async()=>{},openCapture:async()=>{},failure:error=>{throw error}})
 await state.refreshLive([{captureId:'b',messages:4,retainedMessages:4}],['b'])
 assert.equal(loads,0);assert.equal(capture.value.id,'a');assert.equal(unread.value.b,3)
 await state.refreshLive([{captureId:'a',messages:2,retainedMessages:2}],['a']);assert.equal(loads,1)
 autoFollow.value=false
 await state.refreshLive([{captureId:'a',messages:3,retainedMessages:3}],['a']);assert.equal(loads,1);assert.equal(refreshes,1)
 await nextTick()
})
