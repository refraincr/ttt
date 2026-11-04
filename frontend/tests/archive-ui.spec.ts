import { test, expect, type Page } from '@playwright/test'
import { demoPassword } from './support'

const longText = '核实用虚构描述，保留来源与变更依据。'.repeat(35)
const person = { id: 991, name: '虚构档案人员', id_card: '', phone: '13800000000', version: 2 }
const record = { id: 901, version: 3, party_name: person.name, party_phone: person.phone, occurred_at: '2026-11-01T10:00:00', problem_description: longText }
const change = { id: 801, record_id: record.id, kind: 'CORRECT', state: 'PENDING', reason: longText, requester: '虚构上报人员', created_at: '2026-11-01T10:00:00', before_data: JSON.stringify(record), payload: JSON.stringify({ personName: person.name, phone: person.phone, description: '核实后的内容' }) }

async function open(page: Page, username = 'center') {
  await page.goto('/')
  await page.getByLabel('账号', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(demoPassword)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  if (page.viewportSize()!.width < 760) await page.getByRole('button', { name: '打开业务导航' }).click()
  await page.getByRole('button', { name: '修正与归档审核', exact: true }).click()
  await expect(page.getByRole('button', { name: '加载记录', exact: true })).toBeVisible()
  await expect(page.locator('.archive-workspace')).toHaveAttribute('aria-busy', 'false')
}

async function fixtures(page: Page) {
  await page.route('**/api/archive/requests', route => route.fulfill({ json: [change] }))
  await page.route('**/api/archive/people?*', route => route.fulfill({ json: [person] }))
  await page.route('**/api/archive/history', route => route.fulfill({ json: [{ kind: 'SPLIT', from_person_id: 992, to_person_id: person.id, record_id: record.id, reason: longText, actor: '虚构核实人员', created_at: '2026-11-01T10:00:00' }] }))
  await page.route('**/api/intake/records/901', route => route.fulfill({ json: record }))
}

for (const [width, height] of [[1440, 900], [1280, 800], [390, 844]]) {
  test(`修正、审核对照、归档与历史在 ${width} 下可访问且长内容不溢出`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    await fixtures(page)
    await open(page)
    await page.getByRole('spinbutton', { name: '来源记录编号', exact: true }).fill('901')
    await page.getByRole('button', { name: '加载记录', exact: true }).click()
    await expect(page.getByLabel('问题描述', { exact: true })).toHaveValue(longText)
    await page.getByRole('button', { name: '提交变更申请', exact: true }).scrollIntoViewIfNeeded()
    await expect(page.getByRole('button', { name: '提交变更申请', exact: true })).toBeInViewport()
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: `../docs/screenshots/ui-refresh/09-source-${width}.png`, fullPage: true })
    await page.getByRole('tab', { name: '申请审核', exact: true }).click()
    await expect(page.getByText('1 条待审核', { exact: true })).toBeVisible()
    await page.locator('.el-table__expand-icon').click()
    await expect(page.getByRole('columnheader', { name: '变更前', exact: true })).toBeVisible()
    await page.locator('.archive-comparison-scroll').focus()
    await page.screenshot({ path: `../docs/screenshots/ui-refresh/09-review-${width}.png`, fullPage: true })
    if (width === 390) {
      const scroll = page.locator('.archive-comparison-scroll')
      expect(await scroll.evaluate(el => el.scrollWidth > el.clientWidth)).toBe(true)
    }
    for (const tab of ['来源修正', '申请审核', '档案修正与合并拆分', '变更历史']) {
      await page.getByRole('tab', { name: tab, exact: true }).click()
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    }
    await page.getByRole('tab', { name: '档案修正与合并拆分', exact: true }).click()
    const choose = page.getByRole('button', { name: '选择档案', exact: true })
    await choose.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('heading', { name: /当前档案 #991/ })).toBeVisible()
    await page.getByRole('button', { name: '拆分已加载来源到目标', exact: true }).scrollIntoViewIfNeeded()
    await expect(page.getByRole('button', { name: '拆分已加载来源到目标', exact: true })).toBeInViewport()
    await page.screenshot({ path: `../docs/screenshots/ui-refresh/09-profile-${width}.png`, fullPage: true })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}

test('来源读取加载、错误重试及空申请状态', async ({ page }) => {
  await fixtures(page)
  await page.route('**/api/archive/requests', route => route.fulfill({ json: [] }))
  await open(page)
  await expect(page.getByText('输入来源记录编号，加载后填写修正或删除申请')).toBeVisible()
  let finish!: () => void
  const gate = new Promise<void>(resolve => { finish = resolve })
  await page.route('**/api/intake/records/902', async route => {
    await gate
    await route.fulfill({ status: 404, json: { message: '来源记录不存在或无权访问' } })
  })
  await page.getByRole('spinbutton', { name: '来源记录编号', exact: true }).fill('902')
  await page.getByRole('button', { name: '加载记录', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('正在处理')
  await expect(page.getByRole('button', { name: '加载记录', exact: true })).toBeDisabled()
  finish()
  await expect(page.getByRole('alert')).toContainText('来源记录不存在或无权访问')
  await page.getByRole('spinbutton', { name: '来源记录编号', exact: true }).fill('901')
  await page.getByRole('button', { name: '加载记录', exact: true }).click()
  await expect(page.getByLabel('问题描述', { exact: true })).toHaveValue(longText)
  await page.getByRole('tab', { name: '申请审核', exact: true }).click()
  await expect(page.getByText('暂无变更申请', { exact: true })).toBeVisible()
})

test('部门账号可跟踪申请且不显示中心维护与审核动作', async ({ page }) => {
  await fixtures(page)
  await open(page, 'civil')
  await expect(page.getByRole('tab', { name: '档案修正与合并拆分', exact: true })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: '变更历史', exact: true })).toHaveCount(0)
  await page.getByRole('tab', { name: '申请审核', exact: true }).click()
  await expect(page.getByLabel('审核理由', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '批准', exact: true })).toHaveCount(0)
  await expect(page.getByText('待审核 · PENDING', { exact: true })).toBeVisible()
})
