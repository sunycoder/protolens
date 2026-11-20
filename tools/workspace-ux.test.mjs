import test from 'node:test'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
import {useStableRefresh} from '../web/src/useStableRefresh.ts'
import {useAnalysisTabs} from '../web/src/useAnalysisTabs.ts'
import {useProjectSync} from '../web/src/useProjectSync.ts'
import {pointerTrail,validWidth,normalizedSearches} from '../web/src/workspaceNavigation.ts'
import {defaultQuery} from '../web/src/workspaceInteractions.ts'
const require=createRequire(new URL('../web/package.json',import.meta.url))
const {ref,effectScope,nextTick}=require('vue')
const wait=(ms=30)=>new Promise(resolve=>setTimeout(resolve,ms))
const flush=()=>new Promise(resolve=>setImmediate(resolve))

test('实时数据连续到达不取消正在运行的统计，只排队一次尾随刷新，换筛选才作废',async()=>{
 const scope=effectScope(),scopeId=ref('a'),cursor=ref(1),value=ref(0),pending=[]
 try{
  const state=scope.run(()=>useStableRefresh({scope:()=>scopeId.value,input:()=>cursor.value,signature:()=>String(cursor.value),delay:5,load:(input,signal)=>new Promise(resolve=>pending.push({input,signal,resolve})),publish:result=>value.value=result,reset:()=>value.value=0}))
  await wait();assert.equal(pending.length,1)
  for(let i=2;i<=10;i++){cursor.value=i;await nextTick()}
  assert.equal(pending[0].signal.aborted,false);assert.equal(pending.length,1)
  pending[0].resolve(1);await flush();assert.equal(value.value,1)
  await wait();assert.equal(pending.length,2);assert.equal(pending[1].input,10)
  assert.equal(value.value,1) // 后台刷新保留上次结果。
  scopeId.value='b';await nextTick();assert.equal(value.value,0);assert.equal(pending[1].signal.aborted,true)
  pending[1].resolve(10);await flush();assert.equal(value.value,0)
  await wait();pending[2].resolve(20);await flush();assert.equal(value.value,20);assert.ok(state.updatedAt.value)
 }finally{scope.stop()}
})
test('页签切换失败保持原页签，关闭失败不会删除，切换记录清理旧位置',async()=>{
 const scope=effectScope(),record=ref('a');let success=false,calls=0
 try{
  const state=scope.run(()=>useAnalysisTabs({id:()=>record.value,place:()=>({captureId:record.value,query:{...defaultQuery},page:1,scrollTop:50,tab:'tree',detailOpen:true,selectedIndex:1}),follow:()=>false,field:()=>'/amount',temporary:()=>undefined,restore:async()=>{calls++;return success}}))
  const first=state.active.value;state.add();const second=state.active.value
  await state.select(first);assert.equal(state.active.value,second)
  await state.close(second);assert.equal(state.tabs.value.length,2)
  success=true;await state.select(first);assert.equal(state.active.value,first);assert.equal(state.tabs.value.find(tab=>tab.id===second).field,'/amount')
  assert.equal(calls,3);record.value='b';await nextTick();assert.equal(state.tabs.value.length,1)
 }finally{scope.stop()}
})
test('分析成果读后再写并使用 revision，保存冲突保留当前输入且不自动覆盖',async()=>{
 const original=globalThis.fetch,scope=effectScope(),id=ref('a'),draft=ref({notes:[],columns:[],views:[],widths:{}}),requests=[]
 let revision=2,fail=false
 globalThis.fetch=async(url,options)=>{
   requests.push({url,options})
   if(options?.method==='POST'){if(fail)return {ok:false,status:400,json:async()=>({error:'分析成果已被其他页面更新'})};const body=JSON.parse(options.body);assert.equal(body.revision,revision);revision++;return {ok:true,json:async()=>({...body,revision})}}
   return {ok:true,json:async()=>({...draft.value,revision})}
 }
 try{
  const state=scope.run(()=>useProjectSync({id:()=>id.value,enabled:()=>true,document:()=>draft.value,apply:document=>draft.value={notes:document.notes,columns:document.columns,views:document.views,widths:document.widths}}))
  await flush();await nextTick();assert.equal(state.ready.value,true)
  draft.value={...draft.value,notes:[{index:1,text:'新批注'}]};await nextTick();assert.equal(state.pending.value,true);assert.equal(state.saving.value,false);await state.flush()
  assert.equal(state.pending.value,false)
  assert.equal(state.revision.value,3)
  fail=true;draft.value={...draft.value,notes:[{index:1,text:'冲突输入保留'}]};await nextTick()
  await assert.rejects(()=>state.flush(),/其他页面更新/)
  assert.equal(state.pending.value,true)
  assert.equal(draft.value.notes[0].text,'冲突输入保留')
  const writes=requests.filter(item=>item.options?.method==='POST').length
  draft.value={...draft.value,widths:{protocolName:300}};await nextTick();await wait(400)
  assert.equal(requests.filter(item=>item.options?.method==='POST').length,writes)
 }finally{scope.stop();globalThis.fetch=original}
})
test('字段面包屑保留 JSON Pointer 转义，列宽及历史输入有界',()=>{
 assert.deepEqual(pointerTrail('/a~1b/~0value').map(item=>item.label),['消息体','a/b','~value'])
 assert.equal(pointerTrail('/a~1b/~0value')[2].path,'/a~1b/~0value')
 assert.equal(validWidth(170),true);assert.equal(validWidth(-1),false);assert.equal(validWidth(NaN),false)
 assert.deepEqual(normalizedSearches([{q:'GcUpdate',pinned:true},{q:'GcUpdate'},null,{q:'a'.repeat(301)}]),[{q:'GcUpdate',pinned:true}])
})
