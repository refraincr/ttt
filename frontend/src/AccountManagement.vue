<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api, ApiError } from './api'

interface Department { id: number; name: string; departmentType: string; enabled: boolean; version: number }
interface User { id: number; username: string; displayName: string; departmentId: number; departmentName: string; enabled: boolean; roles: string[]; version: number }
const emit = defineEmits<{ expired: []; changed: [] }>()
const departments = ref<Department[]>([])
const users = ref<User[]>([])
const roles = ref<{ code: string; name: string }[]>([])
const tab = ref('users')
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
  <main class="content">
    <div class="page-heading"><div><p class="eyebrow">系统管理</p><h1>部门与账号</h1><p class="muted">分别授予管理、研判、上报、处置和特殊上报权限，支持角色兼任。</p></div></div>
    <el-alert v-if="error && !departmentOpen && !userOpen" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" :title="notice" type="success" :closable="false" />
    <section class="management-card" v-loading="loading">
      <el-tabs v-model="tab">
        <el-tab-pane label="账号管理" name="users">
          <div class="management-toolbar"><span class="muted">账号变更后，原登录会话将失效</span><el-button type="primary" @click="editUser()">新增账号</el-button></div>
          <el-table :data="users" stripe>
            <el-table-column prop="username" label="账号" min-width="140" />
            <el-table-column prop="displayName" label="姓名" min-width="120" />
            <el-table-column prop="departmentName" label="归属部门" min-width="140" />
            <el-table-column label="角色" min-width="230"><template #default="{ row }"><el-tag v-for="role in row.roles" :key="role" class="role-tag">{{ roles.find(item => item.code === role)?.name || role }}</el-tag></template></el-table-column>
            <el-table-column label="状态" width="85"><template #default="{ row }">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column>
            <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="primary" :aria-label="`编辑账号${row.username}`" @click="editUser(row)">编辑</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="部门管理" name="departments">
          <div class="management-toolbar"><span class="muted">停用部门会使该部门账号失效，历史记录保留</span><el-button type="primary" @click="editDepartment()">新增部门</el-button></div>
          <el-table :data="departments" stripe>
            <el-table-column prop="name" label="部门名称" min-width="180" />
            <el-table-column label="单位类型"><template #default="{ row }">{{ row.departmentType === 'CENTER' ? '综治中心' : '上报及处置部门' }}</template></el-table-column>
            <el-table-column label="状态" width="85"><template #default="{ row }">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column>
            <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="primary" :aria-label="`编辑部门${row.name}`" @click="editDepartment(row)">编辑</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </section>
    <el-dialog v-model="departmentOpen" :title="departmentForm.id ? '编辑部门' : '新增部门'" width="min(520px, 95vw)" :close-on-click-modal="false">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="saveDepartment">
        <el-form-item label="部门名称"><el-input v-model="departmentForm.name" aria-label="部门名称" maxlength="100" /></el-form-item>
        <el-form-item label="单位类型"><el-select v-model="departmentForm.departmentType" aria-label="单位类型" :disabled="!!departmentForm.id"><el-option label="上报及处置部门" value="DEPARTMENT" /><el-option label="综治中心" value="CENTER" /></el-select></el-form-item>
        <el-form-item label="启用状态"><el-switch v-model="departmentForm.enabled" aria-label="部门启用" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading">保存部门</el-button>
      </el-form>
    </el-dialog>
    <el-dialog v-model="userOpen" :title="userForm.id ? '编辑账号' : '新增账号'" width="min(600px, 95vw)" :close-on-click-modal="false">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="saveUser">
        <el-form-item label="登录账号"><el-input v-model="userForm.username" aria-label="登录账号" :disabled="!!userForm.id" maxlength="50" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="userForm.displayName" aria-label="姓名" maxlength="100" /></el-form-item>
        <el-form-item label="归属部门"><el-select v-model="userForm.departmentId" aria-label="归属部门" filterable><el-option v-for="department in departments" :key="department.id" :value="department.id" :label="department.name + (department.enabled ? '' : '（停用）')" :disabled="!department.enabled && userForm.departmentId !== department.id" /></el-select></el-form-item>
        <el-form-item label="角色与权限"><el-checkbox-group v-model="userForm.roles"><el-checkbox v-for="role in roles" :key="role.code" :value="role.code">{{ role.name }}</el-checkbox></el-checkbox-group></el-form-item>
        <p class="muted">中心管理与研判可兼任；部门上报与处置可兼任。特殊上报须同时具备上报权限，并归属具体部门。</p>
        <el-form-item :label="userForm.id ? '重置密码（留空则保持原密码）' : '初始密码' "><el-input v-model="userForm.password" aria-label="账号密码" type="password" autocomplete="new-password" show-password /></el-form-item>
        <el-form-item label="启用状态"><el-switch v-model="userForm.enabled" aria-label="账号启用" /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading">保存账号</el-button>
      </el-form>
    </el-dialog>
  </main>
</template>
