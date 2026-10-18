import {test,expect} from '@playwright/test'
import {signIn,write,uniqueName,demoPassword} from './support'
test('中心页面完成重复审核、标签标注并查看风险证据，部门无研判入口',async({page,request})=>{
 await signIn(request)
 const code=uniqueName('UIRISK')
 const source=await(await write(request,'POST','/api/admin/sources',{name:code,sourceType:'OTHER',departmentId:null,enabled:true})).json()
 const category=await(await write(request,'POST','/api/admin/dictionaries',{name:code,code,kind:'EVENT_CATEGORY',enabled:true})).json()
 const dispute=await(await write(request,'POST','/api/admin/dictionaries',{name:code,code,kind:'DISPUTE_TYPE',enabled:true})).json()
 await signIn(request,'civil')
 const personName=uniqueName('页面风险人员'),phone='136'+String(Date.now()).slice(-8)
 const data={personName,phone,idCard:'',description:'虚构普通事项',eventTime:'2026-07-01T10:00:00',sourceId:source.id,categoryId:category.id,disputeId:dispute.id}
 const create=async()=>{const d=await(await write(request,'POST','/api/intake/drafts',data)).json();return(await write(request,'POST',`/api/intake/drafts/${d.id}/submit`,{version:d.version})).json()}
 const first=await create();await signIn(request,'center')
 const confirmed=await(await write(request,'POST',`/api/intake/identity-reviews/${first.id}`,{version:first.version,personName,phone,idCard:'',reason:'本人现场确认姓名手机号'})).json()
 await signIn(request,'civil');const second=await create()
 await page.goto('/');await page.getByRole('textbox',{name:'账号',exact:true}).fill('center');await page.getByRole('textbox',{name:'密码',exact:true}).fill(demoPassword);await page.getByRole('button',{name:'登录',exact:true}).click()
 await page.getByRole('button',{name:'归集与风险',exact:true}).click()
 await page.getByRole('textbox',{name:'核查来源编号',exact:true}).fill(String(second.id));await page.getByRole('button',{name:'读取来源记录',exact:true}).click()
 await page.getByText('确认重复并归集',{exact:true}).click()
 await page.getByRole('spinbutton',{name:'归集目标来源编号',exact:true}).fill(String(first.id))
 await page.getByRole('textbox',{name:'重复审核理由',exact:true}).fill('页面核对为同一事项')
 await page.getByRole('button',{name:'提交重复审核',exact:true}).click();await expect(page.getByText('重复审核已保存，风险已重算',{exact:true})).toBeVisible()
 await page.getByRole('textbox',{name:'人工关键词标签',exact:true}).fill('扬言')
 await page.getByRole('textbox',{name:'标签标注理由',exact:true}).fill('根据虚构核实资料标注')
 await page.getByRole('button',{name:'保存关键词标签',exact:true}).click();await expect(page.getByText('标签已保存，关键词及风险已重新计算',{exact:true})).toBeVisible()
 await page.getByRole('textbox',{name:'筛选风险档案',exact:true}).fill(personName);await page.getByRole('button',{name:'查询档案',exact:true}).click();await page.getByRole('button',{name:'查看风险依据',exact:true}).click()
 await expect(page.getByText('中风险',{exact:true})).toBeVisible();await expect(page.getByRole('heading',{name:`人员 #${confirmed.matched_person_id} 风险依据`})).toBeVisible()
 await expect(page.locator('mark').filter({hasText:'扬言'}).first()).toBeVisible()
 await page.screenshot({path:'test-results/risk-evidence.png',fullPage:true})
 let release!:()=>void;let delayed=false
 const gate=new Promise<void>(resolve=>{release=resolve})
 await page.route(`**/api/intake/records/${first.id}/duplicates`,async route=>{delayed=true;await gate;await route.continue()})
 await page.getByRole('textbox',{name:'核查来源编号',exact:true}).fill(String(first.id));await page.getByRole('button',{name:'读取来源记录',exact:true}).click()
 await expect.poll(()=>delayed).toBe(true)
 await expect(page.getByRole('button',{name:'读取来源记录',exact:true})).toBeDisabled()
 release();await expect(page.getByRole('heading',{name:`来源记录 #${first.id} · ${personName}`,exact:true})).toBeVisible()

 await page.getByRole('button',{name:'退出登录',exact:true}).click();await page.getByRole('textbox',{name:'账号',exact:true}).fill('civil');await page.getByRole('textbox',{name:'密码',exact:true}).fill(demoPassword);await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page.getByRole('button',{name:'归集与风险',exact:true})).toHaveCount(0)
})
