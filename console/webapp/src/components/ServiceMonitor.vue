<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
import AIUsageSummary from './AIUsageSummary.vue'
import EmptyState from './EmptyState.vue'
import ServiceTopology from './ServiceTopology.vue'
import { queryPerformanceReport } from '@/api/console'
import { serviceSnapshot, failureCount } from '@/api/service-monitor'
import { metric, milliseconds } from '@/api/dispatcher-monitor'
import { useChart } from '@/composables/useChart'
import { usePolling } from '@/composables/usePolling'
import { formatFullTime, formatTimeHHMM } from '@/utils/format'
import type { PerformanceReportResponse, ServiceServer } from '@/api/types'
import type { EChartsCoreOption } from '@/charts/echarts'
const props = defineProps<{ servers: ServiceServer[] }>()
const selectedName = ref(''), autoRefresh = ref(true), loading = ref(false), error = ref('')
const response = ref<PerformanceReportResponse | null>(null), receivedAt = ref(0), clock = ref(performance.now())
const trendSource = ref('commands'), moduleSort = ref('requests')
const moduleFilter = ref(''), sortBy = ref('requests'), includeControl = ref(false)
let generation = 0
const selected = computed(() => props.servers.find(s => s.name === selectedName.value))
const snapshot = computed(() => serviceSnapshot(response.value?.report))
const execution = computed(() => snapshot.value?.execution)
const age = computed(() => response.value?.reportAgeMs == null ? null : response.value.reportAgeMs + Math.max(0, clock.value - receivedAt.value))
const fresh = computed(() => !!selected.value?.running && !!snapshot.value && !error.value && age.value != null && age.value < 30000)
const freshness = computed(() => !response.value?.report ? '未采集' : !selected.value?.running ? '节点已停止' : error.value ? '刷新失败' : !snapshot.value ? '未提供执行数据' : age.value == null ? '新鲜度未知' : fresh.value ? '数据正常' : '数据已过期')
const cards = computed(() => {
  const e = execution.value, w = e?.window
  return [
    { label: '近一分钟业务命令', value: metric(w?.requests), unit: '次', detail: `运行累计 ${metric(e?.total.requests)} 次` },
    { label: '命令速率', value: metric(w?.rps, 2), unit: '次/s', detail: '按进入业务执行队列次数计算' },
    { label: '普通命令平均 / P95', value: metric(w?.avgMs, 1) + ' / ' + metric(w?.p95Ms, 1), unit: 'ms', detail: '含排队 · P95 为直方图估算' },
    { label: '排队 / 执行平均', value: metric(w?.queueAvgMs, 1) + ' / ' + metric(w?.executionAvgMs, 1), unit: 'ms', detail: '按已结束的命令观察样本计算' },
    { label: '已知结果失败率', value: metric(w?.failureRate, 2), unit: '%', detail: `失败 ${metric(w ? failureCount(w) : null)} · 拒绝 ${metric(w?.rejected)}` },
    { label: '当前运行 / 排队', value: metric(e?.running) + ' / ' + metric(e?.queued), unit: '个', detail: '异步动作等待终态时保持运行' }
  ]
})
const modules = computed(() => [...(execution.value?.modules ?? [])].sort((a, b) => (b.window[moduleSort.value as 'requests' | 'avgMs' | 'p95Ms'] ?? -1) - (a.window[moduleSort.value as 'requests' | 'avgMs' | 'p95Ms'] ?? -1) || a.module.localeCompare(b.module)))
const actions = computed(() => [...(execution.value?.actions ?? [])].filter(row => (includeControl.value || !row.control) && (!moduleFilter.value || row.module === moduleFilter.value)).sort((a, b) => {
  const k = sortBy.value as 'requests' | 'avgMs' | 'p95Ms' | 'queueAvgMs'
  return (b.window[k] ?? -1) - (a.window[k] ?? -1) || (a.action ?? '').localeCompare(b.action ?? '')
}))
const jobs = computed(() => (snapshot.value?.modules ?? []).filter(m => m.details?.jobs).map(m => ({ module: m.module, ...m.details!.jobs! })))
const capacities = computed(() => (snapshot.value?.modules ?? []).filter(m => m.details?.capacity))
const flowEl = ref<HTMLElement | null>(null), latencyEl = ref<HTMLElement | null>(null), memoryEl = ref<HTMLElement | null>(null), runtimeEl = ref<HTMLElement | null>(null)
function chart(kind: 'flow' | 'latency' | 'memory' | 'runtime'): EChartsCoreOption {
  const e = (trendSource.value === 'commands' ? execution.value?.series : jobs.value.find(j => j.module === trendSource.value)?.series) ?? [], j = snapshot.value?.runtimeSeries ?? []
  const health = kind === 'memory' || kind === 'runtime'
  const lines = kind === 'flow' ? [
    { name: '提交 / s', data: e.map(x => x.rps) }, { name: '失败 + 超时', data: e.map(failureCount), yAxisIndex: 1, type: 'bar' }, { name: '拒绝', data: e.map(x => x.rejected), yAxisIndex: 1, type: 'bar' }
  ] : kind === 'latency' ? [
    { name: '普通平均', data: e.map(x => x.avgMs) }, { name: 'P95（估算）', data: e.map(x => x.p95Ms) }, { name: '排队平均', data: e.map(x => x.queueAvgMs) }
  ] : kind === 'memory' ? [
    { name: '堆已用', data: j.map(x => x.heapUsed / 1048576) }, { name: '堆已提交', data: j.map(x => x.heapCommitted / 1048576) }, { name: '堆上限', data: j.map(x => x.heapMax == null ? null : x.heapMax / 1048576) }
  ] : [
    { name: '线程数', data: j.map(x => x.threads) }, { name: 'GC 耗时增量', data: j.map(x => x.gcDeltaMillis), type: 'bar', yAxisIndex: 1 }
  ]
  return { color: ['#6366f1', '#06b6d4', '#f59e0b'], tooltip: { trigger: 'axis', renderMode: 'richText' },
    legend: { bottom: 0, icon: 'roundRect', itemHeight: 3, textStyle: { color: '#64748b', fontSize: 10 } }, grid: { left: 52, right: 48, top: 30, bottom: 44 },
    xAxis: { type: 'category', boundaryGap: false, data: (health ? j : e).map(x => formatTimeHHMM(x.timestamp)), axisLabel: { fontSize: 10, color: '#94a3b8' }, axisTick: { show: false } },
    yAxis: [{ type: 'value', name: kind === 'flow' ? '次/s' : kind === 'memory' ? 'MiB' : kind === 'runtime' ? '线程' : 'ms', splitLine: { lineStyle: { color: '#f1f5f9' } }, axisLabel: { color: '#94a3b8', fontSize: 10 } },
      ...(kind === 'flow' || kind === 'runtime' ? [{ type: 'value', name: kind === 'flow' ? '次 / 10s' : 'ms / 采集间隔', splitLine: { show: false }, axisLabel: { color: '#94a3b8', fontSize: 10 } }] : [])],
    series: lines.map(line => ({ type: 'line', symbol: 'none', connectNulls: false, lineStyle: { width: 1.8 }, barMaxWidth: 8, ...line })) }
}
const flowOption = computed(() => chart('flow')), latencyOption = computed(() => chart('latency'))
const memoryOption = computed(() => chart('memory')), runtimeOption = computed(() => chart('runtime'))
useChart(flowEl, flowOption); useChart(latencyEl, latencyOption); useChart(memoryEl, memoryOption); useChart(runtimeEl, runtimeOption)
const stateName = (state: string | number) => ({ queued: '排队', running: '运行', waiting_input: '等待输入', waiting_approval: '等待审批', cancelling: '取消中', reconciling: '对账中' }[state] ?? state)
const mib = (n: number | null | undefined) => n == null ? '--' : metric(n / 1048576, 1) + ' MiB'
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
  ++generation; response.value = null; trendSource.value = 'commands'; error.value = ''; moduleFilter.value = ''; loading.value = false
  void refresh()
}, { immediate: true })
watch(autoRefresh, enabled => { if (enabled) void refresh() })
usePolling(() => autoRefresh.value ? refresh() : undefined, 10_000, { immediate: false })
usePolling(() => { clock.value = performance.now() }, 1000, { immediate: false, pauseOnHidden: false })
onBeforeUnmount(() => { ++generation })
</script>
<template>
  <section class="card" data-testid="service-monitor">
    <div class="card-header flex-wrap gap-4"><div><h2 class="card-title">服务执行监控</h2><p class="card-subtitle">业务命令 · 长任务 · 运行连接与容量<span v-if="response?.report"> · 采集于 {{ formatFullTime(response.report.timestamp) }}</span></p></div>
      <div class="flex max-w-full flex-wrap items-center gap-3"><span class="rounded-full px-2.5 py-1 text-[11px]" :class="fresh ? 'bg-emerald-50 text-emerald-600' : 'bg-amber-50 text-amber-600'">{{ freshness }}</span><select v-model="selectedName" aria-label="选择监控服务单元" class="input w-60 max-w-full text-xs"><option v-for="s in servers" :key="s.name" :value="s.name">{{ s.name }}</option></select><label class="flex items-center gap-1.5 whitespace-nowrap text-xs text-slate-500"><input v-model="autoRefresh" type="checkbox" class="accent-indigo-600">自动刷新</label><button type="button" class="btn btn-ghost btn-sm" :disabled="loading || !selected" @click="refresh"><AppIcon name="refresh" :size="14" :class="{ 'animate-spin': loading }" />刷新</button></div>
    </div>
    <EmptyState v-if="!selected" title="暂无服务单元" description="添加部署后查看业务执行与运行拓扑。" />
    <div v-else class="space-y-5 p-5">
      <p v-if="error" role="alert" class="rounded-lg bg-rose-50 px-4 py-3 text-xs text-rose-600">刷新失败：{{ error }}<span v-if="snapshot">；以下保留最后一次成功快照。</span></p>
      <EmptyState v-if="!snapshot" :title="loading ? '正在读取性能报告' : response?.report ? '当前节点尚未提供服务执行监控数据' : '暂无性能报告'" description="升级 common 与 service 后自动提供监控快照；缺少采集数据时不填充零值。" />
      <template v-if="snapshot && execution">
        <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-6"><div v-for="c in cards" :key="c.label" class="min-w-0 rounded-xl border border-slate-200 px-4 py-4"><p class="text-xs text-slate-500">{{ c.label }}</p><p class="tabular my-3 break-words text-xl font-semibold tracking-tight text-indigo-600">{{ c.value }}<span class="ml-1 text-[10px] font-normal text-slate-400">{{ c.unit }}</span></p><p class="text-[10px] leading-4 text-slate-400">{{ c.detail }}</p></div></div>
        <div class="flex flex-wrap gap-x-5 gap-y-1 text-[11px] text-slate-500"><span>窗口 {{ formatTimeHHMM(execution.windowStartedAt) }}–{{ formatTimeHHMM(execution.windowEndedAt) }} · {{ execution.windowSeconds }} 秒</span><span>累计 Cellet 消息 {{ metric(execution.receivedMessages) }}</span><span>控制消息命令 {{ metric(execution.control.window.requests) }} 次 / 近一分钟</span><span>已受理 {{ metric(execution.window.accepted) }} · 结果未观测 {{ metric(execution.window.unknown) }}</span></div>
        <div class="flex flex-wrap gap-2"><span v-for="m in snapshot.modules" :key="m.module" class="rounded px-2 py-1 text-[10px]" :class="!fresh || m.collectionError ? 'bg-slate-100 text-slate-400' : m.started ? 'bg-indigo-50 text-indigo-600' : 'bg-amber-50 text-amber-600'">{{ m.module }} · {{ !fresh || m.collectionError ? '状态未知' : m.started ? '已启动' : '未启动' }}</span><span v-if="response?.report?.items.Contact" class="px-2 py-1 text-[10px] text-slate-500">Contact 在线联系人 {{ metric(response.report.items.Contact.onlineNum) }} / {{ metric(response.report.items.Contact.maxNum) }}</span></div>
        <p v-if="execution.windowSeconds === 0" class="text-xs text-slate-400">正在采集首个 10 秒统计窗口。</p><p v-else-if="execution.window.requests === 0" class="text-xs text-slate-400">最近一分钟无业务命令。无耗时样本时显示“--”。</p>
        <div class="flex flex-wrap items-center justify-between gap-2 text-xs"><span class="text-slate-500">趋势统计对象</span><select v-model="trendSource" aria-label="趋势统计对象" class="input w-44 text-xs"><option value="commands">业务命令</option><option v-for="job in jobs" :key="job.module" :value="job.module">{{ job.module }} 长任务</option></select></div>
        <div class="grid gap-4 lg:grid-cols-2"><div class="min-w-0 rounded-xl border border-slate-200 p-4"><h3 class="text-xs font-semibold">提交与失败趋势 <span class="font-normal text-slate-400">最近 30 分钟</span></h3><div ref="flowEl" class="h-56" /></div><div class="min-w-0 rounded-xl border border-slate-200 p-4"><h3 class="text-xs font-semibold">普通耗时与排队 <span class="font-normal text-slate-400">最近 30 分钟</span></h3><div ref="latencyEl" class="h-56" /></div></div>
        <section class="rounded-xl border border-slate-200" data-testid="service-jobs"><div class="border-b border-slate-100 px-5 py-4"><h3 class="text-sm font-semibold">异步长任务</h3><p class="mt-1 text-[11px] text-slate-400">与命令受理分开统计 · AIGC generation 按终态事件，Harness run 按已提交状态迁移</p></div>
          <p v-if="!jobs.length" class="p-6 text-xs text-slate-400">当前模块尚未提供长任务生命周期数据。</p>
          <div v-for="job in jobs" :key="job.module" class="border-b border-slate-100 p-5 last:border-0"><div class="mb-3 flex flex-wrap justify-between gap-2"><b class="text-xs text-indigo-600">{{ job.module }}</b><span class="text-[11px] text-slate-400">运行累计 {{ metric(job.total.requests) }} · 当前运行 {{ job.running }} / 排队 {{ job.queued }}</span></div>
            <div class="grid grid-cols-2 gap-3 text-xs sm:grid-cols-4 lg:grid-cols-8"><div v-for="[label, value] in [['提交', job.window.requests], ['完成', job.window.completed], ['成功', job.window.succeeded], ['失败', job.window.failed], ['超时', job.window.timedOut], ['取消', job.window.cancelled], ['拒绝', job.window.rejected], ['P95 ms', job.window.p95Ms]]" :key="String(label)"><p class="text-[10px] text-slate-400">{{ label }}</p><p class="tabular mt-1 font-semibold">{{ value == null ? '--' : value }}</p></div></div>
            <p class="mt-3 text-[11px] text-slate-500">排队 {{ milliseconds(job.window.queueAvgMs) }} · 执行 {{ milliseconds(job.window.executionAvgMs) }} · 普通总耗时 {{ milliseconds(job.window.avgMs) }} · 流式首响应 {{ milliseconds(job.window.firstByteAvgMs) }} / 总时长 {{ milliseconds(job.window.streamAvgMs) }}</p>
            <p v-if="job.states" class="mt-2 flex flex-wrap gap-3 text-[10px] text-slate-400"><span v-for="(count, state) in job.states" :key="state">{{ stateName(state) }} {{ count }}</span><span>恢复后观测 {{ metric(job.recovered) }} · 超出观测上限 {{ metric(job.untracked) }}</span></p>
          </div><p class="px-5 py-3 text-[10px] leading-5 text-slate-400">命令受理成功不等于任务完成。长任务时长包含运行期间的等待；恢复任务仅记录本 JVM 观测到的时长。逻辑终态后，底层单元可能仍被占用，容量以调度器为准。</p>
        </section>
        <section v-for="m in capacities" :key="m.module" class="rounded-xl border border-slate-200" data-testid="service-capacity"><div class="flex flex-wrap justify-between gap-3 border-b border-slate-100 px-5 py-4"><div><h3 class="text-sm font-semibold">{{ m.module }} 可执行容量</h3><p class="mt-1 text-[11px] text-slate-400">按共享后端与资源组去重，直接采用调度器结果</p></div><span class="text-xs text-slate-500">接单 {{ fresh ? m.details?.accepting ? '开启' : '关闭' : '未知' }} · run 就绪 {{ fresh ? m.details?.runsReady ? '是' : '否' : '未知' }}</span></div>
          <div class="grid grid-cols-3 gap-3 p-5 text-xs"><div><p class="text-slate-400">注册单元</p><b class="tabular mt-2 block text-xl">{{ metric(m.details?.capacity?.registeredUnits) }}</b></div><div><p class="text-slate-400">可执行并发</p><b class="tabular mt-2 block text-xl">{{ metric(fresh ? m.details?.capacity?.executableConcurrency : null) }}</b></div><div><p class="text-slate-400">实际可用</p><b class="tabular mt-2 block text-xl text-emerald-600">{{ metric(fresh ? m.details?.capacity?.available : null) }}</b></div></div>
          <div class="overflow-x-auto"><table class="w-full whitespace-nowrap text-left text-xs"><thead class="bg-slate-50 text-[10px] text-slate-400"><tr><th class="px-5 py-2">后端</th><th class="px-4">就绪单元</th><th class="px-4">物理占用 / 上限</th><th class="px-4">可执行并发 / 可用</th></tr></thead><tbody><tr v-for="(b, i) in m.details?.capacity?.backends" :key="i" class="border-t border-slate-100"><td class="px-5 py-3">{{ b.backend_id }}</td><td class="px-4">{{ b.readyUnits ?? '--' }}</td><td class="px-4">{{ b.occupied }} / {{ b.capacity }}</td><td class="px-4">{{ fresh ? b.executableConcurrency : '--' }} / {{ fresh ? b.available : '--' }}</td></tr></tbody></table></div>
        </section>
        <p v-for="m in snapshot.modules.filter(m => m.collectionError)" :key="m.module" class="text-xs text-amber-600">{{ m.module }} 运行状态采集失败；该模块连接状态未知。</p>
      </template>
      <AIUsageSummary />
      <ServiceTopology :server="selected" :snapshot="snapshot" :fresh="fresh" />
      <template v-if="snapshot && execution">
        <section class="rounded-xl border border-slate-200"><div class="border-b border-slate-100 px-5 py-4"><h3 class="text-sm font-semibold">模块业务量与耗时排行</h3><p class="mt-1 text-[11px] text-slate-400">近一分钟 · 点击模块过滤动作</p><select v-model="moduleSort" aria-label="模块排行排序" class="input mt-2 w-40 text-xs"><option value="requests">按命令量排序</option><option value="avgMs">按平均耗时排序</option><option value="p95Ms">按 P95 排序</option></select></div><div class="overflow-auto"><table class="w-full whitespace-nowrap text-left text-xs"><thead class="bg-slate-50 text-[10px] text-slate-400"><tr><th class="px-5 py-3">模块</th><th class="px-4">命令量 / 累计</th><th class="px-4">平均 / P95</th><th class="px-4">失败 / 拒绝</th><th class="px-4">运行 / 排队</th></tr></thead><tbody><tr v-for="m in modules" :key="m.module" class="border-t border-slate-100"><td class="px-5 py-3"><button class="font-semibold text-indigo-600" @click="moduleFilter = moduleFilter === m.module ? '' : m.module">{{ m.module }}</button></td><td class="tabular px-4">{{ metric(m.window.requests) }} / {{ metric(m.total.requests) }}</td><td class="tabular px-4">{{ milliseconds(m.window.avgMs) }} / {{ milliseconds(m.window.p95Ms) }}</td><td class="tabular px-4">{{ failureCount(m.window) }} / {{ m.window.rejected }}</td><td class="tabular px-4">{{ m.running }} / {{ m.queued }}</td></tr></tbody></table></div><p v-if="!modules.length" class="p-6 text-xs text-slate-400">等待首个业务命令。</p></section>
        <section class="rounded-xl border border-slate-200" data-testid="service-actions"><div class="flex flex-wrap justify-between gap-3 border-b border-slate-100 px-5 py-4"><h3 class="text-sm font-semibold">模块动作明细</h3><div class="flex flex-wrap items-center gap-2"><label class="text-xs text-slate-500"><input v-model="includeControl" type="checkbox"> 包含控制命令</label><select v-model="moduleFilter" aria-label="按模块过滤动作" class="input w-32 text-xs"><option value="">全部模块</option><option v-for="m in [...new Set(execution.actions.map(a => a.module))]" :key="m">{{ m }}</option></select><select v-model="sortBy" aria-label="动作排序" class="input w-36 text-xs"><option value="requests">按命令量排序</option><option value="avgMs">按平均耗时排序</option><option value="p95Ms">按 P95 排序</option><option value="queueAvgMs">按排队耗时排序</option></select></div></div>
          <div class="max-h-96 overflow-auto"><table class="w-full whitespace-nowrap text-left text-xs"><thead class="sticky top-0 bg-slate-50 text-[10px] text-slate-400"><tr><th class="px-5 py-3">动作 / 模块</th><th class="px-4">进入 / 结束 / 受理</th><th class="px-4">失败 / 超时 / 取消 / 拒绝</th><th class="px-4">普通平均 / P95</th><th class="px-4">排队 / 执行</th><th class="px-4">流式首响应 / 总时长</th><th class="px-4">结果未知 / 当前运行</th></tr></thead><tbody><tr v-for="a in actions" :key="a.module + ':' + a.action + ':' + a.control" class="border-t border-slate-100"><td class="px-5 py-3"><p class="font-mono text-indigo-600">{{ a.action }}<span v-if="a.control" class="ml-2 text-slate-400">控制</span></p><p class="mt-1 text-[10px] text-slate-400">{{ a.module }}</p></td><td class="tabular px-4">{{ a.window.requests }} / {{ a.window.completed }} / {{ a.window.accepted }}</td><td class="tabular px-4">{{ a.window.failed }} / {{ a.window.timedOut }} / {{ a.window.cancelled }} / {{ a.window.rejected }}</td><td class="tabular px-4">{{ milliseconds(a.window.avgMs) }} / {{ milliseconds(a.window.p95Ms) }}</td><td class="tabular px-4">{{ milliseconds(a.window.queueAvgMs) }} / {{ milliseconds(a.window.executionAvgMs) }}</td><td class="tabular px-4">{{ milliseconds(a.window.firstByteAvgMs) }} / {{ milliseconds(a.window.streamAvgMs) }}</td><td class="tabular px-4">{{ a.window.unknown }} / {{ a.running }}</td></tr></tbody></table></div><p v-if="!actions.length" class="p-6 text-xs text-slate-400">暂无匹配动作。</p>
          <p class="border-t border-slate-100 px-5 py-3 text-[10px] leading-5 text-slate-400">命令统计不包含原始消息回包和文件字节流；控制命令单独展示。失败率分母排除“已受理”和“结果未观测”；受理不是最终成功。普通耗时排除流式动作，首响应不等于首个 Token。旧异步动作若未接入终态回调，返回后标记“结果未观测”。</p>
        </section>
        <section class="rounded-xl border border-slate-200"><div class="border-b border-slate-100 px-5 py-4"><h3 class="text-sm font-semibold">JVM 与执行器健康</h3></div><div class="p-5"><div class="grid gap-4 text-xs sm:grid-cols-3"><div><p class="text-slate-400">堆已用 / 已提交 / 上限</p><p class="tabular mt-2">{{ mib(snapshot.jvm.heapUsed) }} / {{ mib(snapshot.jvm.heapCommitted) }} / {{ mib(snapshot.jvm.heapMax) }}</p></div><div><p class="text-slate-400">线程 / 守护线程 / 峰值</p><p class="tabular mt-2">{{ snapshot.jvm.threads }} / {{ snapshot.jvm.daemonThreads }} / {{ snapshot.jvm.peakThreads }}</p></div><div><p class="text-slate-400">本次 GC 次数 / 耗时 · 累计耗时</p><p class="tabular mt-2">{{ metric(snapshot.jvm.gcDeltaCount) }} / {{ milliseconds(snapshot.jvm.gcDeltaMillis) }} · {{ milliseconds(snapshot.jvm.gcMillis) }}</p></div></div><div class="mt-4 grid gap-4 lg:grid-cols-2"><div class="min-w-0"><div ref="memoryEl" class="h-56" /></div><div class="min-w-0"><div ref="runtimeEl" class="h-56" /></div></div></div>
          <div class="overflow-auto"><table class="w-full whitespace-nowrap text-left text-xs"><thead class="bg-slate-50 text-[10px] text-slate-400"><tr><th class="px-5 py-3">执行器</th><th class="px-4">状态</th><th class="px-4">活跃 / 线程 / 上限</th><th class="px-4">队列长度</th><th class="px-4">完成任务</th></tr></thead><tbody><tr v-for="p in snapshot.pools" :key="p.name" class="border-t border-slate-100"><td class="px-5 py-3">{{ p.name }}</td><td class="px-4">{{ !fresh ? '未知' : p.running ? '运行中' : '已停止' }}</td><td class="tabular px-4">{{ metric(p.active) }} / {{ metric(p.threads) }} / {{ metric(p.maxThreads) }}</td><td class="tabular px-4">{{ metric(p.queued) }}</td><td class="tabular px-4">{{ metric(p.completed) }}</td></tr></tbody></table></div>
          <p class="px-5 py-3 text-[10px] leading-5 text-slate-400">内存单位为 MiB。JVM 健康随性能快照采集；原 JVMReport 保留原上报周期。不支持观测的执行器字段显示“--”，共享执行器只展示一次。</p>
        </section>
      </template>
    </div>
  </section>
</template>
