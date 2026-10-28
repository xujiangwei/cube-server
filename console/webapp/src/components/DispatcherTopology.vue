<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useChart } from '@/composables/useChart'
import { metric } from '@/api/dispatcher-monitor'
import type { DispatcherServer, DispatcherServiceConnection, DispatcherSnapshot } from '@/api/types'
import type { EChartsCoreOption } from '@/charts/echarts'

const props = defineProps<{ server: DispatcherServer; snapshot: DispatcherSnapshot | null; fresh: boolean }>()
const chartEl = ref<HTMLElement | null>(null)
const selectedId = ref('')
const peers = computed<DispatcherServiceConnection[]>(() => props.snapshot?.topology.services ?? props.server.directors.map(item => ({
  id: `${item.address.includes(':') ? `[${item.address}]` : item.address}:${item.port}`,
  address: item.address, port: item.port, cellets: item.cellets,
  ready: null, calls: null, sendFailures: null, totalCalls: null, totalSendFailures: null
})))
const selected = computed(() => peers.value.find(item => item.id === selectedId.value) ?? peers.value[0])
const height = computed(() => Math.max(320, peers.value.length * 88))
const state = (peer: DispatcherServiceConnection) => !props.fresh || peer.ready == null ? '状态未知' : peer.ready ? '已连接' : '连接断开'
const color = (peer: DispatcherServiceConnection) => !props.fresh || peer.ready == null ? '#94a3b8' : peer.ready ? '#10b981' : '#f43f5e'
const option = computed<EChartsCoreOption>(() => {
  const center = Math.max(0, peers.value.length - 1) * 48
  const nodes = [
    { id: 'ingress', name: 'HTTP / HTTPS', x: 0, y: center, symbolSize: [132, 64], itemStyle: { color: '#f1f5f9', borderColor: '#cbd5e1' },
      label: { color: '#475569', formatter: 'HTTP / HTTPS\nREST API 访问入口' } },
    { id: 'dispatcher', name: props.server.name, x: 235, y: center, symbolSize: [150, 72], itemStyle: { color: '#4f46e5', borderColor: '#4f46e5' },
      label: { color: '#fff', formatter: 'Dispatcher\n请求接入 · 模块路由' } },
    ...peers.value.map((peer, index) => ({ id: peer.id, name: peer.id, x: 505, y: index * 96,
      symbolSize: [180, 76], itemStyle: { color: '#fff', borderColor: color(peer), borderWidth: selected.value?.id === peer.id ? 3 : 1.5 },
      label: { color: '#334155', lineHeight: 18, formatter: `${peer.id}\n${peer.cellets.slice(0, 3).join(' · ')}${peer.cellets.length > 3 ? ' …' : ''}\n${state(peer)}` } }))
  ]
  return {
    tooltip: { trigger: 'item', renderMode: 'richText', formatter: (params: unknown) => {
      const item = params as { data: { id?: string; source?: string; target?: string } }
      const peer = peers.value.find(p => p.id === (item.data.id ?? item.data.target))
      return peer ? `${peer.id}\n${state(peer)}\n模块：${peer.cellets.join('、')}\n近一分钟下游调用：${metric(peer.calls)} 次\n发送失败：${metric(peer.sendFailures)} 次`
        : item.data.id === 'ingress' ? `HTTP ${props.server.http.host}:${props.server.http.port}\nHTTPS ${props.server.https.port > 0 ? props.server.https.port : '未启用'}` : props.server.name
    } },
    series: [{ type: 'graph', layout: 'none', roam: true, symbol: 'roundRect', edgeSymbol: ['none', 'arrow'], edgeSymbolSize: 7,
      left: 80, right: 95, top: 50, bottom: 50,
      label: { show: true, fontSize: 11, lineHeight: 20, overflow: 'truncate', width: 160 },
      lineStyle: { width: 1.5, color: '#c7d2fe', curveness: .06 },
      edgeLabel: { show: true, fontSize: 10, color: '#64748b', backgroundColor: '#fff', padding: [3, 5], formatter: '{c}' },
      emphasis: { focus: 'adjacency' }, data: nodes,
      links: [{ source: 'ingress', target: 'dispatcher', value: 'REST 请求' }, ...peers.value.map(peer => ({
        source: 'dispatcher', target: peer.id, value: peer.calls == null ? '未采集' : `${metric(peer.calls)} 次调用`,
        lineStyle: { color: color(peer), type: props.fresh && peer.ready ? 'solid' : 'dashed', opacity: .7 }
      }))]
    }]
  }
})
const { getInstance } = useChart(chartEl, option)
watch(option, async () => {
  await nextTick()
  const chart = getInstance()
  chart?.off('click')
  chart?.on('click', (event: { dataType?: string; data?: unknown }) => {
    const id = (event.data as { id?: string })?.id
    if (event.dataType === 'node' && peers.value.some(peer => peer.id === id)) selectedId.value = id ?? ''
  })
}, { immediate: true, flush: 'post' })
watch(() => props.server.name, () => { selectedId.value = '' })
</script>

<template>
  <section class="overflow-hidden rounded-xl border border-slate-200 bg-white" data-testid="dispatcher-topology">
    <div class="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 px-5 py-4">
      <div><h3 class="text-sm font-semibold text-slate-800">服务连接拓扑</h3><p class="mt-1 text-xs text-slate-400">一个调度机连接多个服务节点 · 点击节点查看模块与调用数据</p></div>
      <span class="rounded-full bg-slate-100 px-3 py-1 text-xs text-slate-600">{{ peers.length }} 个 service · {{ fresh ? peers.filter(p => p.ready).length : '--' }} 个已连接</span>
    </div>
    <div class="grid xl:grid-cols-[minmax(0,1fr)_280px]">
      <div class="min-w-0 overflow-auto bg-slate-50/40" style="max-height: 520px">
        <div ref="chartEl" :style="{ height: `${height}px`, minWidth: '620px' }" />
      </div>
      <div class="border-t border-slate-100 p-4 xl:border-l xl:border-t-0">
        <p v-if="!snapshot" class="mb-3 text-xs leading-5 text-amber-600">根据部署配置展示，未获实时状态。</p>
        <p v-else-if="!fresh" class="mb-3 text-xs leading-5 text-amber-600">快照已过期或新鲜度未知，当前连接状态未知。</p>
        <p v-if="!peers.length" class="py-8 text-center text-sm text-slate-400">尚未配置服务单元</p>
        <div v-else class="mb-4 flex max-h-36 flex-wrap gap-1.5 overflow-auto">
          <button v-for="peer in peers" :key="peer.id" type="button" :aria-pressed="selected?.id === peer.id"
            class="rounded-md border px-2 py-1.5 text-[11px] transition-colors"
            :class="selected?.id === peer.id ? 'border-indigo-300 bg-indigo-50 text-indigo-700' : 'border-slate-200 text-slate-500 hover:border-indigo-200'"
            @click="selectedId = peer.id">{{ peer.id }}</button>
        </div>
        <template v-if="selected">
          <p class="break-all text-sm font-semibold text-slate-800">{{ selected.id }}</p>
          <p class="mt-1.5 flex items-center gap-1.5 text-xs" :style="{ color: color(selected) }"><span class="h-1.5 w-1.5 rounded-full" :style="{ backgroundColor: color(selected) }" />{{ state(selected) }}</p>
          <p class="mb-2 mt-5 text-[11px] text-slate-400">承载服务模块</p>
          <div class="flex flex-wrap gap-1.5"><span v-for="module in selected.cellets" :key="module" class="rounded bg-slate-100 px-2 py-1 text-[11px] text-slate-600">{{ module }}</span></div>
          <dl class="mt-5 space-y-2.5 text-xs">
            <div class="flex justify-between"><dt class="text-slate-500">近一分钟下游调用</dt><dd class="tabular font-semibold">{{ metric(selected.calls) }}</dd></div>
            <div class="flex justify-between"><dt class="text-slate-500">发送失败</dt><dd class="tabular text-rose-500">{{ metric(selected.sendFailures) }}</dd></div>
            <div class="flex justify-between"><dt class="text-slate-500">运行累计调用</dt><dd class="tabular">{{ metric(selected.totalCalls) }}</dd></div>
          </dl>
          <p class="mt-4 text-[10px] leading-5 text-slate-400">下游调用统计 Cell 消息发送，包含内部控制调用；发送成功表示消息已交给连接，不代表业务执行成功。</p>
        </template>
      </div>
    </div>
  </section>
</template>
