<script setup lang="ts">
import {ref,onMounted,computed} from 'vue'
import {api,ApiError,type Account} from './api'
const props=defineProps<{account:Account}>(),emit=defineEmits<{expired:[]}>()
const data=ref<any>(null),events=ref<any[]>([]),risks=ref<any[]>([]),history=ref<any[]>([]),details=ref<any[]>([]),error=ref(''),q=ref(''),term=ref(''),type=ref(''),objectId=ref(''),personId=ref(''),from=ref(''),to=ref('')
const historyPage=ref(1),historyPageSize=10
const visibleHistory=computed(()=>history.value.slice((historyPage.value-1)*historyPageSize,historyPage.value*historyPageSize))
const eventPage=ref(1)
const eventPageSize=10
const metrics=[['reports','原始上报'],['effectiveEvents','权限内有效事件'],['tasks','待处置任务'],['overdue','部门逾期'],['pending','中心待审'],['pendingIdentity','身份待审'],['pendingDuplicate','重复待审'],['pendingChanges','修正待审']]

const riskLevelLabel=(value:string)=>({LOW:'低风险',MEDIUM:'中风险',HIGH:'高风险'}[value]||value)

function evidence(s:any){try{return (typeof s==='string'?JSON.parse(s):s).flat().map((e:any)=>e.term+'：'+e.text).join('；')}catch{return ''}}
const expandedEvents=ref<number[]>([])
const formatTime=(value?:string)=>value?value.replace("T"," "):"暂无记录"
const riskTagType=(value:string)=>value==='HIGH'?'danger':value==='MEDIUM'?'warning':'success'
const drawer=ref(false),personDetail=ref<any>(null),riskDetail=ref<any>(null),eventId=ref<number>()
async function inspect(id:number,event?:number){await run(async()=>{const [person,risk]=await Promise.all([api<any>('/intake/people/'+id),api<any>('/intake/people/'+id+'/risk')]);personDetail.value=person;riskDetail.value=risk;eventId.value=event;expandedEvents.value=event?[event]:risk.events.length?[risk.events[0].id]:[];drawer.value=true})}
const center=props.account.roles.includes('CENTER_ANALYST')
async function run(f:()=>Promise<void>){error.value='';try{await f()}catch(e){error.value=e instanceof Error?e.message:'请求失败';if(e instanceof ApiError&&e.status===401)emit('expired')}}
async function load(){const query='?q='+encodeURIComponent(q.value);data.value=await api('/insights/dashboard'+query);events.value=await api('/insights/events'+query+'&page='+eventPage.value+'&size='+eventPageSize);if(center)risks.value=await api('/insights/risks'+query)}
async function logs(){const p=new URLSearchParams({type:type.value,from:from.value||'',to:to.value||''});if(objectId.value)p.set('objectId',objectId.value);if(personId.value)p.set('personId',personId.value);const rows=await api<any[]>('/insights/history?'+p);history.value=rows;historyPage.value=1}
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
      <section class="department-distribution"><h3>来源部门分布</h3><div class="department-list"><div v-for="(count,name) in data.departments" :key="name" class="department-item"><span>{{name}}</span><strong>{{count}}</strong></div><p v-if="!Object.keys(data.departments||{}).length" class="muted">暂无数据</p></div></section>
    </template>
  </section>
  <section v-if="data" class="event-card"><h2>热点关键词</h2><div class="keyword-list"><el-button v-for="(count,word) in data.keywords" :key="word" @click="term=String(word);run(async()=>{details=await api<any[]>('/insights/keyword?term='+encodeURIComponent(term)+'&q='+encodeURIComponent(q))})">{{word}}（{{count}}）</el-button><p v-if="!Object.keys(data.keywords||{}).length" class="muted">当前筛选范围内暂无关键词命中。</p></div><el-table :data="details" empty-text="选择关键词后查看人员、事件与命中依据"><el-table-column prop="personId" label="人员编号" width="100"/><el-table-column prop="eventId" label="事件编号" width="100"/><el-table-column label="详情" width="160"><template #default="{row}"><el-button link type="primary" @click="inspect(row.personId,row.eventId)">查看人员与事件</el-button></template></el-table-column><el-table-column label="命中依据" min-width="240"><template #default="{row}">{{evidence(row.evidence)}}</template></el-table-column></el-table></section>
  <section class="event-card"><h2>筛选范围内导出</h2><p class="help-note">按当前姓名或事件描述筛选及账号权限导出。</p><div class="export-actions"><a :href="'/api/insights/export?kind=events&q='+encodeURIComponent(q)">导出事件 CSV</a><a :href="'/api/insights/export?kind=statistics&q='+encodeURIComponent(q)">导出统计 CSV</a><a v-if="center" :href="'/api/insights/export?kind=risks&q='+encodeURIComponent(q)">导出风险 CSV</a></div></section>
  <section class="event-card"><h2>事件明细</h2><el-table :data="events" max-height="420"><el-table-column prop="id" label="来源编号" width="100"/><el-table-column prop="eventId" label="有效事件编号" width="120"/><el-table-column prop="name" label="姓名" min-width="140" show-overflow-tooltip/><el-table-column prop="description" label="描述" min-width="240" show-overflow-tooltip/><el-table-column prop="source" label="来源" min-width="140" show-overflow-tooltip/></el-table><div class="pagination"><el-pagination v-if="data" v-model:current-page="eventPage" :total="data.reports" :page-size="eventPageSize" layout="total, prev, pager, next" @current-change="run(load)"/></div></section>
  <section v-if="center" class="event-card"><h2>风险明细</h2><el-table :data="risks"><el-table-column prop="id" label="档案编号" width="100"/><el-table-column prop="name" label="姓名" min-width="160" show-overflow-tooltip/><el-table-column label="等级"><template #default="{row}">{{riskLevelLabel(row.level)}}</template></el-table-column><el-table-column prop="effectiveCount" label="有效次数"/><el-table-column label="档案" width="120"><template #default="{row}"><el-button link type="primary" @click="inspect(row.id)">查看档案</el-button></template></el-table-column></el-table></section>
  <section class="event-card"><h2>操作历史</h2><el-form class="history-filter" label-position="top" @submit.prevent="run(logs)"><el-form-item label="对象类型"><el-input v-model="type" placeholder="对象类型，可留空"/></el-form-item><el-form-item label="对象编号"><el-input v-model="objectId" placeholder="对象编号，可留空"/></el-form-item><el-form-item label="人员编号"><el-input v-model="personId" placeholder="人员编号，可留空"/></el-form-item><el-form-item label="起始时间"><el-date-picker v-model="from" type="datetime" aria-label="起始时间" placeholder="选择起始时间" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DDTHH:mm:ss" :editable="false" clearable/></el-form-item><el-form-item label="截止时间"><el-date-picker v-model="to" type="datetime" aria-label="截止时间" placeholder="选择截止时间" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DDTHH:mm:ss" :editable="false" clearable/></el-form-item><div class="history-actions"><el-button type="primary" native-type="submit">查询操作历史</el-button></div></el-form><el-table :data="visibleHistory" max-height="420"><el-table-column type="expand"><template #default="{row}"><pre class="history-detail">{{row}}</pre></template></el-table-column><el-table-column prop="target_type" label="对象" min-width="140"/><el-table-column prop="target_id" label="编号" width="90"/><el-table-column prop="created_at" label="时间" min-width="180"/><el-table-column prop="state" label="业务状态" min-width="120"/><el-table-column label="操作者" min-width="120"><template #default="{row}">{{row.actor_username||row.actor||row.requester}}</template></el-table-column><el-table-column label="原因/结果" min-width="200" show-overflow-tooltip><template #default="{row}">{{row.reason||row.content||row.result||row.state}}</template></el-table-column></el-table><div v-if="history.length" class="pagination"><el-pagination v-model:current-page="historyPage" :total="history.length" :page-size="historyPageSize" layout="total, prev, pager, next"/></div></section>
<el-drawer v-model="drawer" title="关联人员与有效事件" size="min(820px,100vw)" class="archive-drawer">
  <div v-if="personDetail && riskDetail" class="archive-content">
    <div class="archive-heading"><div><p class="archive-kicker">人员风险档案 · #{{personDetail.person.id}}</p><h2>{{personDetail.person.name}}</h2></div><el-tag :type="riskTagType(riskDetail.finalLevel)" size="large" effect="light">{{riskLevelLabel(riskDetail.finalLevel)}}</el-tag></div>
    <section class="archive-section"><h3>基本信息</h3><dl class="profile-grid"><div><dt>联系电话</dt><dd>{{personDetail.person.phone||'未填写'}}</dd></div><div><dt>身份证号</dt><dd>{{personDetail.person.idCard||'未填写'}}</dd></div></dl></section>
    <div class="archive-metrics"><div><span>有效事件</span><strong>{{riskDetail.effectiveCount}}<small> 次</small></strong></div><div><span>来源上报</span><strong>{{riskDetail.sourceCount}}<small> 条</small></strong></div><div><span>最近有效事件</span><strong class="archive-time">{{formatTime(riskDetail.lastEventTime)}}</strong></div></div>
    <section class="archive-section"><h3>风险评估依据</h3><dl class="profile-grid"><div><dt>次数基础等级</dt><dd>{{riskLevelLabel(riskDetail.baseLevel)}}</dd></div><div><dt>规则评估等级</dt><dd>{{riskLevelLabel(riskDetail.ruleLevel)}}</dd></div><div><dt>有效人工调整</dt><dd>{{riskDetail.manualLevel?riskLevelLabel(riskDetail.manualLevel):'无有效调整'}}</dd></div><div><dt>当前规则版本</dt><dd>{{riskDetail.ruleVersion}}</dd></div></dl><p class="archive-note">有效事件去重计数；多条来源归集到同一事件，不重复累计。</p><el-alert v-if="riskDetail.specialFloor" title="存在可信特殊账号来源，最终等级至少为中风险" type="warning" :closable="false" show-icon/></section>
    <section class="archive-section"><div class="archive-section-heading"><h3>有效事件与来源</h3><span class="muted">{{eventId?'当前关联事件':'展开查看归集来源'}}</span></div><el-empty v-if="!riskDetail.events.length" description="暂无有效事件" :image-size="64"/><el-collapse v-else v-model="expandedEvents"><el-collapse-item v-for="e in riskDetail.events.filter((e:any)=>!eventId||e.id===eventId)" :key="e.id" :name="e.id"><template #title><div class="archive-event-title"><strong>有效事件 #{{e.id}}</strong><span>{{formatTime(e.eventTime)}} · {{e.sources.length}} 条来源</span></div></template><article v-for="source in e.sources" :key="source.recordId" class="archive-source"><div class="archive-source-heading"><strong>来源记录 #{{source.recordId}}</strong><el-tag v-if="source.specialReport" type="warning" size="small">特殊账号来源</el-tag></div><p class="archive-source-meta">{{source.departmentName||'未知部门'}} · {{source.source||'未注明来源'}} · {{formatTime(source.eventTime)}}</p><p class="archive-description">{{source.description||'暂无描述'}}</p><p class="archive-tags">人工标签：{{source.tags||'无'}}</p></article></el-collapse-item></el-collapse></section>
    <section class="archive-section"><h3>关键词命中证据</h3><p v-if="!riskDetail.keywordHits.filter((hit:any)=>!eventId||hit.eventId===eventId).length" class="archive-note">当前查看范围内暂无关键词命中。</p><article v-for="hit in riskDetail.keywordHits.filter((hit:any)=>!eventId||hit.eventId===eventId)" :key="hit.eventId+'-'+hit.ruleId" class="archive-source"><h4>{{hit.ruleName}}</h4><p class="archive-source-meta">有效事件 #{{hit.eventId}} · 规则版本 {{hit.ruleVersion}}</p><p v-for="(item,index) in hit.evidence" :key="index" class="archive-description">来源 #{{item.recordId}} · {{item.field==='tags'?'人工标签':'问题描述'}}：{{item.text.slice(0,item.start)}}<mark>{{item.text.slice(item.start,item.end)}}</mark>{{item.text.slice(item.end)}}</p></article></section>
  </div>
</el-drawer>
</main>
</template>
<style scoped>
.archive-content { color: #243b53; font-size: 14px; line-height: 1.7; }
.archive-heading, .archive-section-heading, .archive-source-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.archive-heading { padding: 20px; background: #f0f7f5; border-radius: 10px; margin-bottom: 20px; }
.archive-kicker { margin: 0 0 6px; font-size: 12px; color: #657585; }
.archive-heading h2 { margin: 0; font-size: 24px; overflow-wrap: anywhere; }
.archive-section { margin-bottom: 20px; padding: 20px; border: 1px solid #e2e8ef; border-radius: 8px; }
.archive-section h3 { font-size: 15px; margin: 0 0 16px; }
.profile-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 16px; margin: 0; }
.profile-grid dt { color: #657585; font-size: 12px; }
.profile-grid dd { margin: 4px 0 0; overflow-wrap: anywhere; }
.archive-metrics { display: grid; grid-template-columns: 1fr 1fr 2fr; gap: 12px; margin-bottom: 20px; }
.archive-metrics > div { padding: 16px; background: #f5f8fb; border-radius: 8px; min-width: 0; }
.archive-metrics span { display: block; font-size: 12px; color: #657585; }
.archive-metrics strong { display: block; font-size: 24px; color: #246c64; margin-top: 8px; }
.archive-metrics small { font-size: 12px; font-weight: normal; }
.archive-metrics .archive-time { font-size: 14px; overflow-wrap: anywhere; }
.archive-note { font-size: 13px; color: #657585; margin: 16px 0 0; }
.archive-section :deep(.el-alert) { margin-top: 16px; }
.archive-event-title { display: flex; flex-direction: column; line-height: 1.7; padding: 12px 0; }
.archive-event-title span, .archive-source-meta, .archive-tags { font-size: 12px; color: #657585; }
.archive-section :deep(.el-collapse-item__header) { height: auto; min-height: 56px; }
.archive-source { padding: 16px; margin-top: 12px; background: #f6f8fa; border-radius: 6px; overflow-wrap: anywhere; }
.archive-source h4 { margin: 0; }
.archive-description { white-space: pre-wrap; margin: 12px 0; }
.archive-source-meta, .archive-tags { margin: 8px 0 0; }
@media(max-width: 600px) { .archive-section, .archive-heading { padding: 16px; } .profile-grid { grid-template-columns: 1fr; } .archive-metrics { grid-template-columns: repeat(2,minmax(0,1fr)); } .archive-metrics > div:last-child { grid-column: 1 / -1; } }
.event-card { padding: 24px; margin-bottom: 24px; }
.event-card h2 { margin-bottom: 24px; font-size: 18px; font-weight: 600; }
.insights-toolbar { display: flex; gap: 12px; margin-bottom: 24px; }
.insights-toolbar .el-input { max-width: 420px; }
.metric-grid { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 16px; }
.metric { padding: 18px; background: #f5f9f7; border: 1px solid #e3ede8; border-radius: 7px; }
.metric span { display: block; font-size: 13px; color: #657585; margin-bottom: 12px; }
.metric strong { font-size: 26px; font-weight: 600; color: #246c64; }
.help-note { color: #657585; font-size: 13px; line-height: 1.9; margin: 18px 0; }
.department-distribution { border-top: 1px solid #edf0f4; padding-top: 16px; }
.department-distribution h3 { font-size: 14px; margin: 0 0 12px; }
.department-list { display: flex; flex-wrap: wrap; gap: 12px; }
.department-item { display: flex; align-items: center; gap: 24px; padding: 10px 14px; background: #f5f9f7; border-radius: 6px; min-width: 0; font-size: 13px; }
.department-item span { overflow-wrap: anywhere; }
.department-item strong { color: #246c64; flex-shrink: 0; }
.keyword-list, .export-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-bottom: 20px; }
.keyword-list .el-button { margin-left: 0; }
.export-actions { margin-bottom: 0; }
.export-actions a { color: #246c64; border: 1px solid #cddfd7; border-radius: 4px; padding: 8px 14px; text-decoration: none; font-size: 13px; }
.export-actions a:hover { background: #f0f7f3; }
.pagination { display: flex; justify-content: flex-end; margin-top: 20px; }
.history-filter { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); column-gap: 20px; margin-bottom: 20px; }
.history-filter :deep(.el-date-editor.el-input) { width: 100%; min-width: 0; }
.history-actions { display: flex; align-items: flex-end; padding-bottom: 18px; }
.history-detail { margin: 0; padding: 20px; white-space: pre-wrap; overflow-wrap: anywhere; background: #f6f8fa; }
@media(max-width: 1000px) { .history-filter { grid-template-columns: repeat(2,minmax(0,1fr)); } }
@media(max-width: 600px) { .event-card { padding: 16px; } .metric-grid { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 10px; } .metric { padding: 12px; } .history-filter { grid-template-columns: minmax(0,1fr); } .insights-toolbar { flex-wrap: wrap; } .insights-toolbar .el-input { max-width: none; flex-basis: 100%; } .pagination { justify-content: flex-start; } }
</style>
