import { test, expect } from '@playwright/test'
import { signIn, write } from './support'

test('提交合并描述中的横向空白，保留段落与原始提交内容', async ({ request }) => {
  await signIn(request, 'civil')
  const options = async (path: string) => (await (await request.get(path)).json())[0].id
  const description = '  虚构案例：居民反映   路灯损坏。\n第二段\t\t保留换行。\n\n全角　　空格。  '
  const entry = {
    personName: '空格清洗回归验证', idCard: '', phone: '',
    eventTime: '2026-10-18T10:00:00', description,
    sourceId: await options('/api/options/sources'),
    categoryId: await options('/api/options/dictionaries?kind=EVENT_CATEGORY'),
    disputeId: await options('/api/options/dictionaries?kind=DISPUTE_TYPE'),
  }
  const saved = await write(request, 'POST', '/api/intake/drafts', entry)
  expect(saved.ok()).toBeTruthy()
  const draft = await saved.json()
  expect(draft.payload.description).toBe(description)
  const submitted = await write(request, 'POST', `/api/intake/drafts/${draft.id}/submit`, { version: draft.version })
  expect(submitted.ok(), await submitted.text()).toBeTruthy()
  const record = await submitted.json()
  const stored = await (await request.get(`/api/intake/records/${record.id}`)).json()
  expect(stored.problem_description).toBe('虚构案例:居民反映 路灯损坏。\n第二段 保留换行。\n\n全角 空格。')
  const original = typeof stored.original_payload === 'string' ? JSON.parse(stored.original_payload) : stored.original_payload
  expect(original.description).toBe(description)
})
