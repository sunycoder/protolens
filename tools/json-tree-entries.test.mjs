import test from 'node:test'
import assert from 'node:assert/strict'
import {visibleJsonKeys} from '../web/src/jsonTreeEntries.ts'

test('百万项数组只枚举可见前百项，不枚举全部键或读取隐藏值',()=>{
  let enumerations=0, reads=0
  const value=new Proxy(new Array(1_000_000),{
    ownKeys(){enumerations++;throw new Error('禁止全量枚举大数组')},
    get(target,key,receiver){if(key!=='length') reads++;return Reflect.get(target,key,receiver)},
  })
  const keys=visibleJsonKeys(value,'/items',100)
  assert.equal(keys.length,100)
  assert.equal(keys[99],'99')
  assert.equal(enumerations,0)
  assert.equal(reads,0)
})

test('窗口外的搜索命中仍显示，嵌套路径、转义和重复命中均正确',()=>{
  const array=Array.from({length:1000},(_,i)=>({id:i}))
  const keys=visibleJsonKeys(array,'/items',2,['/items/999/id','/items/999','/items/21/id','/else/5'])
  assert.deepEqual(keys,['0','1','21','999'])
  const obj={'head':1,'a/b':2,'~key':3}
  assert.deepEqual(visibleJsonKeys(obj,'',1,['/a~1b/value','/~0key']),['head','a/b','~key'])
})

test('不存在的路径、原型字段和数组length不能成为展示项',()=>{
  const value=[1,2,3]
  assert.deepEqual(visibleJsonKeys(value,'',1,['/length','/999','/__proto__','/1/x']),['0','1'])
  assert.deepEqual(visibleJsonKeys({a:1},'',1,['/toString','/missing']),['a'])
})
