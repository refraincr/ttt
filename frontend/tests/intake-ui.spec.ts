import { test, expect } from '@playwright/test'
import { demoPassword, signIn, write, uniqueName } from './support'
test('业务人员保存和重新打开草稿，中心可进入审核及筛查配置',async({page})=>{
  await page.goto('/')
  await page.getByRole('textbox',{name:'账号',exact:true}).fill('civil')
  await page.getByRole('textbox',{name:'密码',exact:true}).fill(demoPassword)
  await page.getByRole('button',{name:'登录',exact:true}).click()
  await page.getByRole('button',{name:'录入与身份处理',exact:true}).click()
  const name='页面草稿'+Date.now()
  await page.getByRole('textbox',{name:'主要当事人姓名',exact:true}).fill(name)
  await page.getByRole('textbox',{name:'联系地址',exact:true}).fill('测试县草稿地址9号')
  await page.getByRole('button',{name:'保存草稿',exact:true}).click()
  await expect(page.getByText('草稿已保存',{exact:true})).toBeVisible()
  await page.reload()
  await page.getByRole('button',{name:'录入与身份处理',exact:true}).click()
  const row=page.getByRole('row').filter({hasText:name})
  await row.getByRole('button',{name:'编辑草稿',exact:true}).click()
  await expect(page.getByRole('textbox',{name:'主要当事人姓名',exact:true})).toHaveValue(name)
  await expect(page.getByRole('textbox',{name:'联系地址',exact:true})).toHaveValue('测试县草稿地址9号')
  await row.getByRole('button',{name:'删除草稿',exact:true}).click()
  await expect(row).toHaveCount(0)
  await page.getByRole('button',{name:'退出登录'}).click()
  await page.getByRole('textbox',{name:'账号',exact:true}).fill('center')
  await page.getByRole('textbox',{name:'密码',exact:true}).fill(demoPassword)
  await page.getByRole('button',{name:'登录',exact:true}).click()
  await page.getByRole('button',{name:'录入与身份处理',exact:true}).click()
  await page.getByRole('tab',{name:'身份审核',exact:true}).click()
  await expect(page.getByRole('button',{name:'刷新待审',exact:true})).toBeVisible()
  await page.getByRole('tab',{name:'重复筛查配置',exact:true}).click()
  await expect(page.getByRole('button',{name:'保存筛查配置',exact:true})).toBeVisible()
})

test('中心通过身份审核受控建档，原始记录展示确认结果', async ({ page }) => {
  await signIn(page.request, 'civil')
  const options = async (path: string) => (await (await page.request.get('/api' + path)).json())[0].id
  const name = uniqueName('页面身份审核')
  const payload = {
    personName: name, idCard: '', phone: '', eventTime: '2026-10-18T09:00:00',
    description: '居民反映小区公共设施需要维修', address: '测试县居民小区',
    sourceId: await options('/options/sources'), categoryId: await options('/options/dictionaries?kind=EVENT_CATEGORY'),
    disputeId: await options('/options/dictionaries?kind=DISPUTE_TYPE'),
  }
  const draft = await (await write(page.request, 'POST', '/api/intake/drafts', payload)).json()
  const submitted = await write(page.request, 'POST', `/api/intake/drafts/${draft.id}/submit`, { version: draft.version })
  expect(submitted.status()).toBe(200)
  const record = await submitted.json()
  await signIn(page.request, 'center')
  // The queue exposes at most 100 records. Isolate this real fixture from older demo backlog;
  // confirmation and record lookup below still use the actual API and database.
  await page.route('**/api/intake/identity-reviews', async route => {
    const current = await (await page.request.get(`/api/intake/records/${record.id}`)).json()
    await route.fulfill({ json: current.identity_review_state === 'PENDING' ? [current] : [] })
  })
  await page.goto('/')
  await page.getByRole('button', { name: '录入与身份处理', exact: true }).click()
  await page.getByRole('tab', { name: '身份审核', exact: true }).click()
  await page.getByRole('row').filter({ hasText: name }).getByRole('button', { name: '审核身份' }).click()
  await page.getByRole('textbox', { name: '确认手机号', exact: true }).fill('139' + String(Date.now()).slice(-8))
  await page.getByRole('button', { name: '确认身份归属', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('审核理由')
  await page.getByRole('textbox', { name: '审核依据和理由', exact: true }).fill('已核对当事人提供的联系方式及来源信息')
  await page.getByRole('button', { name: '确认身份归属', exact: true }).click()
  await expect(page.getByText('身份审核已保存', { exact: true })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: name })).toHaveCount(0)
  await page.getByRole('tab', { name: '原始记录与重复候选', exact: true }).click()
  await page.getByRole('textbox', { name: '来源记录编号', exact: true }).fill(String(record.id))
  await page.getByRole('button', { name: '查询记录', exact: true }).click()
  await expect(page.getByText('身份已确认', { exact: true })).toBeVisible()
  await expect(page.locator('.record-original')).toContainText(name)
  await expect(page.locator('.record-fields')).toContainText(payload.address)
})

test('窄屏部门录入保留草稿操作，加载期间禁止重复保存，失败后可重试', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page.request, 'civil')
  await page.goto('/')
  await page.getByRole('button', { name: '打开业务导航', exact: true }).click()
  await page.getByRole('button', { name: '录入与身份处理', exact: true }).click()
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeEnabled()
  await expect(page.getByRole('tab', { name: '身份审核', exact: true })).toHaveCount(0)
  await expect(page.getByRole('combobox', { name: '代录部门', exact: true })).toHaveCount(0)
  const name = uniqueName('窄屏草稿')
  await page.getByRole('textbox', { name: '主要当事人姓名', exact: true }).fill(name)
  let writes = 0
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/intake/drafts', async route => {
    if (route.request().method() !== 'POST') return route.continue()
    writes++
    await gate
    await route.fulfill({ status: 503, json: { message: '服务暂不可用，请重试' } })
  })
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '提交事件', exact: true })).toBeDisabled()
  release()
  await expect(page.getByRole('alert')).toContainText('服务暂不可用')
  expect(writes).toBe(1)
  await expect(page.getByRole('textbox', { name: '主要当事人姓名', exact: true })).toHaveValue(name)
  await page.unroute('**/api/intake/drafts')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect(page.getByText('草稿已保存', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.getByRole('row').filter({ hasText: name }).getByRole('button', { name: '删除草稿' }).click()
  await expect(page.getByRole('row').filter({ hasText: name })).toHaveCount(0)
})

test('提交成功独立于重复候选查询，查询失败后不会恢复已提交草稿', async ({ page }) => {
  await signIn(page.request, 'center')
  const options = async (path: string) => (await (await page.request.get('/api' + path)).json())
  const name = uniqueName('提交状态验收')
  const draft = await (await write(page.request, 'POST', '/api/intake/drafts', {
    personName: name, eventTime: '2026-10-18T09:00:00', description: '虚构验收：居民申请公共设施维修',
    departmentId: (await options('/intake/departments'))[0].id,
    sourceId: (await options('/options/sources')).find((s: { departmentId: number | null }) => s.departmentId === null).id,
    categoryId: (await options('/options/dictionaries?kind=EVENT_CATEGORY'))[0].id,
    disputeId: (await options('/options/dictionaries?kind=DISPUTE_TYPE'))[0].id,
  })).json()
  await page.route('**/api/intake/records/*/duplicates', route => route.fulfill({ status: 503, json: { message: '候选查询暂不可用' } }))
  await page.goto('/')
  await page.getByRole('button', { name: '录入与身份处理', exact: true }).click()
  const row = page.getByRole('row').filter({ hasText: name })
  await row.getByRole('button', { name: '编辑草稿', exact: true }).click()
  await expect(page.getByRole('heading', { name: `编辑草稿 #${draft.id}`, exact: true })).toBeVisible()
  await page.getByRole('button', { name: '提交事件', exact: true }).click()
  await expect(page.getByText('提交成功，已进入身份匹配与重复筛查流程', { exact: true })).toBeVisible()
  await expect(row).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: '主要当事人姓名', exact: true })).toHaveValue('')
  await page.getByRole('tab', { name: '原始记录与重复候选', exact: true }).click()
  await expect(page.getByText('请点击查询记录，加载当前重复候选。', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '查询记录', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('候选查询暂不可用')
  await page.getByRole('tab', { name: '事件录入与草稿', exact: true }).click()
  await expect(page.getByRole('heading', { name: '单条事件录入', exact: true })).toBeVisible()
  await expect(page.getByRole('textbox', { name: '主要当事人姓名', exact: true })).toHaveValue('')
})
