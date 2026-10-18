import { expect, type APIRequestContext } from '@playwright/test'
import { signIn, write } from './support'

// Only retire configuration IDs created by the caller's own test run.
export async function retireTestCatalog(request: APIRequestContext, sources: number[], dictionaries: number[]) {
  await signIn(request)
  for (const [type, ids] of [['sources', sources], ['dictionaries', dictionaries]] as const) {
    if (!ids.length) continue
    const response = await request.get(`/api/admin/${type}`)
    expect(response.ok()).toBeTruthy()
    const rows = await response.json()
    for (const row of rows.filter((item: { id: number; enabled: boolean }) => ids.includes(item.id) && item.enabled)) {
      const saved = await write(request, 'PUT', `/api/admin/${type}/${row.id}`, { ...row, enabled: false })
      expect(saved.ok(), await saved.text()).toBeTruthy()
    }
  }
}
