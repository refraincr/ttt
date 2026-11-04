import { test, expect, type Page } from '@playwright/test'
import { demoPassword } from './support'

const rules = { medium: 3, high: 5, keywords: [{ id: 1, name: '虚构关键词规则', terms: ['扬言'], enabled: true, version: 1 }] }
const release = { id: 8001, version: 1, state: 'DRAFT', actor: '虚构测试', payload: rules }
async function open(page: Page) {
  await page.goto('/')
  await page.getByRole('textbox', { name: '账号', exact: true }).fill('center')
  await page.getByRole('textbox', { name: '密码', exact: true }).fill(demoPassword)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  if (page.viewportSize()!.width < 760) await page.getByRole('button', { name: '打开业务导航' }).click()
  await page.getByRole('button', { name: '规则与批量接入', exact: true }).click()
  await page.getByRole('tab', { name: '规则发布', exact: true }).click()
  await expect(page.getByRole('button', { name: '新建规则草稿', exact: true })).toBeEnabled()
}
async function fixtures(page: Page, empty = false) {
  await page.route('**/api/risk-governance/rules', route => route.fulfill({ json: {
    active: rules, releases: empty ? [] : [release], jobs: empty ? [] : [{ id: 9001, release_id: 8000, state: 'FAILED', completed: 2, total: 10, error: '虚构重算失败原因。'.repeat(40) }],
  } }))
}

for (const [width, height] of [[1440, 900], [1280, 800], [390, 844]]) {
  test(`规则发布 ${width} 布局、版本编辑和重算重试`, async ({ page }) => {
    await page.setViewportSize({ width, height })
    await fixtures(page)
    await page.route('**/api/risk-governance/jobs/9001/retry', route => route.fulfill({ json: { id: 9001 } }))
    await open(page)
    await expect(page.getByRole('tab', { name: '批量导入', exact: true })).toBeVisible()
    await page.getByRole('button', { name: '编辑草稿', exact: true }).click()
    await expect(page.getByRole('textbox', { name: '规则名称 1', exact: true })).toHaveValue('虚构关键词规则')
    await expect(page.getByRole('button', { name: '预览影响', exact: true })).toBeEnabled()
    await expect(page.getByRole('button', { name: '发布规则', exact: true })).toBeDisabled()
    await page.getByRole('button', { name: '重试', exact: true }).click()
    await expect(page.getByText('重算任务已重新提交', { exact: true })).toBeVisible()
    await page.getByText('查看失败原因', { exact: true }).click()
    await expect(page.getByText('虚构重算失败原因。'.repeat(40), { exact: true })).toBeVisible()
    await page.getByText('查看失败原因', { exact: true }).click()
    await page.locator('.rule-publication .el-scrollbar__wrap').evaluateAll(elements => elements.forEach(element => { element.scrollLeft = 0 }))
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: `../docs/screenshots/ui-refresh/07-editor-${width}.png`, fullPage: true, animations: 'disabled' })
    await page.getByRole('button', { name: '复制配置', exact: true }).click()
    await expect(page.getByRole('button', { name: '预览影响', exact: true })).toBeDisabled()
    await expect(page.getByText('新草稿', { exact: true })).toBeVisible()
  })
}

test('保存校验、预览失败重试、未保存修改拦截和发布状态', async ({ page }) => {
  await fixtures(page, true)
  let saved = { ...rules }, previewCalls = 0, publishCalls = 0, failPublish = true
  await page.route('**/api/risk-governance/rules/8001/preview', async route => {
    previewCalls++
    await route.fulfill(previewCalls === 1 ? { status: 503, json: { message: '预览暂不可用，请重试' } } : { json: { affected: 0, up: 0, down: 0 } })
  })
  await page.route('**/api/risk-governance/rules/8001/publish', async route => {
    publishCalls++
    await route.fulfill(failPublish ? { status: 409, json: { message: '草稿版本已变化，请核对' } } : { json: { jobId: 9001 } })
  })
  await open(page)
  await expect(page.getByText('暂无重算任务，发布规则后将在此显示', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '新建规则草稿', exact: true }).click()
  await page.getByRole('spinbutton', { name: '高风险起始次数', exact: true }).fill('2')
  await page.getByRole('button', { name: '保存规则草稿', exact: true }).click()
  await expect(page.getByText('高风险起始次数须大于中风险起始次数', { exact: true })).toBeVisible()
  await page.getByRole('spinbutton', { name: '高风险起始次数', exact: true }).fill('6')
  let releaseSave: () => void = () => {}
  const gate = new Promise<void>(resolve => { releaseSave = resolve })
  await page.route('**/api/risk-governance/rules', async route => {
    if (route.request().method() === 'POST') {
      saved = route.request().postDataJSON().rules
      await gate
      await route.fulfill({ json: { ...release, payload: saved } })
    } else await route.fulfill({ json: { active: rules, releases: [release], jobs: [] } })
  })
  await page.getByRole('button', { name: '保存规则草稿', exact: true }).click()
  await expect(page.getByRole('status')).toBeVisible()
  await expect(page.getByRole('textbox', { name: '规则名称 1', exact: true })).toBeDisabled()
  releaseSave()
  await expect(page.getByText('规则草稿已保存', { exact: true })).toBeVisible()
  expect(saved.high).toBe(6)
  await page.getByRole('button', { name: '预览影响', exact: true }).click()
  await expect(page.getByText('预览暂不可用，请重试', { exact: true })).toBeVisible()
  await expect(page.getByRole('textbox', { name: '规则名称 1', exact: true })).toHaveValue('虚构关键词规则')
  await page.getByRole('button', { name: '预览影响', exact: true }).click()
  await expect(page.getByText('受影响 0 人 · 升级 0 人 · 降级 0 人', { exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: '规则名称 1', exact: true }).fill('已修改但未保存')
  await expect(page.getByRole('button', { name: '预览影响', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '发布规则', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '保存规则草稿', exact: true }).click()
  await page.getByRole('button', { name: '预览影响', exact: true }).click()
  await page.getByRole('button', { name: '发布规则', exact: true }).click()
  await expect(page.getByText('草稿版本已变化，请核对', { exact: true })).toBeVisible()
  await expect(page.getByRole('textbox', { name: '规则名称 1', exact: true })).toHaveValue('已修改但未保存')
  failPublish = false
  await page.getByRole('button', { name: '发布规则', exact: true }).click()
  await expect(page.getByText('规则已发布，请刷新查看重算进度', { exact: true })).toBeVisible()
  expect(publishCalls).toBe(2)
  await expect(page.getByRole('button', { name: '保存规则草稿', exact: true })).toHaveCount(0)
})
