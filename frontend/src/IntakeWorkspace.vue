<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, ApiError, type Account } from './api'
const props = defineProps<{ account: Account }>()
const emit = defineEmits<{ expired: [] }>()
const analyst = props.account.roles.includes('CENTER_ANALYST')
type Entry = { personName: string; idCard: string; phone: string; eventTime: string; description: string; sourceId: number | null; categoryId: number | null; disputeId: number | null; departmentId: number | null }
type Draft = { id: number; payload: Entry; version: number; recordId: number | null }
type Option = { id: number; name: string }
type Row = Record<string, any>
const blank = (): Entry => ({ personName:'',idCard:'',phone:'',eventTime:'',description:'',sourceId:null,categoryId:null,disputeId:null,departmentId:null })
const entry=ref<Entry>(blank()), active=ref<Draft|null>(null), drafts=ref<Draft[]>([]), sources=ref<Option[]>([]), categories=ref<Option[]>([]), disputes=ref<Option[]>([]), departments=ref<Option[]>([])
const error=ref(''), message=ref(''), busy=ref(false), record=ref<Row|null>(null), recordId=ref(''), reviews=ref<Row[]>([]), people=ref<Row[]>([]), person=ref<Row|null>(null), candidates=ref<Row[]>([]), query=ref('')
const settings=ref({windowDays:3,threshold:0.7,version:1})
const identityLabels: Record<string, string> = { PENDING: '待确认', CONFIRMED: '已确认' }
const duplicateLabels: Record<string, string> = { PENDING: '待审核', INDEPENDENT: '独立事件', MERGED: '已合并' }
const review=ref({version:1,personId:null as number|null,personName:'',idCard:'',phone:'',reason:''}), reviewing=ref<number|null>(null)
async function run(action:()=>Promise<void>) { busy.value=true;error.value='';try { await action() } catch(e) { error.value=e instanceof Error?e.message:'操作失败';if(e instanceof ApiError && e.status===401) emit('expired') } finally {busy.value=false} }
async function refresh() { drafts.value=await api('/intake/drafts');if(analyst) {reviews.value=await api('/intake/identity-reviews');people.value=await api('/intake/people?q='+encodeURIComponent(query.value))} }
function edit(d?:Draft) {active.value=d??null;entry.value=d?{...d.payload}:blank();message.value=''}
async function save() { active.value=await api<Draft>(active.value?`/intake/drafts/${active.value.id}`:'/intake/drafts',{method:active.value?'PUT':'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(active.value?{payload:entry.value,version:active.value.version}:entry.value)});await refresh();message.value='草稿已保存' }
async function submit() { await save();record.value=await api(`/intake/drafts/${active.value!.id}/submit`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({version:active.value!.version})});recordId.value=String(record.value!.id);edit();await refresh();message.value='提交成功，已进入身份匹配与重复筛查流程' }
async function remove(d:Draft) {await api(`/intake/drafts/${d.id}?version=${d.version}`,{method:'DELETE'});if(active.value?.id===d.id) edit();await refresh()}
async function openRecord() { record.value=await api(`/intake/records/${recordId.value}`);candidates.value=analyst?await api(`/intake/records/${recordId.value}/duplicates`):[] }
function startReview(r:Row) {reviewing.value=r.id;review.value={version:r.version,personId:null,personName:r.party_name??'',idCard:r.party_id_card??'',phone:r.party_phone??'',reason:''} }
async function confirm() {await api(`/intake/identity-reviews/${reviewing.value}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(review.value)});reviewing.value=null;await refresh();message.value='身份审核已保存'}
async function saveSettings() {settings.value=await api('/intake/duplicate-settings',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(settings.value)});if(record.value) await openRecord();message.value='筛查配置已保存并重新筛查'}
onMounted(()=>run(async()=>{sources.value=await api('/options/sources');categories.value=await api('/options/dictionaries?kind=EVENT_CATEGORY');disputes.value=await api('/options/dictionaries?kind=DISPUTE_TYPE');if(analyst) { departments.value=await api('/intake/departments');settings.value=await api('/intake/duplicate-settings') }await refresh()}))
</script>
<template>
<main class="content intake-workspace"><div class="page-heading"><div><p class="eyebrow">事件归集</p><h1>录入与身份处理</h1><p class="muted">保留来源原文，先确认身份，再检查重复。</p></div></div>
<el-alert v-if="error" :title="error" type="error" :closable="false"/><el-alert v-if="message" :title="message" type="success" :closable="false"/>
<el-tabs>
<el-tab-pane label="事件录入与草稿"><section class="event-card"><h2>{{ active ? `编辑草稿 #${active.id}` : '单条事件录入' }}</h2>
<el-form class="entry-form" label-position="top" :disabled="busy">
<el-form-item v-if="analyst" label="代录部门"><el-select v-model="entry.departmentId" aria-label="代录部门"><el-option v-for="o in departments" :key="o.id" :label="o.name" :value="o.id"/></el-select></el-form-item>
<el-form-item label="主要当事人姓名"><el-input v-model="entry.personName" aria-label="主要当事人姓名" maxlength="100"/></el-form-item>
<el-form-item label="身份证号"><el-input v-model="entry.idCard" aria-label="身份证号"/></el-form-item><el-form-item label="手机号"><el-input v-model="entry.phone" aria-label="手机号"/></el-form-item>
<el-form-item label="发生时间"><el-input v-model="entry.eventTime" aria-label="发生时间" placeholder="2026-10-18T09:00:00"/></el-form-item>
<el-form-item label="数据来源"><el-select v-model="entry.sourceId" aria-label="数据来源"><el-option v-for="o in sources" :key="o.id" :label="o.name" :value="o.id"/></el-select></el-form-item>
<el-form-item label="事件类别"><el-select v-model="entry.categoryId" aria-label="事件类别"><el-option v-for="o in categories" :key="o.id" :label="o.name" :value="o.id"/></el-select></el-form-item>
<el-form-item label="纠纷类型"><el-select v-model="entry.disputeId" aria-label="纠纷类型"><el-option v-for="o in disputes" :key="o.id" :label="o.name" :value="o.id"/></el-select></el-form-item>
<el-form-item class="full-width" label="问题描述"><el-input v-model="entry.description" type="textarea" aria-label="问题描述" :rows="3"/></el-form-item>
<div class="form-actions"><el-button @click="run(save)">保存草稿</el-button><el-button type="primary" @click="run(submit)">提交事件</el-button><el-button @click="edit()">新建草稿</el-button></div></el-form>
<h3 class="section-heading">我的草稿</h3><el-table :data="drafts"><el-table-column prop="id" label="编号"/><el-table-column label="当事人"><template #default="{row}">{{ row.payload.personName }}</template></el-table-column><el-table-column label="操作"><template #default="{row}"><el-button link @click="edit(row)">编辑草稿</el-button><el-button link type="danger" :disabled="busy" @click="run(()=>remove(row))">删除草稿</el-button></template></el-table-column></el-table></section></el-tab-pane>
<el-tab-pane label="原始记录与重复候选"><section class="event-card"><form class="intake-toolbar" @submit.prevent="run(openRecord)"><el-input v-model="recordId" aria-label="来源记录编号" placeholder="来源记录编号"/><el-button type="primary" native-type="submit" :disabled="busy||!recordId">查询记录</el-button></form><p v-if="!record" class="muted empty-hint">输入来源记录编号，查看原始内容、身份状态与重复候选。</p>
<section v-if="record" class="detail-panel record-detail">
  <div class="record-heading"><h2>来源记录 #{{ record.id }}</h2><div class="record-status"><el-tag :type="record.identity_review_state === 'CONFIRMED' ? 'success' : 'warning'" effect="plain">身份{{ identityLabels[record.identity_review_state] || '状态未知' }}</el-tag><el-tag type="info" effect="plain">{{ duplicateLabels[record.duplicate_review_state] || '重复状态未知' }}</el-tag></div></div>
  <dl class="record-fields">
    <div><dt>当事人</dt><dd>{{ record.party_name || '未提供' }}</dd></div>
    <div><dt>身份证号</dt><dd>{{ record.party_id_card || '未提供' }}</dd></div>
    <div><dt>手机号</dt><dd>{{ record.party_phone || '未提供' }}</dd></div>
    <div><dt>数据来源</dt><dd>{{ record.event_source || '未提供' }}</dd></div>
    <div><dt>特殊上报</dt><dd>{{ record.special_report ? '是' : '否' }}</dd></div>
    <div><dt>操作者</dt><dd>{{ record.created_by || '未提供' }}</dd></div>
    <div><dt>有效事件编号</dt><dd>{{ record.effectiveEventId ? `#${record.effectiveEventId}` : '尚未归集' }}</dd></div>
  </dl>
  <section class="record-section"><h3>问题描述</h3><p class="record-description">{{ record.problem_description || '未提供' }}</p></section>
  <section class="record-section"><h3>当前来源关键词命中</h3><div v-if="record.keywordHits?.length" class="keyword-hits"><div v-for="hit in record.keywordHits" :key="hit.ruleId" class="keyword-hit"><el-tag effect="plain">{{ hit.ruleName }}</el-tag><span class="muted">规则版本 {{ hit.ruleVersion }} · {{ record.effectiveEventId ? '已归集' : '待归集，暂不参与人员风险' }}</span></div></div><p v-else class="record-empty">暂无关键词命中</p></section>
  <section class="record-section"><h3>提交原文</h3><pre v-if="record.original_payload" class="record-original">{{ record.original_payload }}</pre><p v-else class="record-empty">历史记录未提供原始快照</p></section>
  <section v-if="analyst" class="record-section"><h3>疑似重复候选 <span class="muted">{{ candidates.length }} 条</span></h3><el-table :data="candidates" empty-text="暂无达到阈值的候选"><el-table-column prop="id" label="编号" width="80"/><el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip/><el-table-column prop="secondsApart" label="时间差（秒）" min-width="125"/><el-table-column prop="similarity" label="相似度" min-width="90"/><el-table-column prop="basis" label="依据" min-width="150"/></el-table></section>
</section></section></el-tab-pane>
<el-tab-pane v-if="analyst" label="人员档案"><section class="event-card"><form class="intake-toolbar" @submit.prevent="run(refresh)"><el-input v-model="query" aria-label="搜索人员" placeholder="姓名、身份证或手机号" clearable/><el-button type="primary" native-type="submit" :disabled="busy">搜索档案</el-button></form><el-table :data="people"><el-table-column prop="id" label="编号"/><el-table-column prop="name" label="姓名" min-width="140" show-overflow-tooltip/><el-table-column prop="idCard" label="身份证" min-width="190"/><el-table-column prop="phone" label="手机号"/><el-table-column label="详情"><template #default="{row}"><el-button link @click="run(async()=>{person=await api('/intake/people/'+row.id)})">档案详情</el-button></template></el-table-column></el-table><section v-if="person" class="detail-panel"><h3>{{ person.person.name }} · 有效次数 {{ person.effectiveCount }}</h3><p>手机号差异保留在来源记录，档案不自动覆盖。</p><el-table :data="person.events"><el-table-column prop="id" label="事件"/><el-table-column prop="party_phone" label="来源手机号"/><el-table-column prop="problem_description" label="描述"/><el-table-column prop="identity_review_state" label="身份状态"/><el-table-column prop="duplicate_review_state" label="重复状态"/></el-table></section></section></el-tab-pane>
<el-tab-pane v-if="analyst" label="身份审核"><section class="event-card"><div class="intake-toolbar"><el-button :disabled="busy" @click="run(refresh)">刷新待审</el-button></div><el-table :data="reviews"><el-table-column prop="id" label="编号"/><el-table-column prop="party_name" label="姓名"/><el-table-column prop="party_id_card" label="身份证" min-width="190"/><el-table-column prop="party_phone" label="手机号"/><el-table-column label="操作"><template #default="{row}"><el-button link @click="startReview(row)">审核身份</el-button></template></el-table-column></el-table>
<section v-if="reviewing" class="detail-panel"><h3>审核记录 #{{ reviewing }}</h3><el-form class="review-form" label-position="top"><el-form-item class="full-width archive-picker" label="关联档案（留空则新建）"><el-select v-model="review.personId" clearable filterable><el-option v-for="p in people" :key="p.id" :label="`${p.id} ${p.name} ${p.idCard||p.phone}`" :value="p.id"/></el-select><div class="candidate-filter"><el-input v-model="query" placeholder="筛选档案姓名、身份证或手机号"/><el-button :disabled="busy" @click="run(refresh)">筛选候选档案</el-button></div></el-form-item><el-form-item label="确认姓名"><el-input v-model="review.personName"/></el-form-item><el-form-item label="确认身份证"><el-input v-model="review.idCard"/></el-form-item><el-form-item label="确认手机号"><el-input v-model="review.phone"/></el-form-item><el-form-item class="full-width" label="审核依据和理由"><el-input v-model="review.reason" type="textarea"/></el-form-item><div class="form-actions"><el-button type="primary" :disabled="busy" @click="run(confirm)">确认身份归属</el-button></div></el-form></section></section></el-tab-pane>
<el-tab-pane v-if="analyst" label="重复筛查配置"><section class="event-card"><h2>重复筛查配置</h2><el-form class="settings-form" label-position="top"><el-form-item label="时间窗口（天，含边界）"><el-input-number v-model="settings.windowDays" :min="0" :max="365"/></el-form-item><el-form-item label="描述相似度阈值"><el-input-number v-model="settings.threshold" :min="0.01" :max="1" :step="0.05"/></el-form-item><el-button :disabled="busy" type="primary" @click="run(saveSettings)">保存筛查配置</el-button></el-form><p class="muted settings-note">默认三天、阈值0.7。疑似重复只提示，后续由中心审核是否归集。</p></section></el-tab-pane>
</el-tabs></main>
</template>

<style scoped>
.intake-workspace :deep(.el-tabs__header) { margin-bottom: 16px; }
.event-card { padding: 24px; }
.event-card h2 { margin-bottom: 24px; font-size: 18px; font-weight: 600; }
.entry-form, .review-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 24px; }
.full-width, .form-actions { grid-column: 1 / -1; }
.form-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.form-actions .el-button + .el-button { margin-left: 0; }
.section-heading { margin: 28px 0 16px; padding-top: 24px; border-top: 1px solid #edf0f4; font-size: 16px; }
.intake-toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 20px; }
.intake-toolbar .el-input { max-width: 360px; }
.detail-panel { margin-top: 24px; padding-top: 24px; border-top: 1px solid #edf0f4; }
.detail-panel h3 { font-size: 16px; margin-bottom: 16px; }
.detail-panel p { line-height: 1.8; overflow-wrap: anywhere; }
.detail-panel pre { background: #f6f8fa; padding: 16px; border-radius: 6px; overflow-wrap: anywhere; }
.record-detail { font-size: 14px; }
.record-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 24px; }
.record-heading h2 { margin: 0; }
.record-status { display: flex; flex-wrap: wrap; gap: 8px; }
.record-fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 22px 24px; margin: 0; padding: 20px; background: #f6f8fa; border-radius: 8px; }
.record-fields dt { color: #657585; font-size: 12px; margin-bottom: 8px; }
.record-fields dd { margin: 0; line-height: 1.6; overflow-wrap: anywhere; }
.record-section { margin-top: 24px; }
.record-section h3 { margin: 0 0 12px; font-size: 14px; font-weight: 600; }
.record-section h3 .muted { margin-left: 8px; font-weight: 400; }
.record-description { margin: 0; white-space: pre-wrap; }
.record-empty { margin: 0; padding: 14px 16px; color: #657585; font-size: 13px; background: #f6f8fa; border-radius: 6px; }
.record-original { margin: 0; max-height: 320px; overflow: auto; white-space: pre-wrap; font-size: 13px; line-height: 1.7; }
.keyword-hits, .keyword-hit { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.keyword-hits { flex-direction: column; align-items: flex-start; }
.archive-picker :deep(.el-form-item__content) { display: flex; flex-direction: column; align-items: stretch; gap: 12px; }
.candidate-filter { display: flex; gap: 10px; }
.settings-form { max-width: 480px; }
.settings-note { margin: 20px 0 0; line-height: 1.8; }
.empty-hint { margin: 0; padding: 16px 0; }
@media (max-width: 850px) {
  .record-fields { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .entry-form, .review-form { grid-template-columns: minmax(0, 1fr); }
}
@media (max-width: 600px) {
  .record-fields { grid-template-columns: minmax(0, 1fr); padding: 16px; }
  .event-card { padding: 16px; }
  .intake-toolbar, .candidate-filter { flex-wrap: wrap; }
  .intake-toolbar .el-input { max-width: none; flex-basis: 100%; }
  .candidate-filter .el-input { flex-basis: 100%; }
}
</style>
