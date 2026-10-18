<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, ApiError, type Account } from './api'
const props=defineProps<{account:Account}>()
const emit=defineEmits<{expired:[]}>()
const analyst=computed(()=>props.account.roles.includes('CENTER_ANALYST'))
const error=ref(''), message=ref(''), busy=ref(false)
const requests=ref<any[]>([]), people=ref<any[]>([]), history=ref<any[]>([])
const recordId=ref<number>(), record=ref<any>(null), selected=ref<any>(null), targetId=ref<number>()
const reason=ref(''), reviewReason=ref(''), query=ref(''), kind=ref('CORRECT')
const content=ref({personName:'',idCard:'',phone:'',eventTime:'',description:''})
function decoded(value:any){if(typeof value!=='string')return value||{};try{return JSON.parse(value)}catch{return {}}}
const fields=[['姓名','party_name','personName'],['身份证','party_id_card','idCard'],['手机号','party_phone','phone'],['发生时间','occurred_at','eventTime'],['问题描述','problem_description','description']]
const profile=ref({name:'',idCard:'',phone:''})
async function run(action:()=>Promise<void>){busy.value=true;error.value='';message.value='';try{await action()}catch(e){error.value=e instanceof Error?e.message:'操作失败';if(e instanceof ApiError&&e.status===401)emit('expired')}finally{busy.value=false}}
async function refresh(){requests.value=await api<any[]>('/archive/requests');if(analyst.value){people.value=await api<any[]>('/archive/people?q='+encodeURIComponent(query.value));history.value=await api<any[]>('/archive/history')}}
function write(path:string,body:unknown,method='POST'){return api(path,{method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})}
function choose(p:any){selected.value=p;profile.value={name:p.name,idCard:p.id_card||'',phone:p.phone}}
async function loadRecord(){await run(async()=>{record.value=null;const r=await api<any>('/intake/records/'+recordId.value);record.value=r;content.value={personName:r.party_name||'',idCard:r.party_id_card||'',phone:r.party_phone||'',eventTime:r.occurred_at,description:r.problem_description}})}
async function submit(){await run(async()=>{await write('/archive/records/'+record.value.id+'/requests',{version:record.value.version,kind:kind.value,payload:content.value,reason:reason.value});await refresh();message.value='申请已提交，审核前继续使用旧版本'})}
async function review(id:number,approve:boolean){await run(async()=>{await write('/archive/requests/'+id+'/review',{approve,reason:reviewReason.value});await refresh();message.value='审核完成；批准的修正已重新处理'})}
async function saveProfile(){await run(async()=>{const p=await write('/archive/people/'+selected.value.id,{...profile.value,version:selected.value.version,reason:reason.value},'PUT');await refresh();choose(p);message.value='档案已修正'})}
async function move(split:boolean){await run(async()=>{const target=people.value.find(p=>p.id===targetId.value);if(!target)throw new Error('请选择目标档案');await write(split?'/archive/records/'+record.value.id+'/split':'/archive/people/'+selected.value.id+'/merge',{version:split?record.value.version:selected.value.version,targetPersonId:target.id,targetVersion:target.version,reason:reason.value});record.value=null;selected.value=null;await refresh();message.value='归档变更已保存，迁入迁出档案风险已重算'})}
onMounted(()=>run(refresh))
</script>
<template>
<main class="content archive-workspace"><div class="page-heading"><div><p class="eyebrow">数据修正与归档管理</p><h1>修正与归档审核</h1><p class="muted">核实来源变更与档案关系，保留审核依据和历史记录。</p></div></div><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-alert v-if="message" :title="message" type="success" :closable="false"/>
<section class="event-card"><h2>来源记录修正 / 删除申请</h2><form class="archive-toolbar" @submit.prevent="loadRecord"><el-input-number v-model="recordId" :min="1" aria-label="来源记录编号" placeholder="来源编号"/><el-button type="primary" native-type="submit" :disabled="busy||!recordId">加载记录</el-button></form>
<el-form v-if="record" class="detail-panel record-form" label-position="top"><p>记录 #{{record.id}} · 版本 {{record.version}} · 原始上报保持可追溯</p><el-form-item label="申请类型"><el-select v-model="kind"><el-option label="修正" value="CORRECT"/><el-option label="删除" value="DELETE"/></el-select></el-form-item>
<template v-if="kind==='CORRECT'"><el-form-item label="姓名"><el-input v-model="content.personName"/></el-form-item><el-form-item label="身份证号"><el-input v-model="content.idCard"/></el-form-item><el-form-item label="手机号"><el-input v-model="content.phone"/></el-form-item><el-form-item label="发生时间"><el-input v-model="content.eventTime" placeholder="2026-10-01T10:00:00"/></el-form-item><el-form-item class="full-width" label="问题描述"><el-input v-model="content.description" type="textarea"/></el-form-item></template>
<el-form-item class="full-width" label="变更理由"><el-input v-model="reason" type="textarea"/></el-form-item><div class="form-actions"><el-button type="primary" :disabled="busy" @click="submit">提交变更申请</el-button></div></el-form></section>
<section class="event-card"><h2>变更申请与审核记录</h2><div class="archive-toolbar"><el-input v-if="analyst" v-model="reviewReason" aria-label="审核理由" placeholder="必填：审核理由"/><el-button :disabled="busy" @click="run(refresh)">刷新</el-button></div><el-table :data="requests"><el-table-column type="expand"><template #default="{row}"><div class="request-detail"><p>申请人：{{row.requester}} · {{row.created_at}} · 审核人：{{row.reviewer||'待审'}} · {{row.reviewed_at||''}}</p><table style="width:100%;table-layout:fixed;overflow-wrap:anywhere"><thead><tr><th>字段</th><th>变更前</th><th>申请变更后</th></tr></thead><tbody><tr v-for="f in fields" :key="f[0]"><th>{{f[0]}}</th><td>{{decoded(row.before_data)[f[1]]}}</td><td>{{row.kind==='DELETE'?'申请删除该来源记录':decoded(row.payload)[f[2]]}}</td></tr></tbody></table></div></template></el-table-column><el-table-column prop="id" label="申请"/><el-table-column prop="record_id" label="来源记录"/><el-table-column prop="kind" label="类型"/><el-table-column prop="state" label="状态"/><el-table-column prop="reason" label="申请理由"/><el-table-column prop="review_reason" label="审核理由"/><el-table-column v-if="analyst" label="审核"><template #default="{row}"><template v-if="row.state==='PENDING'"><el-button :disabled="busy" @click="review(row.id,true)">批准</el-button><el-button :disabled="busy" @click="review(row.id,false)">驳回</el-button></template></template></el-table-column></el-table></section>
<template v-if="analyst"><section class="event-card"><h2>人员档案修正、合并与来源拆分</h2><form class="archive-toolbar" @submit.prevent="run(refresh)"><el-input v-model="query" aria-label="搜索档案" placeholder="姓名、电话或身份证" clearable/><el-button type="primary" native-type="submit" :disabled="busy">查询档案</el-button></form><p class="muted">点击档案行，可修正基本信息或选择合并来源。</p><el-table class="people-table" :data="people" highlight-current-row @row-click="choose"><el-table-column prop="id" label="编号"/><el-table-column prop="name" label="姓名" min-width="150" show-overflow-tooltip/><el-table-column prop="id_card" label="身份证" min-width="190"/><el-table-column prop="phone" label="电话" min-width="140"/><el-table-column prop="version" label="版本"/></el-table>
<section v-if="selected" class="detail-panel"><h3>当前档案 #{{selected.id}} · 基本信息修正</h3><el-form class="profile-form" label-position="top"><el-form-item label="姓名"><el-input v-model="profile.name"/></el-form-item><el-form-item label="身份证"><el-input v-model="profile.idCard"/></el-form-item><el-form-item label="电话"><el-input v-model="profile.phone"/></el-form-item><el-form-item class="full-width" label="核实理由"><el-input v-model="reason" type="textarea"/></el-form-item><div class="form-actions"><el-button type="primary" :disabled="busy" @click="saveProfile">保存基本信息</el-button></div></el-form></section>
<section class="detail-panel"><h3>档案合并与来源拆分</h3><p class="help-note">合并需姓名一致且身份证无冲突；请核实身份后操作。拆分将加载的单条来源归入目标档案，并重新检测重复。</p><el-form class="move-form" label-position="top"><el-form-item label="目标档案"><el-select v-model="targetId" aria-label="目标档案" placeholder="选择目标档案"><el-option v-for="p in people" :key="p.id" :label="`${p.id} ${p.name} ${p.phone}`" :value="p.id"/></el-select></el-form-item><el-form-item label="归档变更理由"><el-input v-model="reason" aria-label="归档变更理由" type="textarea" :rows="3" placeholder="必填：可靠身份依据及变更理由"/></el-form-item><div class="form-actions"><el-button :disabled="busy||!selected" @click="move(false)">合并当前档案到目标</el-button><el-button :disabled="busy||!record" @click="move(true)">拆分已加载来源到目标</el-button></div></el-form></section></section>
<section class="event-card"><h2>归档变更历史</h2><el-table :data="history"><el-table-column prop="kind" label="类型"/><el-table-column prop="from_person_id" label="原档案"/><el-table-column prop="to_person_id" label="目标档案"/><el-table-column prop="record_id" label="来源记录"/><el-table-column prop="reason" label="理由"/><el-table-column prop="actor" label="操作者"/><el-table-column prop="created_at" label="时间"/></el-table></section></template>
</main>
</template>

<style scoped>
.event-card { padding: 24px; margin-bottom: 24px; }
.event-card h2 { font-size: 18px; font-weight: 600; margin-bottom: 24px; }
.event-card h3 { font-size: 16px; margin-bottom: 20px; }
.archive-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 20px; }
.archive-toolbar .el-input { max-width: 420px; }
.detail-panel { margin-top: 24px; padding-top: 24px; border-top: 1px solid #edf0f4; }
.record-form, .profile-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 24px; }
.record-form > p, .full-width, .form-actions { grid-column: 1 / -1; }
.form-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.form-actions .el-button + .el-button { margin-left: 0; }
.move-form { max-width: 680px; }
.help-note { color: #657585; font-size: 13px; line-height: 1.9; margin-bottom: 24px; }
.request-detail { padding: 20px 24px; }
.request-detail p { line-height: 1.8; }
.request-detail table { border-collapse: collapse; font-size: 13px; }
.request-detail th, .request-detail td { padding: 12px; border: 1px solid #e2e8ef; text-align: left; vertical-align: top; }
.request-detail th { background: #f6f8fa; font-weight: 500; }
.people-table :deep(.el-table__row) { cursor: pointer; }
@media (max-width: 850px) {
  .record-form, .profile-form { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 600px) {
  .event-card { padding: 16px; }
  .archive-toolbar .el-input { max-width: none; flex-basis: 100%; }
  .request-detail { padding: 12px; }
}
</style>
