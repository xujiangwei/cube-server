<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useChart } from '@/composables/useChart'
import { metric } from '@/api/dispatcher-monitor'
import type { ServiceSnapshot, ServiceWorker } from '@/api/service-monitor'
import type { ServiceServer } from '@/api/types'
import type { EChartsCoreOption } from '@/charts/echarts'
const props = defineProps<{ server: ServiceServer; snapshot: ServiceSnapshot | null; fresh: boolean }>()
const el = ref<HTMLElement | null>(null)
const selectedId = ref('')
const workers = computed(() => props.snapshot?.topology.workers ?? [])
const peers = computed(() => props.snapshot?.topology.dispatchers ?? [])
const selected = computed(() => workers.value.find(w => w.id === selectedId.value) ?? workers.value[0])
const state = (w: ServiceWorker) => !props.fresh || w.connected == null ? '状态未知' : !w.connected ? '连接断开' : w.draining ? '排水中' : w.healthy === false ? '不健康' : w.ready == null ? '就绪未知' : w.role === 'AI Unit' ? w.busy ? '已连接 · 调度忙碌' : '已连接' : w.ready ? '就绪' : '未就绪'
const color = (w: ServiceWorker) => !props.fresh || w.connected == null ? '#94a3b8' : !w.connected || w.healthy === false ? '#f43f5e' : w.draining || !w.ready ? '#f59e0b' : '#10b981'
const height = computed(() => Math.max(330, Math.max(workers.value.length, peers.value.length) * 88))
const option = computed<EChartsCoreOption>(() => {
  const mid = Math.max(workers.value.length - 1, peers.value.length - 1, 0) * 44
  const nodes = [
    { id: 'service', x: 270, y: mid, name: props.server.name, symbolSize: [160, 76], itemStyle: { color: '#4f46e5' }, label: { color: '#fff', formatter: 'Service JVM\n' + props.server.server.host + ':' + props.server.server.port } },
    ...peers.value.map((p, i) => ({ id: 'p:' + p.id, x: 0, y: peers.value.length === 1 ? mid : i * 88, name: p.name, symbolSize: [175, 68], itemStyle: { color: '#fff', borderColor: !props.fresh ? '#94a3b8' : p.ready ? '#10b981' : '#f43f5e', borderWidth: 1.5 }, label: { color: '#475569', formatter: p.name + '\n' + (!props.fresh ? '状态未知' : p.ready ? '已连接' : '连接断开') } })),
    ...workers.value.map((w, i) => ({ id: w.id, x: 550, y: workers.value.length === 1 ? mid : i * 88, name: w.workerId, symbolSize: [185, 72], itemStyle: { color: '#fff', borderColor: color(w), borderWidth: selected.value?.id === w.id ? 3 : 1.5 }, label: { color: '#334155', formatter: `${w.workerId}\n${w.module} · ${w.model || w.runnerType || w.role || 'Unit'}\n${state(w)}` } }))
  ]
  return {
    tooltip: { renderMode: 'richText', formatter: (params: unknown) => {
      const id = (params as { data: { id?: string } }).data.id
      const w = workers.value.find(w => w.id === id)
      const peer = peers.value.find(p => 'p:' + p.id === id)
      return w ? `${w.workerId}\n实例：${w.instanceId}\n${w.address ?? w.module}\n${state(w)}\n占用 / 可用：${metric(w.occupied)} / ${metric(props.fresh && w.connected ? w.available : null)}` : peer ? `${peer.name}\n${peer.address}\n累计上游消息 ${metric(peer.totalCalls)}` : props.server.name
    } },
    series: [{ type: 'graph', layout: 'none', roam: true, symbol: 'roundRect', left: 100, right: 110, top: 48, bottom: 48,
      label: { show: true, fontSize: 10, width: 165, overflow: 'truncate', lineHeight: 18 }, edgeSymbol: ['none', 'arrow'], edgeSymbolSize: 7,
      lineStyle: { color: '#c7d2fe', width: 1.5, curveness: .05 }, emphasis: { focus: 'adjacency' },
      data: nodes, links: [...peers.value.map(p => ({ source: 'p:' + p.id, target: 'service', lineStyle: { type: props.fresh && p.ready ? 'solid' : 'dashed' } })),
        ...workers.value.map(w => ({ source: 'service', target: w.id, lineStyle: { color: color(w), type: props.fresh && w.connected ? 'solid' : 'dashed' } }))]
    }]
  }
})
const { getInstance } = useChart(el, option)
watch(option, async () => {
  await nextTick()
  const chart = getInstance(); chart?.off('click')
  chart?.on('click', (e: { data?: unknown }) => { const id = (e.data as { id?: string })?.id; if (workers.value.some(w => w.id === id)) selectedId.value = id ?? '' })
}, { immediate: true, flush: 'post' })
watch(() => props.server.name, () => { selectedId.value = '' })
</script>
<template>
  <section class="overflow-hidden rounded-xl border border-slate-200" data-testid="service-topology">
    <div class="flex flex-wrap justify-between gap-2 border-b border-slate-100 px-5 py-4"><div><h3 class="text-sm font-semibold text-slate-800">运行连接拓扑</h3><p class="mt-1 text-xs text-slate-400">多个 Dispatcher → Service JVM → 多个 Python Unit / Harness worker</p></div><span class="text-xs text-slate-500">{{ peers.length }} 个上游 · {{ workers.length }} 个下游</span></div>
    <div class="grid xl:grid-cols-[minmax(0,1fr)_280px]">
      <div class="min-w-0 overflow-auto bg-slate-50/40" style="max-height:520px"><div ref="el" :style="{ height: height + 'px', minWidth: '720px' }" /></div>
      <div class="border-t border-slate-100 p-4 xl:border-l xl:border-t-0">
        <p v-if="!snapshot" class="mb-3 text-xs leading-5 text-amber-600">当前节点未提供实时拓扑；仅展示配置中的 Service 端点。</p>
        <p v-else-if="!fresh" class="mb-3 text-xs leading-5 text-amber-600">快照已过期或新鲜度未知，当前连接状态未知。</p>
        <p v-if="!workers.length" class="text-xs leading-5 text-slate-400">尚未观测到下游单元。通用业务模块可以直接在 JVM 中执行。</p>
        <div class="mb-4 flex max-h-32 flex-wrap gap-1.5 overflow-auto"><button v-for="w in workers" :key="w.id" type="button" :aria-pressed="selected?.id === w.id" class="rounded border px-2 py-1 text-[11px]" :class="selected?.id === w.id ? 'border-indigo-300 bg-indigo-50 text-indigo-600' : 'border-slate-200 text-slate-500'" @click="selectedId = w.id">{{ w.workerId }}</button></div>
        <template v-if="selected"><p class="break-all text-sm font-semibold">{{ selected.workerId }}</p><p class="mt-2 text-xs" :style="{ color: color(selected) }">{{ state(selected) }}</p>
          <dl class="mt-4 space-y-3 text-xs"><div><dt class="text-slate-400">实例身份</dt><dd class="mt-1 break-all">{{ selected.instanceId || '--' }}</dd></div><div><dt class="text-slate-400">模块 / 角色</dt><dd class="mt-1">{{ selected.module }} / {{ selected.model || selected.runnerType || selected.role || '--' }}<span v-if="selected.backendId"> · {{ selected.backendId }}</span></dd></div><div><dt class="text-slate-400">端点</dt><dd class="mt-1 break-all">{{ selected.address ?? '复用 Cell / SHM 连接' }}</dd></div><div class="flex justify-between"><dt class="text-slate-400">占用 / 可用</dt><dd>{{ metric(selected.occupied) }} / {{ metric(fresh && selected.connected ? selected.available : null) }}</dd></div><div class="flex justify-between"><dt class="text-slate-400">剩余租约</dt><dd>{{ fresh && selected.connected && selected.leaseRemainingMs != null ? metric(selected.leaseRemainingMs / 1000, 1) + ' 秒（采集时）' : '--' }}</dd></div></dl>
        </template>
        <p class="mt-5 text-[10px] leading-5 text-slate-400">上游从实际消息会话发现，消息次数包含控制调用。下游按 worker 与实例身份区分；断线节点保留 30 分钟。单节点声明容量不可相加。</p>
      </div>
    </div>
  </section>
</template>
