<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { api, ApiError } from './api'
import NavIcon from './NavIcon.vue'
import './catalog.css'

interface Source { id: number; name: string; sourceType: string; departmentId: number | null; departmentName: string | null; enabled: boolean; version: number }
interface Dictionary { id: number; code: string; name: string; kind: string; enabled: boolean; version: number }
const props = defineProps<{ administrator: boolean }>()
const emit = defineEmits<{ expired: [] }>()
const sources = ref<Source[]>([])
const dictionaries = ref<Dictionary[]>([])
const departments = ref<{ id: number; name: string; enabled: boolean }[]>([])
const sourceTypes = [{ value: 'DEPARTMENT', label: '部门上报' }, { value: 'PETITION', label: '信访来源' }, { value: 'EXTERNAL', label: '外部系统' }, { value: 'OTHER', label: '其他来源' }]
const tab = ref('sources')
const filters = reactive({ keyword: '', type: '', status: '' })
const applied = reactive({ ...filters })
function resetFilters() {
  Object.assign(filters, { keyword: '', type: '', status: '' })
  Object.assign(applied, filters)
}
function sourceTypeName(type: string) { return sourceTypes.find(item => item.value === type)?.label || type }
const filteredSources = computed(() => sources.value.filter(source =>
  [source.name, source.departmentName || '全部门可用'].some(value => value.toLocaleLowerCase().includes(applied.keyword.trim().toLocaleLowerCase()))
  && (!applied.type || source.sourceType === applied.type)
  && (!applied.status || String(source.enabled) === applied.status),
))
const filteredDictionaries = computed(() => dictionaries.value.filter(dictionary =>
  [dictionary.name, dictionary.code].some(value => value.toLocaleLowerCase().includes(applied.keyword.trim().toLocaleLowerCase()))
  && (!applied.type || dictionary.kind === applied.type)
  && (!applied.status || String(dictionary.enabled) === applied.status),
))
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
  <main class="content catalog-workspace">
    <div class="page-heading">
      <div><p class="eyebrow">数据配置 / 统一标准</p><h1>来源与分类</h1><p class="muted">{{ administrator ? '规范数据来源与分类口径，让每条信息有据可循。' : '查看本账号可用的数据来源与标准分类。' }}</p></div>
      <el-button :loading="loading" @click="run(load)">刷新配置</el-button>
    </div>
    <el-alert v-if="error && !sourceOpen && !dictionaryOpen" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" :title="notice" type="success" :closable="false" />
    <section class="catalog-summary" aria-label="来源与分类概况">
      <div><span class="catalog-summary-icon"><NavIcon name="catalog" /></span><div><span>{{ administrator ? '数据来源' : '可用来源' }}</span><strong>{{ sources.length }}<small>个来源</small></strong></div></div>
      <div><span class="catalog-summary-icon"><NavIcon name="events" /></span><div><span>事件类别</span><strong>{{ dictionaries.filter(item => item.kind === 'EVENT_CATEGORY').length }}<small>{{ administrator ? '项配置' : '项可用分类' }}</small></strong></div></div>
      <div><span class="catalog-summary-icon"><NavIcon name="risk" /></span><div><span>纠纷类型</span><strong>{{ dictionaries.filter(item => item.kind === 'DISPUTE_TYPE').length }}<small>{{ administrator ? '项配置' : '项可用分类' }}</small></strong></div></div>
    </section>
    <section class="management-card catalog-card" v-loading="loading" :aria-busy="loading">
      <el-tabs v-model="tab" @tab-change="resetFilters">
        <el-tab-pane v-for="pane in [{ name: 'sources', label: '数据来源' }, { name: 'dictionaries', label: '标准分类' }]" :key="pane.name" :name="pane.name" :label="pane.label">
          <template v-if="tab === pane.name">
            <form class="catalog-filters" @submit.prevent="Object.assign(applied, filters)">
              <div class="catalog-filter-fields">
                <label for="catalog-keyword">{{ tab === 'sources' ? '来源 / 可用部门' : '分类名称 / 编码' }}<el-input id="catalog-keyword" v-model="filters.keyword" :aria-label="tab === 'sources' ? '查找来源' : '查找分类'" :placeholder="tab === 'sources' ? '输入来源名称或部门关键词' : '输入分类名称或编码关键词'" clearable /></label>
                <label for="catalog-type">{{ tab === 'sources' ? '来源类型' : '字典类别' }}<el-select id="catalog-type" v-model="filters.type" aria-label="筛选配置类型" placeholder="全部类型" clearable><template v-if="tab === 'sources'"><el-option v-for="item in sourceTypes" :key="item.value" :value="item.value" :label="item.label" /></template><template v-else><el-option label="事件类别" value="EVENT_CATEGORY" /><el-option label="纠纷类型" value="DISPUTE_TYPE" /></template></el-select></label>
                <label v-if="administrator" for="catalog-status">启用状态<el-select id="catalog-status" v-model="filters.status" aria-label="筛选启用状态" placeholder="全部状态" clearable><el-option label="启用" value="true" /><el-option label="停用" value="false" /></el-select></label>
              </div>
              <div class="catalog-filter-actions"><el-button type="primary" native-type="submit">查询</el-button><el-button @click="resetFilters">重置</el-button><span class="muted">{{ administrator ? '配置停用后保留历史解释，不再用于新录入。' : '只读浏览 · 仅展示本账号可用的启用配置。' }}</span></div>
            </form>
            <div class="catalog-results-heading">
              <div><h2>{{ tab === 'sources' ? '来源列表' : '分类列表' }}<span>{{ tab === 'sources' ? filteredSources.length : filteredDictionaries.length }}</span></h2><p class="muted">{{ tab === 'sources' ? '按来源类型和可用部门维护接入范围' : '事件类别与纠纷类型分别维护，分类编码保持稳定' }}</p></div>
              <el-button v-if="administrator" type="primary" :disabled="loading" @click="tab === 'sources' ? editSource() : editDictionary()">{{ tab === 'sources' ? '新增来源' : '新增标准分类' }}</el-button>
              <el-tag v-else type="info" effect="plain">只读</el-tag>
            </div>
            <el-table v-if="tab === 'sources'" :data="filteredSources" row-key="id">
              <el-table-column prop="name" label="来源名称" min-width="210" show-overflow-tooltip />
              <el-table-column label="来源类型" min-width="140"><template #default="{ row }"><el-tag type="info" effect="plain">{{ sourceTypeName(row.sourceType) }}</el-tag></template></el-table-column>
              <el-table-column label="可用部门" min-width="190" show-overflow-tooltip><template #default="{ row }">{{ row.departmentName || '全部门可用' }}</template></el-table-column>
              <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
              <el-table-column v-if="administrator" label="操作" width="80" fixed="right"><template #default="{ row }"><el-button link type="primary" :disabled="loading" :aria-label="`编辑来源${row.name}`" @click="editSource(row)">编辑</el-button></template></el-table-column>
              <template #empty><el-empty :description="sources.length ? '没有符合条件的来源，请调整查询条件' : '暂无可用来源'" :image-size="64" /></template>
            </el-table>
            <el-table v-else :data="filteredDictionaries" row-key="id">
              <el-table-column label="字典类别" min-width="140"><template #default="{ row }"><el-tag type="info" effect="plain">{{ row.kind === 'EVENT_CATEGORY' ? '事件类别' : '纠纷类型' }}</el-tag></template></el-table-column>
              <el-table-column prop="code" label="分类编码" min-width="190" show-overflow-tooltip><template #default="{ row }"><span class="catalog-code">{{ row.code }}</span></template></el-table-column>
              <el-table-column prop="name" label="分类名称" min-width="210" show-overflow-tooltip />
              <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
              <el-table-column v-if="administrator" label="操作" width="80" fixed="right"><template #default="{ row }"><el-button link type="primary" :disabled="loading" :aria-label="`编辑分类${row.code}`" @click="editDictionary(row)">编辑</el-button></template></el-table-column>
              <template #empty><el-empty :description="dictionaries.length ? '没有符合条件的分类，请调整查询条件' : '暂无可用分类'" :image-size="64" /></template>
            </el-table>
            <div class="catalog-table-note">共 {{ tab === 'sources' ? sources.length + ' 个来源' : dictionaries.length + ' 项分类' }} · {{ administrator ? '包含启用与停用配置' : '按本账号可用范围展示' }}</div>
          </template>
        </el-tab-pane>
      </el-tabs>
    </section>
    <p class="data-note">{{ administrator ? '配置变更即时生效。历史事件保留原始来源与分类文本，停用配置不会删除历史记录。' : '如需新增或调整配置，请联系管理员。' }}</p>
    <el-dialog v-model="sourceOpen" class="catalog-dialog" :title="sourceForm.id ? '编辑来源' : '新增来源'" width="min(580px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!loading" :show-close="!loading">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form id="catalog-source-form" label-position="top" :disabled="loading" @submit.prevent="saveSource">
        <section class="catalog-form-section"><h3>来源信息</h3>
          <el-form-item label="来源名称" required><el-input v-model="sourceForm.name" aria-label="来源名称" placeholder="请输入来源名称" maxlength="100" show-word-limit /></el-form-item>
          <el-form-item label="来源类型" required><el-select v-model="sourceForm.sourceType" aria-label="来源类型"><el-option v-for="item in sourceTypes" :key="item.value" :value="item.value" :label="item.label" /></el-select></el-form-item>
        </section>
        <section class="catalog-form-section"><h3>使用范围与状态</h3>
          <el-form-item label="可用部门"><el-select v-model="sourceForm.departmentId" aria-label="来源可用部门" :disabled="!!sourceForm.id" filterable><el-option label="全部门可用" :value="null" /><el-option v-for="department in departments" :key="department.id" :value="department.id" :label="department.name" :disabled="!department.enabled" /></el-select><p class="catalog-form-help">{{ sourceForm.id ? '可用部门创建后不可修改，避免改变已有引用范围。' : '选择全部门可用，或指定一个启用部门。' }}</p></el-form-item>
          <div class="catalog-status-setting"><div><strong>启用来源</strong><p>停用后不再用于新录入，历史来源文本仍保留。</p></div><el-switch v-model="sourceForm.enabled" aria-label="来源启用" /></div>
        </section>
      </el-form>
      <template #footer><el-button :disabled="loading" @click="sourceOpen = false">取消</el-button><el-button form="catalog-source-form" native-type="submit" type="primary" :loading="loading">保存来源</el-button></template>
    </el-dialog>
    <el-dialog v-model="dictionaryOpen" class="catalog-dialog" :title="dictionaryForm.id ? '编辑标准分类' : '新增标准分类'" width="min(580px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!loading" :show-close="!loading">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form id="catalog-dictionary-form" label-position="top" :disabled="loading" @submit.prevent="saveDictionary">
        <section class="catalog-form-section"><h3>分类信息</h3>
          <el-form-item label="字典类别" required><el-select v-model="dictionaryForm.kind" aria-label="字典类别" :disabled="!!dictionaryForm.id"><el-option label="事件类别" value="EVENT_CATEGORY" /><el-option label="纠纷类型" value="DISPUTE_TYPE" /></el-select></el-form-item>
          <el-form-item label="分类编码" required><el-input v-model="dictionaryForm.code" aria-label="分类编码" placeholder="请输入稳定的分类编码" maxlength="50" :disabled="!!dictionaryForm.id" /><p class="catalog-form-help">分类编码与字典类别创建后不可修改；同一字典类别内，编码与名称须分别保持唯一。</p></el-form-item>
          <el-form-item label="分类名称" required><el-input v-model="dictionaryForm.name" aria-label="分类名称" placeholder="请输入分类名称" maxlength="100" show-word-limit /></el-form-item>
        </section>
        <section class="catalog-form-section"><h3>使用状态</h3><div class="catalog-status-setting"><div><strong>启用分类</strong><p>停用后不再用于新录入，已有事件分类不受影响。</p></div><el-switch v-model="dictionaryForm.enabled" aria-label="分类启用" /></div></section>
      </el-form>
      <template #footer><el-button :disabled="loading" @click="dictionaryOpen = false">取消</el-button><el-button form="catalog-dictionary-form" native-type="submit" type="primary" :loading="loading">保存标准分类</el-button></template>
    </el-dialog>
  </main>
</template>
