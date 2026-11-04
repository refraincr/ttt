import { test, expect, type Page } from '@playwright/test'
import { signIn } from './support'

const batches = Array.from({ length: 40 }, (_, i) => ({ id: 7100 + i, filename: `验收批次-${i}.csv`, total: 2, pending: 1, succeeded: 0, duplicates: 0, failed: 1 }))
const pendingRows = [{ line_number: 2, state: 'PENDING', record_id: null, error: null }, { line_number: 3, state: 'FAILED', record_id: null, error: '发生时间格式无效' }]

async function open(page: Page) {
  await signIn(page.request, 'civil')
  await page.route('**/api/batches', route => route.fulfill({ json: route.request().method() === 'POST' ? { id: 7199 } : batches }))
  await page.route('**/api/options/sources', route => route.fulfill({ json: [{ id: 1, name: '虚构可信来源' }] }))
  await page.route('**/api/batches/mappings', route => route.fulfill({ json: [] }))
  await page.goto('/')
  await page.getByRole('button', { name: '规则与批量接入', exact: true }).click()
  await expect(page.getByRole('button', { name: '逐行结果', exact: true }).first()).toBeEnabled()
}

test('结果弹窗自动刷新至完成，手动刷新及所有关闭入口可用，关闭后停止请求', async ({ page }) => {
  await open(page)
  let reads = 0, finished = false
  await page.route('**/api/batches/7100', route => {
    reads++
    return route.fulfill({ json: finished ? [{ line_number: 2, state: 'SUCCESS', record_id: 9901, error: null }, pendingRows[1]] : pendingRows })
  })
  await page.clock.install()
  const button = page.getByRole('button', { name: '逐行结果', exact: true }).first()
  await button.click()
  const dialog = page.getByRole('dialog', { name: /批次 #7100/ })
  await expect(dialog).toContainText('验收批次-0.csv')
  await expect(dialog).toContainText('发生时间格式无效')
  await expect(dialog.getByRole('cell', { name: '待处理', exact: true })).toBeVisible()
  finished = true
  await page.clock.runFor(3100)
  await expect(dialog.getByRole('cell', { name: '9901', exact: true })).toBeVisible()
  const completedReads = reads
  await page.clock.runFor(6000)
  expect(reads).toBe(completedReads)
  await dialog.getByRole('button', { name: '刷新结果', exact: true }).click()
  await expect.poll(() => reads).toBe(completedReads + 1)
  await expect(dialog.getByRole('button', { name: '刷新结果', exact: true })).toBeEnabled()
  for (const close of [
    () => dialog.getByRole('button', { name: '关闭', exact: true }).click(),
    () => dialog.locator('.el-dialog__headerbtn').click(),
    () => page.keyboard.press('Escape'),
    () => page.mouse.click(1, 1),
  ]) {
    await close()
    await expect(dialog).not.toBeVisible()
    const closedReads = reads
    await page.clock.runFor(6000)
    expect(reads).toBe(closedReads)
    finished = false
    await button.click()
    await expect(dialog.getByRole('cell', { name: '待处理', exact: true })).toBeVisible()
  }
  await page.keyboard.press('Escape')
})

test('提交文件后自动打开新批次，读取失败可在弹窗重试', async ({ page }) => {
  await open(page)
  let fail = true
  await page.route('**/api/batches/7199', route => route.fulfill(fail
    ? { status: 503, json: { message: '结果暂不可用，请重试' } }
    : { json: [{ line_number: 2, state: 'DUPLICATE', record_id: 8801, error: null }] }))
  await page.locator('.import-form .el-select').first().click()
  await page.getByRole('option', { name: '虚构可信来源', exact: true }).click()
  await page.getByLabel('导入文件', { exact: true }).setInputFiles({ name: '自动打开.csv', mimeType: 'text/csv', buffer: Buffer.from('虚构导入文件') })
  await page.getByRole('button', { name: '提交批量导入', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: /批次 #7199/ })
  await expect(dialog).toContainText('自动打开.csv')
  await expect(dialog.getByRole('alert')).toContainText('结果暂不可用，请重试')
  fail = false
  await dialog.getByRole('button', { name: '刷新结果', exact: true }).click()
  await expect(dialog.getByRole('cell', { name: '重复，未新增', exact: true })).toBeVisible()
  await expect(dialog.getByRole('alert')).toHaveCount(0)
})

test('刷新失败保留已有结果，关闭和切换页面停止后台查询', async ({ page }) => {
  await open(page)
  let fail = false, reads = 0
  await page.route('**/api/batches/7100', route => {
    reads++
    return route.fulfill(fail ? { status: 503, json: { message: '读取中断' } } : { json: pendingRows })
  })
  await page.clock.install()
  await page.getByRole('button', { name: '逐行结果', exact: true }).first().click()
  const dialog = page.getByRole('dialog', { name: /批次 #7100/ })
  await expect(dialog).toContainText('发生时间格式无效')
  fail = true
  await page.clock.runFor(3100)
  await expect(dialog.getByRole('alert')).toContainText('读取中断')
  await expect(dialog).toContainText('发生时间格式无效')
  await page.keyboard.press('Escape')
  await expect(dialog).not.toBeVisible()
  fail = false
  await page.getByRole('button', { name: '逐行结果', exact: true }).first().click()
  await expect(dialog.getByRole('cell', { name: '待处理', exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(dialog).not.toBeVisible()
  await page.getByRole('button', { name: '事件列表', exact: true }).click()
  const stoppedReads = reads
  await page.clock.runFor(6000)
  expect(reads).toBe(stoppedReads)
})

test('上传返回前离开页面，不重新启动结果查询', async ({ page }) => {
  await open(page)
  let started = false, reads = 0, release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/batches', async route => {
    if (route.request().method() !== 'POST') return route.fallback()
    started = true
    await gate
    await route.fulfill({ json: { id: 7199 } })
  })
  await page.route('**/api/batches/7199', route => { reads++; return route.fulfill({ json: pendingRows }) })
  await page.locator('.import-form .el-select').first().click()
  await page.getByRole('option', { name: '虚构可信来源', exact: true }).click()
  await page.getByLabel('导入文件', { exact: true }).setInputFiles({ name: '慢上传.csv', mimeType: 'text/csv', buffer: Buffer.from('虚构导入文件') })
  await page.getByRole('button', { name: '提交批量导入', exact: true }).click()
  await expect.poll(() => started).toBe(true)
  await page.getByRole('button', { name: '事件列表', exact: true }).click()
  await expect(page.getByRole('heading', { name: '事件列表', exact: true })).toBeVisible()
  const response = page.waitForResponse(r => r.url().endsWith('/api/batches') && r.request().method() === 'POST')
  await page.clock.install()
  release()
  await response
  await page.clock.runFor(6100)
  expect(reads).toBe(0)
  await expect(page.getByRole('dialog', { name: /批次 #7199/ })).toHaveCount(0)
})

test('慢请求不重叠，关闭后旧请求不会覆盖新批次', async ({ page }) => {
  await open(page)
  let reads = 0, release!: () => void
  const gate = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/batches/7100', async route => {
    reads++
    await gate
    await route.fulfill({ json: pendingRows })
  })
  await page.route('**/api/batches/7101', route => route.fulfill({ json: [{ line_number: 2, state: 'SUCCESS', record_id: 9902, error: null }] }))
  await page.clock.install()
  await page.getByRole('button', { name: '逐行结果', exact: true }).first().click()
  const first = page.getByRole('dialog', { name: /批次 #7100/ })
  await expect(first).toContainText('正在读取结果')
  await expect.poll(() => reads).toBe(1)
  await page.clock.runFor(6100)
  expect(reads).toBe(1)
  await page.keyboard.press('Escape')
  await expect(first).not.toBeVisible()
  await page.getByRole('button', { name: '逐行结果', exact: true }).nth(1).click()
  const second = page.getByRole('dialog', { name: /批次 #7101/ })
  await expect(second.getByRole('cell', { name: '9902', exact: true })).toBeVisible()
  release()
  await page.clock.runFor(6100)
  await expect(second).not.toContainText('发生时间格式无效')
  await expect(second.getByRole('cell', { name: '9902', exact: true })).toBeVisible()
  expect(reads).toBe(1)
})

for (const [width, height] of [[1440, 900], [1280, 800], [390, 844]]) {
  test(`批次较多时 ${width} 弹窗内滚动、长原因可读且关闭保留列表位置`, async ({ page }) => {
    await open(page)
    await page.setViewportSize({ width, height })
    await page.route('**/api/batches/7120', route => route.fulfill({ json: Array.from({ length: 40 }, (_, i) => ({ line_number: i + 2, state: 'FAILED', record_id: null, error: '虚构失败原因，需修正后重导。'.repeat(12) })) }))
    const button = page.getByRole('button', { name: '逐行结果', exact: true }).nth(20)
    await button.scrollIntoViewIfNeeded()
    const position = await page.evaluate(() => window.scrollY)
    await button.click()
    const dialog = page.getByRole('dialog', { name: /批次 #7120/ })
    await expect(dialog).toContainText('验收批次-20.csv')
    for (const locator of [dialog.locator('.el-dialog'), dialog.getByRole('button', { name: '关闭', exact: true })]) {
      await expect.poll(async () => {
        const box = await locator.boundingBox()
        return !!box && box.x >= 0 && box.y >= 0 && box.x + box.width <= width && box.y + box.height <= height
      }).toBe(true)
    }
    expect(await dialog.locator('.el-table .el-scrollbar__wrap').evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true)
    expect(await dialog.locator('.el-dialog__body').evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true)
    if (width < 760) {
      await dialog.locator('.el-table .el-scrollbar__wrap').evaluate(el => { el.scrollLeft = el.scrollWidth })
      await expect(dialog.getByRole('columnheader', { name: '失败原因', exact: true })).toBeInViewport()
    }
    await dialog.screenshot({ path: `../docs/screenshots/ui-refresh/07-dialog-batch-${width}.png`, animations: 'disabled' })
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(dialog).not.toBeVisible()
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(position)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}
