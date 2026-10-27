<script setup lang="ts">
import { computed, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import type { UnitCapability, UnitDeploymentGroup, UnitDeploymentsResponse } from '@/api/types'
import { formatTimeMDHMS } from '@/utils/format'

const props = defineProps<{
  snapshot: UnitDeploymentsResponse | null
  loading: boolean
  errorMessage: string
}>()

const search = ref('')
const groups = computed(() => props.snapshot?.groups ?? [])
const visibleGroups = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return groups.value.filter((group) => group.name.toLocaleLowerCase().includes(query))
})
const deploymentCount = computed(() => groups.value.reduce((sum, group) => sum + group.deploymentCount, 0))
const subtasksOf = (capability: UnitCapability): string[] =>
  capability.subtask ? [capability.subtask] : capability.subtasks ?? []

function summaryOf(group: UnitDeploymentGroup): string {
  const tasks = [...new Set(group.capabilities.map((capability) => capability.task).filter(Boolean))]
  const versions = [...new Set(group.capabilities.map((capability) => capability.version).filter(Boolean))]
  return [...tasks, ...versions.map((version) => `v${version}`)].join(' · ') || '--'
}

const timeOf = (time: number) => time > 0 ? formatTimeMDHMS(time) : '--'
</script>

<template>
  <div :aria-busy="loading" class="space-y-4">
    <div class="card flex flex-wrap items-center justify-between gap-4 px-5 py-4">
      <div>
        <p class="text-sm font-medium text-slate-800">
          {{ snapshot ? groups.length : '--' }} 个单元名称
          <span class="mx-2 text-slate-300">/</span>
          {{ snapshot ? deploymentCount : '--' }} 个同名部署
          <span v-if="snapshot?.truncated" class="ml-2 text-xs text-amber-700">（部分数据）</span>
        </p>
        <p class="mt-1 text-xs leading-5 text-slate-500">
          按当前有效上报统计，同名单元下每个 Contact 计为一个部署；同一 Contact 可承载多个不同名称的单元。
        </p>
        <p v-if="snapshot" class="mt-1 text-xs text-slate-400">
          查询于 {{ timeOf(snapshot.queryTime) }} · 最近有效上报 {{ timeOf(snapshot.unitReportTime) }}
        </p>
      </div>
      <label class="flex items-center gap-2 text-xs text-slate-500">
        单元名称
        <input v-model="search" type="search" class="input w-56" placeholder="搜索 Baize、Baize2…" />
      </label>
    </div>

    <p class="px-1 text-xs leading-5 text-slate-500">
      上报约每分钟更新，超过 5 分钟未上报的节点退出列表。接入 IP 为服务端观察到的连接地址，能力为单元声明信息。
    </p>
    <div v-if="snapshot?.truncated" role="status" class="rounded-xl bg-amber-50 px-4 py-3 text-sm text-amber-800 ring-1 ring-amber-200">
      节点上报达到条目上限，当前仅显示部分部署，数量并非全部部署数。
    </div>

    <section v-if="errorMessage" class="card" role="alert">
      <EmptyState icon="cube" :title="errorMessage" description="可使用页面右上角的刷新按钮重试。" />
    </section>
    <section v-else-if="loading && !snapshot" class="card">
      <EmptyState icon="cube" title="正在加载单元部署…" />
    </section>
    <section v-else-if="groups.length === 0" class="card">
      <EmptyState icon="cube" title="该域暂无有效部署上报" description="单元接入并完成节点上报后会显示在这里。" />
    </section>
    <section v-else-if="visibleGroups.length === 0" class="card">
      <EmptyState icon="cube" title="没有匹配的单元名称" description="请调整名称搜索条件。" />
    </section>

    <details v-for="group in visibleGroups" v-else :key="group.name" class="card group overflow-hidden">
      <summary class="flex cursor-pointer list-none flex-wrap items-center gap-3 px-5 py-4 transition hover:bg-slate-50 [&::-webkit-details-marker]:hidden">
        <AppIcon name="chevronDown" :size="16" class="shrink-0 text-slate-400 transition-transform group-open:rotate-180" />
        <span class="min-w-0 flex-1 break-words text-sm font-semibold text-slate-800 sm:min-w-32 sm:flex-none">{{ group.name }}</span>
        <span class="pill pill-neutral tabular">{{ group.deploymentCount }} 个部署</span>
        <span class="order-last basis-full break-words pl-7 text-xs text-slate-500 sm:order-none sm:min-w-0 sm:flex-1 sm:basis-auto sm:pl-0">{{ summaryOf(group) }}</span>
        <span class="hidden text-xs text-slate-400 sm:inline">部署明细</span>
      </summary>
      <div class="overflow-x-auto border-t border-slate-100">
        <table class="data-table table-fixed min-w-[66rem]">
          <thead>
            <tr>
              <th class="w-40">部署身份</th>
              <th class="w-36">接入 IP</th>
              <th class="w-40">设备 / 平台</th>
              <th class="w-48">所属服务节点</th>
              <th class="w-36">最近上报</th>
              <th>能力信息</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="deployment in group.deployments" :key="deployment.id">
              <td class="align-top">
                <p class="break-words font-medium text-slate-700">{{ deployment.name || '--' }}</p>
                <p class="tabular mt-1 text-xs text-slate-500">Contact ID: {{ deployment.id }}</p>
              </td>
              <td class="tabular align-top text-xs text-slate-600">
                <p v-for="address in deployment.addresses" :key="address" class="break-all leading-5">{{ address }}</p>
                <span v-if="deployment.addresses.length === 0">--</span>
              </td>
              <td class="align-top text-xs text-slate-600">
                <p class="break-words">{{ deployment.device || '--' }}</p>
                <p class="mt-1 break-words text-slate-400">{{ deployment.platform || '--' }}</p>
              </td>
              <td class="align-top text-xs text-slate-600">
                <p v-for="reporter in deployment.reporters" :key="reporter" class="break-all leading-5">{{ reporter }}</p>
                <span v-if="deployment.reporters.length === 0">--</span>
              </td>
              <td class="tabular whitespace-nowrap align-top text-xs text-slate-600">{{ timeOf(deployment.reportedAt) }}</td>
              <td class="align-top">
                <div class="space-y-3">
                  <div v-for="(capability, index) in deployment.capabilities" :key="index" class="rounded-lg bg-slate-50 px-3 py-2 text-xs leading-5 text-slate-600">
                    <p class="font-medium text-slate-800">{{ capability.name }} · {{ capability.version ? `v${capability.version}` : '--' }}</p>
                    <p class="break-words">任务类型：{{ capability.task || '--' }}</p>
                    <p class="break-words">子任务：{{ subtasksOf(capability).join(' / ') || '--' }}</p>
                    <p class="break-words">描述：{{ capability.description || '--' }}</p>
                    <details v-if="capability.chatCompletion && Object.keys(capability.chatCompletion).length > 0" class="mt-1">
                      <summary class="cursor-pointer text-sky-700">已声明的协议能力</summary>
                      <pre class="mt-1 whitespace-pre-wrap break-all rounded bg-white p-2 text-[11px]">{{ JSON.stringify(capability.chatCompletion, null, 2) }}</pre>
                    </details>
                    <p v-else class="text-slate-400">协议能力：--</p>
                  </div>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
  </div>
</template>
