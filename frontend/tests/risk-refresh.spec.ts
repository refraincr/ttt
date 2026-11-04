import { test, expect, type Page } from '@playwright/test'
import { demoPassword } from './support'

const person = { id: 987654, name: '虚构研判人员', phone: '13800000000', idCard: '' }
const description = '核查用虚构来源描述。'.repeat(45)
const snapshot = {
  personId: person.id, effectiveCount: 1, sourceCount: 3, baseLevel: 'LOW', ruleLevel: 'MEDIUM',
  finalLevel: 'HIGH', manualLevel: 'HIGH', specialFloor: false, ruleVersion: 'INITIAL-1', lastEventTime: '2026-11-01T10:00:00',
  events: [{ id: 12, eventTime: '2026-11-01T10:00:00', sources: [{ recordId: 901, description, source: '虚构上报', tags: '扬言', specialReport: false }] }],
  keywordHits: [{ eventId: 12, ruleId: 1, ruleName: '单词：扬言', ruleVersion: 1, evidence: [{ recordId: 901, field: 'tags', text: '扬言', start: 0, end: 2 }] }],
}
const record = (id: number) => ({
  id, party_name: person.name, party_address: '某街道虚构联系地址'.repeat(30), problem_description: description,
  identity_review_state: 'CONFIRMED', duplicate_review_state: 'SUSPECTED', effectiveEventId: null,
  keyword_tags: '', version: 1, keywordHits: [], duplicateHistory: [],
})

async function open(page: Page) {
  await page.goto('/')
  await page.getByRole('textbox', { name: '账号', exact: true }).fill('center')
  await page.getByRole('textbox', { name: '密码', exact: true }).fill(demoPassword)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  if (page.viewportSize()!.width < 760) await page.getByRole('button', { name: '打开业务导航' }).click()
  await page.getByRole('button', { name: '归集与风险', exact: true }).click()
  await expect(page.getByRole('button', { name: '查询档案', exact: true })).toBeEnabled()
}

async function fixtures(page: Page) {
  await page.route('**/api/intake/people?*', route => route.fulfill({ json: [person] }))
  await page.route(`**/api/intake/people/${person.id}/risk`, route => route.fulfill({ json: snapshot }))
  await page.route('**/api/intake/duplicate-reviews', route => route.fulfill({ json: [{ id: 901, party_name: person.name, problem_description: description }] }))
  await page.route(/\/api\/intake\/records\/90\d(?:\/duplicates)?$/, route => {
    const path = new URL(route.request().url()).pathname
    return route.fulfill({ json: path.endsWith('/duplicates') ? [] : record(Number(path.split('/').pop())) })
  })
}

for (const [width, height] of [[1440, 900], [1280, 800], [390, 844]]) {
  test(`研判四个标签及人工调整在 ${width} 下可操作且长内容不溢出`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    await fixtures(page)
    await open(page)
    await page.getByRole('button', { name: '查看风险依据', exact: true }).click()
    await expect(page.locator('.risk-detail-heading .el-tag')).toHaveText('高风险')
    await expect(page.locator('.risk-metrics strong')).toHaveText(['1', '3', '1'])
    await expect(page.getByRole('cell', { name: '中风险', exact: true })).toBeVisible()
    await expect(page.locator('mark')).toHaveText('扬言')
    await page.locator('.risk-detail').scrollIntoViewIfNeeded()
    await page.screenshot({ path: `../docs/screenshots/ui-refresh/06-risk-${width}.png`, animations: 'disabled' })
    await page.getByRole('button', { name: /^有效事件：/ }).click()
    await page.getByRole('button', { name: /^来源记录：/ }).click()
    await expect(page.getByRole('heading', { name: `来源记录 #901 · ${person.name}`, exact: true })).toBeVisible()
    await page.screenshot({ path: `../docs/screenshots/ui-refresh/06-source-${width}.png`, animations: 'disabled' })
    await page.getByRole('button', { name: '提交重复审核', exact: true }).scrollIntoViewIfNeeded()
    await expect(page.getByRole('button', { name: '提交重复审核', exact: true })).toBeInViewport()
    await page.getByRole('button', { name: '保存关键词标签', exact: true }).scrollIntoViewIfNeeded()
    await expect(page.getByRole('button', { name: '保存关键词标签', exact: true })).toBeInViewport()
    for (const tab of ['人员风险档案', '重复审核池', '关键词规则', '来源记录核查']) {
      await page.getByRole('tab', { name: tab, exact: true }).click()
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    }
    if (width < 760) await page.getByRole('button', { name: '打开业务导航' }).click()
    await page.getByRole('button', { name: '规则与批量接入', exact: true }).click()
    await page.route(`**/api/risk-governance/people/${person.id}`, route => route.fulfill({ json: {
      snapshot, fingerprint: 'layout-only', hits: [], adjustments: [{ level: 'HIGH', active: true, reason: description, actor: 'center', created_at: '2026-11-01T10:00:00' }], history: [], exclusions: [],
    } }))
    await page.getByRole('tab', { name: '误命中与人工调整', exact: true }).click()
    await page.getByRole('spinbutton', { name: '风险人员编号', exact: true }).fill(String(person.id))
    await page.getByRole('button', { name: '查看调整依据', exact: true }).click()
    await expect(page.locator('.risk-adjustment-card .risk-metrics strong')).toHaveText(['中风险', '高风险', '高风险'])
    await page.getByRole('button', { name: '保存人工调整', exact: true }).scrollIntoViewIfNeeded()
    await expect(page.getByRole('button', { name: '保存人工调整', exact: true })).toBeInViewport()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: `../docs/screenshots/ui-refresh/06-adjustment-${width}.png`, animations: 'disabled' })
  })
}

test('查询重置、空态、失败重试及来源切换清除旧审核选择', async ({ page }) => {
  await fixtures(page)
  await open(page)
  await page.route('**/api/intake/people?*', route => route.fulfill({ json: new URL(route.request().url()).searchParams.get('q') ? [] : [person] }))
  await page.getByRole('textbox', { name: '筛选风险档案', exact: true }).fill('不存在')
  await page.getByRole('button', { name: '查询档案', exact: true }).click()
  await expect(page.getByText('暂无符合条件的人员档案', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '重置', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '筛选风险档案', exact: true })).toHaveValue('')
  await expect(page.getByRole('button', { name: '查看风险依据', exact: true })).toBeVisible()
  await page.getByRole('tab', { name: '来源记录核查', exact: true }).click()
  await expect(page.getByText('输入来源编号，或从风险证据、重复审核池进入核查')).toBeVisible()
  await page.getByRole('textbox', { name: '核查来源编号', exact: true }).fill('901')
  await page.getByRole('button', { name: '读取来源记录', exact: true }).click()
  await page.getByText('确认重复并归集', { exact: true }).click()
  await page.getByRole('spinbutton', { name: '归集目标来源编号', exact: true }).fill('123')
  await page.getByRole('textbox', { name: '重复审核理由', exact: true }).fill('旧审核理由')
  let release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/intake/records/902', async route => { await gate; await route.fulfill({ status: 503, json: { message: '核查服务暂不可用' } }) })
  await page.getByRole('textbox', { name: '核查来源编号', exact: true }).fill('902')
  await page.getByRole('button', { name: '读取来源记录', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('正在处理')
  await expect(page.getByRole('textbox', { name: '重复审核理由', exact: true })).toBeDisabled()
  release()
  await expect(page.locator('.el-alert--error')).toContainText('核查服务暂不可用')
  await expect(page.getByRole('textbox', { name: '重复审核理由', exact: true })).toHaveValue('旧审核理由')
  await page.unroute('**/api/intake/records/902')
  await page.getByRole('button', { name: '读取来源记录', exact: true }).click()
  await expect(page.getByRole('heading', { name: `来源记录 #902 · ${person.name}`, exact: true })).toBeVisible()
  await expect(page.getByRole('radio', { name: '确认独立有效', exact: true })).toBeChecked()
  await expect(page.getByRole('textbox', { name: '重复审核理由', exact: true })).toHaveValue('')
  await expect(page.getByRole('spinbutton', { name: '归集目标来源编号', exact: true })).toHaveCount(0)
})
