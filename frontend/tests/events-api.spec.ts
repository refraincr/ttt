import { test, expect, type APIRequestContext } from '@playwright/test'

async function signIn(request: APIRequestContext, username: string) {
  const csrf = await (await request.get('/api/auth/csrf')).json()
  expect((await request.post('/api/auth/login', {
    form: { username, password: process.env.DEMO_PASSWORD || 'Demo-Only-2026!' },
    headers: { [csrf.headerName]: csrf.token },
  })).status()).toBe(204)
}

test('中心研判员可查看全部门列表及详情', async ({ request }) => {
  await signIn(request, 'center')
  const response = await request.get('/api/events?q=演示')
  expect(response.status()).toBe(200)
  const page = await response.json()
  expect(page.total).toBe(3)
  expect(page.items.map((item: { departmentName: string }) => item.departmentName)).toEqual(['民政局', '公安局', '民政局'])
  expect(await (await request.get('/api/events/2')).json()).toMatchObject({ id: 2, departmentName: '公安局' })
})

test('普通部门无法通过参数、搜索或猜测编号访问其他部门', async ({ request }) => {
  await signIn(request, 'civil')
  const result = await (await request.get('/api/events?q=演示&departmentId=3&all=true')).json()
  expect(result.total).toBe(2)
  expect(result.items.map((item: { id: number }) => item.id)).toEqual([3, 1])
  expect((await request.get('/api/events/2')).status()).toBe(404)
  expect((await request.get('/api/events/999999')).status()).toBe(404)
  expect(await (await request.get('/api/events?q=停车位')).json()).toMatchObject({ total: 0, items: [] })
  expect(await (await request.get('/api/events/1')).json()).toMatchObject({ id: 1, personName: '演示张某' })
})

test('单独处置角色不能读取来源事件列表或详情', async ({ request }) => {
  await signIn(request, 'police')
  expect((await request.get('/api/events?q=演示')).status()).toBe(403)
  expect((await request.get('/api/events/2')).status()).toBe(403)
  expect((await request.get('/api/events/1')).status()).toBe(403)
})

test('读取响应仅包含授权展示字段，不暴露内部路径', async ({ request }) => {
  await signIn(request, 'center')
  const detail = await (await request.get('/api/events/2')).json()
  expect(Object.keys(detail).sort()).toEqual(['id', 'personName', 'eventTime', 'eventCategory', 'disputeType', 'eventDescription', 'eventSource', 'departmentName', 'isCompleted', 'idCard', 'phone', 'address', 'keywords'].sort())
})

test('管理员未兼任研判角色时无事件读取权限', async ({ request }) => {
  await signIn(request, 'admin')
  expect((await request.get('/api/events?q=演示')).status()).toBe(403)
  expect((await request.get('/api/events/1')).status()).toBe(403)
})

test('分页和字面搜索正确，非法分页参数被拒绝', async ({ request }) => {
  await signIn(request, 'center')
  const first = await (await request.get('/api/events?q=演示&page=1&size=1')).json()
  const second = await (await request.get('/api/events?q=演示&page=2&size=1')).json()
  expect(first.total).toBe(3)
  expect(first.items.map((item: { id: number }) => item.id)).toEqual([3])
  expect(second.items.map((item: { id: number }) => item.id)).toEqual([2])
  expect(await (await request.get('/api/events?q=张某')).json()).toMatchObject({ total: 1, items: [{ id: 1 }] })
  expect(await (await request.get('/api/events?q=%25')).json()).toMatchObject({ total: 0, items: [] })
  expect(await (await request.get('/api/events?q=%27%20OR%201%3D1--')).json()).toMatchObject({ total: 0, items: [] })
  for (const suffix of ['page=0', 'size=101', 'page=nope', `q=${'a'.repeat(101)}`]) {
    expect((await request.get(`/api/events?${suffix}`)).status()).toBe(400)
  }
})

test('上传时间与部门关键词组合筛选，保持字面匹配和部门权限', async ({ request }) => {
  await signIn(request, 'center')
  const {setupCases,riskRecord} = await import('./cases-support')
  const options = await setupCases(request)
  const record = await riskRecord(request, options, {eventTime:'2001-01-01T10:00:00'})
  await signIn(request, 'center')
  const query = new URLSearchParams({q:record.party_name,department:'民政',from:'2000-01-01T00:00:00',to:'2099-12-31T23:59:59',size:'1'})
  const result = await (await request.get('/api/events?'+query)).json()
  expect(result).toMatchObject({total:1,items:[{id:record.id,departmentName:'民政局'}]})
  query.set('to','2002-01-01T00:00:00')
  expect(await (await request.get('/api/events?'+query)).json()).toMatchObject({total:0,items:[]})
  query.delete('from');query.delete('to')
  for (const department of ['公安', '%', "' OR 1=1--"]) {
    query.set('department',department)
    expect(await (await request.get('/api/events?'+query)).json()).toMatchObject({total:0,items:[]})
  }
  await signIn(request,'town');query.set('department','民政')
  expect(await (await request.get('/api/events?'+query)).json()).toMatchObject({total:0,items:[]})
  await signIn(request,'civil')
  expect(await (await request.get('/api/events?'+query)).json()).toMatchObject({total:1})
  for (const params of [{from:'invalid'},{to:'2026-13-01T00:00:00'},{from:'2026-02-01T00:00:00',to:'2026-01-01T00:00:00'},{department:'a'.repeat(101)}]) {
    expect((await request.get('/api/events?'+new URLSearchParams(params))).status()).toBe(400)
  }
})
