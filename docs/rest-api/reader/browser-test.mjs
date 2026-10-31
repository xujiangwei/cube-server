import {chromium} from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import assert from 'node:assert/strict';
import {fileURLToPath,pathToFileURL} from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const output=path.join(root,'output');
const artifacts=process.env.CUBE_DOCS_TEST_OUTPUT || path.resolve(root,'../../..','.tmp/rest-api-reader-test');
fs.mkdirSync(artifacts,{recursive:true});
const server=http.createServer((request,response)=>{
  const url=new URL(request.url,'http://localhost');
  const baseline=url.pathname.startsWith('/baseline/');
  const directory=baseline?process.env.CUBE_DOCS_BASELINE:output;
  const prefix=baseline?'/baseline/':'/doc/api/';
  if(!directory || !url.pathname.startsWith(prefix)) {response.writeHead(404).end();return;}
  let name;
  try{name=decodeURIComponent(url.pathname.slice(prefix.length))||'index.html';}catch{response.writeHead(400).end();return;}
  const file=path.resolve(directory,name);
  if(!file.startsWith(path.resolve(directory)+path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {response.writeHead(404).end();return;}
  const type={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json'}[path.extname(file)] || 'application/octet-stream';
  response.writeHead(200,{'Content-Type':type});response.end(fs.readFileSync(file));
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const localAddress=`http://127.0.0.1:${server.address().port}`;
const address=process.env.CUBE_DOCS_BASE_URL || localAddress;
const executable=process.env.CUBE_DOCS_BROWSER || ['C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(file=>fs.existsSync(file));
const browser=await chromium.launch({headless:true,...(executable?{executablePath:executable}:{})});
const page=await browser.newPage({viewport:{width:1440,height:1000}});
await page.addInitScript(()=>{
  Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async text=>{window.__testCopiedText=text;}}});
});
const errors=[];page.on('pageerror',error=>errors.push(error.message));
const contentRequests=[];page.on('request',request=>{if(request.url().includes('/content/'))contentRequests.push(request.url());});
const metrics={};
const endpoint='api-SpecializedDetection-SpecializedDetectionImage';
const stream='api-SpecializedDetection-SpecializedDetectionStream';
async function ready(id=endpoint) {
  await page.waitForFunction(id=>document.querySelector('#main article')?.dataset.apiId===id,id);
  await page.waitForFunction(()=>document.querySelector('#main').getAttribute('aria-busy')==='false');
}
async function scrollMetrics() {
  return page.evaluate(async()=>{
    const intervals=[];let previous=performance.now();
    for(let i=0;i<60;i++) {
      await new Promise(requestAnimationFrame);
      const now=performance.now();intervals.push(now-previous);previous=now;
      window.scrollTo(0,(i+1)*42);
    }
    window.scrollTo(0,0);
    return {p95FrameMs:intervals.sort((a,b)=>a-b)[Math.floor(intervals.length*.95)],nodes:document.querySelectorAll('*').length,articles:document.querySelectorAll('article').length};
  });
}
try {
  if(process.env.CUBE_DOCS_BASELINE) {
    await page.goto(localAddress+'/baseline/index.html#'+endpoint,{waitUntil:'networkidle'});
    metrics.baseline=await scrollMetrics();
  }
  await page.goto(address+'/doc/api/index.html',{waitUntil:'networkidle'});
  assert.equal(await page.locator('#main article').count(),0);
  assert.equal(contentRequests.length,0,'home must not download interface bodies');
  assert.equal(await page.locator('.group-card').count(),14);
  assert.equal(await page.locator('a[href="README.html"], a[href="SpecializedDetection.html"]').count(),0);
  await page.screenshot({path:path.join(artifacts,'home.png'),fullPage:true});
  await page.goto(address+'/doc/api/index.html#'+endpoint);
  await ready();
  assert.equal(await page.locator('#main article').count(),1);
  assert.equal(contentRequests.length,1);
  assert(await page.locator('#main').innerText().then(text=>text.includes('UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx')));
  assert.equal(await page.locator('#version-select').count(),0,'single versions do not need a picker');
  await page.locator('[data-copy="path"]').click();
  assert.equal(await page.evaluate(()=>window.__testCopiedText),'/specialized-detection/base/:token');
  const firstCode=await page.locator('.code-block code').first().textContent();
  await page.locator('[data-copy="code"]').first().click();
  assert.equal(await page.evaluate(()=>window.__testCopiedText),firstCode);
  metrics.reader=await scrollMetrics();
  assert(metrics.reader.nodes<metrics.baseline?.nodes || !metrics.baseline);
  const input=page.locator('#api-search');
  await input.fill('specialized');
  await page.waitForFunction(()=>document.querySelectorAll('#search-results li').length===2);
  await input.press('ArrowDown');await input.press('ArrowDown');
  assert.equal(await input.getAttribute('aria-activedescendant'),'search-result-1');
  const destination=await page.locator('#search-result-1 a').getAttribute('href');
  await input.press('Enter');await ready(destination.slice(1));
  assert.equal(await page.locator('#search-panel').isVisible(),false);
  await input.fill('callbackUrl specialized');
  await page.waitForFunction(()=>document.querySelectorAll('#search-results li').length===1);
  assert.equal(await page.locator('#search-results a').getAttribute('href'),'#'+stream);
  const oldStatus=await page.locator('#search-status').textContent();
  await input.dispatchEvent('compositionstart');await input.fill('专项');
  await page.waitForTimeout(160);
  assert.equal(await page.locator('#search-status').textContent(),oldStatus,'IME composition must not search half-finished input');
  await input.dispatchEvent('compositionend');
  await page.waitForFunction(()=>document.querySelectorAll('#search-results li').length===2);
  await input.fill('NO_SUCH_API_928347');
  await page.waitForFunction(()=>document.querySelector('#search-status').textContent.includes('没有匹配'));
  await input.press('Escape');assert.equal(await page.locator('#navigation').isVisible(),true);
  const locationBeforeEmptyEnter=page.url();await input.focus();await input.press('Enter');
  assert.equal(page.url(),locationBeforeEmptyEnter,'empty search must not reopen stale results');
  await input.fill('/harness');
  await page.waitForFunction(()=>document.querySelectorAll('#search-results li').length>0);
  await page.locator('#method-filter').selectOption('GET');
  assert((await page.locator('#search-results .method').allTextContents()).every(x=>x==='GET'));
  await input.press('Escape');
  // Search timings use the full index, including names, paths, Chinese and parameter fields.
  metrics.search=await page.evaluate(()=>{
    const index=CubeApiSearch.createIndex(CubeApiCatalog.entries),durations=[];
    for(let i=0;i<100;i++){const start=performance.now();CubeApiSearch.search(index,['callbackUrl','POST detection','查询','/harness'][i%4]);durations.push(performance.now()-start);}
    return {p95IndexMs:durations.sort((a,b)=>a-b)[95]};
  });
  const inputDurations=[];
  for(const query of ['callbackUrl specialized','/specialized-detection/base','GET /harness','NO_SUCH_API_928347','POST detection','OmniGround','uav-fire-smoke','NO_SUCH_API_28476']) {
    inputDurations.push(await page.evaluate(async query=>{
      const input=document.querySelector('#api-search'),status=document.querySelector('#search-status');
      const start=performance.now();
      const complete=new Promise(resolve=>{const observer=new MutationObserver(()=>{observer.disconnect();resolve(performance.now()-start);});observer.observe(status,{childList:true,subtree:true,characterData:true});});
      input.value=query;input.dispatchEvent(new Event('input',{bubbles:true}));return complete;
    },query));
  }
  metrics.search.p95InputToResultsMs=inputDurations.sort((a,b)=>a-b)[inputDurations.length-1];
  assert(metrics.search.p95InputToResultsMs<200,'input-to-result delay should stay below 200ms on the test machine');
  await input.press('Escape');
  await page.goto(address+'/doc/api/index.html#'+endpoint+'~body');await ready();
  await page.waitForFunction(()=>document.getElementById('body').getBoundingClientRect().top<120);
  await page.locator('#navigation a[href="#'+stream+'"]').click();await ready(stream);
  await page.goBack();await ready(endpoint);
  await page.waitForFunction(()=>document.getElementById('body').getBoundingClientRect().top<120);
  await page.reload();await ready(endpoint);
  await page.locator('#navigation a[href="#'+stream+'"]').click();await ready(stream);
  const lazy=page.locator('details.lazy-example').first();
  assert.equal(await lazy.locator('pre').count(),0);
  await lazy.locator('summary').click();await lazy.locator('pre').waitFor();
  assert.equal(await lazy.locator('pre').count(),1);
  await lazy.locator('summary').click();await lazy.locator('summary').click();
  assert.equal(await lazy.locator('pre').count(),1,'reopening does not duplicate examples');
  await page.evaluate(()=>window.dispatchEvent(new Event('beforeprint')));
  assert.equal(await page.locator('details.lazy-example template').count(),0,'printing materializes all current examples synchronously');
  await page.evaluate(()=>window.dispatchEvent(new Event('afterprint')));
  await page.evaluate(()=>window.scrollTo(0,0));
  await page.screenshot({path:path.join(artifacts,'interface-top.png')});
  await page.screenshot({path:path.join(artifacts,'interface.png'),fullPage:true});
  const switchDurations=[];
  for(let i=0;i<10;i++) {
    switchDurations.push(await page.evaluate(async id=>{
      const start=performance.now(),main=document.querySelector('#main');
      const done=new Promise(resolve=>{const observer=new MutationObserver(()=>{if(main.querySelector('article')?.dataset.apiId===id && main.getAttribute('aria-busy')==='false'){observer.disconnect();resolve(performance.now()-start);}});observer.observe(main,{childList:true,subtree:true,attributes:true,attributeFilter:['aria-busy']});});
      location.hash=id;return done;
    },i%2===0?endpoint:stream));
  }
  metrics.reader.p95CachedSwitchMs=switchDurations.sort((a,b)=>a-b)[9];
  await page.goto(address+'/doc/api/index.html#api-File_Processing-'+encodeURIComponent('上传文件数据')+'-1.0.0');
  await ready('api-File_Processing-上传文件数据');
  metrics.reader.maxNodesAcrossAllInterfaces=await page.evaluate(async()=>{
    let maximum=0;
    for(const entry of CubeApiCatalog.entries) {
      if(document.querySelector('article')?.dataset.apiId!==entry.id) {
        const ready=new Promise((resolve,reject)=>{
          const timer=setTimeout(()=>{observer.disconnect();reject(new Error('Failed to render '+entry.id));},8000);
          const observer=new MutationObserver(()=>{if(document.querySelector('#main article')?.dataset.apiId===entry.id && document.querySelector('#main').getAttribute('aria-busy')==='false'){clearTimeout(timer);observer.disconnect();resolve();}});
          observer.observe(document.querySelector('#main'),{childList:true,subtree:true,attributes:true,attributeFilter:['aria-busy']});
        });
        location.hash=entry.id;await ready;
      }
      if(document.querySelectorAll('#main article').length!==1) throw new Error('Multiple active interfaces');
      maximum=Math.max(maximum,document.querySelectorAll('*').length);
    }
    return maximum;
  });
  assert(metrics.reader.maxNodesAcrossAllInterfaces<10000,'browsing the entire catalog must keep DOM bounded');
  await page.goto(address+'/doc/api/index.html#api-SpecializedDetection');
  await page.locator('.api-card').first().waitFor();
  assert.equal(await page.locator('.api-card').count(),2);
  await page.goto(address+'/doc/api/print.html');assert.equal(await page.locator('article').count(),101);
  await page.emulateMedia({media:'print'});assert.equal(await page.locator('#print-button').isVisible(),false);await page.emulateMedia({media:'screen'});
  await page.setViewportSize({width:390,height:844});
  await page.goto(address+'/doc/api/index.html#'+endpoint);await ready();
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  await page.locator('[data-toggle-nav]').click();assert.equal(await page.locator('[data-toggle-nav]').getAttribute('aria-expanded'),'true');
  await input.fill('callbackUrl specialized');await page.waitForFunction(()=>document.querySelectorAll('#search-results li').length===1);
  await page.locator('#search-results a').click();await ready(stream);
  assert.equal(await page.locator('[data-toggle-nav]').getAttribute('aria-expanded'),'false');
  await page.screenshot({path:path.join(artifacts,'mobile.png'),fullPage:true});
  // Content chunks also work when users open the built index directly from disk.
  await page.goto(pathToFileURL(path.join(output,'index.html')).href+'#'+endpoint);await ready();
  assert.equal(await page.locator('#main article').count(),1);
  assert.deepEqual(errors,[]);
  fs.writeFileSync(path.join(artifacts,'metrics.json'),JSON.stringify(metrics,null,2));
  console.log('Browser checks passed: navigation, search/IME/keyboard, lazy examples, history/reload, print, mobile, file://.');
  console.log(JSON.stringify(metrics,null,2));
  console.log('Screenshots: '+artifacts);
} finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
