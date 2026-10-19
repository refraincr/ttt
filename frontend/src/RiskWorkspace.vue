<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, ApiError } from './api'
type Row = Record<string,any>
const emit=defineEmits<{expired:[]}>()
const error=ref(''),message=ref(''),busy=ref(false),query=ref(''),people=ref<Row[]>([]),risk=ref<Row|null>(null),pending=ref<Row[]>([]),record=ref<Row|null>(null),candidates=ref<Row[]>([]),recordId=ref(''),rules=ref<Row[]>([])
const decision=ref('INDEPENDENT'),target=ref<number|null>(null),reason=ref(''),tags=ref(''),tagReason=ref('')
const level=(value:string)=>({LOW:'低风险',MEDIUM:'中风险',HIGH:'高风险'}[value]||value)
const formatTime=(value:string)=>value?value.replace('T',' '):'—'
const historyDecision=(value:string)=>({MERGE:'确认重复并归集',MERGED:'确认重复并归集',INDEPENDENT:'确认独立有效'}[value]||'未知结论')
const state=(value:string)=>({PENDING:'身份待审',CONFIRMED:'身份已确认',SCREENED:'筛查完成',SUSPECTED:'疑似重复待审',INDEPENDENT:'独立有效',MERGED:'已归集'}[value]||value)
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';message.value='';try{await action()}catch(e){error.value=e instanceof Error?e.message:'操作失败';if(e instanceof ApiError&&e.status===401)emit('expired')}finally{busy.value=false}}
async function refresh(){people.value=await api('/intake/people?q='+encodeURIComponent(query.value));pending.value=await api('/intake/duplicate-reviews')}
async function openRisk(id:number){risk.value=await api('/intake/people/'+id+'/risk')}
async function openRecord(id:number){const nextRecord=await api<Row>('/intake/records/'+id);const nextCandidates=await api<Row[]>('/intake/records/'+id+'/duplicates');record.value=nextRecord;candidates.value=nextCandidates;recordId.value=String(id);tags.value=nextRecord.keyword_tags??'';target.value=null;reason.value='';tagReason.value=''}
async function review(){await api(`/intake/records/${record.value!.id}/duplicate-review`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({version:record.value!.version,decision:decision.value,targetRecordId:target.value,reason:reason.value})});await openRecord(record.value!.id);await refresh();if(risk.value)await openRisk(risk.value.personId);message.value='重复审核已保存，风险已重算'}
async function saveTags(){await api(`/intake/records/${record.value!.id}/tags`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({version:record.value!.version,tags:tags.value,reason:tagReason.value})});await openRecord(record.value!.id);if(risk.value)await openRisk(risk.value.personId);message.value='标签已保存，关键词及风险已重新计算'}
onMounted(()=>run(async()=>{await refresh();rules.value=await api('/intake/keyword-rules')}))
</script>
<template>
<main class="content risk-workspace"><div class="page-heading"><div><p class="eyebrow">中心研判</p><h1>归集与风险</h1><p class="muted">按独立有效事件计数，保留每条来源与判断依据。</p></div></div>
<el-alert v-if="error" :title="error" type="error" :closable="false"/><el-alert v-if="message" :title="message" type="success" :closable="false"/>
<el-tabs class="risk-tabs">
<el-tab-pane label="人员风险档案"><section class="event-card"><form class="filter-bar" @submit.prevent="run(refresh)"><el-input v-model="query" aria-label="筛选风险档案" placeholder="姓名、身份证或手机号" clearable/><el-button type="primary" native-type="submit" :loading="busy">查询档案</el-button></form>
<el-table :data="people" empty-text="暂无符合条件的人员档案"><el-table-column prop="name" label="姓名" min-width="150" show-overflow-tooltip/><el-table-column prop="idCard" label="身份证" min-width="190"/><el-table-column prop="phone" label="手机号" min-width="140"/><el-table-column label="操作" width="140"><template #default="{row}"><el-button link @click="run(()=>openRisk(row.id))">查看风险依据</el-button></template></el-table-column></el-table>
<section v-if="risk" class="risk-detail"><h2>人员 #{{risk.personId}} 风险依据</h2><el-descriptions :column="2" border><el-descriptions-item label="有效事件次数">{{risk.effectiveCount}}</el-descriptions-item><el-descriptions-item label="来源上报记录数">{{risk.sourceCount}}</el-descriptions-item><el-descriptions-item label="基础等级">{{level(risk.baseLevel)}}</el-descriptions-item><el-descriptions-item label="最终等级"><strong>{{level(risk.finalLevel)}}</strong></el-descriptions-item><el-descriptions-item label="最近有效事件时间">{{risk.lastEventTime ? formatTime(risk.lastEventTime) : '暂无有效事件'}}</el-descriptions-item><el-descriptions-item label="规则版本">{{risk.ruleVersion}}</el-descriptions-item></el-descriptions>
<p class="risk-note">次数按当前规则版本计算，阈值可在“规则与批量接入”查看。特殊来源或关键词命中至少中风险。</p><el-alert v-if="risk.specialFloor" title="存在可信特殊账号来源，固定至少中风险" type="warning" :closable="false"/>
<h3>有效事件与来源证据</h3><el-collapse><el-collapse-item v-for="event in risk.events" :key="event.id" :title="`有效事件 #${event.id} · ${event.eventTime} · ${event.sources.length} 条来源`"><div v-for="source in event.sources" :key="source.recordId"><p><el-button link type="primary" @click="run(()=>openRecord(source.recordId))">来源记录 #{{source.recordId}}</el-button> · 来源部门：{{source.departmentName || '未知部门'}} · {{source.source}} · {{source.specialReport?'特殊账号来源':'普通来源'}}</p><p class="description">{{source.description}}</p><p>标签：{{source.tags||'无'}}</p></div></el-collapse-item></el-collapse>
<h3>关键词命中证据</h3><p v-if="!risk.keywordHits.length">暂无有效事件命中</p><section v-for="hit in risk.keywordHits" :key="`${hit.eventId}-${hit.ruleId}`"><h4>事件 #{{hit.eventId}} · {{hit.ruleName}} · 规则版本 {{hit.ruleVersion}} · 至少中风险</h4><p v-for="(e,index) in hit.evidence" :key="index">来源 #{{e.recordId}} · {{e.field==='tags'?'人工标签':'问题描述'}}：{{e.text.slice(0,e.start)}}<mark>{{e.text.slice(e.start,e.end)}}</mark>{{e.text.slice(e.end)}}</p></section></section>
</section></el-tab-pane>
<el-tab-pane label="重复审核池"><section class="event-card"><div class="filter-bar"><el-button :disabled="busy" @click="run(refresh)">刷新重复待审</el-button></div><el-table :data="pending" empty-text="暂无身份明确的重复待审记录"><el-table-column prop="id" label="编号" width="80"/><el-table-column prop="party_name" label="当事人" min-width="130"/><el-table-column prop="problem_description" label="描述" min-width="260" show-overflow-tooltip/><el-table-column label="操作" width="120"><template #default="{row}"><el-button link @click="run(()=>openRecord(row.id))">核查重复</el-button></template></el-table-column></el-table></section></el-tab-pane>
<el-tab-pane label="关键词规则"><section class="event-card"><p class="rules-note">当前初始版本按字面匹配；组合仅在同一有效事件的描述、标签及归集来源之间成立。</p><el-table :data="rules"><el-table-column prop="name" label="规则" min-width="220"/><el-table-column prop="version" label="版本" width="100"/><el-table-column label="状态" width="100"><template #default="{row}">{{row.enabled?'启用':'停用'}}</template></el-table-column></el-table></section></el-tab-pane>
</el-tabs>
<section class="event-card source-card"><div class="card-heading"><h2>来源记录核查</h2><span class="muted">查看来源与归集依据</span></div><form class="filter-bar" @submit.prevent="recordId && run(()=>openRecord(Number(recordId)))"><el-input v-model="recordId" aria-label="核查来源编号" placeholder="来源记录编号"/><el-button type="primary" native-type="submit" :disabled="busy||!recordId">读取来源记录</el-button></form>
<div v-if="record" class="source-detail"><h3>来源记录 #{{record.id}} · {{record.party_name}}</h3><div class="source-status"><el-tag :type="record.identity_review_state==='CONFIRMED'?'success':'warning'" effect="plain">{{state(record.identity_review_state)}}</el-tag><el-tag type="info" effect="plain">{{state(record.duplicate_review_state)}}</el-tag><span class="muted">{{record.effectiveEventId ? `有效事件 #${record.effectiveEventId}` : '尚未归集'}}</span></div><h4>问题描述</h4><p class="description">{{record.problem_description}}</p>
<h4>关键词证据（当前来源文本）</h4><p v-if="!record.keywordHits.length" class="empty-evidence">当前来源暂无命中；跨归集来源的组合依据见人员风险档案。</p><div v-for="hit in record.keywordHits" :key="hit.ruleId"><p>{{hit.ruleName}} · 版本 {{hit.ruleVersion}} · {{record.effectiveEventId?'参与风险保底':'待归集，暂不影响人员风险'}}</p><p v-for="(e,i) in hit.evidence" :key="i">{{e.field==='tags'?'标签':'描述'}}：{{e.text.slice(0,e.start)}}<mark>{{e.text.slice(e.start,e.end)}}</mark>{{e.text.slice(e.end)}}</p></div>
<template v-if="!record.effectiveEventId&&record.identity_review_state==='CONFIRMED'"><h4>重复候选</h4><el-table :data="candidates"><el-table-column prop="id" label="候选记录" width="100"/><el-table-column prop="description" label="描述" min-width="240" show-overflow-tooltip/><el-table-column prop="secondsApart" label="时间差（秒）" width="140"/><el-table-column prop="similarity" label="相似度" width="100"/></el-table>
<el-form class="duplicate-form" label-position="top"><el-form-item label="审核结论"><el-radio-group v-model="decision"><el-radio value="INDEPENDENT">确认独立有效</el-radio><el-radio value="MERGE">确认重复并归集</el-radio></el-radio-group></el-form-item><el-form-item v-if="decision==='MERGE'" label="归集目标来源编号"><el-input-number v-model="target" :min="1" aria-label="归集目标来源编号"/></el-form-item><el-form-item label="重复审核理由"><el-input v-model="reason" aria-label="重复审核理由" type="textarea" :rows="3" placeholder="请填写判断重复或独立事件的依据"/></el-form-item><el-button type="primary" :disabled="busy" @click="run(review)">提交重复审核</el-button></el-form></template>
<section class="tag-section"><h4>人工标签</h4><p class="muted">补充风险关键词，并记录标注依据。</p><el-form class="tag-form" label-position="top" :disabled="busy"><el-form-item label="关键词标签"><el-input v-model="tags" aria-label="人工关键词标签" maxlength="500" placeholder="请输入需要标注的关键词" show-word-limit/></el-form-item><el-form-item label="标注理由"><el-input v-model="tagReason" aria-label="标签标注理由" placeholder="请填写标签标注理由" type="textarea" :rows="3"/></el-form-item><el-button type="primary" :loading="busy" @click="run(saveTags)">保存关键词标签</el-button></el-form></section>
<h4>重复处理历史</h4><el-table :data="record.duplicateHistory" empty-text="暂无重复处理记录"><el-table-column label="结论" min-width="160"><template #default="{row}">{{historyDecision(row.decision)}}</template></el-table-column><el-table-column prop="reason" label="理由" min-width="200" show-overflow-tooltip/><el-table-column prop="actor_username" label="操作者" min-width="100"/><el-table-column label="时间" width="190"><template #default="{row}"><span class="history-time">{{formatTime(row.created_at)}}</span></template></el-table-column></el-table>
</div></section>
</main>
</template>

<style scoped>
.risk-tabs :deep(.el-tabs__header) { margin-bottom: 16px; }
.source-card { margin-top: 24px; }
.risk-detail, .source-detail { padding: 24px; border-top: 1px solid #edf0f4; }
.risk-detail h2 { font-size: 18px; margin-bottom: 20px; }
.risk-detail h3, .source-detail h4 { margin-top: 24px; }
.source-detail { font-size: 14px; }
.source-detail > h3 { margin: 0 0 14px; font-size: 18px; line-height: 1.6; }
.source-detail h4 { margin-bottom: 12px; font-size: 14px; }
.source-status { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.source-detail .description { margin: 0; padding: 16px; background: #f6f8fa; border-radius: 8px; }
.empty-evidence { margin: 0; padding: 14px 16px; color: #657585; background: #f6f8fa; border-radius: 8px; line-height: 1.8; }
.tag-section { margin-top: 28px; padding: 24px; border: 1px solid #e2e8ef; border-radius: 8px; }
.tag-section h4 { margin: 0 0 8px; }
.tag-section > p { margin-bottom: 20px; }
.tag-form, .duplicate-form { max-width: 760px; }
.tag-form :deep(.el-input), .tag-form :deep(.el-textarea) { width: 100%; }
.duplicate-form { margin-top: 20px; padding: 20px; background: #f6f8fa; border-radius: 8px; }
.duplicate-form :deep(.el-radio-group) { display: flex; flex-wrap: wrap; gap: 8px 20px; }
.duplicate-form :deep(.el-radio) { margin-right: 0; white-space: normal; }
.history-time { white-space: nowrap; font-variant-numeric: tabular-nums; }
.risk-note { margin: 16px 0 24px; color: #657585; font-size: 13px; line-height: 1.8; }
.risk-detail :deep(.el-collapse-item__header) { height: auto; min-height: 48px; line-height: 1.7; padding: 12px 0; }
.risk-detail :deep(.el-descriptions__cell) { overflow-wrap: anywhere; }
.rules-note { margin: 0; padding: 20px 24px; color: #657585; font-size: 13px; line-height: 1.8; }
@media (max-width: 600px) {
  .filter-bar { flex-wrap: wrap; padding: 16px; }
  .filter-bar .el-input { max-width: none; flex-basis: 100%; }
  .card-heading, .risk-detail, .source-detail { padding: 16px; }
  .card-heading { flex-wrap: wrap; gap: 8px; }
  .tag-section, .duplicate-form { padding: 16px; }
}
</style>
