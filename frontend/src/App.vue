<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, ApiError, type Account, type EventPage, type EventSummary } from './api'

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
const detail = ref<EventSummary | null>(null)
const detailOpen = ref(false)
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
  events.value = []
  total.value = 0
  detail.value = null
  detailOpen.value = false
  page.value = 1
  query.value = ''
  appliedQuery.value = ''
  loadingEvents.value = false
}

async function loadEvents() {
  const version = sessionVersion
  loadingEvents.value = true
  error.value = ''
  try {
    const result = await api<EventPage>(`/events?page=${page.value}&size=10&q=${encodeURIComponent(appliedQuery.value)}`)
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

function search() { appliedQuery.value = query.value.trim(); page.value = 1; void loadEvents() }

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
    <aside class="sidebar"><div class="brand"><span class="brand-mark">治</span><span>社会治理<br><small>分析预警工作台</small></span></div><p class="nav-label">业务工作</p><div class="nav-active">▤ &nbsp; 事件列表</div><div class="sidebar-foot">教学演示系统<br>数据仅用于功能验证</div></aside>
    <section class="main-area">
      <header><span>业务工作 <span class="separator">/</span> 事件列表</span><div class="user-info"><span>{{ account.displayName }} <small>{{ account.departmentName }}</small></span><el-button :loading="busy" @click="signOut">退出登录</el-button></div></header>
      <main class="content"><div class="page-heading"><div><p class="eyebrow">事件管理</p><h1>事件列表</h1><p class="muted">查看来源记录与事件详情，掌握权限内的上报信息。</p></div><el-tag v-if="canReadEvents" effect="plain" size="large">{{ scope }}</el-tag></div>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <el-alert v-if="!canReadEvents" title="当前账号未分配事件查看权限" description="来源事件仅向中心研判或部门上报角色开放。" type="info" :closable="false" show-icon />
        <section v-if="canReadEvents" class="event-card"><div class="card-heading"><h2>来源上报记录 <span>{{ total }}</span></h2><span class="muted">{{ account.departmentName }}</span></div>
          <form class="filter-bar" @submit.prevent="search"><el-input v-model="query" placeholder="搜索当事人或事件描述" aria-label="搜索事件" clearable maxlength="100" /><el-button type="primary" native-type="submit" :disabled="loadingEvents">查询</el-button><el-button :disabled="loadingEvents" @click="query = ''; search()">重置</el-button></form>
          <el-table v-loading="loadingEvents" :data="events" empty-text="暂无符合条件的事件" stripe>
            <el-table-column prop="id" label="编号" width="80" />
            <el-table-column prop="personName" label="当事人" min-width="110" />
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
    <el-drawer v-model="detailOpen" title="事件详情" size="min(580px, 100vw)">
      <template v-if="detail"><el-tag effect="plain">来源记录 #{{ detail.id }}</el-tag><h2>{{ detail.personName }}</h2><el-descriptions :column="1" border>
        <el-descriptions-item label="上报部门">{{ detail.departmentName }}</el-descriptions-item>
        <el-descriptions-item label="数据来源">{{ detail.eventSource }}</el-descriptions-item>
        <el-descriptions-item label="发生时间">{{ detail.eventTime.replace('T', ' ') }}</el-descriptions-item>
        <el-descriptions-item label="事件类别">{{ detail.eventCategory }}</el-descriptions-item>
        <el-descriptions-item label="纠纷类型">{{ detail.disputeType }}</el-descriptions-item>
        <el-descriptions-item label="办理状态">{{ detail.isCompleted ? '已办结' : '未办结' }}</el-descriptions-item>
      </el-descriptions><h3>问题描述</h3><p class="description">{{ detail.eventDescription }}</p></template>
    </el-drawer>
  </div>
</template>
