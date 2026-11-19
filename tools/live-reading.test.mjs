import test from 'node:test'
import assert from 'node:assert/strict'
import {activityDelta, activityQuery, latestQuery, activityWindowParams, visibleActivityDelta, readingRange, relaxFilter, watchedChoices} from '../web/src/liveReading.ts'
import {defaultQuery} from '../web/src/workspaceInteractions.ts'

const snapshot = (total,groups,extra={})=>({captureId:'a',mode:'lifetime',clearVersion:0,total,attention:0,lastIndex:total-1,firstRetained:0,complete:true,groups,...extra})
const group=(token,count,firstIndex,lastIndex)=>({token,name:token,count,attentionCount:0,firstIndex,lastIndex})

test('活动差值只基于同一记录与同一清空代次，ID 0 与首次本记录出现保持准确',()=>{
 const before=snapshot(10,[group('id:0',10,0,9)])
 const after=snapshot(15,[group('id:0',12,0,14),group('id:7',3,10,13)],{attention:2})
 const delta=activityDelta(before,after)
 assert.equal(delta.total,5);assert.equal(delta.protocolCount,2);assert.equal(delta.firstSeen[0].token,'id:7');assert.equal(delta.attention,2)
 assert.equal(activityDelta(before,{...after,captureId:'b'}),undefined)
 assert.equal(activityDelta(before,{...after,clearVersion:1}),undefined)
 assert.equal(activityDelta(after,before),undefined)
 assert.equal(activityDelta(before,{...after,complete:false}).firstSeen.length,0)
})
test('新增分类定位固定到通知对应序号范围，不把过去异常混入；条件可用于撤销恢复',()=>{
 const delta={from:0,to:8,total:9,protocolCount:1,firstSeen:[group('id:0',9,0,8)],attention:1,complete:true}
 assert.equal(activityQuery(delta,'first').include,'id:0')
 assert.equal(activityQuery(delta,'attention').status,'ATTENTION')
 assert.deepEqual(readingRange(activityQuery(delta,'all').range,[]),{from:0,to:8,name:'消息 #0–8'})
 assert.equal(readingRange('recent:9:1',[]),undefined)
 assert.equal(readingRange('recent:1:999999999999999999999',[]),undefined)
 assert.equal(readingRange('1',[{name:'标记',from:4,to:8}]).from,4)
})
test('解除原因只改实际阻碍项，排除 7 不会同时删除 70 或其他协议',()=>{
 const query={...defaultQuery,q:'保留草稿',exclude:'id:7,id:70,name:Other',connection:'a'}
 assert.deepEqual(relaxFilter(query,{field:'exclude',value:'id:7'}),{exclude:'id:70,name:Other'})
 assert.deepEqual(relaxFilter(query,{field:'connection',value:''}),{connection:''})
 assert.deepEqual(relaxFilter(query,{field:'hideHeartbeat',value:''}),{hideHeartbeat:false})
 assert.deepEqual(relaxFilter(query,{field:'__proto__',value:''}),{})
 assert.equal(query.connection,'a')
})

test('查看通知再回到最新保留隐藏心跳及所有用户筛选，仅解除通知临时区间',()=>{
 const original={...defaultQuery,q:'amount',protocolId:'7',messageIndex:'',include:'id:7,id:8',exclude:'id:9',direction:'SC',status:'DECODED',connection:'connection-a',order:'asc',hideHeartbeat:true}
 const delta={from:641,to:658,total:18,protocolCount:1,firstSeen:[group('id:7',18,641,658)],attention:1,complete:true}
 const notification={...original,...activityQuery(delta,'all')}
 assert.deepEqual(notification,{...original,range:'recent:641:658'})
 assert.deepEqual(latestQuery(notification),original)
 assert.deepEqual(original,{...notification,range:''})
 assert.equal(latestQuery({...notification,exclude:'id:9,id:10'}).exclude,'id:9,id:10')
 assert.equal(latestQuery({...original,range:'2'}).range,'2')
 assert.equal(latestQuery({...notification,hideHeartbeat:false}).hideHeartbeat,false)
 for (const category of ['first','attention']) {
   const scoped={...original,...activityQuery(delta,category)}
   assert.equal(scoped.hideHeartbeat,true)
   assert.equal(scoped.q,original.q)
   assert.equal(scoped.connection,original.connection)
   assert.equal(scoped.exclude,original.exclude)
 }
})
test('可查看通知应用实际筛选、忽略自身临时区间，已淘汰或隐藏协议不制造首次提示',()=>{
 const delta={from:641,to:658,total:18,protocolCount:2,firstSeen:[group('id:5',17,641,657),group('id:7',1,658,658)],attention:1,complete:true}
 const query={...defaultQuery,q:'value',exclude:'id:9',connection:'a',range:'recent:620:640'}
 const params=activityWindowParams(delta,query,[])
 assert.equal(params.get('from'),'641');assert.equal(params.get('to'),'658')
 assert.equal(params.get('hideHeartbeat'),'true');assert.equal(params.get('q'),'value')
 assert.equal(params.get('exclude'),'id:9');assert.equal(params.get('connection'),'a')
 assert.equal(activityWindowParams(delta,{...query,range:'1'},[{from:0,to:600}]),undefined)
 assert.equal(activityWindowParams(delta,{...query,range:'2'},[]),undefined)
 const visible=visibleActivityDelta(delta,snapshot(1,[group('id:7',1,658,658)]))
 assert.equal(visible.total,1);assert.equal(visible.protocolCount,1);assert.equal(visible.firstSeen[0].token,'id:7')
 assert.equal(visibleActivityDelta(delta,snapshot(0,[])).total,0)
 assert.equal(visibleActivityDelta(delta,snapshot(1,[group('id:7',1,658,658)],{complete:false})).firstSeen.length,0)
})

test('关注配置去重、有界并拒绝损坏或非精确协议条件，不修改输入',()=>{
 const zero={key:'id:0',token:'id:0',label:'Zero'}
 const value=[zero,zero,{key:'id:7',token:'id:70',label:'bad'},null,{key:'name:has space',token:'name:has space',label:'bad'}]
 assert.deepEqual(watchedChoices(value),[zero]);assert.equal(value.length,5)
 assert.equal(watchedChoices(Array.from({length:30},(_,i)=>({key:`id:${i}`,token:`id:${i}`,label:String(i)}))).length,24)
})
