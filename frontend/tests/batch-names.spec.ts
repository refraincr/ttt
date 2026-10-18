import { test, expect } from '@playwright/test'
import { signIn, write, uniqueName } from './support'
import { readFileSync } from 'node:fs'
import { retireTestCatalog } from './catalog-cleanup'

const testSources: number[] = []
const testDictionaries: number[] = []
test.afterEach(async ({ request }) => {
  await retireTestCatalog(request, testSources, testDictionaries)
  testSources.length = 0
  testDictionaries.length = 0
})

test('统一模板支持中文分类名称，错误日期与未知分类逐行反馈', async ({ request }) => {
  await signIn(request)
  const sourceResponse = await write(request, 'POST', '/api/admin/sources', {
    name: uniqueName('名称导入来源'), sourceType: 'DEPARTMENT', departmentId: 2, enabled: true,
  })
  expect(sourceResponse.ok()).toBeTruthy()
  const source = await sourceResponse.json()
  testSources.push(source.id)
  const categoryName = uniqueName('中文事件分类')
  const disputeName = uniqueName('中文纠纷类型')
  const ids: number[] = []
  for (const [kind, name] of [['EVENT_CATEGORY', categoryName], ['DISPUTE_TYPE', disputeName]]) {
    const response = await write(request, 'POST', '/api/admin/dictionaries', {
      name, kind, code: uniqueName('NAME'), enabled: true,
    })
    expect(response.ok()).toBeTruthy()
    const id = (await response.json()).id
    ids.push(id)
    testDictionaries.push(id)
  }
  await signIn(request, 'civil')
  const csrf = await (await request.get('/api/auth/csrf')).json()
  const csv = `姓名,身份证号,手机号,发生时间,问题描述,事件分类,纠纷类型\n名称验收甲,,,2026-10-18T10:00:00,${uniqueName('中文导入')}, ${categoryName} ,${disputeName}\n名称验收乙,,,2026-99-99T10:00:00,错误日期,${categoryName},${disputeName}\n名称验收丙,,,2026-10-18T10:00:00,错误分类,不存在的分类,${disputeName}\n名称验收丁,,,2026-10-18T10:00:00,错误类别,${disputeName},${categoryName}\n名称验收戊,,,2026-10-18T10:00:00,${uniqueName('编号兼容')},${ids[0]},${ids[1]}\n`
  const response = await request.post('/api/batches', {
    headers: { [csrf.headerName]: csrf.token },
    multipart: { sourceId: String(source.id), file: { name: '中文验收.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) } },
  })
  expect(response.ok()).toBeTruthy()
  const batch = await response.json()
  const original = await request.get(`/api/batches/${batch.id}/original`)
  expect(original.headers()['content-type']).toContain('text/csv')
  expect(original.headers()['content-disposition']).toContain("filename*=UTF-8''" + encodeURIComponent('中文验收.csv'))
  expect(await original.body()).toEqual(Buffer.from(csv))
  let rows: any[] = []
  await expect.poll(async () => {
    rows = await (await request.get(`/api/batches/${batch.id}`)).json()
    return rows.filter(row => row.state === 'PENDING').length
  }, { timeout: 20000 }).toBe(0)
  expect(rows.map(row => row.state)).toEqual(['SUCCESS', 'FAILED', 'FAILED', 'FAILED', 'SUCCESS'])
  expect(rows[1].error).toBe('发生时间格式无效')
  expect(rows[2].error).toContain('未知分类值')
  expect(rows[3].error).toContain('未知分类值')
  const record = await (await request.get(`/api/intake/records/${rows[0].record_id}`)).json()
  expect(record.event_category).toBe(categoryName)
  expect(record.dispute_type).toBe(disputeName)
})

test('Excel统一模板直接使用中文分类名称', async ({ request }) => {
  await signIn(request)
  const response = await write(request, 'POST', '/api/admin/sources', {
    name: uniqueName('Excel名称来源'), sourceType: 'DEPARTMENT', departmentId: 2, enabled: true,
  })
  expect(response.ok()).toBeTruthy()
  const source = await response.json()
  testSources.push(source.id)
  await signIn(request, 'civil')
  const csrf = await (await request.get('/api/auth/csrf')).json()
  const upload = await request.post('/api/batches', {
    headers: { [csrf.headerName]: csrf.token },
    multipart: { sourceId: String(source.id), file: {
      name: 'named-events.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: readFileSync(new URL('./fixtures/named-events.xlsx', import.meta.url)),
    } },
  })
  expect(upload.ok()).toBeTruthy()
  const batch = await upload.json()
  const original = await request.get(`/api/batches/${batch.id}/original`)
  expect(original.headers()['content-type']).toContain('spreadsheetml.sheet')
  expect(original.headers()['content-disposition']).toContain('named-events.xlsx')
  expect(await original.body()).toEqual(readFileSync(new URL('./fixtures/named-events.xlsx', import.meta.url)))
  let rows: any[] = []
  await expect.poll(async () => {
    rows = await (await request.get(`/api/batches/${batch.id}`)).json()
    return rows.filter(row => row.state === 'PENDING').length
  }, { timeout: 20000 }).toBe(0)
  expect(rows.map(row => row.state)).toEqual(['SUCCESS', 'FAILED'])
  expect(rows[1].error).toBe('发生时间格式无效')
})
