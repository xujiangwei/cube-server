// Run after npm run build with the local mock console server.
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async () => {
 const base=process.env.CONSOLE_TEST_URL || 'http://127.0.0.1:8897';
 const browser=await chromium.launch({channel:'chrome',headless:true});
 const page=await browser.newPage({viewport:{width:1600,height:1100}});await page.clock.install();
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 let checks=0,mode='normal',requests=0;const check=(ok,msg)=>{assert.ok(ok,msg);++checks;};
 const copy=x=>JSON.parse(JSON.stringify(x));
 const source=await (await page.request.get(base+'/server-report?report=PerfReport&name=test-service')).json();
 await page.route('**/server-report?*', async route=>{
  if(!route.request().url().includes('PerfReport'))return route.continue();++requests;const fixture=copy(source);
  if(mode==='error')return route.fulfill({status:500,json:{}});
  if(mode==='absent')return route.fulfill({json:{name:'test'}});
  if(mode==='legacy')delete fixture.report.items.Service;
  if(mode==='stale')fixture.reportAgeMs=31000;
  if(mode==='zero'){
   const e=fixture.report.items.Service.execution;
   for(const v of [e.window,e.total,...e.series])for(const k of Object.keys(v))if(k!=='timestamp')v[k]=/Ms$|Rate$/.test(k)?null:0;
   e.queued=0;e.running=0;e.actions=[];e.modules=[];
  }
  return route.fulfill({json:fixture});
 });
 const panel=page.getByTestId('service-monitor');const refresh=()=>panel.getByRole('button',{name:'刷新',exact:true}).click();
 try{
  await page.goto(base+'/service');await panel.getByText('数据正常',{exact:true}).waitFor();
  check(await panel.getByRole('checkbox',{name:'自动刷新'}).isChecked(),'default automatic refresh');
  check(await panel.locator('canvas').count()===5,'four health/trend charts and topology');
  check((await panel.innerText()).includes('运行累计 12,000 次'),'lifetime counter');
  const topology=page.getByTestId('service-topology');
  check((await topology.innerText()).includes('2 个上游 · 4 个下游'),'many-to-many topology');
  await topology.getByRole('button',{name:'worker-3',exact:true}).click();
  check((await topology.innerText()).includes('连接断开'),'disconnected worker retained');
  await topology.getByRole('button',{name:'worker-2',exact:true}).click();check((await topology.innerText()).includes('排水中'),'draining distinguished');
  check((await page.getByTestId('service-capacity').innerText()).includes('按共享后端与资源组去重'),'capacity authority labeled');
  check((await page.getByTestId('service-jobs').innerText()).includes('等待审批 3'),'job state separated');
  await panel.getByLabel('趋势统计对象').selectOption('Harness');
  check(await panel.locator('canvas').count()===5,'job trends render');
  const actions=page.getByTestId('service-actions');await panel.getByLabel('按模块过滤动作').selectOption('Harness');
  check(await actions.locator('tbody tr').count()===2,'module filter excludes control');
  await actions.getByRole('checkbox',{name:'包含控制命令'}).check();check(await actions.locator('tbody tr').count()===3,'control opt-in');
  await panel.getByLabel('按模块过滤动作').selectOption('');await panel.getByLabel('动作排序').selectOption('avgMs');
  check((await actions.locator('tbody tr').first().innerText()).includes('chat'),'latency ranking');
  const before=requests;await page.clock.fastForward(11000);await panel.getByText('数据正常',{exact:true}).waitFor();check(requests>before,'10 second refresh');
  await panel.getByRole('checkbox',{name:'自动刷新'}).uncheck();await page.clock.fastForward(31000);await panel.getByText('数据已过期',{exact:true}).waitFor();
  check((await topology.innerText()).includes('当前连接状态未知'),'freshness clock invalidates topology');
  mode='normal';await refresh();await panel.getByText('数据正常',{exact:true}).waitFor();
  mode='error';await refresh();await panel.getByRole('alert').waitFor();check((await panel.innerText()).includes('12,000'),'refresh error retains snapshot');
  mode='stale';await refresh();await panel.getByText('数据已过期',{exact:true}).waitFor();++checks;
  mode='zero';await refresh();await panel.getByText('最近一分钟无业务命令。无耗时样本时显示“--”。').waitFor();++checks;
  mode='legacy';await refresh();await panel.getByText('当前节点尚未提供服务执行监控数据',{exact:true}).waitFor();check(!(await panel.innerText()).includes('运行累计 0'),'legacy does not fabricate zero');
  mode='absent';await refresh();await panel.getByText('暂无性能报告',{exact:true}).waitFor();++checks;
  let releaseOld,requestedOld;const oldRequested=new Promise(r=>requestedOld=r),oldHeld=new Promise(r=>releaseOld=r);
  const selector=panel.getByLabel('选择监控服务单元'),oldName=await selector.inputValue();
  const names=await selector.locator('option').evaluateAll(opts=>opts.map(o=>o.value));const nextName=names.find(n=>n!==oldName);
  check(!!nextName,'second deployment fixture');
  await page.route('**/server-report?*',async route=>{
   const url=new URL(route.request().url());if(url.searchParams.get('report')!=='PerfReport')return route.fallback();
   const fixture=copy(source),old=url.searchParams.get('name')===oldName;fixture.report.items.Service.execution.total.requests=old?111111:222222;
   if(old){requestedOld();await oldHeld;}return route.fulfill({json:fixture});
  });
  await refresh();await oldRequested;await selector.selectOption(nextName);await panel.getByText('运行累计 222,222 次',{exact:true}).waitFor();releaseOld();
  await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));check(!(await panel.innerText()).includes('111,111'),'old node query ignored');
  await page.screenshot({path:process.env.SERVICE_SCREENSHOT || '../.tmp/service-monitor/service-desktop.png',fullPage:true});
  await page.setViewportSize({width:390,height:844});await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'mobile document no overflow');
  await page.screenshot({path:process.env.SERVICE_MOBILE_SCREENSHOT || '../.tmp/service-monitor/service-mobile.png',fullPage:true});
  check(errors.length===0,'no browser errors: '+errors.join('; '));console.log('ServiceMonitor browser: '+checks+' checks passed');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
