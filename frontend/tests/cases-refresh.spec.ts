import { test, expect, type Page } from '@playwright/test'
import { demoPassword } from './support'

const longText = '虚构核查材料：保留完整的处置依据与授权内容。'.repeat(25)
const event = { id: 601, eventTime: '2026-11-01T10:00:00', sources: [{ recordId: 701, source: '虚构来源', description: longText, specialReport: true, tags: '核查材料' }] }
const alert = { id: 901, name: '虚构预警人员', level: 'HIGH', state: 'OPEN', new_basis: true, backlog: true, version: 2, person_id: 501, created_at: '2026-11-01T10:00:00', snapshot: { events: [event], keywordHits: [{ eventId: 601, ruleId: 1, ruleName: '虚构规则', ruleVersion: 1, evidence: [{ term: '核查', text: longText }] }] }, current: { finalLevel: 'HIGH', events: [event] }, reviews: [] }
const task = { id: 801, title: '虚构部门处置任务', state: 'SUBMITTED', department_name: '公安局', department_id: 3, version: 2, deadline: '2026-11-02T12:00:00', submitted_at: '2026-11-03T10:00:00', instruction: longText, summary: '部门获授权的必要摘要', contact: '虚构联系人', events: [601], archive_pending: false, departmentOverdue: true, reviewWaitSeconds: 3600, dispatch_snapshot: { title: '派发时任务标题', departmentName: '公安局', deadline: '2026-11-02T12:00:00', instruction: longText, summary: '', contact: '' }, attachments: [], history: [{ id: 1, action: 'SUBMIT', content: longText, actor: '虚构承办人员', created_at: '2026-11-03T10:00:00', completed_events: '[]' }] }

async function fixtures(page: Page, selected = task) {
  await page.route('**/api/cases/alerts?*', r => r.fulfill({ json: { items: [alert], total: 1 } }))
  await page.route('**/api/cases/alerts/901', r => r.fulfill({ json: alert }))
  await page.route('**/api/cases/tasks?*', r => r.fulfill({ json: { items: [selected], total: 1 } }))
  await page.route('**/api/cases/tasks/801', r => r.fulfill({ json: selected }))
  await page.route('**/api/cases/settings', r => r.fulfill({ json: { backlogDays: 7, version: 1 } }))
}

async function open(page: Page, username = 'center') {
  await page.goto('/')
  await page.getByLabel('账号', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(demoPassword)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  if (page.viewportSize()!.width < 760) await page.getByRole('button', { name: '打开业务导航' }).click()
  await page.getByRole('button', { name: '预警与处置', exact: true }).click()
  await expect(page.locator('.case-workspace')).toHaveAttribute('aria-busy', 'false')
}

async function noOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
}

for (const [width, height] of [[1440, 900], [1280, 800], [390, 844]]) {
  test(`预警依据、派发及中心审核在 ${width} 下可操作且长文本不溢出`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    await fixtures(page)
    await open(page)
    await expect(page.getByText('未研判积压', { exact: true })).toBeVisible()
    const view = page.getByRole('button', { name: '查看预警', exact: true })
    await view.scrollIntoViewIfNeeded()
    await view.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('heading', { name: '预警 #901 · 虚构预警人员' })).toBeVisible()
    await page.getByLabel('任务标题', { exact: true }).fill('虚构派发标题')
    await page.getByRole('button', { name: '派发任务', exact: true }).scrollIntoViewIfNeeded()
    await expect(page.getByRole('button', { name: '派发任务', exact: true })).toBeInViewport()
    await noOverflow(page)
    await page.locator('.case-workspace .el-scrollbar__wrap').evaluateAll(elements => elements.forEach(el => { el.scrollLeft = 0 }))
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: `../docs/screenshots/ui-refresh/10-alert-${width}.png`, fullPage: true })
    await page.getByRole('tab', { name: '处置任务', exact: true }).click()
    await page.getByRole('button', { name: '查看任务', exact: true }).click()
    await expect(page.getByText('部门逾期', { exact: true })).toBeVisible()
    await page.getByLabel('任务审核理由', { exact: true }).fill('虚构核实意见')
    await page.getByRole('button', { name: '通过并关闭任务', exact: true }).scrollIntoViewIfNeeded()
    await expect(page.getByRole('button', { name: '通过并关闭任务', exact: true })).toBeInViewport()
    await noOverflow(page)
    await page.locator('.case-workspace .el-scrollbar__wrap').evaluateAll(elements => elements.forEach(el => { el.scrollLeft = 0 }))
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: `../docs/screenshots/ui-refresh/10-review-${width}.png`, fullPage: true })
    if (width === 390) {
      const scroll = page.locator('.case-workspace .el-table:visible .el-table__body-wrapper .el-scrollbar__wrap').first()
      expect(await scroll.evaluate(el => el.scrollWidth > el.clientWidth)).toBe(true)
    }
  })
}

test('部门仅见任务授权内容，窄屏跟进动作可达', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await fixtures(page, { ...task, state: 'RETURNED' })
  await open(page, 'police')
  await expect(page.getByRole('tab', { name: '风险预警', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '查看任务', exact: true }).click()
  await expect(page.getByLabel('任务审核理由', { exact: true })).toHaveCount(0)
  await expect(page.getByText('部门获授权的必要摘要', { exact: true })).toBeVisible()
  await page.getByLabel('跟进或处理结果', { exact: true }).fill(longText)
  await page.getByRole('button', { name: '提交处理结果', exact: true }).scrollIntoViewIfNeeded()
  await expect(page.getByRole('button', { name: '提交处理结果', exact: true })).toBeInViewport()
  await noOverflow(page)
  await page.screenshot({ path: '../docs/screenshots/ui-refresh/10-department-390.png', fullPage: true })
})

test('归档确认表单及历史保留，提交期间防止重复操作', async ({ page }) => {
  await fixtures(page, { ...task, archive_pending: true })
  await open(page)
  await page.getByRole('tab', { name: '处置任务', exact: true }).click()
  await page.getByRole('button', { name: '查看任务', exact: true }).click()
  await expect(page.getByText('归档变化，任务暂停，等待中心确认', { exact: true })).toBeVisible()
  await page.getByRole('spinbutton', { name: '确认人员编号', exact: true }).fill('501')
  await page.getByLabel('确认有效事件编号', { exact: true }).fill('601')
  await page.getByLabel('确认理由', { exact: true }).fill('核实归档与授权内容')
  let finish!: () => void
  const wait = new Promise<void>(resolve => { finish = resolve })
  await page.route('**/api/cases/tasks/801/confirm', async route => {
    expect(route.request().postDataJSON()).toMatchObject({ personId: 501, eventIds: [601], reason: '核实归档与授权内容', version: 2 })
    await wait
    await route.fulfill({ json: { ...task, history: [{ ...task.history[0], action: 'CONFIRM' }] } })
  })
  const confirm = page.getByRole('button', { name: '确认归档与授权并恢复', exact: true })
  await confirm.click()
  await expect(confirm).toBeDisabled()
  await expect(page.getByLabel('确认理由', { exact: true })).toBeDisabled()
  finish()
  await expect(page.getByRole('cell', { name: '确认归档与授权', exact: true })).toBeVisible()
  await expect(confirm).toHaveCount(0)
})

test('预警加载、失败重试、空状态与重置筛选', async ({ page }) => {
  await fixtures(page)
  await open(page)
  let finish!: () => void
  const wait = new Promise<void>(resolve => { finish = resolve })
  await page.route('**/api/cases/alerts?*', async route => {
    await wait
    await route.fulfill({ status: 500, json: { message: '预警读取失败，请重试' } })
  })
  await page.getByLabel('预警姓名', { exact: true }).fill('筛选条件')
  await page.getByRole('button', { name: '查询预警', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('正在读取或保存')
  await expect(page.getByRole('button', { name: '重置筛选', exact: true })).toBeDisabled()
  finish()
  await expect(page.getByText('预警读取失败，请重试', { exact: true })).toBeVisible()
  await expect(page.getByLabel('预警姓名', { exact: true })).toHaveValue('筛选条件')
  await page.route('**/api/cases/alerts?*', route => {
    const url = new URL(route.request().url())
    expect(url.searchParams.has('name')).toBe(false)
    expect(url.searchParams.get('state')).toBe('OPEN')
    expect(url.searchParams.get('page')).toBe('1')
    return route.fulfill({ json: { items: [], total: 0 } })
  })
  await page.getByRole('button', { name: '重置筛选', exact: true }).click()
  await expect(page.getByLabel('预警姓名', { exact: true })).toHaveValue('')
  await expect(page.getByText('暂无符合条件的预警', { exact: true })).toBeVisible()
})
