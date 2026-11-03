<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, ApiError, type Account, type EventPage, type EventSummary } from './api'
import InsightsWorkspace from './InsightsWorkspace.vue'
import WorkspaceNavigation from './WorkspaceNavigation.vue'
import CaseWorkspace from './CaseWorkspace.vue'
import ArchiveWorkspace from './ArchiveWorkspace.vue'
import GovernanceTools from './GovernanceTools.vue'
import RiskWorkspace from './RiskWorkspace.vue'
import IntakeWorkspace from './IntakeWorkspace.vue'
import AccountManagement from './AccountManagement.vue'
import CatalogManagement from './CatalogManagement.vue'

const account = ref<Account | null>(null)
const initializing = ref(true)
const username = ref('')
const password = ref('')
const busy = ref(false)
const loadingEvents = ref(false)
const error = ref('')
const events = ref<EventSummary[]>([])
const total = ref(0)
const page = ref(1)
const query = ref('')
const appliedQuery = ref('')
const keywordQuery = ref(''), departmentQuery = ref(''), uploadedFrom = ref(''), uploadedTo = ref('')
const appliedFilters = ref({department:'',keyword:'',from:'',to:''})
const detail = ref<EventSummary | null>(null)
const detailOpen = ref(false)
const section = ref('events')
const navigationOpen = ref(false)
function selectSection(value: string) { section.value = value; navigationOpen.value = false }
const isAdministrator = computed(() => account.value?.roles.includes('CENTER_ADMIN'))
let sessionVersion = 0
let detailRequest = 0
const scope = computed(() => account.value?.roles.includes('CENTER_ANALYST') ? '全部门事件' : '本部门事件')
const canReadEvents = computed(() => account.value?.roles.some(role => ['CENTER_ANALYST', 'DEPARTMENT_REPORTER'].includes(role)))

function handleError(cause: unknown) {
  error.value = cause instanceof Error ? cause.message : '请求失败'
  if (cause instanceof ApiError && cause.status === 401) clearSession()
}

function clearSession() {
  sessionVersion++
  detailRequest++
  account.value = null
  section.value = 'events'
  navigationOpen.value = false
  events.value = []
  total.value = 0
  detail.value = null
  detailOpen.value = false
  page.value = 1
  query.value = ''
  appliedQuery.value = ''
  departmentQuery.value = ''; keywordQuery.value = ''; uploadedFrom.value = ''; uploadedTo.value = ''
  appliedFilters.value = {department:'',keyword:'',from:'',to:''}
  loadingEvents.value = false
}

async function loadEvents() {
  const version = sessionVersion
  loadingEvents.value = true
  error.value = ''
  try {
    const params = new URLSearchParams({page:String(page.value),size:'10',q:appliedQuery.value,...appliedFilters.value})
    const result = await api<EventPage>('/events?'+params)
    if (version !== sessionVersion) return
    events.value = result.items
    total.value = result.total
  } catch (cause) {
    if (version === sessionVersion) { events.value = []; total.value = 0; handleError(cause) }
  } finally { if (version === sessionVersion) loadingEvents.value = false }
}

async function signIn() {
  if (!username.value.trim() || !password.value) { error.value = '请输入账号和密码'; return }
  busy.value = true
  error.value = ''
  try {
    await api('/auth/login', { method: 'POST', body: new URLSearchParams({ username: username.value.trim(), password: password.value }) })
    password.value = ''
    sessionVersion++
    account.value = await api<Account>('/auth/me')
    if (canReadEvents.value) await loadEvents()
  } catch (cause) {
    handleError(cause)
    if (cause instanceof ApiError && cause.status === 401) error.value = '账号或密码错误，或账号已停用'
  } finally { busy.value = false }
}

async function signOut() {
  busy.value = true
  try { await api('/auth/logout', { method: 'POST' }); clearSession(); error.value = '' }
  catch (cause) { handleError(cause) }
  finally { busy.value = false }
}

async function openDetail(id: number) {
  const version = sessionVersion
  const request = ++detailRequest
  detail.value = null
  error.value = ''
  try {
    const result = await api<EventSummary>(`/events/${id}`)
    if (version !== sessionVersion || request !== detailRequest) return
    detail.value = result
    detailOpen.value = true
  } catch (cause) {
    if (version === sessionVersion && request === detailRequest) handleError(cause)
  }
}

function search() {
  if (uploadedFrom.value && uploadedTo.value && uploadedFrom.value > uploadedTo.value) { error.value = '上传起始时间不能晚于截止时间'; return }
  appliedQuery.value = query.value.trim()
  appliedFilters.value = {department:departmentQuery.value.trim(),keyword:keywordQuery.value.trim(),from:uploadedFrom.value||'',to:uploadedTo.value||''}
  page.value = 1; void loadEvents()
}
function resetSearch() { query.value = ''; departmentQuery.value = ''; keywordQuery.value = ''; uploadedFrom.value = ''; uploadedTo.value = ''; search() }

async function refreshAccount() {
  const version = sessionVersion
  try {
    const result = await api<Account>('/auth/me')
    if (version === sessionVersion) account.value = result
  } catch (cause) { if (version === sessionVersion) handleError(cause) }
}

onMounted(async () => {
  try { account.value = await api<Account>('/auth/me'); if (canReadEvents.value) await loadEvents() }
  catch (cause) { if (!(cause instanceof ApiError && cause.status === 401)) handleError(cause) }
  finally { initializing.value = false }
})
</script>

<template>
  <div v-if="initializing" class="initializing" role="status">正在加载工作台…</div>
  <main v-else-if="!account" class="login-layout">
    <section class="login-intro">
      <div class="brand"><span class="brand-mark">治</span> 社会治理数据平台</div>
      <div><p class="eyebrow">社会治理 · 协同共治</p><h1>让每一条线索<br>都有清晰的来路。</h1><p class="intro-copy">归集部门事件，连接基层工作。<br>从一份可靠的记录开始。</p></div>
      <span class="intro-foot">社会治理数据分析与风险预警系统</span>
    </section>
    <section class="login-panel">
      <div class="login-form"><p class="eyebrow">工作台登录</p><h2>欢迎回来</h2><p class="muted">请使用已分配的工作账号登录</p>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <el-form label-position="top" @submit.prevent="signIn">
          <el-form-item label="账号"><el-input v-model="username" autocomplete="username" placeholder="请输入工作账号" aria-label="账号" size="large" /></el-form-item>
          <el-form-item label="密码"><el-input v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" aria-label="密码" show-password size="large" /></el-form-item>
          <el-button type="primary" native-type="submit" :loading="busy" size="large" class="login-button">登录</el-button>
        </el-form>
        <p class="login-note">仅展示当前账号权限内的数据<br>教学演示环境 · 使用虚构样例数据</p>
      </div>
    </section>
  </main>
  <div v-else class="workspace">
    <aside class="sidebar"><WorkspaceNavigation :account="account" :section="section" @select="selectSection" /></aside>
    <el-drawer v-model="navigationOpen" class="navigation-drawer" title="业务导航" direction="ltr" size="272px">
      <WorkspaceNavigation :account="account" :section="section" @select="selectSection" />
    </el-drawer>
    <section class="main-area">
      <header class="workspace-header"><div class="header-location"><el-button class="navigation-toggle" aria-label="打开业务导航" :aria-expanded="navigationOpen" @click="navigationOpen = true">☰</el-button><span>工作台 <span class="separator">/</span> {{ section === 'insights' ? '业务概况与追溯' : section === 'cases' ? '预警与处置' : section === 'archive' ? '修正与归档审核' : section === 'tools' ? '规则与批量接入' : section === 'risk' ? '归集与风险' : section === 'intake' ? '录入与身份处理' : section === 'accounts' ? '部门与账号' : section === 'catalog' ? '来源与分类' : '事件列表' }}</span></div><div class="user-info"><span class="account-avatar" aria-hidden="true">{{ account.displayName.slice(0, 1) }}</span><span>{{ account.displayName }} <small>{{ account.departmentName }}</small></span><el-button :loading="busy" @click="signOut">退出登录</el-button></div></header>
      <InsightsWorkspace v-if="section==='insights'" :account="account" @expired="clearSession"/><CaseWorkspace v-else-if="section==='cases'" :key="account.id" :account="account" @expired="clearSession"/><ArchiveWorkspace v-else-if="section==='archive' && canReadEvents" :account="account" @expired="clearSession" /><GovernanceTools v-else-if="section==='tools'" :account="account" @expired="clearSession" /><RiskWorkspace v-else-if="section === 'risk' && account.roles.includes('CENTER_ANALYST')" :key="account.id" @expired="clearSession" />
      <IntakeWorkspace v-else-if="section === 'intake' && canReadEvents" :key="account.id" :account="account" @expired="clearSession" />
      <AccountManagement v-else-if="section === 'accounts' && isAdministrator" :key="account.id" @expired="clearSession" @changed="refreshAccount" />
      <CatalogManagement v-else-if="section === 'catalog'" :key="account.id" :administrator="!!isAdministrator" @expired="clearSession" />
      <main v-else class="content event-workspace"><div class="page-heading"><div><p class="eyebrow">事件管理</p><h1>事件列表</h1><p class="muted">查看来源记录与事件详情，掌握权限内的上报信息。</p></div><div v-if="canReadEvents" class="page-actions"><el-tag effect="plain" size="large">{{ scope }}</el-tag><el-button :loading="loadingEvents" @click="loadEvents">刷新列表</el-button></div></div>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <el-alert v-if="!canReadEvents" title="当前账号未分配事件查看权限" description="来源事件仅向中心研判或部门上报角色开放。" type="info" :closable="false" show-icon />
        <section v-if="canReadEvents" class="event-card"><div class="card-heading"><div><h2>查询条件</h2><p class="muted">组合筛选，快速定位来源记录</p></div><span class="filter-caption">{{ account.departmentName }}</span></div>
          <form class="event-filters" @submit.prevent="search"><div class="event-filter-fields"><label>当事人或描述<el-input v-model="query" placeholder="搜索当事人或事件描述" aria-label="搜索事件" clearable maxlength="100" /></label><label>关键词<el-input v-model="keywordQuery" placeholder="搜索人工关键词标签" aria-label="关键词筛选" clearable maxlength="100" /></label><label>上报部门<el-input v-model="departmentQuery" placeholder="输入部门关键词" aria-label="部门关键词" clearable maxlength="100" /></label><label>上传起始时间<el-date-picker v-model="uploadedFrom" type="datetime" aria-label="上传起始时间" placeholder="选择起始时间" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DDTHH:mm:ss" :editable="false" clearable /></label><label>上传截止时间<el-date-picker v-model="uploadedTo" type="datetime" aria-label="上传截止时间" placeholder="选择截止时间" format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DDTHH:mm:ss" :editable="false" clearable /></label></div><div class="event-filter-actions"><span class="muted">上传时间按记录提交入库时间筛选</span><el-button type="primary" native-type="submit" :disabled="loadingEvents">查询</el-button><el-button :disabled="loadingEvents" @click="resetSearch">重置</el-button></div></form>
          <div class="event-results-heading"><h2>来源上报记录 <span>{{ total }} 条</span></h2><span class="muted" role="status">{{ loadingEvents ? '正在加载记录…' : '每页 10 条' }}</span></div>
          <el-table v-loading="loadingEvents" :data="events" empty-text="暂无符合条件的事件" :aria-busy="loadingEvents">
            <el-table-column prop="id" label="编号" width="80" />
            <el-table-column prop="personName" label="当事人" min-width="110" />
            <el-table-column prop="idCard" label="当事人身份证号" min-width="190" show-overflow-tooltip />
            <el-table-column prop="phone" label="当事人电话" min-width="140" />
            <el-table-column prop="address" label="联系地址" min-width="200" show-overflow-tooltip />
            <el-table-column prop="keywords" label="关键词" min-width="160" show-overflow-tooltip><template #default="{row}">{{row.keywords||'未标注'}}</template></el-table-column>
            <el-table-column prop="eventCategory" label="事件类别" min-width="120" />
            <el-table-column prop="eventDescription" label="问题描述" min-width="240" show-overflow-tooltip />
            <el-table-column prop="departmentName" label="上报部门" min-width="130" />
            <el-table-column label="发生时间" min-width="175"><template #default="{ row }">{{ row.eventTime.replace('T', ' ') }}</template></el-table-column>
            <el-table-column label="办理状态" width="105"><template #default="{ row }"><el-tag :type="row.isCompleted ? 'success' : 'info'">{{ row.isCompleted ? '已办结' : '未办结' }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="110" fixed="right"><template #default="{ row }"><el-button link type="primary" :aria-label="`查看事件${row.id}`" @click="openDetail(row.id)">查看详情</el-button></template></el-table-column>
          </el-table>
          <div class="table-footer"><span class="muted">统计口径：来源上报记录数</span><el-pagination v-model:current-page="page" :disabled="loadingEvents" :page-size="10" :total="total" layout="total, prev, pager, next" @current-change="loadEvents" /></div>
        </section><p class="data-note">权限范围由账号确定。事件办结状态与人员风险等级分别管理。</p>
      </main>
    </section>
    <el-drawer v-model="detailOpen" class="event-detail-drawer" title="事件详情" size="min(620px, 100vw)">
      <template v-if="detail">
        <div class="detail-summary"><div><p class="eyebrow">来源记录 #{{ detail.id }}</p><h2>{{ detail.personName || '未填写姓名' }}</h2><p class="muted">{{ detail.departmentName }}</p></div><el-tag :type="detail.isCompleted ? 'success' : 'info'">{{ detail.isCompleted ? '已办结' : '未办结' }}</el-tag></div>
        <section class="detail-section"><h3>当事人信息</h3><el-descriptions :column="1" border>
          <el-descriptions-item label="当事人身份证号">{{ detail.idCard || '未填写' }}</el-descriptions-item>
          <el-descriptions-item label="当事人电话">{{ detail.phone || '未填写' }}</el-descriptions-item>
          <el-descriptions-item label="联系地址">{{ detail.address || '未填写' }}</el-descriptions-item>
        </el-descriptions></section>
        <section class="detail-section"><h3>事件信息</h3><el-descriptions :column="1" border>
          <el-descriptions-item label="关键词">{{ detail.keywords || '未标注' }}</el-descriptions-item>
          <el-descriptions-item label="上报部门">{{ detail.departmentName }}</el-descriptions-item>
          <el-descriptions-item label="数据来源">{{ detail.eventSource }}</el-descriptions-item>
          <el-descriptions-item label="发生时间">{{ detail.eventTime.replace('T', ' ') }}</el-descriptions-item>
          <el-descriptions-item label="事件类别">{{ detail.eventCategory }}</el-descriptions-item>
          <el-descriptions-item label="纠纷类型">{{ detail.disputeType }}</el-descriptions-item>
          <el-descriptions-item label="办理状态">{{ detail.isCompleted ? '已办结' : '未办结' }}</el-descriptions-item>
        </el-descriptions></section>
        <section class="detail-section"><h3>问题描述</h3><p class="description">{{ detail.eventDescription }}</p></section>
      </template>
      <template #footer><el-button @click="detailOpen = false">关闭详情</el-button></template>
    </el-drawer>
  </div>
</template>
