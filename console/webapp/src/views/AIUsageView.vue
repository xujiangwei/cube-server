<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fetchAIUsage, usageExportUrl, type AIUsageResponse, type UsageGroup, type UsageParams } from '@/api/ai-usage'
import { useAuthStore } from '@/stores/auth'
import { useChart } from '@/composables/useChart'
import type { EChartsCoreOption } from '@/charts/echarts'

const route = useRoute(), router = useRouter(), auth = useAuthStore()
const textQuery = (key: string) => typeof route.query[key] === 'string' ? route.query[key] as string : ''
const domain = ref(textQuery('domain') || (auth.isSuperAdmin ? '' : auth.user?.group ?? ''))
const contact = ref(textQuery('contact')), model = ref(textQuery('model')), module = ref(textQuery('module')), unit = ref(textQuery('unit'))
const source = ref(''), preset = ref('month'), grain = ref('day'), group = ref('contact')
const dayText = (time: number) => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(time)
const today = dayText(Date.now()), fromDay = ref(today.slice(0, 8) + '01'), toDay = ref(today)
const billMonth = ref(textQuery('month') || today.slice(0, 7))
const isBill = computed(() => route.query.bill === '1')
const data = ref<AIUsageResponse | null>(null), error = ref(''), loading = ref(false), offset = ref(0), autoRefresh = ref(false)
const pageSize = 50
let generation = 0
const format = (v: number | null | undefined) => v == null ? '—' : v.toLocaleString('zh-CN')
const time = (v: number | null | undefined) => v == null ? '—' : new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'medium', hour12: false }).format(v)
const quality = { actual: '实际', estimated: '估算', pending: '执行中 / 待核对', unknown: '未知 / 待核对' }
const totals = computed(() => data.value?.summary)
const cards = computed(() => [
  { label: '实际输入 Token', value: totals.value?.input_tokens }, { label: '实际输出 Token', value: totals.value?.output_tokens },
  { label: '实际总 Token', value: totals.value?.total_tokens }, { label: '模型调用次数', value: totals.value?.calls }
])
const ranks = computed(() => [...(data.value?.breakdown ?? [])].sort((a, b) => b.total_tokens - a.total_tokens || b.calls - a.calls))
const invalid = computed(() => !!contact.value && (!/^[1-9]\d*$/.test(contact.value) || !Number.isSafeInteger(Number(contact.value))))
function range() {
  let from = Date.parse(`${fromDay.value}T00:00:00+08:00`), to = Date.parse(`${toDay.value}T00:00:00+08:00`) + 86400000
  if (preset.value !== 'custom') {
    const start = Date.parse(`${dayText(Date.now())}T00:00:00+08:00`)
    from = preset.value === 'today' ? start : preset.value === 'week' ? start - 6 * 86400000 : Date.parse(`${dayText(Date.now()).slice(0, 8)}01T00:00:00+08:00`)
    to = Date.now() + 1
  }
  return { from, to }
}
function params(): UsageParams {
  return { ...range(), domain: domain.value || undefined, contact: contact.value || undefined, model: model.value || undefined, module: module.value || undefined,
    unit: unit.value || undefined, source: source.value || undefined, grain: grain.value, group: group.value, offset: offset.value, limit: pageSize,
    month: isBill.value ? billMonth.value : undefined }
}
async function load() {
  const mine = ++generation
  if (invalid.value) { error.value = '请输入有效的 Contact ID'; loading.value = false; return }
  const q = params()
  if (!Number.isFinite(q.from) || !Number.isFinite(q.to) || Number(q.to) <= Number(q.from) || Number(q.to) - Number(q.from) > 366 * 86400000) { error.value = '请选择不超过 366 天的有效时间范围'; loading.value = false; return }
  if (isBill.value && (!domain.value || !contact.value || !/^\d{4}-\d{2}$/.test(billMonth.value))) { error.value = 'Contact 账单需要域、Contact ID 和账期'; loading.value = false; return }
  loading.value = true; error.value = ''
  try { const result = await fetchAIUsage(isBill.value ? 'bill' : 'calls', q); if (mine === generation) data.value = result }
  catch (e) { if (mine === generation) error.value = e instanceof Error ? e.message : '查询失败' }
  finally { if (mine === generation) loading.value = false }
}
function refresh() { offset.value = 0; data.value = null; void load() }
function openBill(row?: UsageGroup) {
  const d = row?.domain ?? domain.value, c = row?.contact_id?.toString() ?? contact.value
  if (!d || !c) { error.value = '请先选择域和 Contact'; return }
  void router.push({ path: '/ai-usage', query: { bill: '1', domain: d, contact: c, month: billMonth.value } })
}
watch(() => route.query, () => { domain.value = textQuery('domain') || (auth.isSuperAdmin ? '' : auth.user?.group ?? ''); contact.value = textQuery('contact'); model.value = textQuery('model'); module.value = textQuery('module'); unit.value = textQuery('unit'); billMonth.value = textQuery('month') || today.slice(0, 7); refresh() }, { immediate: true })
watch([domain, contact, model, module, unit, source, preset, grain, group, fromDay, toDay, billMonth], () => { ++generation; loading.value = false; data.value = null; offset.value = 0 }, { flush: 'sync' })
const timer = window.setInterval(() => { if (autoRefresh.value && !loading.value) void load() }, 30000)
onBeforeUnmount(() => { ++generation; window.clearInterval(timer) })
const chartEl = ref<HTMLElement | null>(null)
const option = computed<EChartsCoreOption>(() => {
  const rows = data.value?.trend ?? []
  return { color: ['#6366f1', '#06b6d4'], tooltip: { trigger: 'axis', renderMode: 'richText' }, legend: { bottom: 0 }, grid: { left: 64, right: 20, top: 28, bottom: 50 },
    xAxis: { type: 'category', data: rows.map(r => time(r.timestamp)), axisLabel: { fontSize: 10 } }, yAxis: { type: 'value', name: '实际 Token', splitLine: { lineStyle: { color: '#f1f5f9' } } },
    series: [{ name: '输入 Token', type: 'bar', stack: 'tokens', data: rows.map(r => r.input_tokens) }, { name: '输出 Token', type: 'bar', stack: 'tokens', data: rows.map(r => r.output_tokens) }] }
})
useChart(chartEl, option)
const sourceText = (s: string) => ({ ready: '可查询', duplicate: '共享库已去重', unavailable: '不可用或尚未升级', not_configured: '尚未配置', invalid_configuration: '配置无效' }[s] ?? s)
const rankLabel = (r: UsageGroup) => group.value === 'contact' ? `${r.contact_name || 'Contact'} · ${r.contact_id} · ${r.domain}` : group.value === 'model' ? r.model : group.value === 'module' ? r.module : r.unit_id || '未获单元身份'
const exportUrl = computed(() => usageExportUrl(data.value?.filter ?? params()))
</script>

<template>
  <div class="space-y-5">
    <PageHeader :title="isBill ? 'Contact 用量账单' : 'AI 用量'" description="按 REST 访问者 Contact 归账 · Asia/Shanghai · Token 用量" />
    <div class="card space-y-4 p-5">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div class="flex flex-wrap gap-2">
          <RouterLink v-if="isBill" to="/ai-usage" class="btn btn-secondary">返回 AI 用量</RouterLink>
          <select v-if="!isBill" v-model="preset" aria-label="时间范围" class="input"><option value="today">今日</option><option value="week">近 7 天</option><option value="month">本月</option><option value="custom">自定义</option></select>
          <template v-if="!isBill && preset === 'custom'"><input v-model="fromDay" aria-label="开始日期" type="date" class="input" /><input v-model="toDay" aria-label="结束日期" type="date" class="input" /></template>
          <label v-if="isBill" class="flex items-center gap-2 text-sm">账期 <input v-model="billMonth" aria-label="账期" type="month" class="input" /></label>
        </div>
        <div class="flex flex-wrap items-center gap-3 text-xs"><label class="flex items-center gap-1"><input v-model="autoRefresh" type="checkbox" />每 30 秒刷新</label><button class="btn btn-primary" :disabled="loading" @click="refresh">{{ loading ? '查询中…' : '查询 / 刷新' }}</button><a v-if="data?.summary" :href="exportUrl" class="btn btn-secondary">导出 CSV</a></div>
      </div>
      <div class="grid gap-3 sm:grid-cols-2 xl:grid-cols-6">
        <label class="text-xs text-slate-500">域<input v-model.trim="domain" :disabled="!auth.isSuperAdmin" aria-label="域" class="input mt-1 w-full" placeholder="全部域" /></label>
        <label class="text-xs text-slate-500">访问者 Contact<input v-model.trim="contact" aria-label="Contact ID" class="input mt-1 w-full" placeholder="全部访问者" /></label>
        <label class="text-xs text-slate-500">模型<input v-model.trim="model" aria-label="模型" class="input mt-1 w-full" placeholder="全部模型" /></label>
        <label class="text-xs text-slate-500">业务模块<input v-model.trim="module" aria-label="业务模块" class="input mt-1 w-full" placeholder="AIGC / Harness" /></label>
        <label class="text-xs text-slate-500">执行单元<input v-model.trim="unit" aria-label="执行单元" class="input mt-1 w-full" placeholder="全部执行单元" /></label>
        <label class="text-xs text-slate-500">数据源<input v-model.trim="source" aria-label="数据源" class="input mt-1 w-full" placeholder="全部数据源" /></label>
      </div>
      <div class="flex flex-wrap items-center gap-3 text-xs text-slate-500"><button v-if="!isBill" class="text-indigo-600 hover:underline" @click="openBill()">查看此 Contact 的月度账单 →</button><span v-if="data">查询时间 {{ time(data.generatedAt) }}</span><span>仅统计启用后的调用，历史累计值不回填账单。</span></div>
    </div>
    <p v-if="error" role="alert" class="rounded-xl bg-rose-50 p-4 text-sm text-rose-700">查询失败：{{ error }}</p>
    <div v-if="data && !data.complete" role="status" class="rounded-xl bg-amber-50 p-4 text-sm text-amber-800">数据不完整：查询范围包含开始采集前的时间，或部分数据源不可用、未配置、尚未升级。当前结果仅包含已采集且可查询的数据。</div>
    <div v-if="isBill && data?.summary" class="card p-5"><p class="text-sm font-semibold text-slate-800">{{ data.contactName || 'Contact' }} · {{ domain }} / {{ contact }} · {{ billMonth }}</p><p class="mt-2 text-xs text-slate-500">用量账单持续接收迟到结果和修订，不封账。{{ totals?.pending_calls || totals?.unknown_calls ? '存在待核对调用，实际总量尚不完整。' : '当前已记录调用均已完成用量分类。' }}</p></div>
    <div class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4"><div v-for="card in cards" :key="card.label" class="card p-5"><p class="text-xs text-slate-500">{{ card.label }}</p><p class="mt-3 text-2xl font-semibold tabular-nums text-slate-900">{{ format(card.value) }}</p></div></div>
    <div v-if="totals" class="flex flex-wrap gap-4 rounded-xl bg-slate-50 px-5 py-3 text-xs text-slate-600"><span>实际用量调用 {{ format(totals.actual_calls) }}</span><span>估算 Token {{ format(totals.estimated_input + totals.estimated_output) }}（{{ format(totals.estimated_calls) }} 次，不计入实际账单）</span><span>未知 {{ format(totals.unknown_calls) }} 次</span><span>执行中 / 待核对 {{ format(totals.pending_calls) }} 次</span></div>
    <EmptyState v-if="data && !data.summary" title="尚未获得 AI 计量数据" description="请检查数据源和采集起点，查询开始采集后的时间范围。" icon="activity" />
    <EmptyState v-else-if="totals?.calls === 0" title="所选范围暂无模型调用" description="计量数据源已就绪，可调整时间或筛选条件。" icon="activity" />
    <section class="card p-5"><div class="flex items-center justify-between gap-3"><h2 class="text-sm font-semibold text-slate-800">输入 / 输出 Token 趋势</h2><select v-model="grain" aria-label="趋势粒度" class="input w-auto text-xs"><option value="hour">按小时</option><option value="day">按天</option></select></div><div ref="chartEl" class="mt-3 h-72 w-full" /></section>
    <section class="card p-5"><div class="flex items-center justify-between"><h2 class="text-sm font-semibold text-slate-800">用量排行</h2><select v-model="group" aria-label="排行维度" class="input w-auto text-xs"><option value="contact">按 Contact</option><option value="model">按模型</option><option value="module">按业务模块</option><option value="unit">按执行单元</option></select></div><div class="mt-4 overflow-x-auto"><table class="w-full text-left text-xs"><thead><tr class="border-b text-slate-400"><th class="py-3">{{ group === 'contact' ? '结算 Contact' : '维度' }}</th><th>调用次数</th><th>实际输入</th><th>实际输出</th><th>实际总 Token</th><th>待核对 / 未知</th></tr></thead><tbody><tr v-for="(row, i) in ranks" :key="i" class="border-b border-slate-50 text-slate-600"><td class="max-w-80 break-words py-3 pr-3"><button v-if="group === 'contact'" class="text-indigo-600 hover:underline" @click="openBill(row)">{{ rankLabel(row) }}</button><span v-else>{{ rankLabel(row) }}</span></td><td>{{ format(row.calls) }}</td><td>{{ format(row.input_tokens) }}</td><td>{{ format(row.output_tokens) }}</td><td class="font-medium">{{ format(row.total_tokens) }}</td><td>{{ format(row.pending_calls + row.unknown_calls) }}</td></tr></tbody></table></div></section>
    <section v-if="isBill && data" class="grid gap-4 lg:grid-cols-2"><div v-for="[label, rows] in [['模型汇总', data.models], ['业务模块汇总', data.modules]] as const" :key="label" class="card p-5"><h2 class="text-sm font-semibold text-slate-800">{{ label }}</h2><div v-for="(r, i) in rows" :key="i" class="mt-3 flex justify-between gap-3 text-xs text-slate-600"><span>{{ r.model || r.module }}</span><span>{{ format(r.total_tokens) }} Token · {{ format(r.calls) }} 次</span></div></div></section>
    <section class="card p-5"><div class="flex flex-wrap items-center justify-between gap-3"><h2 class="text-sm font-semibold text-slate-800">模型调用明细</h2><span class="text-xs text-slate-400">共 {{ format(data?.summary ? data.total : null) }} 次 · 每页 {{ pageSize }} 条</span></div><div class="mt-4 overflow-x-auto"><table class="w-full min-w-[1000px] text-left text-xs"><thead><tr class="border-b text-slate-400"><th class="py-3">调用时间</th><th>访问者</th><th>模型 / 模块</th><th>执行单元</th><th>用量口径</th><th>输入 / 输出</th><th>结果</th><th>关联标识</th></tr></thead><tbody><tr v-for="row in data?.calls ?? []" :key="`${row.source}-${row.meter_id}`" class="border-b border-slate-50 text-slate-600"><td class="whitespace-nowrap py-3 pr-3">{{ time(row.started_at) }}</td><td class="pr-3">{{ row.domain }}<br />{{ row.contact_id }}</td><td class="pr-3">{{ row.model }}<br /><span class="text-slate-400">{{ row.module }}</span></td><td class="max-w-40 break-all pr-3">{{ row.unit_id || '未获单元身份' }}</td><td class="pr-3" :class="row.quality === 'actual' ? 'text-emerald-600' : 'text-amber-600'">{{ quality[row.quality] }}</td><td class="whitespace-nowrap pr-3">{{ format(row.input_tokens) }} / {{ format(row.output_tokens) }}</td><td class="pr-3">{{ row.status }}</td><td class="max-w-48 break-all text-[10px] text-slate-400" :title="row.meter_id">{{ row.run_id || row.request_id || row.meter_id }}<br />{{ row.source }} · 修订 {{ row.revision }}</td></tr></tbody></table></div><div class="mt-4 flex justify-end gap-3"><button class="btn btn-secondary" :disabled="offset === 0 || loading" @click="offset = Math.max(0, offset - pageSize); load()">上一页</button><button class="btn btn-secondary" :disabled="!data || offset + pageSize >= data.total || loading" @click="offset += pageSize; load()">下一页</button></div></section>
    <section v-if="data" class="card p-5"><h2 class="text-sm font-semibold text-slate-800">计量数据源</h2><div v-for="(s, i) in data.sources" :key="i" class="mt-3 flex flex-wrap justify-between gap-2 text-xs text-slate-500"><span>{{ s.name || 'AI 用量数据源' }} · {{ sourceText(s.state) }}</span><span v-if="s.collectedAt">开始采集 {{ time(s.collectedAt) }} · 最近记账 {{ time(s.updatedAt) }}</span></div></section>
  </div>
</template>
