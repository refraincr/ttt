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
  const response = await request.get('/api/events')
  expect(response.status()).toBe(200)
  const page = await response.json()
  expect(page.total).toBe(3)
  expect(page.items.map((item: { departmentName: string }) => item.departmentName)).toEqual(['民政局', '公安局', '民政局'])
  expect(await (await request.get('/api/events/2')).json()).toMatchObject({ id: 2, departmentName: '公安局' })
})

test('普通部门无法通过参数、搜索或猜测编号访问其他部门', async ({ request }) => {
  await signIn(request, 'civil')
  const result = await (await request.get('/api/events?departmentId=3&all=true')).json()
  expect(result.total).toBe(2)
  expect(result.items.map((item: { id: number }) => item.id)).toEqual([3, 1])
  expect((await request.get('/api/events/2')).status()).toBe(404)
  expect((await request.get('/api/events/999999')).status()).toBe(404)
  expect(await (await request.get('/api/events?q=停车位')).json()).toMatchObject({ total: 0, items: [] })
  expect(await (await request.get('/api/events/1')).json()).toMatchObject({ id: 1, personName: '演示张某' })
})

test('处置角色也仅能读取本部门来源记录，响应不暴露敏感字段和内部路径', async ({ request }) => {
  await signIn(request, 'police')
  const result = await (await request.get('/api/events')).json()
  expect(result.total).toBe(1)
  expect(result.items[0]).toMatchObject({ id: 2, departmentName: '公安局' })
  const detail = await (await request.get('/api/events/2')).json()
  expect(Object.keys(detail).sort()).toEqual(['id', 'personName', 'eventTime', 'eventCategory', 'disputeType', 'eventDescription', 'eventSource', 'departmentName', 'isCompleted'].sort())
  expect((await request.get('/api/events/1')).status()).toBe(404)
})

test('管理员未兼任研判角色时无事件读取权限', async ({ request }) => {
  await signIn(request, 'admin')
  expect((await request.get('/api/events')).status()).toBe(403)
  expect((await request.get('/api/events/1')).status()).toBe(403)
})

test('分页和字面搜索正确，非法分页参数被拒绝', async ({ request }) => {
  await signIn(request, 'center')
  const first = await (await request.get('/api/events?page=1&size=1')).json()
  const second = await (await request.get('/api/events?page=2&size=1')).json()
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
