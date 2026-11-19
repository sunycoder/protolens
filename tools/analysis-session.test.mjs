import test from 'node:test';
import assert from 'node:assert/strict';
import { compareFields } from '../web/src/analysisWorkspace.ts';
import { patchQuery, validPlace, validQuery } from '../web/src/workspaceSession.ts';
import { defaultQuery } from '../web/src/workspaceInteractions.ts';

test('按业务键对齐不会将重排当修改，数字与字符串 ID 不混淆', () => {
  const a = [{id:1, value:10},{id:2, value:20}];
  assert.equal(compareFields(a,[a[1],a[0]],'id').changes.length,0);
  const result = compareFields(a,[{id:2,value:21},{id:1,value:10}],'id');
  assert.equal(result.changes.length,1);
  assert.match(result.changes[0].path,/id=number:2\/value/);
  assert.equal(compareFields([{id:1}], [{id:'1'}], 'id').changes.length,2);
});
test('重复与缺失业务键回退并告知；超大数组明确截断', () => {
  assert.ok(compareFields([{id:1},{id:1}], [{id:1},{id:2}], 'id').warnings.length);
  assert.ok(compareFields([{}], [{id:2}], 'id').warnings.length);
  assert.equal(compareFields(Array(20001).fill({id:1}),[],'id').truncated,true);
});
test('快捷筛选基于已生效结果，旧条件不被修改', () => {
  const shown = {...defaultQuery,q:'old'};
  const next = patchQuery(shown,{exclude:'id:12'});
  assert.equal(next.q,'old'); assert.equal(next.exclude,'id:12'); assert.equal(shown.exclude,'');
});
test('恢复位置拒绝损坏查询、负页码和非法编号', () => {
  const place={captureId:'test',query:{...defaultQuery},page:2,scrollTop:100,selectedIndex:0};
  assert.ok(validPlace(place));
  assert.equal(validPlace({...place,page:-1}),false);
  assert.equal(validPlace({...place,selectedIndex:NaN}),false);
  assert.equal(validQuery({...defaultQuery,hideHeartbeat:'yes'}),false);
  assert.equal(validQuery({...defaultQuery,order:'unknown'}),false);
});
