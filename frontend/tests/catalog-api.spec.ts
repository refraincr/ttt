import { test, expect } from '@playwright/test'
import { signIn, write, uniqueName } from './support'

test('管理员维护来源和分类，部门可查询启用项，停用后历史仍可解释', async ({ request, playwright, baseURL }) => {
  await signIn(request)
  const sourceResponse = await write(request, 'POST', '/api/admin/sources', { name: uniqueName('数据来源'), sourceType: 'DEPARTMENT', departmentId: 2, enabled: true })
  expect(sourceResponse.status()).toBe(201)
  const source = await sourceResponse.json()
  const dictionaryName = uniqueName('测试标准分类')
  const dictionaryResponse = await write(request, 'POST', '/api/admin/dictionaries', { code: uniqueName('CATEGORY'), name: dictionaryName, kind: 'EVENT_CATEGORY', enabled: true })
  expect(dictionaryResponse.status()).toBe(201)
  const dictionary = await dictionaryResponse.json()
  const department = await playwright.request.newContext({ baseURL })
  try {
    await signIn(department, 'civil')
    expect((await (await department.get('/api/options/sources')).json()).some((item: { id: number }) => item.id === source.id)).toBe(true)
    expect((await (await department.get('/api/options/dictionaries?kind=EVENT_CATEGORY')).json()).some((item: { id: number }) => item.id === dictionary.id)).toBe(true)
    expect((await write(request, 'PUT', `/api/admin/sources/${source.id}`, { ...source, enabled: false })).status()).toBe(200)
    expect((await write(request, 'PUT', `/api/admin/dictionaries/${dictionary.id}`, { ...dictionary, enabled: false })).status()).toBe(200)
    expect((await (await department.get('/api/options/sources')).json()).some((item: { id: number }) => item.id === source.id)).toBe(false)
    expect((await (await department.get('/api/options/dictionaries?kind=EVENT_CATEGORY')).json()).some((item: { id: number }) => item.id === dictionary.id)).toBe(false)
    expect(await (await department.get(`/api/catalog/sources/${source.id}`)).json()).toMatchObject({ name: source.name, enabled: false })
    expect(await (await department.get(`/api/catalog/dictionaries/${dictionary.id}`)).json()).toMatchObject({ name: dictionaryName, enabled: false })
    expect(await (await department.get('/api/events/1')).json()).toMatchObject({ eventSource: '部门上报', eventCategory: '民生诉求' })
  } finally { await department.dispose() }
})

test('来源按部门过滤，停用部门来源不可选，业务人员不能维护配置', async ({ request, playwright, baseURL }) => {
  await signIn(request)
  const source = await (await write(request, 'POST', '/api/admin/sources', { name: uniqueName('公安来源'), sourceType: 'EXTERNAL', departmentId: 3, enabled: true })).json()
  const civil = await playwright.request.newContext({ baseURL })
  try {
    await signIn(civil, 'civil')
    expect((await (await civil.get('/api/options/sources')).json()).some((item: { id: number }) => item.id === source.id)).toBe(false)
    expect((await civil.get(`/api/catalog/sources/${source.id}`)).status()).toBe(404)
    expect((await civil.get('/api/admin/sources')).status()).toBe(403)
    expect((await civil.get('/api/admin/dictionaries')).status()).toBe(403)
    expect((await write(civil, 'PUT', `/api/admin/sources/${source.id}`, { ...source, name: '越权修改' })).status()).toBe(403)
    expect((await write(civil, 'POST', '/api/admin/dictionaries', { code: 'DENIED', name: '越权', kind: 'EVENT_CATEGORY', enabled: true })).status()).toBe(403)
    await signIn(civil, 'center')
    expect((await civil.get('/api/admin/sources')).status()).toBe(403)
    expect((await (await civil.get('/api/options/sources')).json()).some((item: { id: number }) => item.id === source.id)).toBe(true)
  } finally { await civil.dispose() }
  let department = await (await write(request, 'POST', '/api/admin/departments', { name: uniqueName('来源停用部门'), departmentType: 'DEPARTMENT', enabled: true })).json()
  const scoped = await (await write(request, 'POST', '/api/admin/sources', { name: uniqueName('停用部门来源'), sourceType: 'OTHER', departmentId: department.id, enabled: true })).json()
  expect((await write(request, 'PUT', `/api/admin/departments/${department.id}`, { ...department, enabled: false })).status()).toBe(200)
  expect((await (await request.get('/api/options/sources')).json()).some((item: { id: number }) => item.id === scoped.id)).toBe(false)
})

test('配置版本冲突、重复编码和非法类别被拒绝，字典编码保持稳定', async ({ request }) => {
  await signIn(request)
  const source = await (await write(request, 'POST', '/api/admin/sources', { name: uniqueName('版本来源'), sourceType: 'OTHER', departmentId: null, enabled: true })).json()
  expect((await write(request, 'PUT', `/api/admin/sources/${source.id}`, { ...source, name: uniqueName('重命名来源') })).status()).toBe(200)
  expect((await write(request, 'PUT', `/api/admin/sources/${source.id}`, { ...source, enabled: false })).status()).toBe(409)
  const dictionary = await (await write(request, 'POST', '/api/admin/dictionaries', { name: uniqueName('纠纷测试'), code: uniqueName('DISPUTE'), kind: 'DISPUTE_TYPE', enabled: true })).json()
  expect((await write(request, 'PUT', `/api/admin/dictionaries/${dictionary.id}`, { ...dictionary, code: 'CHANGED' })).status()).toBe(400)
  expect((await write(request, 'POST', '/api/admin/dictionaries', { ...dictionary, name: uniqueName('重复编码') })).status()).toBe(409)
  expect((await write(request, 'PUT', `/api/admin/dictionaries/${dictionary.id}`, { ...dictionary, name: uniqueName('已更新') })).status()).toBe(200)
  expect((await write(request, 'PUT', `/api/admin/dictionaries/${dictionary.id}`, { ...dictionary, enabled: false })).status()).toBe(409)
  expect((await write(request, 'POST', '/api/admin/sources', { name: '无效来源', sourceType: 'INVALID', enabled: true })).status()).toBe(400)
  expect((await request.get('/api/options/dictionaries?kind=INVALID')).status()).toBe(400)
})

test('停用已有事件使用的来源和分类不改变事件历史文本', async ({ request }) => {
  await signIn(request)
  const source = (await (await request.get('/api/admin/sources')).json()).find((item: { name: string }) => item.name === '部门上报')
  const dictionary = (await (await request.get('/api/admin/dictionaries')).json()).find((item: { code: string }) => item.code === 'LIVELIHOOD')
  let nextSource = source
  let nextDictionary = dictionary
  try {
    nextSource = await (await write(request, 'PUT', `/api/admin/sources/${source.id}`, { ...source, enabled: false })).json()
    nextDictionary = await (await write(request, 'PUT', `/api/admin/dictionaries/${dictionary.id}`, { ...dictionary, enabled: false })).json()
    await signIn(request, 'civil')
    expect(await (await request.get('/api/events/1')).json()).toMatchObject({ eventSource: '部门上报', eventCategory: '民生诉求' })
    expect(await (await request.get(`/api/catalog/sources/${source.id}`)).json()).toMatchObject({ enabled: false, name: '部门上报' })
    expect(await (await request.get(`/api/catalog/dictionaries/${dictionary.id}`)).json()).toMatchObject({ enabled: false, name: '民生诉求' })
  } finally {
    await signIn(request)
    await write(request, 'PUT', `/api/admin/sources/${source.id}`, { ...nextSource, enabled: source.enabled })
    await write(request, 'PUT', `/api/admin/dictionaries/${dictionary.id}`, { ...nextDictionary, enabled: dictionary.enabled })
  }
})
