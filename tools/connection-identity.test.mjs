import test from 'node:test';
import assert from 'node:assert/strict';
import {connectionLabel} from '../web/src/connectionIdentity.ts';
import {changedCaptures} from '../web/src/workspaceRefresh.ts';

test('连接标题展示备注昵称区服和字符串角色ID，未知身份不冒充账号',()=>{
  assert.equal(connectionLabel({id:'abcdefgh-long',note:'主号',identity:{state:'identified',roleId:'9007199254740993',nickname:'张三',serverId:'138'}}),'主号 · 张三 · 138服 · 角色 9007199254740993 · 连接 abcdefgh');
  assert.match(connectionLabel({id:'a',note:'小号'}),/小号 · 未识别账号/);
  assert.match(connectionLabel({id:'a',identity:{state:'pending'}}),/登录中，待确认/);
});
test('只修改身份或备注也触发状态更新，不依赖新消息或自动切换连接',()=>{
  const a={captureId:'a',messages:1,connectionInfo:{id:'a',note:'主号'}};
  assert.deepEqual(changedCaptures([a],[{...a,connectionInfo:{id:'a',note:'小号'}}]),['a']);
  assert.deepEqual(changedCaptures([a],[a]),[]);
});
