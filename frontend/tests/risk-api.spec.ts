import { test, expect } from '@playwright/test'
import { signIn, write, uniqueName } from './support'
import type { APIRequestContext } from '@playwright/test'
let options:{sourceId:number;categoryId:number;disputeId:number}
test.beforeAll(async({playwright,baseURL})=>{
 const c=await playwright.request.newContext({baseURL});await signIn(c)
 const suffix=uniqueName('RISK')
 const source=await (await write(c,'POST','/api/admin/sources',{name:suffix,sourceType:'DEPARTMENT',departmentId:null,enabled:true})).json()
 const category=await (await write(c,'POST','/api/admin/dictionaries',{name:suffix,code:suffix,kind:'EVENT_CATEGORY',enabled:true})).json()
 const dispute=await (await write(c,'POST','/api/admin/dictionaries',{name:suffix,code:suffix,kind:'DISPUTE_TYPE',enabled:true})).json()
 options={sourceId:source.id,categoryId:category.id,disputeId:dispute.id};await c.dispose()
})
function identity(){ const prefix='110101'+String(1970+Math.floor(Math.random()*35))+String(1+Math.floor(Math.random()*12)).padStart(2,'0')+String(1+Math.floor(Math.random()*28)).padStart(2,'0')+String(Math.floor(Math.random()*999)+1).padStart(3,'0');const weights=[7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2];return {personName:uniqueName('虚构风险'),idCard:prefix+'10X98765432'[[...prefix].reduce((n,x,i)=>n+Number(x)*weights[i],0)%11]} }
async function submit(c:APIRequestContext,patch:Record<string,unknown>={}){
 const data={...options,...identity(),phone:'',eventTime:'2026-09-01T12:00:00',description:'虚构普通事件资料',...patch}
 const draft=await (await write(c,'POST','/api/intake/drafts',data)).json()
 const response=await write(c,'POST',`/api/intake/drafts/${draft.id}/submit`,{version:draft.version})
 expect(response.status(),await response.text()).toBe(200);return response.json()
}
async function risk(c:APIRequestContext,id:number){ const r=await c.get(`/api/intake/people/${id}/risk`);expect(r.status(),await r.text()).toBe(200);return r.json() }
test('无候选自动归集，疑似重复待审，跨部门合并仅计一次且来源权限不扩大',async({request})=>{
 await signIn(request,'civil');const who=identity();const first=await submit(request,who)
 expect(first.duplicate_review_state).toBe('INDEPENDENT')
 await signIn(request,'town');const second=await submit(request,{...who,description:'虚构普通事件资料'})
 expect(second.duplicate_review_state).toBe('SUSPECTED')
 await signIn(request,'center')
 expect((await risk(request,first.matched_person_id)).effectiveCount).toBe(1)
 const response=await write(request,'POST',`/api/intake/records/${second.id}/duplicate-review`,{version:second.version,decision:'MERGE',targetRecordId:first.id,reason:'核对确系同一真实事件'})
 expect(response.status(),await response.text()).toBe(200)
 const result=await risk(request,first.matched_person_id)
 expect(result).toMatchObject({effectiveCount:1,sourceCount:2,baseLevel:'LOW',finalLevel:'LOW'})
 expect(result.events[0].sources.map((s:any)=>s.recordId).sort()).toEqual([first.id,second.id].sort())
 expect((await write(request,'POST',`/api/intake/records/${second.id}/duplicate-review`,{version:second.version,decision:'INDEPENDENT',reason:'重复提交'})).status()).toBe(409)
 await signIn(request,'civil');expect((await request.get(`/api/intake/records/${second.id}`)).status()).toBe(404)
 expect((await request.get(`/api/intake/people/${first.matched_person_id}/risk`)).status()).toBe(403)
})
test('累计次数1、2、3、4、5边界和人工身份审核后自动更新',async({request})=>{
 await signIn(request,'civil');const who=identity()
 const pending=await submit(request,{personName:who.personName,idCard:'',description:'待补资料'})
 await signIn(request,'center')
 const reviewed=await write(request,'POST',`/api/intake/identity-reviews/${pending.id}`,{version:pending.version,...who,phone:'',reason:'已核验证件'})
 expect(reviewed.status()).toBe(200);const personId=(await reviewed.json()).matched_person_id
 expect(await risk(request,personId)).toMatchObject({effectiveCount:1,baseLevel:'LOW'})
 for(const [i,level] of [[2,'LOW'],[3,'MEDIUM'],[4,'MEDIUM'],[5,'HIGH']] as const){
  await signIn(request,'civil');await submit(request,{...who,eventTime:`2026-09-${String(i*5).padStart(2,'0')}T12:00:00`,description:'不同日期的普通事件'+i})
  await signIn(request,'center');const result=await risk(request,personId)
  expect(result).toMatchObject({effectiveCount:i,baseLevel:level,finalLevel:level,sourceCount:i})
  expect(result.lastEventTime).toBe(`2026-09-${String(i*5).padStart(2,'0')}T12:00:00`)
 }
 // 冲突的新上报不增加有效次数。
 await signIn(request,'civil');await submit(request,{...who,personName:'与证件冲突姓名'})
 await signIn(request,'center');expect((await risk(request,personId)).effectiveCount).toBe(5)
})
test('特殊账号保底作用于不同当事人，普通请求不能伪造，高风险不降级',async({request})=>{
 await signIn(request,'special');const who=identity();const first=await submit(request,who)
 const other=await submit(request,identity())
 await signIn(request,'center')
 for(const r of [first,other]){expect(await risk(request,r.matched_person_id)).toMatchObject({effectiveCount:1,baseLevel:'LOW',finalLevel:'MEDIUM',specialFloor:true})}
 await signIn(request,'civil');const plain=await submit(request,{specialReport:true,special_report:true})
 await signIn(request,'center');expect(await risk(request,plain.matched_person_id)).toMatchObject({finalLevel:'LOW',specialFloor:false})
 for(let i=2;i<=5;i++){await signIn(request,'special');await submit(request,{...who,eventTime:`2026-08-${String(i*5).padStart(2,'0')}T12:00:00`})}
 await signIn(request,'center');expect(await risk(request,first.matched_person_id)).toMatchObject({effectiveCount:5,baseLevel:'HIGH',finalLevel:'HIGH',specialFloor:true})
})
test('单词命中、组合不跨事件，标签和合并来源能在同一事件形成可追溯证据',async({request})=>{
 await signIn(request,'civil');const single=await submit(request,{description:'虚构案例：当事人扬言扩大事态'})
 const who=identity();const marriage=await submit(request,{...who,description:'虚构案例：婚姻纠纷'})
 await submit(request,{...who,description:'虚构案例：欠款争议',eventTime:'2026-09-10T12:00:00'})
 await signIn(request,'center');const singleRisk=await risk(request,single.matched_person_id)
 expect(singleRisk.finalLevel).toBe('MEDIUM');expect(singleRisk.keywordHits[0]).toMatchObject({ruleVersion:1,terms:['扬言']})
 expect(singleRisk.keywordHits[0].evidence[0]).toMatchObject({recordId:single.id,field:'description',term:'扬言'})
 expect((await risk(request,marriage.matched_person_id)).finalLevel).toBe('LOW')
 const current=await (await request.get(`/api/intake/records/${marriage.id}`)).json()
 const tagged=await write(request,'PUT',`/api/intake/records/${marriage.id}/tags`,{version:current.version,tags:'欠',reason:'根据当事人补充资料标注'})
 expect(tagged.status(),await tagged.text()).toBe(200)
 const combo=await risk(request,marriage.matched_person_id)
 expect(combo.finalLevel).toBe('MEDIUM')
 expect(combo.keywordHits.find((h:any)=>h.terms.join('+')==='婚+欠').evidence).toEqual(expect.arrayContaining([expect.objectContaining({recordId:marriage.id,field:'tags',term:'欠'})]))
 await signIn(request,'civil');const merged=await submit(request,{...who,description:'虚构案例：婚姻纠纷涉及钱'})
 await signIn(request,'center')
 expect((await write(request,'POST',`/api/intake/records/${merged.id}/duplicate-review`,{version:merged.version,decision:'MERGE',targetRecordId:marriage.id,reason:'核对同一纠纷补充来源'})).status()).toBe(200)
 const result=await risk(request,marriage.matched_person_id)
 expect(result.effectiveCount).toBe(2)
 expect(result.keywordHits.some((h:any)=>h.terms.join('+')==='婚+钱' && h.evidence.some((e:any)=>e.recordId===merged.id))).toBe(true)
 await signIn(request,'civil');expect((await write(request,'PUT',`/api/intake/records/${marriage.id}/tags`,{version:current.version,tags:'杀人',reason:'越权'})).status()).toBe(403)
})
test('待审特殊来源不提前保底，独立或合并审核后证据生效，双审只生效一次',async({request})=>{
 await signIn(request,'civil');const who=identity();const first=await submit(request,who)
 await signIn(request,'special');const pending=await submit(request,who)
 await signIn(request,'center');expect(await risk(request,first.matched_person_id)).toMatchObject({effectiveCount:1,sourceCount:2,finalLevel:'LOW',specialFloor:false})
 const input={version:pending.version,decision:'MERGE',targetRecordId:first.id,reason:'确认同一事件并保留特殊来源'}
 const csrf=await (await request.get('/api/auth/csrf')).json()
 const results=await Promise.all([1,2].map(()=>request.post(`/api/intake/records/${pending.id}/duplicate-review`,{data:input,headers:{[csrf.headerName]:csrf.token}})))
 expect(results.map(r=>r.status()).sort()).toEqual([200,409])
 expect(await risk(request,first.matched_person_id)).toMatchObject({effectiveCount:1,sourceCount:2,finalLevel:'MEDIUM',specialFloor:true})
 await signIn(request,'civil');const independent=await submit(request,who)
 await signIn(request,'center');expect((await write(request,'POST',`/api/intake/records/${independent.id}/duplicate-review`,{version:independent.version,decision:'INDEPENDENT',reason:'相似描述但核实是另一次事件'})).status()).toBe(200)
 expect((await risk(request,first.matched_person_id)).effectiveCount).toBe(2)
 const history=await (await request.get(`/api/intake/records/${pending.id}`)).json()
 expect(history.duplicateHistory).toEqual([expect.objectContaining({decision:'MERGED',reason:input.reason,actor_username:'center'})])
})
test('身份未明不能重复审核，跨人合并和旧标签版本被拒绝',async({request})=>{
 await signIn(request,'civil');const pending=await submit(request,{personName:'身份待补',idCard:''});const who=identity();const first=await submit(request,who);const duplicate=await submit(request,who);const other=await submit(request)
 await signIn(request,'center')
 expect((await write(request,'POST',`/api/intake/records/${pending.id}/duplicate-review`,{version:pending.version,decision:'INDEPENDENT',reason:'不可跳过身份'})).status()).toBe(400)
 expect((await write(request,'POST',`/api/intake/records/${duplicate.id}/duplicate-review`,{version:duplicate.version,decision:'MERGE',targetRecordId:other.id,reason:'不可跨人'})).status()).toBe(400)
 const data={version:first.version,tags:'跳楼',reason:'虚构标签依据'}
 expect((await write(request,'PUT',`/api/intake/records/${first.id}/tags`,data)).status()).toBe(200)
 expect((await write(request,'PUT',`/api/intake/records/${first.id}/tags`,data)).status()).toBe(409)
 const result=await risk(request,first.matched_person_id);expect(result.finalLevel).toBe('MEDIUM');expect(result.keywordHits[0].evidence[0].field).toBe('tags')
})
test('关键词证据使用真实有效事件编号，待归集预览不伪造事件编号',async({request})=>{
 await signIn(request,'civil');const who=identity();const first=await submit(request,{...who,description:'虚构扬言事件'});const second=await submit(request,{...who,description:'虚构扬言事件'})
 await signIn(request,'center');expect((await write(request,'POST',`/api/intake/records/${second.id}/duplicate-review`,{version:second.version,decision:'MERGE',targetRecordId:first.id,reason:'同一事件来源'})).status()).toBe(200)
 const merged=await(await request.get(`/api/intake/records/${second.id}`)).json();expect(merged.keywordHits[0].eventId).toBe(merged.effectiveEventId)
 await signIn(request,'civil');const pending=await submit(request,{personName:'身份未明预览',idCard:'',description:'虚构扬言线索'})
 expect(pending.keywordHits[0].eventId).toBeNull()
})
