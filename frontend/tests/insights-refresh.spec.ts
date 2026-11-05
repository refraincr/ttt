import { test, expect, type Page } from '@playwright/test'
import { demoPassword } from './support'

const longText='虚构核查材料与关键词命中依据。'.repeat(40)
const dashboard={reports:21,effectiveEvents:12,tasks:3,overdue:1,pending:2,pendingIdentity:1,pendingDuplicate:0,pendingChanges:1,departments:{'虚构民政部门':15,'虚构公安部门':6},keywords:{'扬言':3}}
async function fixtures(page:Page) {
  await page.route('**/api/insights/dashboard?*',r=>r.fulfill({json:dashboard}))
  await page.route('**/api/insights/events?*',r=>r.fulfill({json:[{id:701,eventId:601,name:'虚构人员',description:longText,source:'虚构来源'}]}))
  await page.route('**/api/insights/risks?*',r=>r.fulfill({json:[{id:501,name:'虚构人员',level:'HIGH',effectiveCount:12}]}))
  await page.route('**/api/insights/keyword?*',r=>r.fulfill({json:[{personId:501,eventId:601,evidence:[{term:'扬言',text:longText}]}]}))
  await page.route('**/api/insights/history?*',r=>r.fulfill({json:Array.from({length:12},(_,i)=>({target_type:'TASK',target_id:800+i,actor_username:'虚构核查员',created_at:'2026-11-01T10:00:00',reason:longText}))}))
  await page.route('**/api/intake/people/501',r=>r.fulfill({json:{person:{id:501,name:'虚构人员',phone:'',idCard:''},events:[{id:701,party_address:longText}]}}))
  await page.route('**/api/intake/people/501/risk',r=>r.fulfill({json:{finalLevel:'HIGH',baseLevel:'HIGH',ruleLevel:'HIGH',effectiveCount:12,sourceCount:21,ruleVersion:1,events:[{id:601,eventTime:'2026-11-01T10:00:00',sources:[{recordId:701,description:longText,departmentName:'虚构部门',source:'虚构来源'}]}],keywordHits:[]}}))
}
async function open(page:Page,user='center') {
  await page.goto('/')
  await page.getByLabel('账号',{exact:true}).fill(user)
  await page.getByLabel('密码',{exact:true}).fill(demoPassword)
  await page.getByRole('button',{name:'登录',exact:true}).click()
  if(page.viewportSize()!.width<760)await page.getByRole('button',{name:'打开业务导航'}).click()
  await page.getByRole('button',{name:'业务概况与追溯',exact:true}).click()
  await expect(page.locator('.insights-workspace')).toHaveAttribute('aria-busy','false')
}
for(const [width,height] of [[1440,900],[1280,800],[390,844]]) {
  test(`概况下钻、历史与详情在 ${width} 下可操作`,async({page})=>{
    await page.setViewportSize({width,height});await fixtures(page);await open(page)
    await page.getByRole('button',{name:'扬言（3）',exact:true}).click()
    await expect(page.getByText('当前关键词：扬言',{exact:true})).toBeVisible()
    await page.getByText('展开命中依据',{exact:true}).click()
    await expect(page.locator('.insights-evidence p')).toHaveText('扬言：'+longText)
    await page.getByRole('button',{name:'查询操作历史',exact:true}).click()
    await expect(page.locator('.insights-history .el-table__body-wrapper tbody tr')).toHaveCount(10)
    await page.locator('.insights-history .btn-next').click()
    await expect(page.locator('.insights-history .el-table__body-wrapper tbody tr')).toHaveCount(2)
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
    await page.screenshot({path:`../docs/screenshots/ui-refresh/11-overview-${width}.png`,fullPage:true})
    await page.getByRole('button',{name:'查看人员与事件',exact:true}).click()
    await expect(page.getByRole('heading',{name:'虚构人员',exact:true})).toBeVisible()
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
    await page.getByRole("dialog",{name:"关联人员与有效事件",exact:true}).evaluate(async el=>{await Promise.all(el.getAnimations({subtree:true}).map(animation=>animation.finished))})
    await page.screenshot({path:`../docs/screenshots/ui-refresh/11-detail-${width}.png`})
  })
}

test('筛选提交后同步概况、导出与下钻，失败保留上次查询，重置清理旧依据',async({page})=>{
  await fixtures(page);await open(page)
  await page.getByRole('button',{name:'扬言（3）',exact:true}).click()
  await page.getByLabel('统计筛选',{exact:true}).fill('新筛选')
  await expect(page.getByRole('link',{name:'导出事件 CSV'})).toHaveAttribute('href',/q=$/)
  await page.getByRole('button',{name:'查询概况',exact:true}).click()
  await expect(page.getByRole('link',{name:'导出事件 CSV'})).toHaveAttribute('href','/api/insights/export?kind=events&q='+encodeURIComponent('新筛选'))
  await expect(page.getByRole('button',{name:'查看人员与事件',exact:true})).toHaveCount(0)
  const drill=page.waitForRequest(r=>r.url().includes('/insights/keyword?'))
  await page.getByRole('button',{name:'扬言（3）',exact:true}).click()
  expect(new URL((await drill).url()).searchParams.get('q')).toBe('新筛选')
  await page.route('**/api/insights/dashboard?*',r=>r.fulfill({status:500,json:{message:'虚构查询失败'}}))
  await page.getByLabel('统计筛选',{exact:true}).fill('失败筛选')
  await page.getByRole('button',{name:'查询概况',exact:true}).click()
  await expect(page.getByRole('alert')).toContainText('虚构查询失败')
  await expect(page.getByRole('link',{name:'导出事件 CSV'})).toHaveAttribute('href','/api/insights/export?kind=events&q='+encodeURIComponent('新筛选'))
  await page.unroute('**/api/insights/dashboard?*')
  await page.route('**/api/insights/dashboard?*',r=>r.fulfill({json:{...dashboard,reports:0,departments:{},keywords:{}}}))
  await page.getByRole('button',{name:'重置',exact:true}).click()
  await expect(page.getByText('当前筛选范围内暂无关键词命中',{exact:true})).toBeVisible()
  await expect(page.getByRole('button',{name:'查看人员与事件',exact:true})).toHaveCount(0)
})

test('加载禁用重复请求，历史编号校验、失败重试，部门隐藏中心导出',async({page})=>{
  await fixtures(page);await open(page,'civil')
  await expect(page.getByRole('link',{name:'导出风险 CSV'})).toHaveCount(0)
  await expect(page.getByRole('heading',{name:'风险明细',exact:true})).toHaveCount(0)
  await page.getByLabel('人员编号',{exact:true}).fill('非法编号')
  await page.getByRole('button',{name:'查询操作历史',exact:true}).click()
  await expect(page.getByRole('alert')).toContainText('须为正整数')
  await page.getByLabel('人员编号',{exact:true}).fill('501')
  let finish!:()=>void
  const wait=new Promise<void>(resolve=>{finish=resolve})
  await page.route('**/api/insights/history?*',async r=>{await wait;await r.fulfill({status:500,json:{message:'虚构历史失败'}})})
  await page.getByRole('button',{name:'查询操作历史',exact:true}).click()
  await expect(page.getByRole('button',{name:'查询概况',exact:true})).toBeDisabled()
  finish()
  await expect(page.getByRole('alert')).toContainText('虚构历史失败')
  await page.unroute('**/api/insights/history?*')
  await page.route('**/api/insights/history?*',r=>r.fulfill({json:[]}))
  await page.getByRole('button',{name:'查询操作历史',exact:true}).click()
  await expect(page.getByText('当前历史筛选下暂无记录',{exact:true})).toBeVisible()
})



test('窄屏长部门名称换行后数量仍在可见区域',async({page})=>{
  await page.setViewportSize({width:390,height:844})
  await fixtures(page)
  await page.route('**/api/insights/dashboard?*',r=>r.fulfill({json:{...dashboard,departments:{['Department'.repeat(10)]:15}}}))
  await open(page)
  const department=page.locator('.department-item')
  const count=department.locator('strong')
  await count.scrollIntoViewIfNeeded()
  await expect(count).toBeInViewport()
  const [itemBox,countBox]=await Promise.all([department.boundingBox(),count.boundingBox()])
  expect(countBox!.x+countBox!.width).toBeLessThanOrEqual(itemBox!.x+itemBox!.width+1)
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
})
