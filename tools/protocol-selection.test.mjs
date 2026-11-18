import test from 'node:test'
import assert from 'node:assert/strict'
import {protocolChoice,mergeProtocolExclusions,toggleProtocolChoices} from '../web/src/protocolSelection.ts'

test('同协议多条消息按协议标识去重；协议0合法，缺少协议标识不可排除',()=>{
  assert.equal(protocolChoice({protocolId:301,protocolName:'GcMapArmyAppear'}).key,protocolChoice({protocolId:301,protocolName:'GcMapArmyAppear',index:99}).key)
  assert.equal(protocolChoice({protocolId:0}).token,'0')
  assert.equal(protocolChoice({}),undefined)
  assert.equal(protocolChoice({protocolName:'  '}),undefined)
  assert.notEqual(protocolChoice({protocolId:30}).key,protocolChoice({protocolId:301}).key)
})
test('批量排除保留已有条件，合并重复项，优先完整协议名避免数字前缀误匹配',()=>{
  const choices=[protocolChoice({protocolId:301,protocolName:'GcMapArmyAppear'}),protocolChoice({protocolId:301,protocolName:'GcMapArmyAppear'}),protocolChoice({protocolId:305,protocolName:'GcMapRouteDisappear'})]
  assert.equal(mergeProtocolExclusions('Heartbeat，GcMapArmyAppear 12',choices),'Heartbeat,GcMapArmyAppear,12,GcMapRouteDisappear')
  assert.equal(mergeProtocolExclusions('Heartbeat',[]),'Heartbeat')
  const first=mergeProtocolExclusions('Heartbeat',[choices[0]])
  assert.equal(mergeProtocolExclusions(first,[choices[2]]),'Heartbeat,GcMapArmyAppear,GcMapRouteDisappear')
})
test('选择跨页保留；同协议另一条消息取消该协议，原数组不被修改',()=>{
  const first=toggleProtocolChoices([],{protocolId:301,protocolName:'GcMapArmyAppear'})
  const second=toggleProtocolChoices(first,{protocolId:305,protocolName:'GcMapRouteDisappear'})
  assert.equal(first.length,1)
  assert.equal(second.length,2)
  const third=toggleProtocolChoices(second,{protocolId:301,protocolName:'GcMapArmyAppear'})
  assert.deepEqual(third.map(item=>item.key),['id:305'])
  assert.equal(toggleProtocolChoices(third,{}),third)
})
