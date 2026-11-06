/**
 * 轮询封装。
 *
 * 统一处理定时器、组件卸载清理，以及页面切到后台时暂停请求，
 * 替代旧版散落在各页面的 `setInterval` + 手工 `clearInterval`。
 */

import { onBeforeUnmount, onMounted, ref } from 'vue'

interface PollingOptions {
  /** 挂载后是否立即执行一次，默认 true */
  immediate?: boolean
  /** 页面隐藏时是否暂停，默认 true */
  pauseOnHidden?: boolean
  /** 是否在服务端返回 429 时静默退避，默认 false */
  backoffOnTooManyRequests?: boolean
}

/** 退避阶梯：命中 429 后按序拉长轮询间隔（基础间隔为 60 秒） */
const BACKOFF_STEPS = [120_000, 240_000, 300_000, 600_000]

/** 判断错误是否为服务端限流（HTTP 429） */
function isTooManyRequests(error: unknown): boolean {
  const status = (error as { status?: number } | null)?.status
  return status === 429
}

export function usePolling(
  task: () => void | Promise<void>,
  intervalMs: number,
  options: PollingOptions = {}
) {
  const { immediate = true, pauseOnHidden = true, backoffOnTooManyRequests = false } = options

  let timer = 0
  let currentInterval = intervalMs
  let backoffIndex = -1
  const running = ref(false)

  const tick = async (): Promise<void> => {
    if (running.value) {
      // 上一次请求尚未结束，跳过本次，避免请求堆积
      return
    }
    running.value = true
    try {
      await task()
      // 成功即回落：下一次恢复基础间隔
      if (backoffIndex >= 0) {
        backoffIndex = -1
        currentInterval = intervalMs
        schedule()
      }
    } catch (error) {
      if (backoffOnTooManyRequests && isTooManyRequests(error)) {
        // 限流是服务端在保护自己，静默退避，不弹错误提示
        backoffIndex = Math.min(backoffIndex + 1, BACKOFF_STEPS.length - 1)
        currentInterval = BACKOFF_STEPS[backoffIndex]
        schedule()
      } else {
        throw error
      }
    } finally {
      running.value = false
    }
  }

  const schedule = (): void => {
    stop()
    timer = window.setInterval(() => {
      void tick()
    }, currentInterval)
  }

  const stop = (): void => {
    if (timer) {
      window.clearInterval(timer)
      timer = 0
    }
  }

  const onVisibilityChange = (): void => {
    if (document.hidden) {
      stop()
    } else {
      schedule()
      void tick()
    }
  }

  onMounted(() => {
    if (immediate) {
      void tick()
    }
    schedule()
    if (pauseOnHidden) {
      document.addEventListener('visibilitychange', onVisibilityChange)
    }
  })

  onBeforeUnmount(() => {
    stop()
    if (pauseOnHidden) {
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  })

  return { start: schedule, stop, tick, running }
}
