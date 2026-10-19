import {test,expect} from '@playwright/test'
import {readFileSync} from 'node:fs'
import {signIn,write,uniqueName} from './support'
import {setupCases,riskRecord,ok} from './cases-support'

test('联系地址贯通录入、草稿、列表关键词、修正和导出，部门权限保持',async({request})=>{
 const options=await setupCases(request)
 let r=await riskRecord(request,options,{address:'  测试县幸福路1号  '})
 expect(r.party_address).toBe('测试县幸福路1号')
 expect(r.original_payload.address).toBe('  测试县幸福路1号  ')
 r=await ok(await write(request,'PUT',`/api/intake/records/${r.id}/tags`,{version:r.version,tags:'联系核查关键词',reason:'已核实的人工标签'}))
 const query=new URLSearchParams({q:r.party_name,keyword:'核查',department:'民政'})
 expect(await ok(await request.get('/api/events?'+query))).toMatchObject({total:1,items:[{id:r.id,phone:r.party_phone,idCard:r.party_id_card,address:'测试县幸福路1号',keywords:'联系核查关键词'}]})
 query.set('keyword','%');expect(await ok(await request.get('/api/events?'+query))).toMatchObject({total:0})
 query.set('keyword',"' OR 1=1--");expect(await ok(await request.get('/api/events?'+query))).toMatchObject({total:0})
 expect((await request.get('/api/events?keyword='+ 'x'.repeat(101))).status()).toBe(400)
 const change=await ok(await write(request,'POST',`/api/archive/records/${r.id}/requests`,{version:r.version,kind:'CORRECT',payload:{personName:r.party_name,idCard:r.party_id_card,phone:r.party_phone,address:'测试县幸福路2号',eventTime:r.occurred_at,description:r.problem_description},reason:'核实新联系地址'}))
 await ok(await write(request,'POST',`/api/archive/requests/${change.id}/review`,{approve:true,reason:'地址已核实'}))
 expect(await ok(await request.get('/api/events/'+r.id))).toMatchObject({address:'测试县幸福路2号'})
 const csv=await(await request.get('/api/insights/export?kind=events&q='+encodeURIComponent(r.party_name))).text()
 expect(csv).toContain('联系地址');expect(csv).toContain('测试县幸福路2号');expect(csv).toContain('联系核查关键词')
 await signIn(request,'town');expect((await request.get('/api/events/'+r.id)).status()).toBe(404)
 query.set('keyword','核查');expect(await ok(await request.get('/api/events?'+query))).toMatchObject({total:0})
 await signIn(request,'civil')
 const data={...options,personName:uniqueName('地址草稿'),phone:'',idCard:'',eventTime:'2026-10-01T10:00:00',description:'普通资料',address:'x'.repeat(501)}
 const draft=await ok(await write(request,'POST','/api/intake/drafts',data))
 expect((await ok(await request.get('/api/intake/drafts/'+draft.id))).payload.address).toHaveLength(501)
 expect((await write(request,'POST',`/api/intake/drafts/${draft.id}/submit`,{version:draft.version})).status()).toBe(400)
})

test('CSV、Excel与来源映射接入联系地址，超长地址逐行失败，重导仍去重',async({request})=>{
 const o=await setupCases(request);await signIn(request,'civil')
 const template=await(await request.get(`/api/batches/template?sourceId=${o.sourceId}&format=csv`)).text();expect(template).toContain('联系地址')
 const upload=async(buffer:Buffer,name='address.csv',mappingId?:number)=>{
  const csrf=await(await request.get('/api/auth/csrf')).json()
  const batch=await ok(await request.post('/api/batches',{headers:{[csrf.headerName]:csrf.token},multipart:{sourceId:String(o.sourceId),...(mappingId?{mappingId:String(mappingId)}:{}),file:{name,mimeType:name.endsWith('xlsx')?'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet':'text/csv',buffer}}}))
  let rows:any[]=[];await expect.poll(async()=>{rows=await ok(await request.get('/api/batches/'+batch.id));return rows.filter(r=>r.state==='PENDING').length},{timeout:30000}).toBe(0);return rows
 }
 const csv=Buffer.from(`姓名,身份证号,手机号,发生时间,问题描述,事件分类,纠纷类型,联系地址\n地址导入,,,2026-10-01T10:00:00,普通材料,${o.categoryId},${o.disputeId},测试县幸福路3号\n超长地址,,,2026-10-01T10:00:00,普通材料,${o.categoryId},${o.disputeId},${'地'.repeat(501)}\n`)
 const rows=await upload(csv);expect(rows.map(r=>r.state)).toEqual(['SUCCESS','FAILED'])
 expect(rows[1].error).toContain('联系地址')
 expect(await ok(await request.get('/api/intake/records/'+rows[0].record_id))).toMatchObject({party_address:'测试县幸福路3号'})
 expect((await upload(csv))[0]).toMatchObject({state:'DUPLICATE',record_id:rows[0].record_id})
 const excel=await upload(readFileSync(new URL('./fixtures/address-events.xlsx',import.meta.url)),'address.xlsx')
 expect(excel[0].state).toBe('SUCCESS');expect(await ok(await request.get('/api/intake/records/'+excel[0].record_id))).toMatchObject({party_address:'测试县幸福路8号'})
 await signIn(request,'center')
 const mapping=await ok(await write(request,'POST','/api/batches/mappings',{name:uniqueName('地址映射'),sourceId:o.sourceId,columns:{personName:'姓名',eventTime:'时间',description:'描述',categoryId:'分类',disputeId:'纠纷',address:'通讯地址'},categories:{},disputes:{},timeFormat:'ISO'}))
 await signIn(request,'civil');const mapped=await upload(Buffer.from(`姓名,时间,描述,分类,纠纷,通讯地址\n地址映射,2026-10-02T10:00:00,映射测试,${o.categoryId},${o.disputeId},测试县幸福路4号\n`),'mapped.csv',mapping.id)
 expect(mapped[0].state).toBe('SUCCESS');expect(await ok(await request.get('/api/intake/records/'+mapped[0].record_id))).toMatchObject({party_address:'测试县幸福路4号'})
})

test('外部接收保留联系地址并按外部编号幂等',async({request,playwright,baseURL})=>{
 const o=await setupCases(request);await signIn(request)
 const credential=await ok(await write(request,'POST','/api/batches/credentials',{name:uniqueName('地址外部'),departmentId:2,sourceId:o.sourceId,specialReport:false}))
 const external=await playwright.request.newContext({baseURL,extraHTTPHeaders:{Authorization:'Bearer '+credential.token}})
 try{const body={externalId:uniqueName('ADDR'),payload:{...o,personName:'外部地址验收',phone:'',eventTime:'2026-10-01T10:00:00',description:'普通外部资料',address:'测试县幸福路5号'}}
 const received=(await ok(await external.post('/api/external/events',{data:[body]})))[0]
 expect((await ok(await external.post('/api/external/events',{data:[body]})))[0]).toMatchObject({recordId:received.recordId,duplicate:true})
 await signIn(request,'civil');expect(await ok(await request.get('/api/intake/records/'+received.recordId))).toMatchObject({party_address:'测试县幸福路5号'})
 }finally{await external.dispose()}
})

test('页面展示联系方式与关键词，筛选和重置正常',async({page,request})=>{
 const o=await setupCases(request);let r=await riskRecord(request,o,{address:'测试县页面地址6号'})
 r=await ok(await write(request,'PUT',`/api/intake/records/${r.id}/tags`,{version:r.version,tags:uniqueName('页面关键词'),reason:'测试标签展示'}))
 await page.goto('/');await page.getByLabel('账号',{exact:true}).fill('civil');await page.getByLabel('密码',{exact:true}).fill('Demo-Only-2026!');await page.getByRole('button',{name:'登录',exact:true}).click()
 await page.getByRole('textbox',{name:'搜索事件',exact:true}).fill(r.party_name)
 await page.getByRole('textbox',{name:'关键词筛选',exact:true}).fill(r.keyword_tags)
 await page.getByRole('button',{name:'查询',exact:true}).click()
 const row=page.getByRole('row').filter({hasText:r.party_name});await expect(row).toContainText('测试县页面地址6号');await expect(row).toContainText(r.party_phone);await expect(row).toContainText(r.keyword_tags)
 for(const name of ['当事人身份证号','当事人电话','联系地址','关键词'])await expect(page.getByRole('columnheader',{name,exact:true})).toBeVisible()
 await page.getByRole('button',{name:`查看事件${r.id}`,exact:true}).click();await expect(page.getByRole('dialog')).toContainText('测试县页面地址6号');await page.getByRole('dialog').locator('.el-drawer__close-btn').click()
 await page.getByRole('button',{name:'重置',exact:true}).click();await expect(page.getByRole('textbox',{name:'关键词筛选',exact:true})).toHaveValue('')
})
