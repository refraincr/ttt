import { test, expect, type APIRequestContext } from '@playwright/test'

const password = process.env.DEMO_PASSWORD || 'Demo-Only-2026!'

async function login(request: APIRequestContext, username: string) {
  const csrf = await (await request.get('/api/auth/csrf')).json()
  return request.post('/api/auth/login', {
    form: { username, password }, headers: { [csrf.headerName]: csrf.token },
  })
}

test('未登录的列表、详情和当前账号均不可访问', async ({ request }) => {
  for (const path of ['/api/events', '/api/events/1', '/api/auth/me']) {
    expect((await request.get(path)).status()).toBe(401)
  }
})

test('有效账号可登录并获取自身身份，退出后旧会话失效', async ({ request }) => {
  expect((await login(request, 'center')).status()).toBe(204)
  expect(await (await request.get('/api/auth/me')).json()).toMatchObject({ username: 'center', roles: ['CENTER_ANALYST'] })
  const csrf = await (await request.get('/api/auth/csrf')).json()
  expect((await request.post('/api/auth/logout', { headers: { [csrf.headerName]: csrf.token } })).status()).toBe(204)
  expect((await request.get('/api/auth/me')).status()).toBe(401)
  expect((await request.get('/api/events')).status()).toBe(401)
  expect((await request.get('/api/events/1')).status()).toBe(401)
})

test('错误密码及停用账号不可登录，登录和退出均校验 CSRF', async ({ request }) => {
  expect((await request.post('/api/auth/login', { form: { username: 'center', password } })).status()).toBe(403)
  const csrf = await (await request.get('/api/auth/csrf')).json()
  expect((await request.post('/api/auth/login', {
    form: { username: 'center', password: 'wrong-password' }, headers: { [csrf.headerName]: csrf.token },
  })).status()).toBe(401)
  expect((await login(request, 'disabled')).status()).toBe(401)
  expect((await login(request, 'center')).status()).toBe(204)
  expect((await request.post('/api/auth/logout')).status()).toBe(403)
  expect((await request.get('/api/auth/me')).status()).toBe(200)
})
