import test from 'node:test'
import assert from 'node:assert/strict'
import {defaultQuery,defaultColumns,chooseDetailLayout,listMinimumWidth,readColumns,contextQuery,contextRankParams,allowReadingShortcut,ResultPublication} from '../web/src/workspaceInteractions.ts'

test('笔记本及展开侧栏时按实际数据区域选择布局，短窗口不强占列表高度',()=>{
  const min=listMinimumWidth(defaultColumns)
  assert.equal(chooseDetailLayout(1366,570,'auto',min),'right')
  assert.equal(chooseDetailLayout(1116,570,'auto',min),'overlay')
  assert.equal(chooseDetailLayout(900,700,'auto',min),'bottom')
  assert.equal(chooseDetailLayout(900,500,'bottom',min),'overlay')
  assert.equal(chooseDetailLayout(900,700,'right',min),'overlay')
  assert.equal(chooseDetailLayout(1900,700,'bottom',min),'bottom')
})
test('显示可选列后重新核算宽度；损坏偏好回退默认值',()=>{
  assert.ok(listMinimumWidth({...defaultColumns,bytes:true,connection:true})>listMinimumWidth(defaultColumns))
  assert.deepEqual(readColumns('broken'),defaultColumns)
  assert.deepEqual(readColumns('{"bytes":true,"time":"false","unknown":true}'),{...defaultColumns,bytes:true})
})
test('上下文保留排序和心跳条件，升序数目标前方，降序数目标后方',()=>{
  const q=contextQuery({...defaultQuery,q:'hit',order:'desc',protocolId:'12'},'C')
  assert.equal(q.order,'desc');assert.equal(q.q,'');assert.equal(q.protocolId,'');assert.equal(q.connection,'C')
  assert.equal(contextRankParams(q,387).get('from'),'387')
  assert.equal(contextRankParams(q,387).get('to'),null)
  assert.equal(contextRankParams({...q,order:'asc'},387).get('to'),'387')
})
test('失败不发布新条件；取消及迟到请求不能覆盖最近成功结果',()=>{
  const state=new ResultPublication()
  assert.equal(state.commit(state.begin(),{query:'A',count:2,rows:[1,2]}),true)
  const failed=state.begin()
  assert.equal(state.value.query,'A')
  const latest=state.begin()
  assert.equal(state.commit(latest,{query:'B',count:1,rows:[3]}),true)
  assert.equal(state.commit(failed,{query:'bad',count:0,rows:[]}),false)
  const cancelled=state.begin();state.cancel()
  assert.equal(state.commit(cancelled,{query:'bad',count:0,rows:[]}),false)
  assert.equal(state.value.query,'B')
})
test('阅读快捷键不抢输入、弹窗、分隔条或带修饰键的系统操作',()=>{
  assert.equal(allowReadingShortcut('ArrowDown',false,false,false),true)
  assert.equal(allowReadingShortcut('Escape',false,false,false),true)
  assert.equal(allowReadingShortcut('ArrowDown',true,false,false),false)
  assert.equal(allowReadingShortcut('Escape',false,true,false),false)
  assert.equal(allowReadingShortcut('ArrowUp',false,false,true),false)
})
