<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, ApiError, type Account } from './api'
import './intake.css'
const props = defineProps<{ account: Account }>()
const emit = defineEmits<{ expired: [] }>()
const analyst = props.account.roles.includes('CENTER_ANALYST')
type Entry = { personName: string; idCard: string; phone: string; address: string; eventTime: string; description: string; sourceId: number | null; categoryId: number | null; disputeId: number | null; departmentId: number | null }
type Draft = { id: number; payload: Entry; version: number; recordId: number | null }
type Option = { id: number; name: string }
type Row = Record<string, any>
const blank = (): Entry => ({ personName:'',idCard:'',phone:'',address:'',eventTime:'',description:'',sourceId:null,categoryId:null,disputeId:null,departmentId:null })
const entry=ref<Entry>(blank()), active=ref<Draft|null>(null), drafts=ref<Draft[]>([]), sources=ref<Option[]>([]), categories=ref<Option[]>([]), disputes=ref<Option[]>([]), departments=ref<Option[]>([])
const error=ref(''), message=ref(''), busy=ref(false), record=ref<Row|null>(null), recordId=ref(''), reviews=ref<Row[]>([]), people=ref<Row[]>([]), person=ref<Row|null>(null), candidates=ref<Row[]>([]), query=ref('')
const candidatesLoaded=ref(false)
const settings=ref({windowDays:3,threshold:0.7,version:1})
const identityLabels: Record<string, string> = { PENDING: '待确认', CONFIRMED: '已确认' }
const duplicateLabels: Record<string, string> = { PENDING: '待审核', SCREENED: '筛查完成', SUSPECTED: '疑似重复待审', INDEPENDENT: '独立事件', MERGED: '已合并' }
const review=ref({version:1,personId:null as number|null,personName:'',idCard:'',phone:'',reason:''}), reviewing=ref<number|null>(null)
async function run(action:()=>Promise<void>) { if(busy.value) return;busy.value=true;error.value='';message.value='';try { await action() } catch(e) { message.value='';error.value=e instanceof Error?e.message:'操作失败';if(e instanceof ApiError && e.status===401) emit('expired') } finally {busy.value=false} }
async function refresh() { drafts.value=await api('/intake/drafts');if(analyst) {reviews.value=await api('/intake/identity-reviews');people.value=await api('/intake/people?q='+encodeURIComponent(query.value))} }
function edit(d?:Draft) {active.value=d??null;entry.value=d?{...d.payload}:blank();message.value=''}
async function save() { active.value=await api<Draft>(active.value?`/intake/drafts/${active.value.id}`:'/intake/drafts',{method:active.value?'PUT':'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(active.value?{payload:entry.value,version:active.value.version}:entry.value)});await refresh();message.value='草稿已保存' }
async function submit() { await save();record.value=await api(`/intake/drafts/${active.value!.id}/submit`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({version:active.value!.version})});recordId.value=String(record.value!.id);candidates.value=[];candidatesLoaded.value=false;edit();await refresh();message.value='提交成功，已进入身份匹配与重复筛查流程' }
async function remove(d:Draft) {await api(`/intake/drafts/${d.id}?version=${d.version}`,{method:'DELETE'});if(active.value?.id===d.id) edit();await refresh()}
async function openRecord() { const nextRecord=await api<Row>(`/intake/records/${recordId.value}`);const nextCandidates=analyst?await api<Row[]>(`/intake/records/${recordId.value}/duplicates`):[];record.value=nextRecord;candidates.value=nextCandidates;candidatesLoaded.value=true }
function startReview(r:Row) {reviewing.value=r.id;review.value={version:r.version,personId:null,personName:r.party_name??'',idCard:r.party_id_card??'',phone:r.party_phone??'',reason:''} }
async function confirm() {await api(`/intake/identity-reviews/${reviewing.value}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(review.value)});reviewing.value=null;await refresh();message.value='身份审核已保存'}
async function saveSettings() {settings.value=await api('/intake/duplicate-settings',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(settings.value)});if(record.value) await openRecord();message.value='筛查配置已保存并重新筛查'}
onMounted(()=>run(async()=>{sources.value=await api('/options/sources');categories.value=await api('/options/dictionaries?kind=EVENT_CATEGORY');disputes.value=await api('/options/dictionaries?kind=DISPUTE_TYPE');if(analyst) { departments.value=await api('/intake/departments');settings.value=await api('/intake/duplicate-settings') }await refresh()}))
</script>
<template>
<main class="content intake-workspace">
  <div class="page-heading"><div><p class="eyebrow">EVENT INTAKE · 事件归集</p><h1>录入与身份处理</h1><p class="muted">从一条原始记录开始，让每次上报有据可查。</p></div><el-tag effect="plain">{{ analyst ? '中心研判工作台' : '部门上报工作台' }}</el-tag></div>
  <section class="intake-process" aria-label="事件处理流程"><div><span>01</span><div><strong>录入与留存</strong><p>保存草稿，保留来源原文</p></div></div><div><span>02</span><div><strong>身份确认</strong><p>自动匹配，疑难身份人工审核</p></div></div><div><span>03</span><div><strong>重复筛查</strong><p>提示疑似重复，按流程归集</p></div></div></section>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
  <el-alert v-if="message" :title="message" type="success" :closable="false" show-icon/>
  <div v-if="busy" class="intake-loading" role="status">正在处理，请稍候…</div>
  <el-tabs class="intake-tabs">
    <el-tab-pane label="事件录入与草稿">
      <div class="intake-entry-layout">
      <section class="event-card intake-card" :aria-busy="busy">
        <header class="intake-card-heading"><div><h2>{{ active ? `编辑草稿 #${active.id}` : '单条事件录入' }}</h2><p>草稿可暂存不完整信息，标 * 项在提交时必填。</p></div><el-tag type="info" effect="plain">{{ active ? '草稿编辑中' : '新建记录' }}</el-tag></header>
        <el-form class="intake-form" label-position="top" :disabled="busy">
          <section class="intake-form-section"><h3><span>01</span> 当事人信息</h3><p class="intake-form-help">身份信息可留空；无法自动匹配的记录将进入中心身份审核。</p><div class="intake-form-grid">
            <el-form-item label="主要当事人姓名"><el-input v-model="entry.personName" aria-label="主要当事人姓名" maxlength="100" placeholder="请输入当事人姓名"/></el-form-item>
            <el-form-item label="手机号"><el-input v-model="entry.phone" aria-label="手机号" maxlength="32" placeholder="用于身份匹配与联系"/></el-form-item>
            <el-form-item class="full-width" label="身份证号"><el-input v-model="entry.idCard" aria-label="身份证号" placeholder="填写时需提供有效身份证号"/></el-form-item>
            <el-form-item class="full-width" label="联系地址"><el-input v-model="entry.address" aria-label="联系地址" maxlength="500" show-word-limit placeholder="可留空，最多500字"/></el-form-item>
          </div></section>
          <section class="intake-form-section"><h3><span>02</span> 事件信息</h3><div class="intake-form-grid">
            <el-form-item label="发生时间" required><el-input v-model="entry.eventTime" aria-label="发生时间" placeholder="2026-10-18T09:00:00"/><p class="intake-form-help">格式：年-月-日T时:分:秒</p></el-form-item>
            <el-form-item label="事件类别" required><el-select v-model="entry.categoryId" aria-label="事件类别" placeholder="请选择事件类别" filterable><el-option v-for="o in categories" :key="o.id" :label="o.name" :value="o.id"/></el-select></el-form-item>
            <el-form-item label="纠纷类型" required><el-select v-model="entry.disputeId" aria-label="纠纷类型" placeholder="请选择纠纷类型" filterable><el-option v-for="o in disputes" :key="o.id" :label="o.name" :value="o.id"/></el-select></el-form-item>
            <el-form-item class="full-width" label="问题描述" required><el-input v-model="entry.description" type="textarea" aria-label="问题描述" :rows="5" maxlength="10000" show-word-limit placeholder="请如实记录事情经过、主要诉求及相关情况"/></el-form-item>
          </div></section>
          <section class="intake-form-section"><h3><span>03</span> 上报来源</h3><div class="intake-form-grid">
            <el-form-item v-if="analyst" label="代录部门" required><el-select v-model="entry.departmentId" aria-label="代录部门" placeholder="请选择上报部门" filterable><el-option v-for="o in departments" :key="o.id" :label="o.name" :value="o.id"/></el-select></el-form-item>
            <el-form-item label="数据来源" required><el-select v-model="entry.sourceId" aria-label="数据来源" placeholder="请选择数据来源" filterable><el-option v-for="o in sources" :key="o.id" :label="o.name" :value="o.id"/></el-select></el-form-item>
          </div><p class="intake-form-help">提交后保留输入原文与操作者信息，用于后续核验和追溯。</p></section>
          <footer class="intake-form-footer"><span>确认内容后提交，或保存草稿稍后继续。</span><div class="form-actions"><el-button @click="edit()">新建草稿</el-button><el-button @click="run(save)">保存草稿</el-button><el-button type="primary" @click="run(submit)">提交事件</el-button></div></footer>
        </el-form>
      </section>
      <aside class="event-card intake-card intake-drafts"><header class="intake-card-heading"><div><h2>我的草稿 <span class="intake-count">{{ drafts.length }}</span></h2><p>仅展示本人尚未提交的记录。</p></div></header>
        <el-table :data="drafts" empty-text="暂无草稿，保存后可在此继续编辑" :aria-busy="busy"><el-table-column prop="id" label="编号" width="80"/><el-table-column label="当事人" min-width="140" show-overflow-tooltip><template #default="{row}">{{ row.payload.personName || '未填写姓名' }}</template></el-table-column><el-table-column label="操作" width="165"><template #default="{row}"><el-button link :disabled="busy" @click="edit(row)">编辑草稿</el-button><el-button link type="danger" :disabled="busy" @click="run(()=>remove(row))">删除草稿</el-button></template></el-table-column></el-table>
        <p class="intake-table-note">草稿支持分次完善。提交后可在原始记录中查看处理结果。</p>
      </aside></div>
      <section v-if="record" class="intake-submission"><div><h3>最近查看或提交 · 来源记录 #{{ record.id }}</h3><p>身份{{ identityLabels[record.identity_review_state] || '状态未知' }} · {{ duplicateLabels[record.duplicate_review_state] || '重复状态未知' }} · {{ record.effectiveEventId ? `有效事件 #${record.effectiveEventId}` : '尚未归集' }}</p></div><span>在「原始记录与重复候选」中查询完整内容</span></section>
    </el-tab-pane>
    <el-tab-pane label="原始记录与重复候选"><section class="event-card intake-card" :aria-busy="busy">
      <header class="intake-card-heading"><div><h2>原始记录查询</h2><p>追溯来源原文、身份匹配与重复筛查结果。</p></div></header>
      <form class="intake-toolbar" @submit.prevent="run(openRecord)"><label for="intake-record-id">来源记录编号</label><el-input id="intake-record-id" v-model="recordId" aria-label="来源记录编号" placeholder="请输入来源记录编号" clearable/><el-button type="primary" native-type="submit" :disabled="busy||!recordId">查询记录</el-button></form>
      <el-empty v-if="!record" description="输入来源记录编号，查看原始内容与处理状态" :image-size="72"/>
      <section v-if="record" class="record-detail">
        <div class="record-heading"><h2>来源记录 #{{ record.id }}</h2><div class="record-status"><el-tag :type="record.identity_review_state === 'CONFIRMED' ? 'success' : 'warning'" effect="plain">身份{{ identityLabels[record.identity_review_state] || '状态未知' }}</el-tag><el-tag type="info" effect="plain">{{ duplicateLabels[record.duplicate_review_state] || '重复状态未知' }}</el-tag></div></div>
        <dl class="record-fields"><div v-for="field in [{label:'当事人',value:record.party_name},{label:'身份证号',value:record.party_id_card},{label:'手机号',value:record.party_phone},{label:'联系地址',value:record.party_address},{label:'数据来源',value:record.event_source},{label:'特殊上报',value:record.special_report?'是':'否'},{label:'操作者',value:record.created_by},{label:'有效事件编号',value:record.effectiveEventId?`#${record.effectiveEventId}`:'尚未归集'}]" :key="field.label"><dt>{{ field.label }}</dt><dd>{{ field.value || '未提供' }}</dd></div></dl>
        <section class="record-section"><h3>问题描述</h3><p class="record-description">{{ record.problem_description || '未提供' }}</p></section>
        <section class="record-section"><h3>当前来源关键词命中</h3><div v-if="record.keywordHits?.length" class="keyword-hits"><div v-for="hit in record.keywordHits" :key="hit.ruleId" class="keyword-hit"><el-tag effect="plain">{{ hit.ruleName }}</el-tag><span class="muted">规则版本 {{ hit.ruleVersion }} · {{ record.effectiveEventId ? '已归集' : '待归集，暂不参与人员风险' }}</span></div></div><p v-else class="record-empty">暂无关键词命中</p></section>
        <section class="record-section"><h3>提交原文</h3><pre v-if="record.original_payload" class="record-original">{{ JSON.stringify(record.original_payload, null, 2) }}</pre><p v-else class="record-empty">历史记录未提供原始快照</p></section>
        <section v-if="analyst" class="record-section"><h3>疑似重复候选 <span class="intake-count">{{ candidates.length }}</span></h3><p v-if="!candidatesLoaded" class="record-empty">请点击查询记录，加载当前重复候选。</p><el-table v-else :data="candidates" empty-text="暂无达到阈值的候选"><el-table-column prop="id" label="编号" width="80"/><el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip/><el-table-column prop="secondsApart" label="时间差（秒）" min-width="125"/><el-table-column prop="similarity" label="相似度" min-width="90"/><el-table-column prop="basis" label="依据" min-width="150" show-overflow-tooltip/></el-table></section>
      </section>
    </section></el-tab-pane>
    <el-tab-pane v-if="analyst" label="人员档案"><section class="event-card intake-card" :aria-busy="busy"><header class="intake-card-heading"><div><h2>人员档案 <span class="intake-count">{{ people.length }}</span></h2><p>检索现有档案，核对人员关联的来源记录。</p></div></header>
      <form class="intake-toolbar" @submit.prevent="run(refresh)"><el-input v-model="query" aria-label="搜索人员" placeholder="姓名、身份证或手机号" clearable/><el-button type="primary" native-type="submit" :disabled="busy">搜索档案</el-button></form>
      <el-table :data="people" empty-text="暂无匹配档案，请调整搜索条件"><el-table-column prop="id" label="编号" width="85"/><el-table-column prop="name" label="姓名" min-width="140" show-overflow-tooltip/><el-table-column prop="idCard" label="身份证" min-width="190"/><el-table-column prop="phone" label="手机号" min-width="140"/><el-table-column label="详情" width="120"><template #default="{row}"><el-button link :disabled="busy" @click="run(async()=>{person=await api('/intake/people/'+row.id)})">档案详情</el-button></template></el-table-column></el-table>
      <section v-if="person" class="detail-panel"><h3>{{ person.person.name }} · 有效次数 {{ person.effectiveCount }}</h3><p class="muted">手机号差异保留在来源记录，档案不自动覆盖。</p><el-table :data="person.events" empty-text="暂无关联来源记录"><el-table-column prop="id" label="事件" width="85"/><el-table-column prop="party_phone" label="来源手机号" min-width="140"/><el-table-column prop="party_address" label="来源联系地址" min-width="200" show-overflow-tooltip/><el-table-column prop="problem_description" label="描述" min-width="240" show-overflow-tooltip/><el-table-column label="身份状态" min-width="115"><template #default="{row}"><el-tag :type="row.identity_review_state==='CONFIRMED'?'success':'warning'" effect="plain">{{ identityLabels[row.identity_review_state] || '状态未知' }}</el-tag></template></el-table-column><el-table-column label="重复状态" min-width="145"><template #default="{row}"><el-tag type="info" effect="plain">{{ duplicateLabels[row.duplicate_review_state] || '重复状态未知' }}</el-tag></template></el-table-column></el-table></section>
    </section></el-tab-pane>
    <el-tab-pane v-if="analyst" label="身份审核"><section class="event-card intake-card" :aria-busy="busy"><header class="intake-card-heading"><div><h2>待审核身份 <span class="intake-count">{{ reviews.length }}</span></h2><p>核实来源信息后关联已有档案，或按依据受控建档。</p></div><el-button :disabled="busy" @click="run(refresh)">刷新待审</el-button></header>
      <el-table :data="reviews" empty-text="暂无待审核身份"><el-table-column prop="id" label="编号" width="85"/><el-table-column prop="party_name" label="姓名" min-width="140" show-overflow-tooltip/><el-table-column prop="party_id_card" label="身份证" min-width="190"/><el-table-column prop="party_phone" label="手机号" min-width="140"/><el-table-column prop="problem_description" label="问题描述" min-width="220" show-overflow-tooltip/><el-table-column label="操作" width="120"><template #default="{row}"><el-button link :disabled="busy" @click="startReview(row)">审核身份</el-button></template></el-table-column></el-table>
      <section v-if="reviewing" class="detail-panel"><h3>审核记录 #{{ reviewing }}</h3><p class="muted">关联档案需核对姓名及身份证或手机号一致；新建档案需提供姓名及有效身份证或手机号。</p><el-form class="review-form intake-form-grid" label-position="top" :disabled="busy">
        <el-form-item class="full-width archive-picker" label="关联档案（留空则新建）"><el-select v-model="review.personId" aria-label="关联档案（留空则新建）" clearable filterable placeholder="选择已有档案，或留空新建"><el-option v-for="p in people" :key="p.id" :label="`${p.id} ${p.name} ${p.idCard||p.phone}`" :value="p.id"/></el-select><div class="candidate-filter"><el-input v-model="query" aria-label="筛选候选档案" placeholder="筛选档案姓名、身份证或手机号"/><el-button :disabled="busy" @click="run(refresh)">筛选候选档案</el-button></div></el-form-item>
        <el-form-item label="确认姓名"><el-input v-model="review.personName" aria-label="确认姓名"/></el-form-item><el-form-item label="确认身份证"><el-input v-model="review.idCard" aria-label="确认身份证"/></el-form-item><el-form-item label="确认手机号"><el-input v-model="review.phone" aria-label="确认手机号"/></el-form-item><el-form-item class="full-width" label="审核依据和理由" required><el-input v-model="review.reason" aria-label="审核依据和理由" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="请记录核验材料及身份归属的判断依据"/></el-form-item>
        <div class="form-actions full-width"><el-button type="primary" @click="run(confirm)">确认身份归属</el-button><el-button @click="reviewing=null">取消审核</el-button></div>
      </el-form></section>
    </section></el-tab-pane>
    <el-tab-pane v-if="analyst" label="重复筛查配置"><section class="event-card intake-card" :aria-busy="busy"><header class="intake-card-heading"><div><h2>重复筛查配置</h2><p>设置候选识别范围，辅助中心审核事件归集。</p></div><el-tag type="info" effect="plain">版本 {{ settings.version }}</el-tag></header><div class="intake-settings-body"><el-form class="settings-form" label-position="top" :disabled="busy"><el-form-item label="时间窗口（天，含边界）"><el-input-number v-model="settings.windowDays" aria-label="时间窗口（天，含边界）" :min="0" :max="365"/><p class="intake-form-help">可设置 0–365 天，包含时间边界。</p></el-form-item><el-form-item label="描述相似度阈值"><el-input-number v-model="settings.threshold" aria-label="描述相似度阈值" :min="0.01" :max="1" :step="0.05"/><p class="intake-form-help">大于 0 且不超过 1，数值越高匹配越严格。</p></el-form-item><el-button type="primary" @click="run(saveSettings)">保存筛查配置</el-button></el-form><aside class="intake-settings-note"><h3>筛查说明</h3><p>默认三天、阈值 0.7。保存后将重新筛查现有记录。</p><p>疑似重复只提示，后续由中心审核是否归集。</p></aside></div></section></el-tab-pane>
  </el-tabs>
</main>
</template>
