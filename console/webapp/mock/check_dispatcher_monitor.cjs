// Run against mock_console_server.py after npm run build. Requires Playwright + Chrome.
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

(async () => {
  const base = process.env.CONSOLE_TEST_URL || 'http://127.0.0.1:8897';
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } });
  await page.clock.install();
  let checks = 0, mode = 'normal', requests = 0;
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const check = (condition, message) => { assert.ok(condition, message); ++checks; };
  const source = await (await page.request.get(base + '/server-report?report=PerfReport&name=cube%23dispatcher%236800')).json();
  const copy = value => JSON.parse(JSON.stringify(value));
  await page.route('**/server-report?*', async route => {
    if (!route.request().url().includes('PerfReport')) return route.continue();
    ++requests;
    const fixture = copy(source);
    if (mode === 'error') return route.fulfill({ status: 500, json: {} });
    if (mode === 'absent') return route.fulfill({ json: { name: 'test' } });
    if (mode === 'legacy') delete fixture.report.items.Dispatcher;
    if (mode === 'stale') fixture.reportAgeMs = 31_000;
    if (mode === 'zero') {
      const http = fixture.report.items.Dispatcher.http;
      for (const metric of [http.window, http.total, ...http.series]) {
        for (const key of Object.keys(metric)) if (typeof metric[key] === 'number' && key !== 'timestamp') metric[key] = 0;
        for (const key of ['avgMs', 'p95Ms', 'streamAvgMs', 'firstByteAvgMs', 'failureRate']) metric[key] = null;
        Object.keys(metric.statusCodes).forEach(key => { metric.statusCodes[key] = 0; });
      }
      http.inFlight = 0; http.modules = []; http.routes = [];
    }
    return route.fulfill({ json: fixture });
  });
  const panel = page.getByTestId('dispatcher-monitor');
  const refresh = () => panel.getByRole('button', { name: '刷新', exact: true }).click();
  try {
    await page.goto(base + '/dispatcher');
    await panel.getByText('数据正常', { exact: true }).waitFor();
    check(await panel.getByRole('checkbox', { name: '自动刷新' }).isChecked(), 'auto refresh enabled by default');
    check((await panel.innerText()).includes('360,000'), 'lifetime requests rendered');
    check(await panel.locator('canvas').count() === 3, 'two trend charts and graph rendered');
    check(await panel.getByRole('button', { name: '10.0.0.9:7001', exact: true }).count() === 1, 'third peer with same AIGC module retained');
    const topology = page.getByTestId('dispatcher-topology');
    const graph = await topology.locator('canvas').boundingBox();
    await topology.locator('canvas').click({ position: { x: graph.width - 95, y: graph.height - 50 } });
    check(await topology.getByRole('button', { name: '10.0.0.9:7001', exact: true }).getAttribute('aria-pressed') === 'true', 'graph node click selects disconnected peer');
    check((await topology.innerText()).includes('连接断开'), 'disconnected endpoint shown');
    await panel.getByLabel('按服务模块过滤接口').selectOption('Harness');
    check(await panel.locator('section').last().locator('tbody tr').count() === 2, 'module filter applies to routes');
    check((await panel.locator('section').last().innerText()).includes('12,480.5 ms'), 'SSE independent durations visible');
    await panel.getByLabel('接口排序').selectOption('avgMs');
    check((await panel.locator('section').last().locator('tbody tr').first().innerText()).includes('/harness/v1/runs'), 'latency sorting keeps sampled ordinary requests first');
    await panel.getByLabel('按服务模块过滤接口').selectOption('');
    await panel.getByLabel('模块排行排序').selectOption('avgMs');
    check((await panel.locator('section').nth(1).locator('tbody tr').first().innerText()).includes('AIGC'), 'module latency ranking');
    const before = requests;
    await page.clock.fastForward(11_000);
    await page.waitForResponse(response => response.url().includes('PerfReport'));
    check(requests > before, '10 second automatic refresh polls');
    await panel.getByRole('checkbox', { name: '自动刷新' }).uncheck();
    await page.clock.fastForward(31_000);
    await panel.getByText('数据已过期', { exact: true }).waitFor();
    check((await topology.innerText()).includes('当前连接状态未知'), 'age advances without report and live readiness becomes unknown');
    mode = 'normal'; await refresh(); await panel.getByText('数据正常', { exact: true }).waitFor();
    mode = 'error'; await refresh(); await panel.getByRole('alert').waitFor();
    check((await panel.innerText()).includes('360,000'), 'failed refresh retains last successful metrics');
    mode = 'stale'; await refresh(); await panel.getByText('数据已过期', { exact: true }).waitFor();
    ++checks;
    mode = 'zero'; await refresh(); await panel.getByText('最近一分钟无请求。无耗时样本时显示“--”。').waitFor();
    check((await panel.innerText()).includes('--'), 'no samples display missing latency');
    mode = 'legacy'; await refresh(); await panel.getByText('当前节点尚未提供 REST 监控数据', { exact: true }).waitFor();
    check((await topology.innerText()).includes('未获实时状态'), 'legacy configured peers use unknown state');
    mode = 'absent'; await refresh(); await panel.getByText('暂无性能报告', { exact: true }).waitFor(); ++checks;

    // Hold an old node query until after the new selection finishes.
    let releaseOld, requestedOld;
    const oldRequested = new Promise(resolve => { requestedOld = resolve; });
    const oldHeld = new Promise(resolve => { releaseOld = resolve; });
    await page.route('**/server-report?*', async route => {
      const url = new URL(route.request().url());
      if (url.searchParams.get('report') !== 'PerfReport') return route.fallback();
      const fixture = copy(source);
      const old = url.searchParams.get('name') === 'cube#dispatcher#6800';
      fixture.report.items.Dispatcher.http.total.requests = old ? 111111 : 222222;
      if (old) { requestedOld(); await oldHeld; }
      await route.fulfill({ json: fixture });
    });
    await refresh(); await oldRequested;
    await panel.getByLabel('选择监控调度机').selectOption('cube#dispatcher#6800-bak');
    await panel.getByText('运行累计 222,222 次', { exact: true }).waitFor();
    const oldReceived = page.waitForResponse(response => response.url().includes('name=cube%23dispatcher%236800&'));
    releaseOld(); await oldReceived;
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    check((await panel.innerText()).includes('222,222') && !(await panel.innerText()).includes('111,111'), 'late response cannot overwrite new dispatcher');

    await page.setViewportSize({ width: 390, height: 844 });
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'narrow screen has no document overflow');
    check(errors.length === 0, 'no browser errors: ' + errors.join('; '));
    console.log('DispatcherMonitor browser: ' + checks + ' checks passed');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
