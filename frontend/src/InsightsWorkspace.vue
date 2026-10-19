<script setup lang="ts">
import {ref,onMounted} from 'vue'
import {api,ApiError,type Account} from './api'
const props=defineProps<{account:Account}>(),emit=defineEmits<{expired:[]}>()
const data=ref<any>(null),events=ref<any[]>([]),risks=ref<any[]>([]),history=ref<any[]>([]),details=ref<any[]>([]),error=ref(''),q=ref(''),term=ref(''),type=ref(''),objectId=ref(''),personId=ref(''),from=ref(''),to=ref('')
const eventPage=ref(1)
const metrics=[['reports','原始上报'],['effectiveEvents','权限内有效事件'],['tasks','待处置任务'],['overdue','部门逾期'],['pending','中心待审'],['pendingIdentity','身份待审'],['pendingDuplicate','重复待审'],['pendingChanges','修正待审']]
const distributions=[['categories','类型分布'],['sources','来源分布'],['departments','来源部门分布']]

const riskLevelLabel=(value:string)=>({LOW:'低风险',MEDIUM:'中风险',HIGH:'高风险'}[value]||value)

function evidence(s:any){try{return (typeof s==='string'?JSON.parse(s):s).flat().map((e:any)=>e.term+'：'+e.text).join('；')}catch{return ''}}
const drawer=ref(false),personDetail=ref<any>(null),riskDetail=ref<any>(null),eventId=ref<number>()
async function inspect(id:number,event?:number){await run(async()=>{const [person,risk]=await Promise.all([api<any>('/intake/people/'+id),api<any>('/intake/people/'+id+'/risk')]);personDetail.value=person;riskDetail.value=risk;eventId.value=event;drawer.value=true})}
const center=props.account.roles.includes('CENTER_ANALYST')
async function run(f:()=>Promise<void>){error.value='';try{await f()}catch(e){error.value=e instanceof Error?e.message:'请求失败';if(e instanceof ApiError&&e.status===401)emit('expired')}}
async function load(){const query='?q='+encodeURIComponent(q.value);data.value=await api('/insights/dashboard'+query);events.value=await api('/insights/events'+query+'&page='+eventPage.value+'&size=50');if(center)risks.value=await api('/insights/risks'+query)}
async function logs(){const p=new URLSearchParams({type:type.value,from:from.value,to:to.value});if(objectId.value)p.set('objectId',objectId.value);if(personId.value)p.set('personId',personId.value);history.value=await api<any[]>('/insights/history?'+p)}
onMounted(()=>run(load))
</script>
<template>
<main class="content insights-workspace">
  <div class="page-heading"><div><p class="eyebrow">业务分析与操作追溯</p><h1>业务概况与追溯</h1><p class="muted">查看权限内业务数据，追溯来源与操作记录。</p></div></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <section class="event-card">
    <h2>业务概况</h2>
    <form class="insights-toolbar" @submit.prevent="eventPage=1;run(load)"><el-input v-model="q" aria-label="统计筛选" placeholder="姓名或事件描述" clearable/><el-button type="primary" native-type="submit">查询概况</el-button></form>
    <template v-if="data">
      <div class="metric-grid"><div v-for="[key,label] in metrics" :key="key" class="metric"><span>{{label}}</span><strong>{{data[key]??0}}</strong></div></div>
      <p class="help-note">上报按来源记录计数，有效事件去重；关键词按人员内规则命中的有效事件次数统计。</p>
      <div class="distribution-grid"><section v-for="[key,label] in distributions" :key="key" class="distribution"><h3>{{label}}</h3><div v-for="(count,name) in data[key]" :key="name" class="distribution-row"><span>{{name}}</span><strong>{{count}}</strong></div><p v-if="!Object.keys(data[key]||{}).length" class="muted">暂无数据</p></section></div>
    </template>
  </section>
  <section v-if="data" class="event-card"><h2>热点关键词</h2><div class="keyword-list"><el-button v-for="(count,word) in data.keywords" :key="word" @click="term=String(word);run(async()=>{details=await api<any[]>('/insights/keyword?term='+encodeURIComponent(term)+'&q='+encodeURIComponent(q))})">{{word}}（{{count}}）</el-button><p v-if="!Object.keys(data.keywords||{}).length" class="muted">当前筛选范围内暂无关键词命中。</p></div><el-table :data="details" empty-text="选择关键词后查看人员、事件与命中依据"><el-table-column prop="personId" label="人员编号" width="100"/><el-table-column prop="eventId" label="事件编号" width="100"/><el-table-column label="详情" width="160"><template #default="{row}"><el-button link type="primary" @click="inspect(row.personId,row.eventId)">查看人员与事件</el-button></template></el-table-column><el-table-column label="命中依据" min-width="240"><template #default="{row}">{{evidence(row.evidence)}}</template></el-table-column></el-table></section>
  <section class="event-card"><h2>筛选范围内导出</h2><p class="help-note">按当前姓名或事件描述筛选及账号权限导出。</p><div class="export-actions"><a :href="'/api/insights/export?kind=events&q='+encodeURIComponent(q)">导出事件 CSV</a><a :href="'/api/insights/export?kind=statistics&q='+encodeURIComponent(q)">导出统计 CSV</a><a v-if="center" :href="'/api/insights/export?kind=risks&q='+encodeURIComponent(q)">导出风险 CSV</a></div></section>
  <section class="event-card"><h2>事件明细</h2><el-table :data="events"><el-table-column prop="id" label="来源编号" width="100"/><el-table-column prop="eventId" label="有效事件编号" width="120"/><el-table-column prop="name" label="姓名" min-width="140" show-overflow-tooltip/><el-table-column prop="description" label="描述" min-width="240" show-overflow-tooltip/><el-table-column prop="source" label="来源" min-width="140"/></el-table><div class="pagination"><el-pagination v-if="data" v-model:current-page="eventPage" :total="data.reports" :page-size="50" layout="total, prev, pager, next" @current-change="run(load)"/></div></section>
  <section v-if="center" class="event-card"><h2>风险明细</h2><el-table :data="risks"><el-table-column prop="id" label="档案编号" width="100"/><el-table-column prop="name" label="姓名" min-width="160" show-overflow-tooltip/><el-table-column label="等级"><template #default="{row}">{{riskLevelLabel(row.level)}}</template></el-table-column><el-table-column prop="effectiveCount" label="有效次数"/><el-table-column label="档案" width="120"><template #default="{row}"><el-button link type="primary" @click="inspect(row.id)">查看档案</el-button></template></el-table-column></el-table></section>
  <section class="event-card"><h2>操作历史</h2><el-form class="history-filter" label-position="top" @submit.prevent="run(logs)"><el-form-item label="对象类型"><el-input v-model="type" placeholder="对象类型，可留空"/></el-form-item><el-form-item label="对象编号"><el-input v-model="objectId" placeholder="对象编号，可留空"/></el-form-item><el-form-item label="人员编号"><el-input v-model="personId" placeholder="人员编号，可留空"/></el-form-item><el-form-item label="起始时间"><el-input v-model="from" placeholder="起始时间 2026-10-01T00:00:00"/></el-form-item><el-form-item label="截止时间"><el-input v-model="to" placeholder="截止时间 2026-10-31T23:59:59"/></el-form-item><div class="history-actions"><el-button type="primary" native-type="submit">查询操作历史</el-button></div></el-form><el-table :data="history"><el-table-column type="expand"><template #default="{row}"><pre class="history-detail">{{row}}</pre></template></el-table-column><el-table-column prop="target_type" label="对象" min-width="140"/><el-table-column prop="target_id" label="编号" width="90"/><el-table-column prop="created_at" label="时间" min-width="180"/><el-table-column prop="state" label="业务状态" min-width="120"/><el-table-column label="操作者" min-width="120"><template #default="{row}">{{row.actor_username||row.actor||row.requester}}</template></el-table-column><el-table-column label="原因/结果" min-width="200"><template #default="{row}">{{row.reason||row.content||row.result||row.state}}</template></el-table-column></el-table></section>
<el-drawer v-model="drawer" title="关联人员与有效事件" size="min(700px,100vw)"><template v-if="personDetail && riskDetail"><h2>{{personDetail.person.name}}</h2><p>档案 #{{personDetail.person.id}} · 电话 {{personDetail.person.phone}} · 身份证 {{personDetail.person.idCard||'未填写'}}</p><p>有效次数 {{riskDetail.effectiveCount}} · 最终风险 {{riskLevelLabel(riskDetail.finalLevel)}}</p><section v-for="e in riskDetail.events.filter((e:any)=>!eventId||e.id===eventId)" :key="e.id"><h3>有效事件 #{{e.id}} · {{e.eventTime}}</h3><p v-for="source in e.sources" :key="source.recordId">来源记录 #{{source.recordId}} · {{source.source}} · {{source.description}}</p></section></template></el-drawer>
</main>
</template>
<style scoped>
.event-card { padding: 24px; margin-bottom: 24px; }
.event-card h2 { margin-bottom: 24px; font-size: 18px; font-weight: 600; }
.insights-toolbar { display: flex; gap: 12px; margin-bottom: 24px; }
.insights-toolbar .el-input { max-width: 420px; }
.metric-grid { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 16px; }
.metric { padding: 18px; background: #f5f9f7; border: 1px solid #e3ede8; border-radius: 7px; }
.metric span { display: block; font-size: 13px; color: #657585; margin-bottom: 12px; }
.metric strong { font-size: 26px; font-weight: 600; color: #246c64; }
.help-note { color: #657585; font-size: 13px; line-height: 1.9; margin: 18px 0; }
.distribution-grid { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 20px; }
.distribution { padding: 18px; border: 1px solid #e2e8ef; border-radius: 7px; }
.distribution h3 { font-size: 14px; margin-bottom: 16px; }
.distribution-row { display: flex; gap: 16px; justify-content: space-between; padding: 9px 0; border-top: 1px solid #edf0f4; font-size: 13px; line-height: 1.6; }
.distribution-row span { overflow-wrap: anywhere; min-width: 0; }
.distribution-row strong { flex-shrink: 0; color: #246c64; }
.keyword-list, .export-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 20px; }
.keyword-list .el-button { margin-left: 0; }
.export-actions { margin-bottom: 0; }
.export-actions a { color: #246c64; border: 1px solid #cddfd7; border-radius: 4px; padding: 8px 14px; text-decoration: none; font-size: 13px; }
.export-actions a:hover { background: #f0f7f3; }
.pagination { display: flex; justify-content: flex-end; margin-top: 20px; }
.history-filter { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); column-gap: 20px; margin-bottom: 20px; }
.history-actions { display: flex; align-items: flex-end; padding-bottom: 18px; }
.history-detail { margin: 0; padding: 20px; white-space: pre-wrap; overflow-wrap: anywhere; background: #f6f8fa; }
@media(max-width: 1000px) { .distribution-grid { grid-template-columns: minmax(0,1fr); } .history-filter { grid-template-columns: repeat(2,minmax(0,1fr)); } }
@media(max-width: 600px) { .event-card { padding: 16px; } .metric-grid { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 10px; } .metric { padding: 12px; } .history-filter { grid-template-columns: minmax(0,1fr); } .insights-toolbar { flex-wrap: wrap; } .insights-toolbar .el-input { max-width: none; flex-basis: 100%; } .pagination { justify-content: flex-start; } }
</style>
