<script setup lang="ts">
/**
 * 联系人概览。
 *
 * 数据来源有两个：
 * - `/statistic/range`：区间逐日数据 + 汇总，承担周/月/年视图（后端只物化日粒度，区间即时聚合）；
 * - `/statistic/recent`：昨日快照，用于时段分布图与新增用户。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import StatCard from '@/components/StatCard.vue'
import { DEFAULT_DOMAIN, fetchDomains, fetchRecentStatistic, fetchStatisticRange, pickList } from '@/api/console'
import type { StatisticRangeResponse, StatisticResponse } from '@/api/types'
import { useChart } from '@/composables/useChart'
import { useToastStore } from '@/stores/toast'
import type { EChartsCoreOption } from '@/charts/echarts'

const toast = useToastStore()

const domains = ref<string[]>([])
const domain = ref('')

/** 区间模式：近 7 天 / 近 30 天 / 近 90 天 / 本年至今 */
type RangeMode = '7d' | '30d' | '90d' | 'ytd'
const mode = ref<RangeMode>('30d')

/** 本年至今的天数，按自然日计算 */
function dayOfYear(): number {
  const now = new Date()
  const start = new Date(now.getFullYear(), 0, 1)
  return Math.floor((now.getTime() - start.getTime()) / 86400000) + 1
}

const days = computed(() => (mode.value === 'ytd' ? dayOfYear() : Number(mode.value.slice(0, -1))))

const range = ref<StatisticRangeResponse | null>(null)
const statistic = ref<StatisticResponse | null>(null)

const loading = ref(false)
const errorMessage = ref('')

const trendEl = ref<HTMLElement | null>(null)
const chartEl = ref<HTMLElement | null>(null)

const summary = computed(() => range.value?.summary ?? null)

/** 区间逐日点位，按日期升序（后端已排序） */
const rangePoints = computed(() => {
  const list = range.value?.days ?? []
  return list.map((day) => ({
    label: `${String(day.month).padStart(2, '0')}-${String(day.date).padStart(2, '0')}`,
    dau: day.statistic.DAU ?? 0,
    aot: day.statistic.AOT ?? 0
  }))
})

const hasTrendData = computed(() => rangePoints.value.length > 0)

const rangeText = computed(() => {
  const data = range.value
  if (!data) {
    return '--'
  }
  const fmt = (item: { year: number; month: number; date: number }) =>
    `${item.year}-${String(item.month).padStart(2, '0')}-${String(item.date).padStart(2, '0')}`
  return `${fmt(data.from)} ~ ${fmt(data.to)}`
})

/** 区间去重活跃人数：-1 表示明细已过保留期 */
const uvText = computed(() => {
  const data = summary.value
  if (!data) {
    return '--'
  }
  return data.uv >= 0 ? String(data.uv) : '—'
})

const uvHint = computed(() => {
  const data = summary.value
  if (!data) {
    return '区间内按用户去重'
  }
  return data.uv >= 0 ? '来自事件明细，按用户去重' : '明细已过保留期，无法精确去重'
})

const aotText = computed(() => {
  const data = summary.value
  return data ? data.aot.toFixed(2) : '--'
})

const aotHint = computed(() => {
  const data = summary.value
  if (!data) {
    return '按区间总时长 ÷ 总人数加权'
  }
  if (data.estimatedDays > 0) {
    return `加权口径；其中 ${data.estimatedDays} 天为旧口径估算`
  }
  return '按区间总时长 ÷ 总人数加权'
})

const peakText = computed(() => {
  const data = summary.value
  return data ? `${data.dauMax} 人 / 日` : '--'
})

const peakHint = computed(() => {
  const data = summary.value
  if (!data) {
    return '区间内单日活跃最高值'
  }
  return `区间日活之和 ${data.dauSum}（逐日相加，非去重）`
})

const tnuText = computed(() => {
  const data = summary.value
  return data ? String(data.tnu) : '--'
})

const tnuHint = computed(() => {
  const data = summary.value
  return data ? `${data.days} 天有统计数据` : '区间末日的用户总数'
})

/** 峰值时段：取 contacts 数最大的切片 */
const peakSlice = computed(() => {
  const slices = statistic.value?.statistic.TD ?? []
  if (slices.length === 0) {
    return null
  }
  return slices.reduce((max, item) => (item.numContacts > max.numContacts ? item : max), slices[0])
})

const peakSliceText = computed(() => {
  const slice = peakSlice.value
  if (!slice) {
    return '--:-- - --:-- # --'
  }
  const begin = new Date(slice.beginning)
  const end = new Date(slice.ending)
  const fmt = (date: Date) => `${String(date.getHours()).padStart(2, '0')}:00`
  return `${fmt(begin)} - ${fmt(end)} # ${slice.numContacts}`
})

/** 昨日快照文案 */
const yesterdayText = computed(() => {
  const data = statistic.value?.statistic
  if (!data) {
    return ''
  }
  const aot = undefined === data.AOT ? '--' : Number(data.AOT).toFixed(2)
  return `新增 ${data.DNU ?? 0} / 总数 ${data.TNU} · 日活 ${data.DAU} · 人均 ${aot} 小时`
})

/** 区间趋势：日活柱 + 人均在线时长折线 */
const trendOption = computed<EChartsCoreOption>(() => {
  const points = rangePoints.value

  return {
    grid: { top: 34, right: 46, bottom: 26, left: 48 },
    tooltip: { trigger: 'axis' },
    legend: {
      top: 0,
      right: 0,
      itemWidth: 10,
      itemHeight: 10,
      textStyle: { color: '#94a3b8', fontSize: 10 }
    },
    xAxis: {
      type: 'category',
      data: points.map((item) => item.label),
      axisLine: { lineStyle: { color: '#e2e8f0' } },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 10, interval: points.length > 40 ? 6 : 0 }
    },
    yAxis: [
      {
        type: 'value',
        name: '活跃',
        nameTextStyle: { color: '#94a3b8', fontSize: 10 },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#94a3b8', fontSize: 10 },
        splitLine: { lineStyle: { color: '#f1f5f9' } }
      },
      {
        type: 'value',
        name: '小时',
        nameTextStyle: { color: '#94a3b8', fontSize: 10 },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#94a3b8', fontSize: 10 },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: 'DAU',
        type: 'bar',
        barMaxWidth: 18,
        data: points.map((item) => item.dau),
        itemStyle: { color: '#818cf8', borderRadius: [3, 3, 0, 0] }
      },
      {
        name: '人均在线',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbolSize: 4,
        data: points.map((item) => item.aot),
        itemStyle: { color: '#f59e0b' },
        lineStyle: { width: 2 }
      }
    ]
  }
})

/** 时段分布图：按小时聚合联系人数量 */
const chartOption = computed<EChartsCoreOption>(() => {
  const slices = statistic.value?.statistic.TD ?? []
  const sorted = [...slices].sort((a, b) => a.beginning - b.beginning)
  const labels = sorted.map((item) => {
    const date = new Date(item.beginning)
    return `${String(date.getHours()).padStart(2, '0')}:00`
  })
  const values = sorted.map((item) => item.numContacts)
  const peak = peakSlice.value?.beginning

  return {
    grid: { top: 24, right: 12, bottom: 28, left: 52 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: {
      type: 'category',
      data: labels,
      axisLine: { lineStyle: { color: '#e2e8f0' } },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      name: '联系人',
      nameTextStyle: { color: '#94a3b8', fontSize: 10 },
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 10 },
      splitLine: { lineStyle: { color: '#f1f5f9' } }
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 26,
        data: values.map((value, index) => ({
          value,
          itemStyle: {
            color: sorted[index].beginning === peak ? '#f59e0b' : '#818cf8',
            borderRadius: [3, 3, 0, 0]
          }
        }))
      }
    ]
  }
})

/** 只有拿到时段分布数据后才渲染图表容器 */
const hasTimelineData = computed(() => (statistic.value?.statistic.TD?.length ?? 0) > 0)

const { update: renderTrend } = useChart(trendEl, trendOption)
const { update: renderTimeline } = useChart(chartEl, chartOption)

watch(hasTrendData, async (ready) => {
  if (!ready) {
    return
  }
  // 容器由 v-if 控制，需等 DOM 就绪后再初始化图表
  await nextTick()
  renderTrend()
})

watch(hasTimelineData, async (ready) => {
  if (!ready) {
    return
  }
  await nextTick()
  renderTimeline()
})

/** 拉取区间统计 */
async function loadRange(): Promise<void> {
  if (!domain.value) {
    return
  }

  try {
    range.value = await fetchStatisticRange(domain.value, days.value)
  } catch (error) {
    range.value = null
    errorMessage.value =
      error instanceof Error ? error.message : `加载域「${domain.value}」区间统计失败`
    toast.error(errorMessage.value)
  }
}

/** 拉取昨日快照 */
async function loadRecent(): Promise<void> {
  if (!domain.value) {
    return
  }

  try {
    statistic.value = await fetchRecentStatistic(domain.value)
  } catch (error) {
    statistic.value = null
    errorMessage.value =
      error instanceof Error ? error.message : `加载域「${domain.value}」统计数据失败`
  }
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    await Promise.all([loadRange(), loadRecent()])
  } finally {
    loading.value = false
  }
}

async function bootstrap(): Promise<void> {
  loading.value = true
  try {
    const response = await fetchDomains()
    domains.value = pickList(response)
    if (domains.value.length > 0) {
      domain.value = domains.value.includes(DEFAULT_DOMAIN) ? DEFAULT_DOMAIN : domains.value[0]
      await load()
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '加载域列表失败'
  } finally {
    loading.value = false
  }
}

watch([domain, days], () => {
  void load()
})

onMounted(bootstrap)
</script>

<template>
  <div>
    <PageHeader
      title="联系人概览"
      description="按域查看用户规模、活跃度与在线时长；区间视图承担周、月、年维度"
    >
      <template #actions>
        <select v-model="domain" class="select w-52" :disabled="domains.length === 0">
          <option v-if="domains.length === 0" value="">暂无可用域</option>
          <option v-for="item in domains" :key="item" :value="item">{{ item }}</option>
        </select>
        <select v-model="mode" class="select w-32">
          <option value="7d">近 7 天</option>
          <option value="30d">近 30 天</option>
          <option value="90d">近 90 天</option>
          <option value="ytd">本年</option>
        </select>
        <button type="button" class="btn btn-ghost" :disabled="loading" @click="load">
          <AppIcon name="refresh" :size="14" :class="loading ? 'animate-spin' : ''" />
          刷新
        </button>
      </template>
    </PageHeader>

    <div
      v-if="range"
      class="mb-4 flex flex-wrap items-center gap-x-5 gap-y-1 text-xs text-slate-500"
    >
      <span class="tabular">统计区间：{{ rangeText }}</span>
      <span>数据域：{{ domain }}</span>
      <span class="text-slate-400">有数据 {{ summary?.days ?? 0 }} 天</span>
      <span v-if="summary && summary.days === 0" class="text-amber-600">
        该区间暂无统计结果（服务可能未运行或尚未采集）
      </span>
    </div>

    <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <StatCard label="联系人总数（期末）" :value="tnuText" icon="users" tone="brand" :hint="tnuHint" />
      <StatCard label="区间活跃（去重）" :value="uvText" icon="activity" tone="emerald" :hint="uvHint" />
      <StatCard
        label="人均在线时长"
        :value="aotText"
        unit="小时"
        icon="gauge"
        tone="amber"
        :hint="aotHint"
      />
      <StatCard label="峰值日活" :value="peakText" icon="bolt" tone="rose" :hint="peakHint" />
    </div>

    <section class="card mt-5 overflow-hidden">
      <div class="card-header">
        <div>
          <h2 class="card-title">活跃与在线时长趋势</h2>
          <p class="mt-0.5 text-xs text-slate-400">
            柱为每日活跃用户数，线为当日人均在线时长（小时）
          </p>
        </div>
      </div>
      <div class="card-body">
        <div v-if="hasTrendData" ref="trendEl" class="h-64 w-full" />
        <EmptyState
          v-else
          icon="activity"
          :title="errorMessage || '暂无区间数据'"
          :description="errorMessage ? '' : '统计服务上报数据后，这里会显示每日趋势'"
        />
      </div>
    </section>

    <section class="card mt-5 overflow-hidden">
      <div class="card-header">
        <div>
          <h2 class="card-title">昨日在线时段分布</h2>
          <p class="mt-0.5 text-xs text-slate-400">
            {{ yesterdayText || '按小时聚合的联系人数量，橙色柱为峰值时段' }}
          </p>
        </div>
        <span v-if="peakSlice" class="pill pill-info">
          <AppIcon name="bolt" :size="12" />
          峰值 {{ peakSliceText }}
        </span>
      </div>
      <div class="card-body">
        <div v-if="hasTimelineData" ref="chartEl" class="h-64 w-full" />
        <EmptyState
          v-else
          icon="activity"
          :title="errorMessage || '暂无时段分布数据'"
          :description="errorMessage ? '' : '统计服务上报数据后，这里会显示各时段的活跃情况'"
        />
      </div>
    </section>
  </div>
</template>
