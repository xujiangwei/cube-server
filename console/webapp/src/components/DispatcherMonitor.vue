<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
import EmptyState from './EmptyState.vue'
import DispatcherTopology from './DispatcherTopology.vue'
import { queryPerformanceReport } from '@/api/console'
import { dispatcherSnapshot, metric, milliseconds } from '@/api/dispatcher-monitor'
import { useChart } from '@/composables/useChart'
import { usePolling } from '@/composables/usePolling'
import type { DispatcherServer, PerformanceReportResponse } from '@/api/types'
import type { EChartsCoreOption } from '@/charts/echarts'
import { formatFullTime, formatTimeHHMM } from '@/utils/format'

const props = defineProps<{ servers: DispatcherServer[] }>()
const selectedName = ref('')
const autoRefresh = ref(true)
const response = ref<PerformanceReportResponse | null>(null)
const receivedAt = ref(0)
const clock = ref(performance.now())
const loading = ref(false)
const error = ref('')
const moduleFilter = ref('')
const moduleSort = ref('requests')
const sortBy = ref('requests')
const flowEl = ref<HTMLElement | null>(null)
const latencyEl = ref<HTMLElement | null>(null)
let generation = 0
const selected = computed(() => props.servers.find(item => item.name === selectedName.value))
const snapshot = computed(() => dispatcherSnapshot(response.value?.report))
const http = computed(() => snapshot.value?.http)
const age = computed(() => response.value?.reportAgeMs == null ? null : response.value.reportAgeMs + Math.max(0, clock.value - receivedAt.value))
const fresh = computed(() => !!selected.value?.running && !!snapshot.value && !error.value && age.value != null && age.value < 30_000)
const freshness = computed(() => !response.value?.report ? '未采集' : !selected.value?.running ? '节点已停止'
  : error.value ? '刷新失败' : !snapshot.value ? '未提供 REST 数据' : age.value == null ? '新鲜度未知' : fresh.value ? '数据正常' : '数据已过期')
const cards = computed(() => [
  { label: '近一分钟请求', value: metric(http.value?.window.requests), suffix: '次', detail: `运行累计 ${metric(http.value?.total.requests)} 次`, tone: 'text-indigo-600' },
  { label: '请求速率', value: metric(http.value?.window.rps, 2), suffix: 'req/s', detail: '按请求到达次数计算', tone: 'text-slate-800' },
  { label: '平均耗时', value: metric(http.value?.window.avgMs, 1), suffix: 'ms', detail: '已完成的普通 HTTP 请求', tone: 'text-slate-800' },
  { label: 'P95 耗时', value: metric(http.value?.window.p95Ms, 1), suffix: 'ms', detail: '普通请求 · 直方图估算', tone: 'text-slate-800' },
  { label: '失败率', value: metric(http.value?.window.failureRate, 2), suffix: '%', detail: `失败 ${metric(http.value?.window.failures)} 次 / 完成 ${metric(http.value?.window.completed)} 次`, tone: 'text-rose-500' },
  { label: '当前并发', value: metric(http.value?.inFlight), suffix: '个', detail: '包含尚未结束的流式连接', tone: 'text-slate-800' }
])
const modules = computed(() => [...(http.value?.modules ?? [])].sort((a, b) => {
  const key = moduleSort.value as 'requests' | 'avgMs' | 'p95Ms'
  return (b.window[key] ?? -1) - (a.window[key] ?? -1) || a.module.localeCompare(b.module)
}))
const routes = computed(() => [...(http.value?.routes ?? [])].filter(row => !moduleFilter.value || row.module === moduleFilter.value)
  .sort((a, b) => {
    const key = sortBy.value as 'requests' | 'avgMs' | 'p95Ms' | 'failures'
    return (b.window[key] ?? -1) - (a.window[key] ?? -1) || a.route.localeCompare(b.route)
  }))
const statuses = computed(() => Object.entries(http.value?.window.statusCodes ?? {}))

function trend(latency: boolean): EChartsCoreOption {
  const samples = http.value?.series ?? []
  return {
    color: latency ? ['#6366f1', '#06b6d4'] : ['#6366f1', '#f43f5e'],
    tooltip: { trigger: 'axis', renderMode: 'richText' }, legend: { bottom: 0, icon: 'roundRect', itemHeight: 3, textStyle: { color: '#64748b', fontSize: 10 } },
    grid: { left: 48, right: 40, top: 24, bottom: 42 },
    xAxis: { type: 'category', boundaryGap: false, data: samples.map(item => formatTimeHHMM(item.timestamp)),
      axisLine: { lineStyle: { color: '#e2e8f0' } }, axisTick: { show: false }, axisLabel: { color: '#94a3b8', fontSize: 10 } },
    yAxis: latency ? { type: 'value', name: 'ms', axisLabel: { color: '#94a3b8' }, splitLine: { lineStyle: { color: '#f1f5f9' } } }
      : [{ type: 'value', name: 'req/s', axisLabel: { color: '#94a3b8' }, splitLine: { lineStyle: { color: '#f1f5f9' } } },
        { type: 'value', name: '失败次数', minInterval: 1, axisLabel: { color: '#94a3b8' }, splitLine: { show: false } }],
    series: latency ? [
      { name: '平均耗时', type: 'line', symbol: 'none', connectNulls: false, data: samples.map(item => item.avgMs), lineStyle: { width: 2 } },
      { name: 'P95（估算）', type: 'line', symbol: 'none', connectNulls: false, data: samples.map(item => item.p95Ms), lineStyle: { width: 1.5, type: 'dashed' } }
    ] : [
      { name: '请求速率', type: 'line', symbol: 'none', data: samples.map(item => item.rps), areaStyle: { opacity: .08 }, lineStyle: { width: 2 } },
      { name: '失败次数', type: 'bar', yAxisIndex: 1, barMaxWidth: 8, data: samples.map(item => item.failures) }
    ]
  }
}
const flowOption = computed(() => trend(false))
const latencyOption = computed(() => trend(true))
useChart(flowEl, flowOption)
useChart(latencyEl, latencyOption)

async function refresh() {
  const server = selected.value
  const current = ++generation
  if (!server) return
  loading.value = true
  try {
    const result = await queryPerformanceReport(server.name, 0, true)
    if (current !== generation || server.name !== selectedName.value) return
    response.value = result
    receivedAt.value = performance.now()
    clock.value = receivedAt.value
    error.value = ''
  } catch (failure) {
    if (current !== generation) return
    error.value = failure instanceof Error ? failure.message : '性能报告刷新失败'
  } finally {
    if (current === generation) loading.value = false
  }
}
watch(() => props.servers, servers => {
  if (servers.some(item => item.name === selectedName.value)) return
  selectedName.value = (servers.find(item => item.running) ?? servers[0])?.name ?? ''
}, { immediate: true })
watch(selectedName, () => {
  ++generation; response.value = null; error.value = ''; moduleFilter.value = ''; loading.value = false
  void refresh()
}, { immediate: true })
watch(autoRefresh, enabled => { if (enabled) void refresh() })
usePolling(() => autoRefresh.value ? refresh() : undefined, 10_000, { immediate: false })
usePolling(() => { clock.value = performance.now() }, 1000, { immediate: false, pauseOnHidden: false })
onBeforeUnmount(() => { ++generation })
</script>

<template>
  <section class="card" data-testid="dispatcher-monitor">
    <div class="card-header flex-wrap gap-4">
      <div>
        <h2 class="card-title">REST 访问监控</h2>
        <p class="card-subtitle">HTTP 请求性能、服务模块与运行拓扑<span v-if="response?.report"> · 采集于 {{ formatFullTime(response.report.timestamp) }}</span></p>
      </div>
      <div class="flex max-w-full flex-wrap items-center gap-3">
        <span class="rounded-full px-2.5 py-1 text-[11px]" :class="fresh ? 'bg-emerald-50 text-emerald-600' : 'bg-amber-50 text-amber-600'">{{ freshness }}</span>
        <select v-model="selectedName" aria-label="选择监控调度机" class="input w-60 max-w-full text-xs"><option v-for="server in servers" :key="server.name" :value="server.name">{{ server.name }}</option></select>
        <label class="flex items-center gap-1.5 whitespace-nowrap text-xs text-slate-500"><input v-model="autoRefresh" type="checkbox" class="accent-indigo-600">自动刷新</label>
        <button class="btn-secondary btn-sm" type="button" :disabled="loading || !selected" @click="refresh"><AppIcon name="refresh" :size="14" :class="{ 'animate-spin': loading }" />刷新</button>
      </div>
    </div>
    <EmptyState v-if="!selected" title="暂无调度机" description="添加部署后可查看 REST 访问指标与服务连接拓扑。" />
    <div v-else class="space-y-5 p-5">
      <p v-if="error" role="alert" class="rounded-lg bg-rose-50 px-4 py-3 text-xs text-rose-600">刷新失败：{{ error }}<span v-if="snapshot">；以下保留最后一次成功快照。</span></p>
      <EmptyState v-if="!snapshot" :title="loading ? '正在读取性能报告' : response?.report ? '当前节点尚未提供 REST 监控数据' : '暂无性能报告'"
        :description="response?.report ? '升级 dispatcher 后将自动采集 HTTP 请求数据；下方仍可查看配置拓扑。' : '等待调度机上报性能快照后显示访问统计。'" />
      <template v-else-if="http">
        <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-6">
          <div v-for="card in cards" :key="card.label" class="min-w-0 rounded-xl border border-slate-200 px-4 py-4">
            <p class="text-xs text-slate-500">{{ card.label }}</p>
            <p class="tabular mb-2 mt-3 whitespace-nowrap text-2xl font-semibold tracking-tight" :class="card.tone">{{ card.value }}<span class="ml-1.5 text-[11px] font-normal text-slate-400">{{ card.suffix }}</span></p>
            <p class="text-[10px] leading-4 text-slate-400">{{ card.detail }}</p>
          </div>
        </div>
        <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px] text-slate-400">
          <span>统计窗口 {{ formatTimeHHMM(http.windowStartedAt) }}–{{ formatTimeHHMM(http.windowEndedAt) }} · {{ http.windowSeconds }} 秒</span>
          <span v-for="[code, count] in statuses" :key="code">{{ code }} <b class="tabular font-medium text-slate-600">{{ metric(count) }}</b></span>
          <span>传输失败 {{ metric(http.window.transportFailures) }}</span>
        </div>
        <p v-if="http.windowSeconds === 0" class="text-xs text-slate-400">正在采集首个 10 秒统计窗口；当前并发与运行累计值实时记录。</p>
        <p v-else-if="http.window.requests === 0" class="text-xs text-slate-400">最近一分钟无请求。无耗时样本时显示“--”。</p>
        <div class="grid gap-4 lg:grid-cols-2">
          <div class="min-w-0 rounded-xl border border-slate-200 p-4"><h3 class="text-xs font-semibold text-slate-700">访问流量与失败趋势 <span class="ml-1 font-normal text-slate-400">最近 30 分钟</span></h3><div ref="flowEl" class="h-56" /></div>
          <div class="min-w-0 rounded-xl border border-slate-200 p-4"><h3 class="text-xs font-semibold text-slate-700">普通请求响应耗时 <span class="ml-1 font-normal text-slate-400">平均 / P95</span></h3><div ref="latencyEl" class="h-56" /></div>
        </div>
      </template>

      <DispatcherTopology :server="selected" :snapshot="snapshot" :fresh="fresh" />

      <template v-if="http">
        <section class="rounded-xl border border-slate-200">
          <div class="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 px-5 py-4"><h3 class="text-sm font-semibold text-slate-800">服务模块访问分布</h3><div class="flex flex-wrap items-center gap-3"><span class="text-[11px] text-slate-400">最近一分钟 · 点击模块过滤接口</span><select v-model="moduleSort" aria-label="模块排行排序" class="input w-36 text-xs"><option value="requests">按请求量排序</option><option value="avgMs">按平均耗时排序</option><option value="p95Ms">按 P95 排序</option></select></div></div>
          <p v-if="!modules.length" class="p-8 text-center text-xs text-slate-400">等待首个 REST 请求</p>
          <div v-else class="overflow-x-auto">
            <table class="w-full whitespace-nowrap text-left text-xs"><thead class="bg-slate-50 text-[11px] text-slate-400"><tr><th class="px-5 py-3 font-medium">模块</th><th class="px-4 py-3 font-medium">请求量 / 占比</th><th class="px-4 py-3 font-medium">平均耗时</th><th class="px-4 py-3 font-medium">P95（估算）</th><th class="px-4 py-3 font-medium">失败</th><th class="px-4 py-3 font-medium">并发</th><th class="px-5 py-3 font-medium">运行累计</th></tr></thead>
              <tbody class="divide-y divide-slate-100"><tr v-for="row in modules" :key="row.module" :class="moduleFilter === row.module ? 'bg-indigo-50/50' : ''">
                <td class="px-5 py-3.5"><button class="font-medium text-indigo-600" type="button" @click="moduleFilter = moduleFilter === row.module ? '' : row.module">{{ row.module }}</button></td>
                <td class="px-4 py-3.5"><div class="flex items-center gap-3"><span class="tabular min-w-8">{{ metric(row.window.requests) }}</span><span class="h-1.5 w-20 overflow-hidden rounded bg-slate-100"><span class="block h-full rounded bg-indigo-400" :style="{ width: `${http.window.requests ? row.window.requests / http.window.requests * 100 : 0}%` }" /></span></div></td>
                <td class="tabular px-4 py-3.5">{{ milliseconds(row.window.avgMs) }}</td><td class="tabular px-4 py-3.5">{{ milliseconds(row.window.p95Ms) }}</td><td class="tabular px-4 py-3.5" :class="row.window.failures ? 'text-rose-500' : 'text-slate-400'">{{ metric(row.window.failures) }}</td><td class="tabular px-4 py-3.5">{{ metric(row.inFlight) }}</td><td class="tabular px-5 py-3.5">{{ metric(row.totalRequests) }}</td>
              </tr></tbody>
            </table>
          </div>
        </section>
        <section class="rounded-xl border border-slate-200">
          <div class="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-5 py-4">
            <h3 class="text-sm font-semibold text-slate-800">接口访问明细</h3>
            <div class="flex flex-wrap gap-2"><select v-model="moduleFilter" aria-label="按服务模块过滤接口" class="input w-36 text-xs"><option value="">全部模块</option><option v-for="row in modules" :key="row.module" :value="row.module">{{ row.module }}</option></select><select v-model="sortBy" aria-label="接口排序" class="input w-36 text-xs"><option value="requests">按请求量排序</option><option value="avgMs">按平均耗时排序</option><option value="p95Ms">按 P95 排序</option><option value="failures">按失败次数排序</option></select></div>
          </div>
          <p v-if="!routes.length" class="p-8 text-center text-xs text-slate-400">暂无接口访问数据</p>
          <div v-else class="max-h-96 overflow-auto">
            <table class="w-full whitespace-nowrap text-left text-xs"><thead class="sticky top-0 bg-slate-50 text-[11px] text-slate-400"><tr><th class="px-5 py-3 font-medium">接口 / 模块</th><th class="px-4 py-3 font-medium">请求 / 失败</th><th class="px-4 py-3 font-medium">普通平均 / P95</th><th class="px-4 py-3 font-medium">流式首包 / 总时长</th><th class="px-5 py-3 font-medium">并发 / 累计</th></tr></thead>
              <tbody class="divide-y divide-slate-100"><tr v-for="row in routes" :key="`${row.module}:${row.method}:${row.route}`">
                <td class="px-5 py-3.5"><p class="font-mono text-[11px] text-slate-700"><span class="mr-2 font-semibold text-indigo-500">{{ row.method }}</span>{{ row.route }}</p><p class="mt-1 text-[10px] text-slate-400">{{ row.module }}</p></td>
                <td class="tabular px-4 py-3.5">{{ metric(row.window.requests) }} / <span :class="row.window.failures ? 'text-rose-500' : 'text-slate-400'">{{ metric(row.window.failures) }}</span></td>
                <td class="tabular px-4 py-3.5">{{ milliseconds(row.window.avgMs) }} / {{ milliseconds(row.window.p95Ms) }}</td>
                <td class="tabular px-4 py-3.5">{{ milliseconds(row.window.firstByteAvgMs) }} / {{ milliseconds(row.window.streamAvgMs) }}<p class="mt-1 text-[10px] text-slate-400">{{ row.window.streamCount }} 个流式请求已结束</p></td>
                <td class="tabular px-5 py-3.5">{{ metric(row.inFlight) }} / {{ metric(row.totalRequests) }}</td>
              </tr></tbody>
            </table>
          </div>
          <p class="border-t border-slate-100 px-5 py-3 text-[10px] leading-5 text-slate-400">失败包含 HTTP 4xx、5xx 及传输失败，HTTP 200 不推断业务执行成功。普通耗时不包含 SSE；首包表示首个响应正文数据，不等同于首个 Token。</p>
        </section>
      </template>
    </div>
  </section>
</template>
