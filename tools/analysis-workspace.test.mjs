import test from 'node:test'
import assert from 'node:assert/strict'
import { findFieldMatches, compareFields, pageContaining, neighbourPage } from '../web/src/analysisWorkspace.ts'

test('定位页依据过滤后的实际条数，覆盖第一页、页尾和下一页首条', () => {
  assert.equal(pageContaining(1), 1)
  assert.equal(pageContaining(100), 1)
  assert.equal(pageContaining(101), 2)
  assert.equal(pageContaining(203), 3)
})
test('跨页阅读尊重列表方向，首尾边界不产生越界页', () => {
  assert.deepEqual(neighbourPage(2, 99, 100, 250, 1), {page: 3, position: 0})
  assert.deepEqual(neighbourPage(2, 0, 100, 250, -1), {page: 1, position: -1})
  assert.equal(neighbourPage(1, 0, 100, 250, -1), undefined)
  assert.equal(neighbourPage(3, 49, 50, 250, 1), undefined)
  assert.equal(neighbourPage(1, -1, 100, 250, 1), undefined)
})
test('字段搜索包含嵌套字段名、零值和数组后部；路径特殊字符不混淆', () => {
  const value = {'a/b': {'~flag': false}, items: Array.from({length: 130}, (_, i) => ({id: i}))}
  assert.deepEqual(findFieldMatches(value, 'false').matches, [{path:'/a~1b/~0flag', value:'false'}])
  assert.ok(findFieldMatches(value, '129').matches.some(m => m.path === '/items/129/id'))
  assert.equal(findFieldMatches({zero:0}, '0').matches[0].value, '0')
  assert.equal(findFieldMatches(value, '').matches.length, 0)
})
test('最新窗口不对齐整页时，向前阅读仍按真实 offset 定位', () => {
  assert.deepEqual(neighbourPage(1, 0, 100, 17104, -1, 100, 17004), {page:171,position:3})
  assert.equal(neighbourPage(1, 99, 100, 17104, 1, 100, 17004), undefined)
  assert.deepEqual(neighbourPage(1, 0, 100, 153, -1, 100, 53), {page:1,position:52})
})
test('字段对比区分新增、移除、null、空字符串及数组结构变化', () => {
  const result=compareFields({a:null,b:1,c:[0,1]}, {a:'',c:[0,2],d:false})
  assert.deepEqual(result.changes.map(c=>[c.path,c.kind]), [['/a','修改'],['/b','移除'],['/c/1','修改'],['/d','新增']])
  assert.equal(result.truncated, false)
  assert.equal(compareFields({x:[1]}, {x:[1]}).changes.length, 0)
  assert.equal(compareFields({x:[]}, {x:{}}).changes.length, 1)
})
test('大消息的搜索和对比有明确截断标记', () => {
  assert.equal(findFieldMatches(Array.from({length: 100}, () => 'hit'), 'hit').truncated, true)
  const before=Array.from({length:600},()=>0), after=Array.from({length:600},()=>1)
  assert.equal(compareFields(before,after).changes.length,500)
  assert.equal(compareFields(before,after).truncated,true)
})
