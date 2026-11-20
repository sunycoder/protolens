import test from 'node:test'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
import {analysisParams,formatScalar,foldRows,repeatGroups,groupedMessageRows,recordGroups,exitTemporaryQuery,compareOperations} from '../web/src/analysisTools.ts'
import {defaultQuery} from '../web/src/workspaceInteractions.ts'
import {useResearchNotes} from '../web/src/useResearchNotes.ts'
import {reconcileRows} from '../web/src/workspaceRefresh.ts'
const require=createRequire(new URL('../web/package.json',import.meta.url))
const {ref}=require('vue')

test('字段格式由用户选择，原始大整数不经浮点转换，非法单位不伪装成日期',()=>{
 assert.equal(formatScalar('255','hex'),'0xFF')
 assert.equal(formatScalar('-16','hex'),'-0x10')
 assert.equal(formatScalar(1280,'bytes'),'1.25 KiB')
 assert.equal(formatScalar('0','unixs'),'1970-01-01T00:00:00.000Z（UTC）')
 assert.match(formatScalar(9007199254740992,'hex'),/安全整数/)
 assert.match(formatScalar('12.5','unixms'),/需要整数/)
 assert.equal(formatScalar('184400368043482','raw'),undefined)
})
test('重复折叠可展开且不改原数据、分页数量或其他连续组',()=>{
 const rows=[{index:1},{index:2,repeatOf:1},{index:3},{index:4,repeatOf:3}]
 assert.deepEqual(foldRows(rows,true,new Set()).map(row=>row.index),[1,3])
 assert.deepEqual(foldRows(rows,true,new Set([1])).map(row=>row.index),[1,2,3])
 assert.equal(repeatGroups(rows).get(1).length,2)
 assert.equal(foldRows(rows,false,new Set()),rows)
 assert.equal(rows.length,4)
})
test('同协议归组可以容纳不同内容和穿插协议，连接方向状态仍隔离，展开保持原顺序',()=>{
 const base={connection:'a',direction:'SC',protocolId:7,protocolName:'GcUpdate',status:'DECODED'}
 const rows=[{...base,index:10,preview:'id=1'},{...base,index:11,protocolId:8,protocolName:'GcOther'},{...base,index:12,preview:'id=2'},{...base,index:13,connection:'b'},{...base,index:14,direction:'CS'},{...base,index:15,status:'PARTIAL'}]
 const before=structuredClone(rows),grouped=groupedMessageRows(rows,'protocol')
 assert.equal(grouped[2].repeatOf,10)
 assert.equal(grouped[3].repeatOf,undefined);assert.equal(grouped[4].repeatOf,undefined);assert.equal(grouped[5].repeatOf,undefined)
 assert.deepEqual(foldRows(grouped,true,new Set()).map(row=>row.index),[10,11,13,14,15])
 assert.deepEqual(foldRows(grouped,true,new Set([10])).map(row=>row.index),rows.map(row=>row.index))
 assert.deepEqual(rows,before)
 assert.equal(groupedMessageRows(rows,'content'),rows)
 assert.equal(groupedMessageRows(rows,'none'),rows)
})
test('添加字段列或开启折叠后刷新必须发布新元数据，相同结果继续复用原行',()=>{
 const original={index:1,connection:'a',status:'DECODED',preview:'same'}
 const projected={...original,fieldValues:{'/amount':'0'},repeatOf:0}
 const result=reconcileRows([original],[projected])
 assert.equal(result[0].fieldValues['/amount'],'0')
 assert.equal(result[0].repeatOf,0)
 assert.equal(reconcileRows(result,[{...projected}]),result)
})
test('临时查看恢复原筛选但保留用户后来修改，分析区间采用当前可见范围',()=>{
 const original={...defaultQuery,exclude:'id:7',q:'amount'}
 const applied={...original,include:'id:9',range:'recent:10:20'}
 assert.deepEqual(exitTemporaryQuery({...applied,direction:'SC'},original,applied),{...original,direction:'SC'})
 const capture={firstIndex:0,lastIndex:99,marks:[]}
 const params=analysisParams(applied,capture,[{path:'/amount',label:'数量'}])
 assert.equal(params.get('from'),'10');assert.equal(params.get('to'),'20');assert.equal(params.get('hideHeartbeat'),'true')
 assert.equal(analysisParams(applied,capture,[],{from:30,to:40}).get('from'),'30')
})
test('相同 URL 与待确认身份不归组，已确认角色必须同区服同字典',()=>{
 const record=(id,state='identified',serverId='1',dictionary='a')=>({id,name:id,catalog:{sha256:dictionary},connections:[{endpoint:'https://game.example.test/websocket',identity:{state,roleId:'99',serverId,nickname:'角色'}}]})
 const groups=recordGroups([record('a'),record('b'),record('c','pending'),record('d','identified','2'),record('e','identified','1','b')])
 assert.equal(groups.length,4);assert.deepEqual(groups[0].items.map(item=>item.id),['a','b'])
})
test('两次操作按协议与方向对照次数，不通过顺序或相似名称自动配对',()=>{
 const a={groups:[{key:'7:GcUpdate:SC',protocolName:'GcUpdate',direction:'SC',count:3,firstIndex:1}]}
 const b={groups:[{key:'7:GcUpdate:SC',protocolName:'GcUpdate',direction:'SC',count:1,firstIndex:10},{key:'8:CgUpdate:CS',protocolName:'CgUpdate',direction:'CS',count:2,firstIndex:11}]}
 const result=compareOperations(a,b)
 assert.equal(result.length,2);assert.equal(result[0].before,3);assert.equal(result[0].after,1);assert.equal(result[1].before,0)
})
test('字段列按字典共享，批注按原记录隔离，损坏存储不污染消息',()=>{
 const descriptor=Object.getOwnPropertyDescriptor(globalThis,'localStorage'),data=new Map()
 Object.defineProperty(globalThis,'localStorage',{configurable:true,value:{getItem:key=>data.get(key)||null,setItem:(key,value)=>data.set(key,value)}})
 try{
  const capture=ref({id:'a',catalog:{sha256:'dict-a'}}),state=useResearchNotes(()=>capture.value)
  state.addColumn('/amount','数量');state.addColumn('/amount','不重复')
  state.saveNote({index:0,path:'/amount',kind:'推测',text:'待确认含义'})
  capture.value={id:'b',catalog:{sha256:'dict-a'}}
  assert.equal(state.columns.value.length,1);assert.equal(state.notes.value.length,0)
  capture.value={id:'a',catalog:{sha256:'dict-a'}}
  assert.equal(state.notes.value[0].kind,'推测')
  const restored=useResearchNotes(()=>capture.value)
  assert.equal(restored.notes.value[0].index,0)
  capture.value={id:'c',catalog:{sha256:'dict-b'}};assert.equal(state.columns.value.length,0)
  assert.throws(()=>state.addColumn('amount','bad'),/路径/)
 }finally{if(descriptor)Object.defineProperty(globalThis,'localStorage',descriptor);else delete globalThis.localStorage}
})
test('本机存储失败不能显示为批注保存成功，也不覆盖已保存的批注',()=>{
 const descriptor=Object.getOwnPropertyDescriptor(globalThis,'localStorage');let failure=false
 Object.defineProperty(globalThis,'localStorage',{configurable:true,value:{getItem:()=>null,setItem:()=>{if(failure)throw new Error('quota')}}})
 try{
  const state=useResearchNotes(()=>({id:'a',catalog:{sha256:'dict'}}))
  state.saveNote({index:1,path:'',kind:'备注',text:'原批注'})
  failure=true
  assert.throws(()=>state.saveNote({index:1,path:'',kind:'推测',text:'未保存修改'}),/未保存/)
  assert.equal(state.notes.value[0].text,'原批注')
 }finally{if(descriptor)Object.defineProperty(globalThis,'localStorage',descriptor);else delete globalThis.localStorage}
})
