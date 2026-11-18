import test from 'node:test'
import assert from 'node:assert/strict'
import { changedCaptures, shouldCountUnread, shouldRefreshMessages, reconcileRows, RequestGate } from '../web/src/workspaceRefresh.ts'

const progress = (id, messages) => ({ captureId: id, messages, frames: messages, failed: 0, connection: id, endpoint: 'local' })
test('查看 B 时 A 新消息不能触发 B 查询或导航；心跳不触发消息查询', () => {
  const changes = changedCaptures([progress('A', 1), progress('B', 2)], [progress('A', 2), progress('B', 2)])
  assert.deepEqual(changes, ['A'])
  assert.equal(shouldRefreshMessages('B', changes, true, false), false)
  assert.equal(shouldRefreshMessages('A', changes, true, false), true)
  assert.deepEqual(changedCaptures([progress('A', 2)], [progress('A', 2)]), [])
})
test('暂停跟随或翻阅旧页时，即使当前记录更新也不改变列表', () => {
  assert.equal(shouldRefreshMessages('B', ['B'], false, false), false)
  assert.equal(shouldRefreshMessages('B', ['B'], true, true), false)
  assert.equal(shouldRefreshMessages(undefined, ['B'], true, false), false)
})
test('已有行与当前选择保持引用，新增行才追加；相同结果不替换整个数组', () => {
  const row = { index: 4, connection: 'B', time: 'fixed', status: 'DECODED', protocolId: 2364 }
  const original = [row]
  assert.equal(reconcileRows(original, [{ ...row }]), original)
  const next = reconcileRows(original, [{ ...row }, { ...row, index: 5 }])
  assert.equal(next[0], row)
  assert.equal(next.length, 2)
  assert.notEqual(reconcileRows(original, [{ ...row, status: 'FAILED' }])[0], row)
})
test('手动选择 B 后迟到的 A 响应必须失效，旧网络请求被取消', async () => {
  const gate = new RequestGate()
  const a = gate.begin()
  let resolveA
  const result = new Promise(resolve => { resolveA = resolve })
  const b = gate.begin()
  assert.equal(a.signal.aborted, true)
  resolveA('A')
  let chosen = 'B'
  if (a.current()) chosen = await result
  assert.equal(chosen, 'B')
  assert.equal(b.current(), true)
  gate.cancel()
  assert.equal(b.current(), false)
})

test('跟随当前记录不会产生瞬时未读按钮；暂停、历史页和其他记录仍保留未读提示', () => {
  assert.equal(shouldCountUnread('A', 'A', true, false), false)
  assert.equal(shouldCountUnread('A', 'B', true, false), true)
  assert.equal(shouldCountUnread('A', 'A', false, false), true)
  assert.equal(shouldCountUnread('A', 'A', true, true), true)
})

test('清空版本变化也触发刷新，即使累计消息数量不变', () => {
  assert.deepEqual(changedCaptures([{...progress('A', 10), clearVersion: 0}], [{...progress('A', 10), clearVersion: 1}]), ['A'])
  assert.deepEqual(changedCaptures([{...progress('A', 10), clearVersion: 1}], [{...progress('A', 10), clearVersion: 1}]), [])
})
