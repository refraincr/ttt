<script setup lang="ts">
import {ref,onMounted,computed} from 'vue'
import {api,ApiError,type Account} from './api'
import './insights.css'
const props=defineProps<{account:Account}>(),emit=defineEmits<{expired:[]}>()
const data=ref<any>(null),events=ref<any[]>([]),risks=ref<any[]>([]),history=ref<any[]>([]),details=ref<any[]>([]),error=ref(''),q=ref(''),term=ref(''),type=ref(''),objectId=ref(''),personId=ref(''),from=ref(''),to=ref('')
const busy=ref(false),appliedQuery=ref(''),loaded=ref(false),historyLoaded=ref(false),keywordLoaded=ref(false)
const departmentRows=computed(()=>Object.entries(data.value?.departments||{}).sort((a,b)=>Number(b[1])-Number(a[1])).map(([name,count])=>({name,count:Number(count)})))
const maxDepartment=computed(()=>Math.max(1,...departmentRows.value.map(row=>row.count)))
const exportUrl=(kind:string)=>'/api/insights/export?'+new URLSearchParams({kind,q:appliedQuery.value})
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
async function run(f:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';try{await f()}catch(e){error.value=e instanceof Error?e.message:'请求失败';if(e instanceof ApiError&&e.status===401)emit('expired')}finally{busy.value=false}}
async function load(query=appliedQuery.value,page=eventPage.value){
  const suffix='?q='+encodeURIComponent(query)
  const [dashboard,eventRows,riskRows]=await Promise.all([api('/insights/dashboard'+suffix),api<any[]>('/insights/events'+suffix+'&page='+page+'&size='+eventPageSize),center?api<any[]>('/insights/risks'+suffix):Promise.resolve([])])
  data.value=dashboard;events.value=eventRows;risks.value=riskRows;eventPage.value=page;appliedQuery.value=query;loaded.value=true
  term.value='';details.value=[];keywordLoaded.value=false
}
async function drill(word:string){const rows=await api<any[]>('/insights/keyword?term='+encodeURIComponent(word)+'&q='+encodeURIComponent(appliedQuery.value));term.value=word;details.value=rows;keywordLoaded.value=true}
async function logs(){
  if(from.value&&to.value&&from.value>to.value)throw new Error('起始时间不能晚于截止时间')
  if([objectId.value,personId.value].some(value=>value&&!/^[1-9]\d*$/.test(value)))throw new Error('对象编号与人员编号须为正整数')
  const p=new URLSearchParams({type:type.value,from:from.value||'',to:to.value||''});if(objectId.value)p.set('objectId',objectId.value);if(personId.value)p.set('personId',personId.value);const rows=await api<any[]>('/insights/history?'+p);history.value=rows;historyPage.value=1;historyLoaded.value=true
}
onMounted(()=>run(()=>load()))
</script>
<template>
<main class="content insights-workspace" :aria-busy="busy">
  <div class="page-heading"><div><p class="eyebrow">业务分析与操作追溯</p><h1>业务概况与追溯</h1><p class="muted">从业务全貌到事件依据，让每一次查询有据可循。</p></div><el-tag effect="plain" size="large">{{center?'中心分析视角':'部门授权视角'}}</el-tag></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <section class="event-card insights-query">
    <div class="insights-heading"><div><p class="eyebrow">数据范围</p><h2>业务概况</h2></div><span class="insights-scope">{{center?'统计账号权限内全部业务':'仅统计当前账号授权业务'}}</span></div>
    <form class="insights-toolbar" @submit.prevent="run(()=>load(q,1))"><label for="insights-query">姓名或事件描述</label><div><el-input id="insights-query" v-model="q" aria-label="统计筛选" placeholder="输入姓名或事件描述" :disabled="busy" clearable/><el-button type="primary" native-type="submit" :loading="busy">查询概况</el-button><el-button :disabled="busy" @click="q='';run(()=>load('',1))">重置</el-button></div></form>
    <p class="insights-current" role="status">{{busy?'正在读取，请稍候…':loaded?'当前筛选：'+(appliedQuery||'全部权限内数据'):'尚未读取数据，请查询概况重试'}}<span v-if="loaded && q!==appliedQuery">输入已修改，查询后生效</span></p>
  </section>
  <template v-if="data">
    <div class="metric-grid" aria-label="业务指标"><div v-for="([key,label],index) in metrics" :key="key" class="metric" :class="{'metric-primary':index===0}"><span>{{label}}</span><strong>{{data[key]??'—'}}</strong><small>{{index<2?'业务记录':'待办与审核'}}</small></div></div>
    <p class="help-note insights-count-note">统计口径：上报按来源记录计数，有效事件去重；关键词按人员内规则命中的有效事件次数统计。</p>
    <div class="insights-analysis">
      <section class="event-card department-distribution"><div class="insights-heading"><div><p class="eyebrow">来源观察</p><h2>来源部门分布</h2></div><span class="insights-count">{{departmentRows.length}} 个部门</span></div><div class="department-list"><div v-for="row in departmentRows" :key="row.name" class="department-item"><div><span>{{row.name}}</span><strong>{{row.count}}<small> 条</small></strong></div><div class="department-track" aria-hidden="true"><i :style="{width:row.count/maxDepartment*100+'%'}"/></div></div><el-empty v-if="!departmentRows.length" description="当前筛选范围内暂无部门数据" :image-size="56"/></div></section>
      <section class="event-card insights-keywords"><div class="insights-heading"><div><p class="eyebrow">风险线索</p><h2>热点关键词</h2></div><span class="insights-count">{{Object.keys(data.keywords||{}).length}} 个关键词</span></div><p class="help-note">选择关键词，查看当前筛选范围内的人员、事件与命中依据。</p><div class="keyword-list"><el-button v-for="(count,word) in data.keywords" :key="word" :type="term===String(word)?'primary':undefined" :plain="term!==String(word)" :disabled="busy" @click="run(()=>drill(String(word)))">{{word}}（{{count}}）</el-button><el-empty v-if="!Object.keys(data.keywords||{}).length" description="当前筛选范围内暂无关键词命中" :image-size="56"/></div></section>
    </div>
    <section class="event-card insights-drill"><div class="insights-heading"><div><h2>关键词命中明细</h2><p>{{term?'当前关键词：'+term:'选择上方关键词，沿命中线索查看原始依据'}}</p></div><el-tag v-if="keywordLoaded" effect="plain">{{details.length}} 条命中</el-tag></div><el-table :data="details" :empty-text="keywordLoaded?'该关键词在当前筛选范围内暂无命中':'选择关键词后查看人员、事件与命中依据'"><el-table-column prop="personId" label="人员编号" width="100"/><el-table-column prop="eventId" label="事件编号" width="100"/><el-table-column label="详情" width="160"><template #default="{row}"><el-button link type="primary" :disabled="busy" @click="inspect(row.personId,row.eventId)">查看人员与事件</el-button></template></el-table-column><el-table-column label="命中依据" min-width="280"><template #default="{row}"><details class="insights-evidence"><summary>展开命中依据</summary><p>{{evidence(row.evidence)||'暂无命中原文'}}</p></details></template></el-table-column></el-table></section>
  </template>
  <section class="event-card insights-export"><div class="insights-heading"><div><h2>筛选范围内导出</h2><p>与已查询的概况使用相同筛选及账号权限。{{loaded?'当前：'+(appliedQuery||'全部权限内数据'):'查询成功后可导出。'}}</p></div></div><div v-if="loaded && !busy" class="export-actions"><a :href="exportUrl('events')">导出事件 CSV <span aria-hidden="true">↗</span></a><a :href="exportUrl('statistics')">导出统计 CSV <span aria-hidden="true">↗</span></a><a v-if="center" :href="exportUrl('risks')">导出风险 CSV <span aria-hidden="true">↗</span></a></div><p v-else class="help-note">{{busy?'正在读取数据…':'暂无可导出的查询结果'}}</p></section>
  <section class="event-card insights-events"><div class="insights-heading"><div><h2>事件明细</h2><p>来源记录与有效事件编号对应展示，每页 10 条。</p></div><span v-if="data" class="insights-count">{{data.reports}} 条上报</span></div><el-table :data="events" max-height="420" :empty-text="busy?'正在读取事件…':loaded?'当前筛选范围内暂无事件':'请先查询概况'"><el-table-column prop="id" label="来源编号" width="100"/><el-table-column prop="eventId" label="有效事件编号" width="120"/><el-table-column prop="name" label="姓名" min-width="140" show-overflow-tooltip/><el-table-column prop="description" label="描述" min-width="240" show-overflow-tooltip/><el-table-column prop="source" label="来源" min-width="140" show-overflow-tooltip/></el-table><div class="pagination"><el-pagination v-if="data" :current-page="eventPage" :disabled="busy" :total="data.reports" :page-size="eventPageSize" layout="total, prev, pager, next" :pager-count="5" @current-change="(page:number)=>run(()=>load(appliedQuery,page))"/></div></section>
  <section v-if="center" class="event-card"><div class="insights-heading"><div><h2>风险明细</h2><p>查看当前筛选范围内的人员风险档案及有效事件依据。</p></div><el-tag effect="plain" type="info">中心权限</el-tag></div><el-table :data="risks" :empty-text="busy?'正在读取风险…':loaded?'当前筛选范围内暂无风险档案':'请先查询概况'"><el-table-column prop="id" label="档案编号" width="100"/><el-table-column prop="name" label="姓名" min-width="160" show-overflow-tooltip/><el-table-column label="等级" min-width="110"><template #default="{row}"><el-tag :type="riskTagType(row.level)" effect="plain">{{riskLevelLabel(row.level)}}</el-tag></template></el-table-column><el-table-column prop="effectiveCount" label="有效次数" min-width="100"/><el-table-column label="档案" width="120"><template #default="{row}"><el-button link type="primary" :disabled="busy" @click="inspect(row.id)">查看档案</el-button></template></el-table-column></el-table></section>
  <section class="event-card insights-history"><div class="insights-heading"><div><p class="eyebrow">操作留痕</p><h2>操作历史</h2><p>按对象、人员与时间追溯操作，展开记录查看完整内容。</p></div><span class="insights-count">{{historyLoaded?history.length+' 条记录':'待查询'}}</span></div><el-form class="history-filter" label-position="top" :disabled="busy" @submit.prevent="run(logs)"><el-form-item label="对象类型"><el-input v-model="type" aria-label="对象类型" placeholder="对象类型，可留空" clearable/></el-form-item><el-form-item label="对象编号"><el-input v-model="objectId" aria-label="对象编号" placeholder="对象编号，可留空" clearable/></el-form-item><el-form-item label="人员编号"><el-input v-model="personId" aria-label="人员编号" placeholder="人员编号，可留空" clearable/></el-form-item><el-form-item label="起始时间"><el-date-picker v-model="from" type="datetime" aria-label="起始时间" placeholder="选择起始时间" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DDTHH:mm:ss" :editable="false" clearable/></el-form-item><el-form-item label="截止时间"><el-date-picker v-model="to" type="datetime" aria-label="截止时间" placeholder="选择截止时间" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DDTHH:mm:ss" :editable="false" clearable/></el-form-item><div class="history-actions"><el-button type="primary" native-type="submit" :loading="busy">查询操作历史</el-button></div></el-form><el-table :data="visibleHistory" max-height="420" :empty-text="historyLoaded?'当前历史筛选下暂无记录':'设置筛选后查询操作历史'"><el-table-column type="expand"><template #default="{row}"><pre class="history-detail">{{row}}</pre></template></el-table-column><el-table-column prop="target_type" label="对象" min-width="140"/><el-table-column prop="target_id" label="编号" width="90"/><el-table-column prop="created_at" label="时间" min-width="180"/><el-table-column prop="state" label="业务状态" min-width="120"/><el-table-column label="操作者" min-width="120"><template #default="{row}">{{row.actor_username||row.actor||row.requester}}</template></el-table-column><el-table-column label="原因/结果" min-width="200" show-overflow-tooltip><template #default="{row}">{{row.reason||row.content||row.result||row.state}}</template></el-table-column></el-table><div v-if="history.length" class="pagination"><el-pagination v-model:current-page="historyPage" :total="history.length" :page-size="historyPageSize" layout="total, prev, pager, next" :pager-count="5"/></div></section>
<el-drawer v-model="drawer" title="关联人员与有效事件" size="min(820px,100vw)" class="archive-drawer">
  <div v-if="personDetail && riskDetail" class="archive-content">
    <div class="archive-heading"><div><p class="archive-kicker">人员风险档案 · #{{personDetail.person.id}}</p><h2>{{personDetail.person.name}}</h2></div><el-tag :type="riskTagType(riskDetail.finalLevel)" size="large" effect="light">{{riskLevelLabel(riskDetail.finalLevel)}}</el-tag></div>
    <section class="archive-section"><h3>基本信息</h3><dl class="profile-grid"><div><dt>联系电话</dt><dd>{{personDetail.person.phone||'未填写'}}</dd></div><div><dt>身份证号</dt><dd>{{personDetail.person.idCard||'未填写'}}</dd></div></dl></section>
    <div class="archive-metrics"><div><span>有效事件</span><strong>{{riskDetail.effectiveCount}}<small> 次</small></strong></div><div><span>来源上报</span><strong>{{riskDetail.sourceCount}}<small> 条</small></strong></div><div><span>最近有效事件</span><strong class="archive-time">{{formatTime(riskDetail.lastEventTime)}}</strong></div></div>
    <section class="archive-section"><h3>风险评估依据</h3><dl class="profile-grid"><div><dt>次数基础等级</dt><dd>{{riskLevelLabel(riskDetail.baseLevel)}}</dd></div><div><dt>规则评估等级</dt><dd>{{riskLevelLabel(riskDetail.ruleLevel)}}</dd></div><div><dt>有效人工调整</dt><dd>{{riskDetail.manualLevel?riskLevelLabel(riskDetail.manualLevel):'无有效调整'}}</dd></div><div><dt>当前规则版本</dt><dd>{{riskDetail.ruleVersion}}</dd></div></dl><p class="archive-note">有效事件去重计数；多条来源归集到同一事件，不重复累计。</p><el-alert v-if="riskDetail.specialFloor" title="存在可信特殊账号来源，最终等级至少为中风险" type="warning" :closable="false" show-icon/></section>
    <section class="archive-section"><div class="archive-section-heading"><h3>有效事件与来源</h3><span class="muted">{{eventId?'当前关联事件':'展开查看归集来源'}}</span></div><el-empty v-if="!riskDetail.events.length" description="暂无有效事件" :image-size="64"/><el-collapse v-else v-model="expandedEvents"><el-collapse-item v-for="e in riskDetail.events.filter((e:any)=>!eventId||e.id===eventId)" :key="e.id" :name="e.id"><template #title><div class="archive-event-title"><strong>有效事件 #{{e.id}}</strong><span>{{formatTime(e.eventTime)}} · {{e.sources.length}} 条来源</span></div></template><article v-for="source in e.sources" :key="source.recordId" class="archive-source"><div class="archive-source-heading"><strong>来源记录 #{{source.recordId}}</strong><el-tag v-if="source.specialReport" type="warning" size="small">特殊账号来源</el-tag></div><p class="archive-source-meta">{{source.departmentName||'未知部门'}} · {{source.source||'未注明来源'}} · {{formatTime(source.eventTime)}}</p><p class="archive-description">{{source.description||'暂无描述'}}</p><p class="archive-tags">联系地址：{{personDetail.events.find((record:any)=>record.id===source.recordId)?.party_address||'未填写'}}</p><p class="archive-tags">人工标签：{{source.tags||'无'}}</p></article></el-collapse-item></el-collapse></section>
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
 .history-filter :deep(.el-date-editor.el-input) { width: 100%; min-width: 0; }
.history-actions { display: flex; align-items: flex-end; padding-bottom: 18px; }
.history-detail { margin: 0; padding: 20px; white-space: pre-wrap; overflow-wrap: anywhere; background: #f6f8fa; }
.help-note { color: #687c75; font-size: 13px; line-height: 1.9; margin: 18px 0; }
.keyword-list, .export-actions { display: flex; flex-wrap: wrap; }
.keyword-list .el-button { margin-left: 0; }
.export-actions a { color: #246c5b; text-decoration: none; font-size: 13px; }
.export-actions a:hover { background: #f0f7f3; }
</style>
