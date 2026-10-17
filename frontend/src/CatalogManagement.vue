<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api, ApiError } from './api'

interface Source { id: number; name: string; sourceType: string; departmentId: number | null; departmentName: string | null; enabled: boolean; version: number }
interface Dictionary { id: number; code: string; name: string; kind: string; enabled: boolean; version: number }
const props = defineProps<{ administrator: boolean }>()
const emit = defineEmits<{ expired: [] }>()
const sources = ref<Source[]>([])
const dictionaries = ref<Dictionary[]>([])
const departments = ref<{ id: number; name: string; enabled: boolean }[]>([])
const sourceTypes = [{ value: 'DEPARTMENT', label: '部门上报' }, { value: 'PETITION', label: '信访来源' }, { value: 'EXTERNAL', label: '外部系统' }, { value: 'OTHER', label: '其他来源' }]
const tab = ref('sources')
const loading = ref(false)
const error = ref('')
const notice = ref('')
const sourceOpen = ref(false)
const dictionaryOpen = ref(false)
const sourceForm = reactive({ id: 0, name: '', sourceType: 'DEPARTMENT', departmentId: null as number | null, enabled: true, version: 0 })
const dictionaryForm = reactive({ id: 0, code: '', name: '', kind: 'EVENT_CATEGORY', enabled: true, version: 0 })
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
  const [nextSources, nextDictionaries, nextDepartments] = await Promise.all([
    api<Source[]>(props.administrator ? '/admin/sources' : '/options/sources'),
    props.administrator ? api<Dictionary[]>('/admin/dictionaries') : Promise.all([api<Dictionary[]>('/options/dictionaries?kind=EVENT_CATEGORY'), api<Dictionary[]>('/options/dictionaries?kind=DISPUTE_TYPE')]).then(values => values.flat()),
    props.administrator ? api<{ id: number; name: string; enabled: boolean }[]>('/admin/departments') : Promise.resolve([]),
  ])
  if (!active) return
  sources.value = nextSources; dictionaries.value = nextDictionaries; departments.value = nextDepartments
}
function editSource(value?: Source) {
  Object.assign(sourceForm, value || { id: 0, name: '', sourceType: 'DEPARTMENT', departmentId: null, enabled: true, version: 0 })
  error.value = ''; sourceOpen.value = true
}
function editDictionary(value?: Dictionary) {
  Object.assign(dictionaryForm, value || { id: 0, name: '', code: '', kind: 'EVENT_CATEGORY', enabled: true, version: 0 })
  error.value = ''; dictionaryOpen.value = true
}
async function saveSource() {
  await run(async () => {
    await api(`/admin/sources${sourceForm.id ? `/${sourceForm.id}` : ''}`, { method: sourceForm.id ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(sourceForm) })
    if (!active) return
    sourceOpen.value = false; await load(); notice.value = '来源已保存'
  })
}
async function saveDictionary() {
  await run(async () => {
    await api(`/admin/dictionaries${dictionaryForm.id ? `/${dictionaryForm.id}` : ''}`, { method: dictionaryForm.id ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(dictionaryForm) })
    if (!active) return
    dictionaryOpen.value = false; await load(); notice.value = '标准分类已保存'
  })
}
onMounted(() => { void run(load) })
</script>

<template>
  <main class="content">
    <div class="page-heading"><div><p class="eyebrow">数据配置</p><h1>来源与分类</h1><p class="muted">{{ administrator ? '维护数据来源及统一分类，停用后保留历史解释。' : '查看本账号可用的数据来源和标准分类。' }}</p></div><el-button :loading="loading" @click="run(load)">刷新配置</el-button></div>
    <el-alert v-if="error && !sourceOpen && !dictionaryOpen" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" :title="notice" type="success" :closable="false" />
    <section class="management-card" v-loading="loading">
      <el-tabs v-model="tab">
        <el-tab-pane label="数据来源" name="sources">
          <div class="management-toolbar"><span class="muted">{{ administrator ? '停用来源不再用于新录入，原始来源文本保留' : '仅显示启用且属于可用部门范围的来源' }}</span><el-button v-if="administrator" type="primary" @click="editSource()">新增来源</el-button></div>
          <el-table :data="sources" stripe empty-text="暂无可用来源">
            <el-table-column prop="name" label="来源名称" min-width="160" />
            <el-table-column label="来源类型" min-width="120"><template #default="{ row }">{{ sourceTypes.find(item => item.value === row.sourceType)?.label }}</template></el-table-column>
            <el-table-column label="可用部门" min-width="150"><template #default="{ row }">{{ row.departmentName || '全部门可用' }}</template></el-table-column>
            <el-table-column label="状态" width="85"><template #default="{ row }">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column>
            <el-table-column v-if="administrator" label="操作" width="90"><template #default="{ row }"><el-button link type="primary" :aria-label="`编辑来源${row.name}`" @click="editSource(row)">编辑</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="标准分类" name="dictionaries">
          <div class="management-toolbar"><span class="muted">事件类别与纠纷类型分别维护，分类编码保持稳定</span><el-button v-if="administrator" type="primary" @click="editDictionary()">新增标准分类</el-button></div>
          <el-table :data="dictionaries" stripe empty-text="暂无可用分类">
            <el-table-column label="字典类别" min-width="130"><template #default="{ row }">{{ row.kind === 'EVENT_CATEGORY' ? '事件类别' : '纠纷类型' }}</template></el-table-column>
            <el-table-column prop="code" label="分类编码" min-width="160" />
            <el-table-column prop="name" label="分类名称" min-width="160" />
            <el-table-column label="状态" width="85"><template #default="{ row }">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column>
            <el-table-column v-if="administrator" label="操作" width="90"><template #default="{ row }"><el-button link type="primary" :aria-label="`编辑分类${row.code}`" @click="editDictionary(row)">编辑</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </section>
    <el-dialog v-model="sourceOpen" :title="sourceForm.id ? '编辑来源' : '新增来源'" width="min(550px, 95vw)" :close-on-click-modal="false">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="saveSource">
        <el-form-item label="来源名称"><el-input v-model="sourceForm.name" aria-label="来源名称" maxlength="100" /></el-form-item>
        <el-form-item label="来源类型"><el-select v-model="sourceForm.sourceType" aria-label="来源类型"><el-option v-for="item in sourceTypes" :key="item.value" :value="item.value" :label="item.label" /></el-select></el-form-item>
        <el-form-item label="可用部门"><el-select v-model="sourceForm.departmentId" aria-label="来源可用部门" :disabled="!!sourceForm.id"><el-option label="全部门可用" :value="null" /><el-option v-for="department in departments" :key="department.id" :value="department.id" :label="department.name" :disabled="!department.enabled" /></el-select></el-form-item>
        <el-form-item label="启用状态"><el-switch v-model="sourceForm.enabled" aria-label="来源启用" /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="loading">保存来源</el-button>
      </el-form>
    </el-dialog>
    <el-dialog v-model="dictionaryOpen" :title="dictionaryForm.id ? '编辑标准分类' : '新增标准分类'" width="min(550px, 95vw)" :close-on-click-modal="false">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="saveDictionary">
        <el-form-item label="字典类别"><el-select v-model="dictionaryForm.kind" aria-label="字典类别" :disabled="!!dictionaryForm.id"><el-option label="事件类别" value="EVENT_CATEGORY" /><el-option label="纠纷类型" value="DISPUTE_TYPE" /></el-select></el-form-item>
        <el-form-item label="分类编码"><el-input v-model="dictionaryForm.code" aria-label="分类编码" maxlength="50" :disabled="!!dictionaryForm.id" /></el-form-item>
        <el-form-item label="分类名称"><el-input v-model="dictionaryForm.name" aria-label="分类名称" maxlength="100" /></el-form-item>
        <el-form-item label="启用状态"><el-switch v-model="dictionaryForm.enabled" aria-label="分类启用" /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="loading">保存标准分类</el-button>
      </el-form>
    </el-dialog>
  </main>
</template>
