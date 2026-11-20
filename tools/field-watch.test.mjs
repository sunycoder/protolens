import test from 'node:test'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
import {useFieldWatch} from '../web/src/useFieldWatch.ts'
const require=createRequire(new URL('../web/package.json',import.meta.url))
const {effectScope,ref,nextTick}=require('vue')
const wait=()=>new Promise(resolve=>setTimeout(resolve,300))

test('字段关注限定原连接方向协议，识别改后恢复，切换记录后不继承旧关注',async()=>{
 const originalFetch=globalThis.fetch,scope=effectScope(),urls=[]
 const capture=ref({id:'a'}),index=ref(0)
 let field={path:'/amount',first:'0',last:'0',lastIndex:0,changed:false,changes:0,signature:'zero'}
 globalThis.fetch=async url=>{urls.push(url);return {ok:true,json:async()=>({fields:[field],missingBefore:false,truncated:false})}}
 try{
  const state=scope.run(()=>useFieldWatch(()=>capture.value,()=>index.value,()=>true))
  const packet={index:0,protocolId:0,protocolName:'CgZero',connection:'connection-a',direction:'CS',body:{amount:0}}
  state.add({path:'/amount',label:'数量'},packet)
  await wait();assert.equal(state.items.value[0].changed,false)
  assert.match(urls[0],/protocolId=0/);assert.match(urls[0],/connection=connection-a/);assert.match(urls[0],/direction=CS/)
  field={...field,lastIndex:2,changes:2}
  index.value=2;await nextTick();await wait()
  assert.equal(state.items.value[0].changed,true)
  assert.equal(state.items.value[0].last,'0')
  assert.equal(state.items.value[0].index,2)
  assert.throws(()=>state.add({path:'/missing',label:'缺失'},packet),/不存在/)
  capture.value={id:'b'};await nextTick();assert.equal(state.items.value.length,0)
 }finally{scope.stop();globalThis.fetch=originalFetch}
})
