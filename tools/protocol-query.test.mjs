import test from 'node:test'
import assert from 'node:assert/strict'
import {definitionUrl, definitionPathRemainder, protocolMessageQuery} from '../web/src/protocolQuery.ts'

test('定义请求保留字典范围、版本、ID 0 和转义字符，不拼接任意路径', () => {
  const query = new URL(definitionUrl({captureId: 'capture', version: 'old'}, 'Ps &/?', 100, '/a~1b'), 'http://localhost').searchParams
  assert.equal(query.get('captureId'), 'capture')
  assert.equal(query.get('version'), 'old')
  assert.equal(query.get('key'), 'Ps &/?')
  assert.equal(query.get('fieldOffset'), '100')
  assert.equal(query.get('matchPath'), '/a~1b')
  assert.equal(new URL(definitionUrl({captureId:'', version:'v'}, '0'), 'http://localhost').searchParams.get('key'), '0')
})
test('字段定位区分定义容器路径和实际消息下标，支持嵌套 Map 和原始转义字段', () => {
  assert.equal(definitionPathRemainder('/heroes/[]', '/heroes/3/roleId'), '/roleId')
  assert.equal(definitionPathRemainder('/heroes/[]/roleId', '/heroes/3/roleId'), '')
  assert.equal(definitionPathRemainder('/mapping/{value}', '/mapping/$map/0/value/roleId'), '/roleId')
  assert.equal(definitionPathRemainder('/a~1b', '/a~1b/c'), '/c')
  assert.equal(definitionPathRemainder('/heroes/[]', '/heroes/no/id'), undefined)
  assert.equal(definitionPathRemainder('/mapping/{key}', '/mapping/$map/0/value/id'), undefined)
})
test('查看协议消息清除其他筛选，协议 0 保持为字符串；调用方可保留原阅读位置用于撤销', () => {
  const query = protocolMessageQuery(0)
  assert.equal(query.protocolId, '0')
  assert.equal(query.hideHeartbeat, false)
  for (const field of ['q', 'include', 'exclude', 'messageIndex', 'direction', 'status', 'range', 'connection']) assert.equal(query[field], '')
})
