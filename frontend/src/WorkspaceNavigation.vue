<script setup lang="ts">
import { computed } from 'vue'
import type { Account } from './api'
import NavIcon from './NavIcon.vue'

const props = defineProps<{ account: Account; section: string }>()
const emit = defineEmits<{ select: [section: string] }>()
const items = computed(() => {
  const roles = props.account.roles
  const readEvents = roles.some(role => ['CENTER_ANALYST', 'DEPARTMENT_REPORTER'].includes(role))
  const admin = roles.includes('CENTER_ADMIN')
  return [
    { id: 'events', title: '事件列表', visible: true },
    { id: 'risk', title: '归集与风险', visible: roles.includes('CENTER_ANALYST') },
    { id: 'intake', title: '录入与身份处理', visible: readEvents },
    { id: 'accounts', title: '部门与账号', visible: admin },
    { id: 'catalog', title: '来源与分类', visible: true },
    { id: 'tools', title: '规则与批量接入', visible: readEvents || admin },
    { id: 'archive', title: '修正与归档审核', visible: readEvents },
    { id: 'cases', title: '预警与处置', visible: roles.some(role => ['CENTER_ANALYST', 'DEPARTMENT_HANDLER'].includes(role)) },
    { id: 'insights', title: '业务概况与追溯', visible: readEvents || roles.includes('DEPARTMENT_HANDLER') },
  ].filter(item => item.visible)
})
</script>

<template>
  <div class="workspace-navigation">
    <div class="brand"><span class="brand-mark">治</span><span>社会治理<small>分析预警工作台</small></span></div>
    <p class="nav-label">业务工作 <span>WORKSPACE</span></p>
    <nav aria-label="业务导航">
      <button v-for="item in items" :key="item.id" class="nav-item" :class="{ 'nav-active': section === item.id }" :aria-current="section === item.id ? 'page' : undefined" @click="emit('select', item.id)">
        <NavIcon :name="item.id" /><span>{{ item.title }}</span><span v-if="section === item.id" class="nav-indicator" aria-hidden="true"></span>
      </button>
    </nav>
    <div class="sidebar-foot"><span class="demo-indicator"></span> 教学演示环境<p>虚构样例数据 · 仅用于功能验证</p></div>
  </div>
</template>
