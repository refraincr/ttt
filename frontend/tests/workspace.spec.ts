import { test, expect } from '@playwright/test'

test('页面完成登录、搜索、查看详情、刷新和退出', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('textbox', { name: '账号', exact: true }).fill('civil')
  await page.getByRole('textbox', { name: '密码', exact: true }).fill(process.env.DEMO_PASSWORD || 'Demo-Only-2026!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '事件列表' })).toBeVisible()
  await expect(page.getByText('本部门事件', { exact: true })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: '演示张某' })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: '演示李某' })).toHaveCount(0)
  await page.getByRole('textbox', { name: '搜索事件' }).fill('张某')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.getByRole('row').filter({ hasText: '演示王某' })).toHaveCount(0)
  await page.getByRole('button', { name: '查看事件1', exact: true }).click()
  await expect(page.getByRole('heading', { name: '事件详情' })).toBeVisible()
  await expect(page.getByText('虚构案例：咨询临时救助申请材料。', { exact: true }).last()).toBeVisible()
  await page.screenshot({ path: 'test-results/event-detail.png', fullPage: true, animations: 'disabled' })
  await page.reload()
  await expect(page.getByRole('heading', { name: '事件列表' })).toBeVisible()
  await page.screenshot({ path: 'test-results/event-list.png', fullPage: true })
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
  await expect(page.getByText('演示张某')).toHaveCount(0)
})

test('登录失败显示可理解的提示', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('textbox', { name: '账号', exact: true }).fill('center')
  await page.getByRole('textbox', { name: '密码', exact: true }).fill('wrong-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('账号或密码错误')
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
})

test('仅处置账号登录后显示权限说明，不展示来源记录', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('textbox', { name: '账号', exact: true }).fill('police')
  await page.getByRole('textbox', { name: '密码', exact: true }).fill(process.env.DEMO_PASSWORD || 'Demo-Only-2026!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('当前账号未分配事件查看权限')
  await expect(page.getByRole('table')).toHaveCount(0)
  await expect(page.getByText('演示李某')).toHaveCount(0)
})

test('中心页面展示跨部门记录', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('textbox', { name: '账号', exact: true }).fill('center')
  await page.getByRole('textbox', { name: '密码', exact: true }).fill(process.env.DEMO_PASSWORD || 'Demo-Only-2026!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByText('全部门事件', { exact: true })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: '演示李某' })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: '演示张某' })).toBeVisible()
})

test('切换账号后，旧账号延迟返回的详情不能重新展示', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('textbox', { name: '账号', exact: true }).fill('center')
  await page.getByRole('textbox', { name: '密码', exact: true }).fill(process.env.DEMO_PASSWORD || 'Demo-Only-2026!')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '查看事件2', exact: true })).toBeVisible()
  let release!: () => void
  const delayed = new Promise<void>(resolve => { release = resolve })
  let fetched = false
  await page.route('**/api/events/2', async route => {
    const response = await route.fetch()
    fetched = true
    await delayed
    await route.fulfill({ response })
  })
  try {
    await page.getByRole('button', { name: '查看事件2', exact: true }).click()
    await expect.poll(() => fetched).toBe(true)
    await page.getByRole('button', { name: '退出登录' }).click()
    await page.getByRole('textbox', { name: '账号', exact: true }).fill('civil')
    await page.getByRole('textbox', { name: '密码', exact: true }).fill(process.env.DEMO_PASSWORD || 'Demo-Only-2026!')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.getByText('本部门事件', { exact: true })).toBeVisible()
    const received = page.waitForResponse('**/api/events/2')
    release()
    await received
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => resolve())))
    await expect(page.getByRole('heading', { name: '事件详情' })).toHaveCount(0)
    await expect(page.getByRole('heading', { name: '演示李某' })).toHaveCount(0)
  } finally { release() }
})
