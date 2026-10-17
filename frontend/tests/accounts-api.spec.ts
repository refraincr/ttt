import { test, expect } from '@playwright/test'
import { signIn, write, uniqueName } from './support'

async function createUser(request: import('@playwright/test').APIRequestContext, overrides = {}) {
  const response = await write(request, 'POST', '/api/admin/users', {
    username: uniqueName('staff'), displayName: '权限测试员', password: 'Integration-Test-2026!',
    departmentId: 2, enabled: true, roles: ['DEPARTMENT_REPORTER'], ...overrides,
  })
  expect(response.status()).toBe(201)
  return response.json()
}

test('管理员新建部门和兼任账号，可通过新账号登录并读取自身部门', async ({ request, playwright, baseURL }) => {
  await signIn(request)
  const response = await write(request, 'POST', '/api/admin/departments', {
    name: uniqueName('测试部门'), departmentType: 'DEPARTMENT', enabled: true,
  })
  expect(response.status()).toBe(201)
  const department = await response.json()
  expect((await (await request.get('/api/admin/departments')).json()).some((item: { id: number }) => item.id === department.id)).toBe(true)
  const username = uniqueName('reporter')
  const created = await write(request, 'POST', '/api/admin/users', {
    username, displayName: '兼任测试员', password: 'Integration-Test-2026!', departmentId: department.id,
    roles: ['DEPARTMENT_REPORTER', 'DEPARTMENT_HANDLER', 'SPECIAL_REPORTER'], enabled: true,
  })
  expect(created.status()).toBe(201)
  expect(await created.json()).toMatchObject({ username, roles: ['DEPARTMENT_REPORTER', 'DEPARTMENT_HANDLER', 'SPECIAL_REPORTER'] })
  const user = await playwright.request.newContext({ baseURL })
  try {
    await signIn(user, username, 'Integration-Test-2026!')
    expect(await (await user.get('/api/auth/me')).json()).toMatchObject({ departmentId: department.id, roles: ['DEPARTMENT_REPORTER', 'DEPARTMENT_HANDLER', 'SPECIAL_REPORTER'] })
    expect(await (await user.get('/api/events')).json()).toMatchObject({ total: 0, items: [] })
  } finally { await user.dispose() }
})

test('账号改权、停用和密码重置立即使旧会话失效，旧版本不可覆盖', async ({ request, playwright, baseURL }) => {
  await signIn(request)
  let user = await createUser(request)
  const client = await playwright.request.newContext({ baseURL })
  try {
    await signIn(client, user.username, 'Integration-Test-2026!')
    const original = user
    const changed = await write(request, 'PUT', `/api/admin/users/${user.id}`, { ...user, roles: ['DEPARTMENT_HANDLER'] })
    expect(changed.status()).toBe(200)
    user = await changed.json()
    expect((await client.get('/api/auth/me')).status()).toBe(401)
    expect((await write(request, 'PUT', `/api/admin/users/${user.id}`, { ...original, displayName: '旧版本覆盖' })).status()).toBe(409)
    await signIn(client, user.username, 'Integration-Test-2026!')
    expect((await client.get('/api/events')).status()).toBe(403)
    const stopped = await write(request, 'PUT', `/api/admin/users/${user.id}`, { ...user, enabled: false })
    expect(stopped.status()).toBe(200)
    user = await stopped.json()
    expect((await client.get('/api/auth/me')).status()).toBe(401)
    const csrf = await (await client.get('/api/auth/csrf')).json()
    expect((await client.post('/api/auth/login', { form: { username: user.username, password: 'Integration-Test-2026!' }, headers: { [csrf.headerName]: csrf.token } })).status()).toBe(401)
    const reset = await write(request, 'PUT', `/api/admin/users/${user.id}`, { ...user, enabled: true, password: 'Changed-Password-2026!' })
    expect(reset.status()).toBe(200)
    expect(JSON.stringify(await reset.json())).not.toContain('Changed-Password')
    await signIn(client, user.username, 'Changed-Password-2026!')
  } finally { await client.dispose() }
})

test('越权管理、无部门特殊权限及混淆中心部门角色均被拒绝', async ({ request }) => {
  await signIn(request, 'civil')
  for (const path of ['/api/admin/users', '/api/admin/departments', '/api/admin/roles']) expect((await request.get(path)).status()).toBe(403)
  expect((await write(request, 'POST', '/api/admin/departments', { name: '越权', departmentType: 'DEPARTMENT', enabled: true })).status()).toBe(403)
  await signIn(request, 'center')
  expect((await request.get('/api/admin/users')).status()).toBe(403)
  await signIn(request)
  for (const overrides of [
    { departmentId: null, roles: ['SPECIAL_REPORTER', 'DEPARTMENT_REPORTER'] },
    { departmentId: 2, roles: ['SPECIAL_REPORTER'] },
    { departmentId: 2, roles: ['CENTER_ANALYST'] },
    { departmentId: 1, roles: ['DEPARTMENT_REPORTER'] },
    { departmentId: 2, roles: ['UNKNOWN'] },
  ]) {
    expect((await write(request, 'POST', '/api/admin/users', { username: uniqueName('invalid'), displayName: '无效测试', password: 'Integration-Test-2026!', enabled: true, ...overrides })).status()).toBe(400)
  }
})

test('停用部门使账号不可用，重启用不恢复原会话，部门版本冲突会拒绝', async ({ request, playwright, baseURL }) => {
  await signIn(request)
  let department = await (await write(request, 'POST', '/api/admin/departments', { name: uniqueName('停用测试'), departmentType: 'DEPARTMENT', enabled: true })).json()
  const user = await createUser(request, { departmentId: department.id })
  const client = await playwright.request.newContext({ baseURL })
  try {
    await signIn(client, user.username, 'Integration-Test-2026!')
    const old = department
    department = await (await write(request, 'PUT', `/api/admin/departments/${department.id}`, { ...department, enabled: false })).json()
    expect((await client.get('/api/events')).status()).toBe(401)
    expect((await write(request, 'PUT', `/api/admin/departments/${department.id}`, { ...old, name: '旧版本' })).status()).toBe(409)
    expect((await write(request, 'PUT', `/api/admin/departments/${department.id}`, { ...department, enabled: true })).status()).toBe(200)
    expect((await client.get('/api/events')).status()).toBe(401)
    await signIn(client, user.username, 'Integration-Test-2026!')
  } finally { await client.dispose() }
})

test('管理员可兼任研判，且不能停用最后一个可用管理员', async ({ request, playwright, baseURL }) => {
  await signIn(request)
  const client = await playwright.request.newContext({ baseURL })
  let user = await createUser(request, { departmentId: 1, roles: ['CENTER_ADMIN', 'CENTER_ANALYST'] })
  try {
    await signIn(client, user.username, 'Integration-Test-2026!')
    expect((await client.get('/api/admin/users')).status()).toBe(200)
    expect((await client.get('/api/events')).status()).toBe(200)
  } finally {
    await client.dispose()
    user = await (await write(request, 'PUT', `/api/admin/users/${user.id}`, { ...user, enabled: false })).json()
  }
  const admin = (await (await request.get('/api/admin/users')).json()).find((item: { username: string }) => item.username === 'admin')
  expect((await write(request, 'PUT', `/api/admin/users/${admin.id}`, { ...admin, enabled: false })).status()).toBe(409)
  expect((await request.get('/api/admin/users')).status()).toBe(200)
  const center = (await (await request.get('/api/admin/departments')).json()).find((item: { id: number }) => item.id === 1)
  expect((await write(request, 'PUT', '/api/admin/departments/1', { ...center, enabled: false })).status()).toBe(409)
})
