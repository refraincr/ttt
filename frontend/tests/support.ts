import { expect, type APIRequestContext } from '@playwright/test'

export const demoPassword = process.env.DEMO_PASSWORD || 'Demo-Only-2026!'
export async function signIn(request: APIRequestContext, username = 'admin', password = demoPassword) {
  const csrf = await (await request.get('/api/auth/csrf')).json()
  expect((await request.post('/api/auth/login', {
    form: { username, password }, headers: { [csrf.headerName]: csrf.token },
  })).status()).toBe(204)
}

export async function write(request: APIRequestContext, method: 'POST' | 'PUT', path: string, data: unknown) {
  const csrf = await (await request.get('/api/auth/csrf')).json()
  return request.fetch(path, { method, data, headers: { [csrf.headerName]: csrf.token } })
}

export function uniqueName(prefix: string) { return `${prefix}${Date.now()}${Math.floor(Math.random() * 100000)}` }
