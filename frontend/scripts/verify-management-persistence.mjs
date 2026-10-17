import { request } from '@playwright/test'
import assert from 'node:assert/strict'
import { readFile, writeFile, mkdir } from 'node:fs/promises'

// 通过公开 API 保存验收对象；在服务/数据库重启后复核同一组对象。
const client = await request.newContext({ baseURL: process.env.BASE_URL || 'http://127.0.0.1:5173' })
const filename = new URL('../../.local/management-persistence.json', import.meta.url)
async function write(method, path, data) {
  const csrf = await (await client.get('/api/auth/csrf')).json()
  const response = await client.fetch(path, { method, data, headers: { [csrf.headerName]: csrf.token } })
  assert.ok(response.ok(), `${method} ${path}: ${response.status()}`)
  return response.json()
}
try {
  const csrf = await (await client.get('/api/auth/csrf')).json()
  assert.equal((await client.post('/api/auth/login', {
    form: { username: 'admin', password: process.env.DEMO_PASSWORD || 'Demo-Only-2026!' }, headers: { [csrf.headerName]: csrf.token },
  })).status(), 204)
  if (process.argv[2] === 'create') {
    const suffix = Date.now()
    const department = await write('POST', '/api/admin/departments', { name: `持久化验收${suffix}`, departmentType: 'DEPARTMENT', enabled: true })
    const user = await write('POST', '/api/admin/users', { username: `persist${suffix}`, displayName: '持久化验收员', departmentId: department.id, roles: ['DEPARTMENT_REPORTER', 'DEPARTMENT_HANDLER'], password: 'Persistence-Test-2026!', enabled: true })
    const source = await write('POST', '/api/admin/sources', { name: `持久化来源${suffix}`, sourceType: 'EXTERNAL', departmentId: department.id, enabled: false })
    const dictionary = await write('POST', '/api/admin/dictionaries', { code: `PERSIST${suffix}`, name: `持久化分类${suffix}`, kind: 'DISPUTE_TYPE', enabled: false })
    await mkdir(new URL('../../.local/', import.meta.url), { recursive: true })
    await writeFile(filename, JSON.stringify({ department, user, source, dictionary }, null, 2))
    process.stdout.write('已通过 API 创建并记录部门、兼任账号、停用来源和分类。\n')
  } else {
    const saved = JSON.parse(await readFile(filename, 'utf8'))
    for (const [key, path] of Object.entries({ department: 'departments', user: 'users', source: 'sources', dictionary: 'dictionaries' })) {
      const response = await client.get(`/api/admin/${path}`)
      assert.equal(response.status(), 200)
      const found = (await response.json()).find(item => item.id === saved[key].id)
      assert.deepEqual(found, saved[key])
    }
    const sources = await (await client.get('/api/options/sources')).json()
    const dictionaries = await (await client.get('/api/options/dictionaries?kind=DISPUTE_TYPE')).json()
    assert.ok(!sources.some(item => item.id === saved.source.id))
    assert.ok(!dictionaries.some(item => item.id === saved.dictionary.id))
    const nextCsrf = await (await client.get('/api/auth/csrf')).json()
    assert.equal((await client.post('/api/auth/login', { form: { username: saved.user.username, password: 'Persistence-Test-2026!' }, headers: { [nextCsrf.headerName]: nextCsrf.token } })).status(), 204)
    assert.equal((await client.get('/api/events')).status(), 200)
    process.stdout.write('重启后部门、账号、角色、来源、分类及停用状态全部保留，账号密码仍可登录。\n')
  }
} finally { await client.dispose() }
