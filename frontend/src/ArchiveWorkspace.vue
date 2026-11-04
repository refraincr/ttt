<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, ApiError, type Account } from './api'
import './archive.css'
const props=defineProps<{account:Account}>()
const emit=defineEmits<{expired:[]}>()
const analyst=computed(()=>props.account.roles.includes('CENTER_ANALYST'))
const error=ref(''), message=ref(''), busy=ref(false)
const requests=ref<any[]>([]), people=ref<any[]>([]), history=ref<any[]>([])
const recordId=ref<number>(), record=ref<any>(null), selected=ref<any>(null), targetId=ref<number>()
const reason=ref(''), reviewReason=ref(''), query=ref(''), kind=ref('CORRECT')
const content=ref({personName:'',idCard:'',phone:'',address:'',eventTime:'',description:''})
function decoded(value:any){if(typeof value!=='string')return value||{};try{return JSON.parse(value)}catch{return {}}}
const fields=[['姓名','party_name','personName'],['身份证','party_id_card','idCard'],['手机号','party_phone','phone'],['联系地址','party_address','address'],['发生时间','occurred_at','eventTime'],['问题描述','problem_description','description']]
const profile=ref({name:'',idCard:'',phone:''})
const pendingCount=computed(()=>requests.value.filter(r=>r.state==='PENDING').length)
const states: Record<string,string>={PENDING:'待审核',APPROVED:'已批准',REJECTED:'已驳回'}
const kinds: Record<string,string>={CORRECT:'来源修正',DELETE:'来源删除',MERGE:'档案合并',SPLIT:'来源拆分',PROFILE:'档案修正'}
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';message.value='';try{await action()}catch(e){error.value=e instanceof Error?e.message:'操作失败';if(e instanceof ApiError&&e.status===401)emit('expired')}finally{busy.value=false}}
async function refresh(){requests.value=await api<any[]>('/archive/requests');if(analyst.value){people.value=await api<any[]>('/archive/people?q='+encodeURIComponent(query.value));history.value=await api<any[]>('/archive/history')}}
function write(path:string,body:unknown,method='POST'){return api(path,{method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})}
function choose(p:any){selected.value=p;profile.value={name:p.name,idCard:p.id_card||'',phone:p.phone}}
async function loadRecord(){await run(async()=>{record.value=null;const r=await api<any>('/intake/records/'+recordId.value);record.value=r;content.value={personName:r.party_name||'',idCard:r.party_id_card||'',phone:r.party_phone||'',address:r.party_address||'',eventTime:r.occurred_at,description:r.problem_description}})}
async function submit(){await run(async()=>{await write('/archive/records/'+record.value.id+'/requests',{version:record.value.version,kind:kind.value,payload:content.value,reason:reason.value});await refresh();message.value='申请已提交，审核前继续使用旧版本'})}
async function review(id:number,approve:boolean){await run(async()=>{await write('/archive/requests/'+id+'/review',{approve,reason:reviewReason.value});await refresh();message.value='审核完成；批准的修正已重新处理'})}
async function saveProfile(){await run(async()=>{const p=await write('/archive/people/'+selected.value.id,{...profile.value,version:selected.value.version,reason:reason.value},'PUT');await refresh();choose(p);message.value='档案已修正'})}
async function move(split:boolean){await run(async()=>{const target=people.value.find(p=>p.id===targetId.value);if(!target)throw new Error('请选择目标档案');await write(split?'/archive/records/'+record.value.id+'/split':'/archive/people/'+selected.value.id+'/merge',{version:split?record.value.version:selected.value.version,targetPersonId:target.id,targetVersion:target.version,reason:reason.value});record.value=null;selected.value=null;await refresh();message.value='归档变更已保存，迁入迁出档案风险已重算'})}
onMounted(()=>run(refresh))
</script>
<template>
  <main class="content archive-workspace" :aria-busy="busy">
    <div class="page-heading">
      <div><p class="eyebrow">数据治理 · 变更留痕</p><h1>修正与归档审核</h1><p class="muted">核实来源变更与档案关系，让每一次修正都有据可查。</p></div>
      <span class="archive-role"><span></span>{{ analyst ? '中心研判 · 审核与维护' : '部门上报 · 申请与跟踪' }}</span>
    </div>
    <div class="archive-guide" aria-label="变更处理流程">
      <div><b>01</b><p>核对来源<small>按编号读取当前记录</small></p></div>
      <div><b>02</b><p>申请变更<small>说明修正内容与依据</small></p></div>
      <div><b>03</b><p>审核留痕<small>对照版本，保留历史</small></p></div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="message" :title="message" type="success" :closable="false" show-icon />
    <p v-if="busy" class="archive-loading" role="status">正在处理，请稍候…</p>
    <el-tabs class="archive-tabs">
      <el-tab-pane label="来源修正">
        <section class="event-card">
          <div class="archive-card-heading"><div><h2>来源记录修正 / 删除申请</h2><p>先核对当前版本，再填写变更内容与核实理由。</p></div><el-tag effect="plain" type="info">审核后生效</el-tag></div>
          <form class="archive-toolbar source-loader" @submit.prevent="loadRecord">
            <label for="archive-record-id">来源记录编号</label><el-input-number id="archive-record-id" v-model="recordId" :min="1" :disabled="busy" aria-label="来源记录编号" placeholder="来源编号"/><el-button type="primary" native-type="submit" :disabled="busy||!recordId">加载记录</el-button>
          </form>
          <el-form v-if="record" class="detail-panel record-form" label-position="top" :disabled="busy">
            <div class="archive-record-summary"><div><p class="eyebrow">当前来源</p><h3>记录 #{{record.id}} · {{record.party_name || '未填写姓名'}}</h3><p class="muted">原始上报保持可追溯，审核前继续使用旧版本。</p></div><el-tag type="info" effect="plain">版本 {{record.version}}</el-tag></div>
            <el-form-item class="full-width archive-kind" label="申请类型"><el-select v-model="kind" aria-label="申请类型"><el-option label="修正" value="CORRECT"/><el-option label="删除" value="DELETE"/></el-select></el-form-item>
            <template v-if="kind==='CORRECT'">
              <h3 class="archive-section-title full-width">修正内容 <small>请填写核实后的信息</small></h3>
              <el-form-item label="姓名"><el-input v-model="content.personName"/></el-form-item><el-form-item label="身份证号"><el-input v-model="content.idCard"/></el-form-item><el-form-item label="手机号"><el-input v-model="content.phone"/></el-form-item><el-form-item label="联系地址"><el-input v-model="content.address" maxlength="500" show-word-limit/></el-form-item><el-form-item label="发生时间"><el-input v-model="content.eventTime" placeholder="2026-10-01T10:00:00"/></el-form-item><el-form-item class="full-width" label="问题描述"><el-input v-model="content.description" type="textarea" :rows="4"/></el-form-item>
            </template>
            <el-alert v-else class="full-width" title="删除申请获批后，该来源记录将退出有效归集，相关风险会重新计算。请核实后提交。" type="warning" :closable="false" show-icon/>
            <el-form-item class="full-width" label="变更理由" required><el-input v-model="reason" type="textarea" :rows="3" placeholder="请说明核实依据及变更原因"/></el-form-item><div class="form-actions"><span class="muted">提交后可在「申请审核」跟踪进度</span><el-button type="primary" :disabled="busy" @click="submit">提交变更申请</el-button></div>
          </el-form>
          <el-empty v-else description="输入来源记录编号，加载后填写修正或删除申请" :image-size="88"/>
        </section>
      </el-tab-pane>
      <el-tab-pane label="申请审核">
        <section class="event-card">
          <div class="archive-card-heading"><div><h2>变更申请与审核记录 <span class="archive-count">{{ requests.length }} 条</span></h2><p>{{ analyst ? '展开申请，对照变更前后内容，填写理由后完成审核。' : '查看本部门变更申请及审核结果。' }}</p></div><el-tag :type="pendingCount ? 'warning' : 'info'" effect="plain">{{ pendingCount }} 条待审核</el-tag></div>
          <div class="archive-toolbar review-toolbar"><label v-if="analyst" for="archive-review-reason">审核理由 <em>*</em></label><el-input v-if="analyst" id="archive-review-reason" v-model="reviewReason" :disabled="busy" aria-label="审核理由" placeholder="必填：说明核实依据与审核结论"/><el-button :disabled="busy" @click="run(refresh)">刷新</el-button></div>
          <el-table :data="requests" empty-text="暂无变更申请" row-key="id">
            <el-table-column type="expand"><template #default="{row}"><div class="request-detail"><div class="archive-request-meta"><span>申请人 <strong>{{row.requester}}</strong></span><span>申请时间 {{row.created_at}}</span><span>审核人 <strong>{{row.reviewer||'待审'}}</strong></span><span v-if="row.reviewed_at">审核时间 {{row.reviewed_at}}</span></div><div class="archive-comparison-scroll" tabindex="0" aria-label="变更前后对照，可横向滚动"><table class="archive-comparison"><caption>来源 #{{row.record_id}} · 变更前后对照</caption><thead><tr><th scope="col">字段</th><th scope="col">变更前</th><th scope="col">申请变更后</th></tr></thead><tbody><tr v-for="f in fields" :key="f[0]"><th scope="row">{{f[0]}}</th><td>{{decoded(row.before_data)[f[1]] || '—'}}</td><td>{{row.kind==='DELETE'?'申请删除该来源记录':decoded(row.payload)[f[2]] || '—'}}</td></tr></tbody></table></div><p class="archive-reason"><strong>申请理由</strong>{{ row.reason }}</p><p v-if="row.review_reason" class="archive-reason"><strong>审核理由</strong>{{ row.review_reason }}</p></div></template></el-table-column>
            <el-table-column prop="id" label="申请" width="85"/><el-table-column prop="record_id" label="来源记录" width="110"/>
            <el-table-column prop="kind" label="类型" width="110"><template #default="{row}">{{ kinds[row.kind] || row.kind }}</template></el-table-column>
            <el-table-column prop="state" label="状态" min-width="200"><template #default="{row}"><el-tag :type="row.state==='PENDING'?'warning':row.state==='APPROVED'?'success':'info'" effect="light">{{ states[row.state] || row.state }} · {{row.state}}</el-tag></template></el-table-column>
            <el-table-column prop="reason" label="申请理由" min-width="190" show-overflow-tooltip/><el-table-column prop="review_reason" label="审核理由" min-width="190" show-overflow-tooltip/>
            <el-table-column v-if="analyst" label="审核" width="150"><template #default="{row}"><template v-if="row.state==='PENDING'"><el-button link type="primary" :disabled="busy" @click="review(row.id,true)">批准</el-button><el-button link type="danger" :disabled="busy" @click="review(row.id,false)">驳回</el-button></template><span v-else class="muted">已完成</span></template></el-table-column>
          </el-table><p class="archive-footnote">点击行首展开按钮查看完整内容与变更前后对照。</p>
        </section>
      </el-tab-pane>
      <el-tab-pane v-if="analyst" label="档案修正与合并拆分">
        <section class="event-card">
          <div class="archive-card-heading"><div><h2>人员档案修正、合并与来源拆分</h2><p>检索并选择档案，核实身份信息或调整来源归属。</p></div><el-tag type="info" effect="plain">{{ people.length }} 份档案</el-tag></div>
          <form class="archive-toolbar" @submit.prevent="run(refresh)"><el-input v-model="query" :disabled="busy" aria-label="搜索档案" placeholder="姓名、电话或身份证" clearable/><el-button type="primary" native-type="submit" :disabled="busy">查询档案</el-button></form>
          <el-table class="people-table" :data="people" highlight-current-row :current-row-key="selected?.id" row-key="id" empty-text="暂无匹配档案，请调整查询条件" @row-click="choose"><el-table-column prop="id" label="编号" width="90"/><el-table-column prop="name" label="姓名" min-width="150" show-overflow-tooltip/><el-table-column prop="id_card" label="身份证" min-width="190"/><el-table-column prop="phone" label="电话" min-width="140"/><el-table-column prop="version" label="版本" width="80"/><el-table-column label="操作" width="110"><template #default="{row}"><el-button link type="primary" :disabled="busy" @click.stop="choose(row)">选择档案</el-button></template></el-table-column></el-table>
          <p class="archive-footnote">点击档案行或「选择档案」，在下方修正基本信息或选择合并来源。</p>
          <section v-if="selected" class="detail-panel"><h3 class="archive-section-title">当前档案 #{{selected.id}} · 基本信息修正 <el-tag type="info" size="small">版本 {{ selected.version }}</el-tag></h3><el-form class="profile-form" label-position="top" :disabled="busy"><el-form-item label="姓名"><el-input v-model="profile.name"/></el-form-item><el-form-item label="身份证"><el-input v-model="profile.idCard"/></el-form-item><el-form-item label="电话"><el-input v-model="profile.phone"/></el-form-item><el-form-item class="full-width" label="核实理由" required><el-input v-model="reason" type="textarea" :rows="3" placeholder="请填写可靠身份依据及修正理由"/></el-form-item><div class="form-actions"><span class="muted">保存将保留本次修正的核实依据</span><el-button type="primary" :disabled="busy" @click="saveProfile">保存基本信息</el-button></div></el-form></section>
          <section class="detail-panel archive-move-panel"><h3 class="archive-section-title">档案合并与来源拆分</h3><p class="help-note">合并需姓名一致且身份证无冲突；请核实身份后操作。拆分将加载的单条来源归入目标档案，并重新检测重复。</p><div class="archive-selection"><span>当前合并档案</span><strong>{{ selected ? `#${selected.id} · ${selected.name}` : '请先在上方选择档案' }}</strong></div><form class="archive-toolbar" @submit.prevent="loadRecord"><el-input-number v-model="recordId" :min="1" :disabled="busy" aria-label="待拆分来源编号" placeholder="待拆分来源编号"/><el-button :disabled="busy||!recordId" native-type="submit">加载拆分来源</el-button></form><p v-if="record" class="help-note archive-loaded-source">已加载来源 #{{record.id}} · {{record.party_name}}：{{record.problem_description}}</p><el-form class="move-form" label-position="top" :disabled="busy"><el-form-item label="目标档案" required><el-select v-model="targetId" aria-label="目标档案" placeholder="选择目标档案" filterable><el-option v-for="p in people" :key="p.id" :label="`${p.id} ${p.name} ${p.phone}`" :value="p.id"/></el-select></el-form-item><el-form-item label="归档变更理由" required><el-input v-model="reason" aria-label="归档变更理由" type="textarea" :rows="3" placeholder="必填：可靠身份依据及变更理由"/></el-form-item><div class="form-actions"><el-button type="primary" plain :disabled="busy||!selected" @click="move(false)">合并当前档案到目标</el-button><el-button :disabled="busy||!record" @click="move(true)">拆分已加载来源到目标</el-button></div></el-form></section>
        </section>
      </el-tab-pane>
      <el-tab-pane v-if="analyst" label="变更历史"><section class="event-card"><div class="archive-card-heading"><div><h2>归档变更历史 <span class="archive-count">{{history.length}} 条</span></h2><p>保留档案迁入迁出、来源归属与每次操作的核实依据。</p></div><el-button :disabled="busy" @click="run(refresh)">刷新历史</el-button></div><el-table :data="history" empty-text="暂无归档变更历史"><el-table-column prop="kind" label="类型" width="120"><template #default="{row}">{{ kinds[row.kind] || row.kind }}</template></el-table-column><el-table-column prop="from_person_id" label="原档案" width="100"/><el-table-column prop="to_person_id" label="目标档案" width="100"/><el-table-column prop="record_id" label="来源记录" width="110"/><el-table-column prop="reason" label="理由" min-width="250" show-overflow-tooltip/><el-table-column prop="actor" label="操作者" min-width="120"/><el-table-column prop="created_at" label="时间" min-width="190"/></el-table></section></el-tab-pane>
    </el-tabs>
    <p class="archive-page-note">变更申请获批后重新处理来源与风险；档案合并、拆分保留原始上报及历史记录。</p>
  </main>
</template>
