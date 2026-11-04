<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api, ApiError } from './api'
import NavIcon from './NavIcon.vue'
import './accounts.css'

interface Department { id: number; name: string; departmentType: string; enabled: boolean; version: number }
interface User { id: number; username: string; displayName: string; departmentId: number; departmentName: string; enabled: boolean; roles: string[]; version: number }
const emit = defineEmits<{ expired: []; changed: [] }>()
const departments = ref<Department[]>([])
const users = ref<User[]>([])
const roles = ref<{ code: string; name: string }[]>([])
const tab = ref('users')
const filters = reactive({ keyword: '', status: '', departmentId: undefined as number | undefined })
const applied = reactive({ ...filters })
const filteredUsers = computed(() => users.value.filter(user => {
  const keyword = applied.keyword.trim().toLocaleLowerCase()
  return (!keyword || [user.username, user.displayName, user.departmentName].some(value => value.toLocaleLowerCase().includes(keyword)))
    && (!applied.status || String(user.enabled) === applied.status)
    && (!applied.departmentId || user.departmentId === applied.departmentId)
}))
const filteredDepartments = computed(() => departments.value.filter(department =>
  department.name.toLocaleLowerCase().includes(applied.keyword.trim().toLocaleLowerCase())
  && (!applied.status || String(department.enabled) === applied.status),
))
function resetFilters() {
  Object.assign(filters, { keyword: '', status: '', departmentId: undefined })
  Object.assign(applied, filters)
}
function roleName(code: string) { return roles.value.find(role => role.code === code)?.name || code }
const loading = ref(false)
const error = ref('')
const notice = ref('')
const departmentOpen = ref(false)
const userOpen = ref(false)
const departmentForm = reactive({ id: 0, name: '', departmentType: 'DEPARTMENT', enabled: true, version: 0 })
const userForm = reactive({ id: 0, username: '', displayName: '', departmentId: undefined as number | undefined, enabled: true, roles: [] as string[], password: '', version: 0 })
let active = true
onBeforeUnmount(() => { active = false })

async function run(action: () => Promise<void>) {
  if (loading.value) return
  loading.value = true; error.value = ''; notice.value = ''
  try { await action() }
  catch (cause) {
    if (!active) return
    error.value = cause instanceof Error ? cause.message : '请求失败'
    if (cause instanceof ApiError && cause.status === 401) emit('expired')
  } finally { if (active) loading.value = false }
}
async function load() {
  const [nextDepartments, nextUsers, nextRoles] = await Promise.all([
    api<Department[]>('/admin/departments'), api<User[]>('/admin/users'), api<{ code: string; name: string }[]>('/admin/roles'),
  ])
  if (!active) return
  departments.value = nextDepartments; users.value = nextUsers; roles.value = nextRoles
}
function editDepartment(value?: Department) {
  Object.assign(departmentForm, value || { id: 0, name: '', departmentType: 'DEPARTMENT', enabled: true, version: 0 })
  error.value = ''; departmentOpen.value = true
}
function editUser(value?: User) {
  Object.assign(userForm, value ? { ...value, roles: [...value.roles], password: '' } : { id: 0, username: '', displayName: '', departmentId: undefined, enabled: true, roles: [], password: '', version: 0 })
  error.value = ''; userOpen.value = true
}
async function saveDepartment() {
  await run(async () => {
    await api(`/admin/departments${departmentForm.id ? `/${departmentForm.id}` : ''}`, { method: departmentForm.id ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(departmentForm) })
    if (!active) return
    departmentOpen.value = false; emit('changed'); await load(); notice.value = '部门已保存'
  })
}
async function saveUser() {
  await run(async () => {
    await api(`/admin/users${userForm.id ? `/${userForm.id}` : ''}`, { method: userForm.id ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(userForm) })
    if (!active) return
    userForm.password = ''; userOpen.value = false; emit('changed'); await load(); notice.value = '账号已保存'
  })
}
onMounted(() => { void run(load) })
</script>

<template>
  <main class="content accounts-workspace">
    <div class="page-heading">
      <div><p class="eyebrow">系统管理 / 组织与权限</p><h1>部门与账号</h1><p class="muted">维护组织信息与成员权限，让每个账号各司其职。</p></div>
      <el-button :loading="loading" @click="run(load)">刷新列表</el-button>
    </div>
    <el-alert v-if="error && !departmentOpen && !userOpen" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" :title="notice" type="success" :closable="false" />
    <section class="accounts-summary" aria-label="组织与账号概况">
      <div><span class="summary-icon"><NavIcon name="catalog" /></span><div><span>部门总数</span><strong>{{ departments.length }}<small>个部门</small></strong></div></div>
      <div><span class="summary-icon"><NavIcon name="accounts" /></span><div><span>账号总数</span><strong>{{ users.length }}<small>个账号</small></strong></div></div>
      <div><span class="summary-icon"><NavIcon name="risk" /></span><div><span>已启用账号</span><strong>{{ users.filter(user => user.enabled).length }}<small>按账号启用状态统计</small></strong></div></div>
    </section>
    <section class="management-card accounts-card" v-loading="loading" :aria-busy="loading">
      <el-tabs v-model="tab" @tab-change="resetFilters">
        <el-tab-pane v-for="pane in [{ name: 'users', label: '账号管理' }, { name: 'departments', label: '部门管理' }]" :key="pane.name" :label="pane.label" :name="pane.name">
          <template v-if="tab === pane.name">
      <form class="accounts-filters" @submit.prevent="Object.assign(applied, filters)">
        <div class="accounts-filter-fields">
          <label :for="'account-keyword'">{{ tab === 'users' ? '账号 / 姓名 / 部门' : '部门名称' }}<el-input id="account-keyword" v-model="filters.keyword" :aria-label="tab === 'users' ? '查找账号' : '查找部门'" :placeholder="tab === 'users' ? '输入账号、姓名或部门关键词' : '输入部门名称关键词'" clearable /></label>
          <label v-if="tab === 'users'" for="account-department">归属部门<el-select id="account-department" v-model="filters.departmentId" aria-label="筛选归属部门" placeholder="全部部门" clearable filterable><el-option v-for="department in departments" :key="department.id" :value="department.id" :label="department.name" /></el-select></label>
          <label for="account-status">启用状态<el-select id="account-status" v-model="filters.status" aria-label="筛选启用状态" placeholder="全部状态" clearable><el-option label="启用" value="true" /><el-option label="停用" value="false" /></el-select></label>
        </div>
        <div class="accounts-filter-actions"><el-button type="primary" native-type="submit">查询</el-button><el-button @click="resetFilters">重置</el-button><span class="muted">{{ tab === 'users' ? '角色可兼任，权限以实际授予为准。' : '部门停用后，该部门账号将无法使用。' }}</span></div>
      </form>
      <div class="accounts-results-heading">
        <div><h2>{{ tab === 'users' ? '账号列表' : '部门列表' }}<span>{{ tab === 'users' ? filteredUsers.length : filteredDepartments.length }}</span></h2><p class="muted">{{ tab === 'users' ? '账号变更后，原登录会话将失效' : '停用部门不影响已有历史记录的保留' }}</p></div>
        <el-button type="primary" :disabled="loading" @click="tab === 'users' ? editUser() : editDepartment()">{{ tab === 'users' ? '新增账号' : '新增部门' }}</el-button>
      </div>
      <el-table v-if="tab === 'users'" :data="filteredUsers" row-key="id">
        <el-table-column prop="username" label="登录账号" min-width="155" show-overflow-tooltip />
        <el-table-column prop="displayName" label="姓名" min-width="120" show-overflow-tooltip />
        <el-table-column prop="departmentName" label="归属部门" min-width="170" show-overflow-tooltip />
        <el-table-column label="角色与权限" min-width="245"><template #default="{ row }"><div class="account-role-tags"><el-tag v-for="role in row.roles" :key="role" effect="plain">{{ roleName(role) }}</el-tag></div></template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" effect="light">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="80" fixed="right"><template #default="{ row }"><el-button link type="primary" :disabled="loading" :aria-label="`编辑账号${row.username}`" @click="editUser(row)">编辑</el-button></template></el-table-column>
        <template #empty><el-empty :description="users.length ? '没有符合条件的账号，请调整查询条件' : '暂无账号'" :image-size="64" /></template>
      </el-table>
      <el-table v-else :data="filteredDepartments" row-key="id">
        <el-table-column prop="name" label="部门名称" min-width="240" show-overflow-tooltip />
        <el-table-column label="单位类型" min-width="180"><template #default="{ row }"><el-tag type="info" effect="plain">{{ row.departmentType === 'CENTER' ? '综治中心' : '上报及处置部门' }}</el-tag></template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="80" fixed="right"><template #default="{ row }"><el-button link type="primary" :disabled="loading" :aria-label="`编辑部门${row.name}`" @click="editDepartment(row)">编辑</el-button></template></el-table-column>
        <template #empty><el-empty :description="departments.length ? '没有符合条件的部门，请调整查询条件' : '暂无部门，可新增部门后配置账号'" :image-size="64" /></template>
      </el-table>
      <div class="accounts-table-note">{{ tab === 'users' ? '共 ' + users.length + ' 个账号 · 启用状态与角色权限分别维护' : '共 ' + departments.length + ' 个部门 · 单位类型创建后不可修改' }}</div>
          </template>
        </el-tab-pane>
      </el-tabs>
    </section>
    <p class="data-note">组织与权限配置即时生效，请在保存前确认归属部门、角色及启用状态。</p>
    <el-dialog v-model="departmentOpen" class="account-dialog" :title="departmentForm.id ? '编辑部门' : '新增部门'" width="min(540px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!loading" :show-close="!loading">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form id="department-editor" label-position="top" :disabled="loading" @submit.prevent="saveDepartment">
        <section class="account-form-section"><h3>部门信息</h3><p class="muted">维护部门名称、单位类型和可用状态。</p>
          <el-form-item label="部门名称" required><el-input v-model="departmentForm.name" aria-label="部门名称" maxlength="100" placeholder="请输入部门全称" /></el-form-item>
          <el-form-item label="单位类型" required><el-select v-model="departmentForm.departmentType" aria-label="单位类型" :disabled="!!departmentForm.id"><el-option label="上报及处置部门" value="DEPARTMENT" /><el-option label="综治中心" value="CENTER" /></el-select></el-form-item>
          <p v-if="departmentForm.id" class="muted">单位类型创建后不可修改。</p>
        </section>
        <div class="account-status-setting"><div><strong>启用状态</strong><p>停用后，该部门账号将无法使用，历史记录保留。</p></div><el-switch v-model="departmentForm.enabled" aria-label="部门启用" /></div>
      </el-form>
      <template #footer><el-button :disabled="loading" @click="departmentOpen = false">取消</el-button><el-button type="primary" native-type="submit" form="department-editor" :loading="loading">保存部门</el-button></template>
    </el-dialog>
    <el-dialog v-model="userOpen" class="account-dialog" :title="userForm.id ? '编辑账号' : '新增账号'" width="min(680px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!loading" :show-close="!loading" @closed="userForm.password = ''">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form id="user-editor" label-position="top" :disabled="loading" @submit.prevent="saveUser">
        <section class="account-form-section"><h3>基本信息</h3><p class="muted">账号用于登录系统，姓名用于识别操作人员。</p>
          <div class="account-form-grid">
            <el-form-item label="登录账号" required><el-input v-model="userForm.username" aria-label="登录账号" :disabled="!!userForm.id" maxlength="50" placeholder="3–50 位字母、数字或 _ . -" /></el-form-item>
            <el-form-item label="姓名" required><el-input v-model="userForm.displayName" aria-label="姓名" maxlength="100" placeholder="请输入姓名" /></el-form-item>
          </div>
          <el-form-item label="归属部门" required><el-select v-model="userForm.departmentId" aria-label="归属部门" placeholder="请选择归属部门" filterable><el-option v-for="department in departments" :key="department.id" :value="department.id" :label="department.name + (department.enabled ? '' : '（停用）')" :disabled="!department.enabled && userForm.departmentId !== department.id" /></el-select></el-form-item>
        </section>
        <section class="account-form-section"><h3>角色与权限 <span>可多选</span></h3>
          <el-checkbox-group v-model="userForm.roles" class="account-role-options" aria-label="角色与权限"><el-checkbox v-for="role in roles" :key="role.code" :value="role.code">{{ role.name }}</el-checkbox></el-checkbox-group>
          <p class="account-form-help">中心管理与研判可兼任；部门上报与处置可兼任。特殊上报须同时具备上报权限，并归属具体部门。</p>
        </section>
        <section class="account-form-section"><h3>安全设置</h3>
          <el-form-item :label="userForm.id ? '重置密码（留空则保持原密码）' : '初始密码'" :required="!userForm.id"><el-input v-model="userForm.password" aria-label="账号密码" type="password" autocomplete="new-password" show-password :placeholder="userForm.id ? '无需重置时请留空' : '请设置初始登录密码'" /></el-form-item>
          <p class="account-form-help">密码至少 12 字符，UTF-8 编码不超过 72 字节。</p>
          <div class="account-status-setting"><div><strong>启用状态</strong><p>保存变更后，原登录会话将失效。</p></div><el-switch v-model="userForm.enabled" aria-label="账号启用" /></div>
        </section>
      </el-form>
      <template #footer><el-button :disabled="loading" @click="userOpen = false">取消</el-button><el-button type="primary" native-type="submit" form="user-editor" :loading="loading">保存账号</el-button></template>
    </el-dialog>
  </main>
</template>
