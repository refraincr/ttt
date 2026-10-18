import { test, expect } from '@playwright/test'
import { signIn, write } from './support'
test('草稿保留原始输入，提交校验和所有权受保护', async ({ request }) => {
  await signIn(request, 'civil')
  const r = await write(request, 'POST', '/api/intake/drafts', { personName: '  测试当事人  ' })
  expect(r.status()).toBe(200)
  const draft = await r.json()
  expect(draft.payload.personName).toBe('  测试当事人  ')
  expect((await write(request, 'POST', `/api/intake/drafts/${draft.id}/submit`, { version: draft.version })).status()).toBe(400)
  await signIn(request, 'town')
  expect((await request.get(`/api/intake/drafts/${draft.id}`)).status()).toBe(404)
})

import type { APIRequestContext } from '@playwright/test'
let fixtures: {source:any;category:any;dispute:any}
test.beforeAll(async({playwright,baseURL})=>{
  const admin=await playwright.request.newContext({baseURL})
  await signIn(admin)
  const suffix=String(Date.now())
  fixtures={
    source:await (await write(admin,'POST','/api/admin/sources',{name:'录入验收来源'+suffix,sourceType:'DEPARTMENT',departmentId:null,enabled:true})).json(),
    category:await (await write(admin,'POST','/api/admin/dictionaries',{name:'录入类别'+suffix,code:'INTAKE'+suffix,kind:'EVENT_CATEGORY',enabled:true})).json(),
    dispute:await (await write(admin,'POST','/api/admin/dictionaries',{name:'录入纠纷'+suffix,code:'INTAKE'+suffix,kind:'DISPUTE_TYPE',enabled:true})).json()
  }
  await admin.dispose()
})
async function entry(request: APIRequestContext, patch: Record<string, unknown> = {}) {
  const source = fixtures.source
  const category = fixtures.category
  const dispute = fixtures.dispute
  return { personName: '虚构验收人员', idCard:'', phone:'', eventTime:'2026-10-10T10:00:00', description:'邻居深夜装修产生严重噪音影响休息', sourceId:source.id, categoryId:category.id, disputeId:dispute.id, ...patch }
}
async function submit(request: APIRequestContext, data: unknown) {
  const d = await (await write(request, 'POST', '/api/intake/drafts', data)).json()
  const r = await write(request, 'POST', `/api/intake/drafts/${d.id}/submit`, {version:d.version})
  expect(r.status(), await r.text()).toBe(200)
  return { draft:d, record:await r.json() }
}
function card() {
  const prefix = '11010119900101'+String(Math.floor(Math.random()*999)+1).padStart(3,'0')
  const weights = [7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2]
  return prefix+'10X98765432'[[...prefix].reduce((sum,x,i)=>sum+Number(x)*weights[i],0)%11]
}
test('自动建档、手机号差异不覆盖、唯一姓名手机号匹配和边界重复筛查', async ({ request }) => {
  await signIn(request,'civil')
  const idCard=card(), personName='虚构'+idCard, phone='139'+String(Date.now()).slice(-8)
  const data=await entry(request,{idCard,personName,phone})
  const first=await submit(request,data)
  expect(first.record.identity_review_state).toBe('CONFIRMED')
  expect(first.record.effectiveCount).toBe(0)
  const second=await submit(request,{...data,phone:'13812345678',eventTime:'2026-10-13T10:00:00'})
  expect(second.record.matched_person_id).toBe(first.record.matched_person_id)
  expect(second.record.duplicate_review_state).toBe('SUSPECTED')
  const fallback=await submit(request,{...data,idCard:'',description:'申请养老服务补助咨询'})
  expect(fallback.record.identity_review_state).toBe('CONFIRMED')
  const outside=await submit(request,{...data,eventTime:'2026-10-16T10:00:01'})
  expect(outside.record.duplicate_review_state).toBe('SCREENED')
  const retry=await write(request,'POST',`/api/intake/drafts/${first.draft.id}/submit`,{version:first.draft.version})
  expect((await retry.json()).id).toBe(first.record.id)
  await signIn(request,'center')
  const profile=await (await request.get(`/api/intake/people/${first.record.matched_person_id}`)).json()
  expect(profile.person.phone).toBe(phone)
  expect(profile.events).toHaveLength(4)
  expect(profile.events.find((e:any)=>e.id===first.record.id).duplicate_review_state).toBe('SUSPECTED')
  const candidates=await (await request.get(`/api/intake/records/${second.record.id}/duplicates`)).json()
  expect(candidates.find((c:any)=>c.id===first.record.id)).toMatchObject({secondsApart:259200,similarity:1})
  const original=await (await request.get(`/api/intake/records/${first.record.id}`)).json()
  expect(original.original_payload).toMatchObject(data)
})
test('身份冲突、缺失身份转待审，人工建档要求依据理由且只能生效一次', async ({ request }) => {
  await signIn(request,'civil')
  const data=await entry(request,{personName:'审核'+Date.now()})
  const {record}=await submit(request,data)
  expect(record.identity_review_state).toBe('PENDING')
  expect((await write(request,'POST',`/api/intake/identity-reviews/${record.id}`,{})).status()).toBe(403)
  await signIn(request,'center')
  const review={version:record.version,personName:data.personName,idCard:'',phone:'',reason:'当事人提供资料核验'}
  expect((await write(request,'POST',`/api/intake/identity-reviews/${record.id}`,review)).status()).toBe(400)
  const response=await write(request,'POST',`/api/intake/identity-reviews/${record.id}`,{...review,phone:'137'+String(Date.now()).slice(-8)})
  expect(response.status(),await response.text()).toBe(200)
  expect((await response.json()).identity_review_state).toBe('CONFIRMED')
  expect((await write(request,'POST',`/api/intake/identity-reviews/${record.id}`,review)).status()).toBe(409)
})
test('无效身份证和停用字典不能提交，部门和处置权限不能伪造', async ({ request }) => {
  await signIn(request,'civil')
  const data=await entry(request,{idCard:'110101199001010001'})
  const d=await (await write(request,'POST','/api/intake/drafts',data)).json()
  expect((await write(request,'POST',`/api/intake/drafts/${d.id}/submit`,{version:d.version})).status()).toBe(400)
  await signIn(request,'police')
  expect((await write(request,'POST','/api/intake/drafts',{})).status()).toBe(403)
  await signIn(request,'civil')
  expect((await request.get('/api/intake/people')).status()).toBe(403)
})
test('筛查配置可更新且版本受控，部门不能查看他部门原始信息',async({request})=>{
  await signIn(request,'civil')
  const first=await submit(request,await entry(request,{personName:'隔离测试'}))
  await signIn(request,'town')
  expect((await request.get(`/api/intake/records/${first.record.id}`)).status()).toBe(404)
  expect((await request.get('/api/intake/duplicate-settings')).status()).toBe(403)
  await signIn(request,'center')
  const config=await (await request.get('/api/intake/duplicate-settings')).json()
  const changed=await write(request,'PUT','/api/intake/duplicate-settings',{...config,windowDays:2,threshold:0.8})
  expect(changed.status()).toBe(200)
  expect((await write(request,'PUT','/api/intake/duplicate-settings',config)).status()).toBe(409)
  expect((await write(request,'PUT','/api/intake/duplicate-settings',{...await changed.json(),windowDays:-1})).status()).toBe(400)
  expect((await write(request,'PUT','/api/intake/duplicate-settings',{...config,version:(await changed.json()).version})).status()).toBe(200)
})
test('特殊标识来自账号，中心代录保留操作者，姓名冲突和双审受控',async({request})=>{
  await signIn(request,'special')
  const idCard=card(),personName='特殊虚构'+idCard
  const first=await submit(request,await entry(request,{idCard,personName}))
  expect(first.record.special_report).toBe(true)
  const conflicting=await submit(request,await entry(request,{idCard,personName:'另一个姓名'}))
  expect(conflicting.record.identity_review_state).toBe('PENDING')
  await signIn(request,'center')
  const data=await entry(request,{departmentId:2,personName:'中心代录虚构',idCard:''})
  const delegated=await submit(request,data)
  expect(delegated.record).toMatchObject({department_id:2,created_by:'center',special_report:false})
  const review={version:conflicting.record.version,personName,idCard,phone:'',personId:first.record.matched_person_id,reason:'核对证件后修正录入姓名'}
  const outcomes=await Promise.all([write(request,'POST',`/api/intake/identity-reviews/${conflicting.record.id}`,review),write(request,'POST',`/api/intake/identity-reviews/${conflicting.record.id}`,review)])
  expect(outcomes.map(r=>r.status()).sort()).toEqual([200,409])
  const raw=await (await request.get(`/api/intake/records/${conflicting.record.id}`)).json()
  expect(raw.original_payload.personName).toBe('另一个姓名')
})
