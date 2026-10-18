import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'

// Use the deployed Nginx endpoint for every request, including writes.
export async function startupSmoke(baseURL, existing) {
  const { request } = await import('../frontend/node_modules/@playwright/test/index.mjs')
  const client = await request.newContext({ baseURL, timeout: 10_000 })
  try {
    const page = await client.get('/')
    assert.equal(page.status(), 200)
    const html = await page.text()
    const assets = [...html.matchAll(/(?:src|href)="(\/assets\/[^" ]+)"/g)]
    assert.ok(assets.length > 0, 'Production assets must be referenced')
    for (const [, asset] of assets) {
      const response = await client.get(asset)
      assert.equal(response.status(), 200, asset)
      assert.doesNotMatch(response.headers()['content-type'] || '', /text\/html/, asset)
    }
    assert.equal((await client.get('/api/auth/me')).status(), 401)
    const csrf = await (await client.get('/api/auth/csrf')).json()
    assert.equal((await client.post('/api/auth/login', {
      form: { username: 'admin', password: 'Demo-Only-2026!' },
      headers: { [csrf.headerName]: csrf.token },
    })).status(), 204)
    assert.equal((await (await client.get('/api/auth/me')).json()).username, 'admin')
    if (existing) {
      const response = await client.get(`/api/catalog/dictionaries/${existing.id}`)
      assert.equal(response.status(), 200)
      assert.deepEqual(await response.json(), existing)
      return existing
    }
    const token = await (await client.get('/api/auth/csrf')).json()
    const code = `STARTUP_${randomUUID()}`
    const response = await client.post('/api/admin/dictionaries', {
      data: { code, name: '容器持久化验收', kind: 'EVENT_CATEGORY', enabled: true },
      headers: { [token.headerName]: token.token },
    })
    assert.equal(response.status(), 201, await response.text())
    const created = await response.json()
    assert.equal(created.code, code)
    return created
  } finally {
    await client.dispose()
  }
}
