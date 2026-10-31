(function () {
  'use strict';
  const catalog = window.CubeApiCatalog;
  const entries = new Map(catalog.entries.map(entry => [entry.id,entry]));
  const groups = new Map(catalog.groups.map(group => [group.id,group]));
  const index = window.CubeApiSearch.createIndex(catalog.entries);
  const $ = selector => document.querySelector(selector);
  const main = $('#main'), nav = $('#navigation'), sidebarScroll = $('.sidebar-scroll');
  const input = $('#api-search'), resultList = $('#search-results'), panel = $('#search-panel');
  const methodFilter = $('#method-filter'), groupFilter = $('#group-filter');
  const cache = new Map(), requests = new Map(), navItems = new Map();
  const escape = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
  let routeSequence = 0, currentEntry = null, currentDetail = null, sectionObserver;
  let searchTimer, composing = false, resultIndex = -1, matches = [], searchActive = false, navScroll = 0;
  let toastTimer, focusAfterRoute = false;
  const positions = new Map();
  let lastLocation = '';

  window.CubeApiDocs = {define(id,detail) {
    if (entries.has(id) && detail.id === id) cache.set(id,detail);
  }};
  function load(entry) {
    if (cache.has(entry.id)) return Promise.resolve(cache.get(entry.id));
    if (requests.has(entry.id)) return requests.get(entry.id);
    const promise = new Promise((resolve,reject)=>{
      const script = document.createElement('script');
      const timeout = window.setTimeout(()=>finish(new Error('接口内容加载超时')),15000);
      function finish(error) {
        clearTimeout(timeout); script.remove();
        if(error || !cache.has(entry.id)) { requests.delete(entry.id); reject(error || new Error('接口内容不完整')); }
        else resolve(cache.get(entry.id));
      }
      script.src = entry.file;
      script.onload = ()=>finish();
      script.onerror = ()=>finish(new Error('无法加载接口内容，请检查连接后重试'));
      document.head.appendChild(script);
    });
    requests.set(entry.id,promise);
    return promise;
  }
  function method(entry) { return `<span class="method ${entry.method.toLowerCase()}">${escape(entry.method)}</span>`; }
  function card(entry) { return `<a class="api-card" href="#${escape(entry.id)}"><div>${method(entry)}<h3>${escape(entry.title)}</h3><span aria-hidden="true">↗</span></div><code>${escape(entry.url)}</code><p>${escape(entry.summary || '查看参数、调用示例和响应说明。')}</p></a>`; }
  function renderNav() {
    nav.innerHTML = '<a class="nav-home" href="#">文档总览</a>'+catalog.groups.map(group=>`<details class="nav-group" data-group="${escape(group.id)}"><summary><span>${escape(group.title)}</span><span class="count">${group.count}</span></summary><div><a class="group-overview" href="#api-${escape(group.id)}">查看分组概览</a>${catalog.entries.filter(entry=>entry.group===group.id).map(entry=>`<a class="nav-entry" href="#${escape(entry.id)}" data-entry="${escape(entry.id)}">${method(entry)}<span>${escape(entry.title)}</span></a>`).join('')}</div></details>`).join('');
    for(const node of nav.querySelectorAll('[data-entry]')) navItems.set(node.dataset.entry,node);
    methodFilter.insertAdjacentHTML('beforeend',[...new Set(catalog.entries.map(x=>x.method))].sort().map(m=>`<option>${m}</option>`).join(''));
    groupFilter.insertAdjacentHTML('beforeend',catalog.groups.map(g=>`<option value="${escape(g.id)}">${escape(g.title)}</option>`).join(''));
  }
  function breadcrumbs(group,entry) {
    $('#breadcrumbs').innerHTML = `<a href="#">文档总览</a>${group ? `<span>/</span><a href="#api-${escape(group.id)}">${escape(group.title)}</a>` : ''}${entry ? `<span>/</span><span aria-current="page">${escape(entry.title)}</span>` : ''}`;
    $('#permalink').hidden = !entry;
    $('#print-current').hidden = !entry;
  }
  function home() {
    breadcrumbs();
    main.innerHTML = `<section class="hero"><p class="eyebrow">BAIZE · DEVELOPER REFERENCE</p><h1>从一个接口开始。</h1><p>查找接口、查看请求与响应，快速完成服务接入。</p><div class="hero-links"><button class="primary-link" type="button" data-focus-search>搜索全部接口 <kbd>Ctrl K</kbd></button></div><div class="doc-stats"><span><strong>${catalog.entries.length}</strong> 个接口</span><span><strong>${catalog.groups.length}</strong> 个分组</span><span>v${escape(catalog.project.version)}</span></div></section><section class="home-section"><div class="section-heading"><h2>接入步骤</h2><span>选择接口前，先确认这些约定</span></div><div class="steps"><div><b>01</b><h3>确认服务地址</h3><p>业务接口使用 dispatcher 地址；Console 与其他服务地址分别配置。</p></div><div><b>02</b><h3>准备认证与文件</h3><p>按接口说明获取令牌；文件上传完成后，使用返回的文件码。</p></div><div><b>03</b><h3>处理请求与响应</h3><p>参考完整示例，核对响应结构、错误状态和异步处理方式。</p></div></div></section><section class="home-section"><div class="section-heading"><h2>接口目录</h2><span>按业务场景浏览</span></div><div class="group-grid">${catalog.groups.map(group=>`<a class="group-card" href="#api-${escape(group.id)}"><span class="group-code">${escape(group.id.replaceAll('_',' '))}</span><h3>${escape(group.title)}</h3><p>${group.count} 个接口 <span>查看分组 →</span></p></a>`).join('')}</div></section>`;
    $('#toc').innerHTML = '<p>接口分组</p>'+catalog.groups.map(group=>`<a href="#api-${escape(group.id)}">${escape(group.title)}</a>`).join('')+'<a href="print.html" target="_blank" rel="noopener">完整打印 ↗</a>';
  }
  function groupPage(group) {
    breadcrumbs(group);
    main.innerHTML = `<header class="group-header"><p class="eyebrow">API REFERENCE · ${escape(group.id)}</p><h1>${escape(group.title)}</h1><p>${group.count} 个接口。选择接口查看调用说明、参数和示例。</p></header><div class="api-cards">${catalog.entries.filter(x=>x.group===group.id).map(card).join('')}</div>`;
    $('#toc').innerHTML = '';
  }
  function parseRoute() {
    let value;
    try {value = decodeURIComponent(location.hash.slice(1));} catch {return {};}
    const [id,section] = value.split('~');
    if(entries.has(id)) return {entry:entries.get(id),section};
    const group = catalog.groups.find(g=>'api-'+g.id===id);
    if(group) return {group};
    for(const entry of catalog.entries) {
      const version=entry.versions.find(v=>`${entry.id}-${v}`===id);
      if(version) return {entry,version,section};
    }
    return {unknown:Boolean(value)};
  }
  function selectNav(entry,group) {
    nav.querySelectorAll('[aria-current]').forEach(node=>node.removeAttribute('aria-current'));
    const node=entry?navItems.get(entry.id):null;
    if(node) { node.setAttribute('aria-current','page');node.closest('details').open=true; }
    else if(group) nav.querySelector(`[data-group="${group.id}"]`).open=true;
    else $('.nav-home').setAttribute('aria-current','page');
  }
  function toc(detail) {
    $('#toc').innerHTML='<p>本页内容</p>'+detail.toc.map(section=>`<a href="#${escape(currentEntry.id)}~${escape(section.id)}" data-section="${escape(section.id)}">${escape(section.title)}</a>`).join('');
    sectionObserver?.disconnect();
    if(!('IntersectionObserver' in window)) return;
    sectionObserver = new IntersectionObserver(records=>{
      const visible=records.filter(x=>x.isIntersecting).sort((a,b)=>a.boundingClientRect.top-b.boundingClientRect.top);
      if(!visible.length) return;
      $('#toc').querySelectorAll('a').forEach(link=>link.classList.toggle('active',link.dataset.section===visible[0].target.id));
    },{rootMargin:'-70px 0px -55% 0px',threshold:0});
    main.querySelectorAll('.doc-section').forEach(section=>sectionObserver.observe(section));
  }
  function showDetail(version) {
    const detail=currentDetail.versions.find(x=>x.version===version)||currentDetail.versions[0];
    main.innerHTML=currentDetail.versions.length>1?`<label class="version-picker">接口版本 <select id="version-select">${currentDetail.versions.map(x=>`<option${x.version===detail.version?' selected':''}>${escape(x.version)}</option>`).join('')}</select></label>`+detail.html:detail.html;
    toc(detail);
  }
  async function route({restore=false}={}) {
    const sequence=++routeSequence, previousEntry=currentEntry;
    if(lastLocation) positions.set(lastLocation,window.scrollY);
    const route=parseRoute();
    const same=route.entry && previousEntry?.id===route.entry.id && currentDetail;
    currentEntry=route.entry || null;
    if(!same) {currentDetail=null; sectionObserver?.disconnect();}
    selectNav(route.entry,route.group);
    document.body.classList.remove('nav-open'); $('[data-toggle-nav]').setAttribute('aria-expanded','false'); $('.nav-backdrop').hidden=true;
    main.setAttribute('aria-busy','true');
    try {
      if(route.entry) {
        breadcrumbs(groups.get(route.entry.group),route.entry);
        document.title=`${route.entry.title} · ${catalog.project.title}`;
        if(!same) main.innerHTML='<p class="loading" role="status">正在加载接口内容…</p>';
        const detail=await load(route.entry);
        if(sequence!==routeSequence) return;
        currentDetail=detail;
        if(!same || route.version) showDetail(route.version);
      } else {
        document.title=catalog.project.title;
        if(route.group) groupPage(route.group);
        else if(route.unknown) {breadcrumbs();main.innerHTML='<div class="empty-state"><h1>未找到接口</h1><p>此链接可能已变更，可以搜索接口名称或路径。</p><button type="button" data-focus-search>搜索接口</button><a href="#">返回总览</a></div>';$('#toc').innerHTML='';}
        else home();
      }
      lastLocation=location.hash;
      if(route.section && document.getElementById(route.section)?.closest('main')) document.getElementById(route.section).scrollIntoView();
      else if(restore && positions.has(location.hash)) window.scrollTo(0,positions.get(location.hash));
      else if(!same) window.scrollTo(0,0);
      if(focusAfterRoute) {main.querySelector('h1')?.focus({preventScroll:true});focusAfterRoute=false;}
    } catch(error) {
      if(sequence===routeSequence) main.innerHTML=`<div class="empty-state"><h1>暂时无法打开接口</h1><p>${escape(error.message)}</p><button type="button" data-retry>重试</button></div>`;
    } finally {if(sequence===routeSequence) main.setAttribute('aria-busy','false');}
  }
  function highlight(text) {
    const tokens=window.CubeApiSearch.normalize(input.value).split(' ').filter(Boolean).sort((a,b)=>b.length-a.length);
    if(!tokens.length) return escape(text);
    const regexp=new RegExp(tokens.map(t=>t.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')).join('|'),'gi');
    let html='',end=0;
    for(const match of text.matchAll(regexp)) {html+=escape(text.slice(end,match.index))+'<mark>'+escape(match[0])+'</mark>';end=match.index+match[0].length;}
    return html+escape(text.slice(end));
  }
  function activeResult(next) {
    if(!matches.length) return;
    resultIndex=(next+matches.length)%matches.length;
    resultList.querySelectorAll('[role="option"]').forEach((item,i)=>item.setAttribute('aria-selected',String(i===resultIndex)));
    const item=resultList.children[resultIndex];
    input.setAttribute('aria-activedescendant',item.id);
    item.scrollIntoView({block:'nearest'});
  }
  function search() {
    clearTimeout(searchTimer);
    searchTimer=null;
    const active=Boolean(input.value.trim() || methodFilter.value || groupFilter.value);
    if(active && !searchActive) navScroll=sidebarScroll.scrollTop;
    nav.hidden=active;panel.hidden=!active;searchActive=active;
    $('.search-filters').hidden=!active;
    $('[data-clear-search]').hidden=!active;
    input.setAttribute('aria-expanded',String(active));
    input.removeAttribute('aria-activedescendant'); resultIndex=-1;
    if(!active) {matches=[];sidebarScroll.scrollTop=navScroll;resultList.replaceChildren();$('#search-status').textContent='';return;}
    matches=window.CubeApiSearch.search(index,input.value,{method:methodFilter.value,group:groupFilter.value});
    resultList.innerHTML=matches.map(({entry},i)=>{
      const query=window.CubeApiSearch.normalize(input.value).split(' ').find(q=>q && window.CubeApiSearch.normalize(entry.terms).includes(q));
      const summary=query && !window.CubeApiSearch.normalize(entry.summary).includes(query)?'参数：'+entry.terms:entry.summary;
      return `<li id="search-result-${i}" role="option" aria-selected="false"><a tabindex="-1" href="#${escape(entry.id)}"><div>${method(entry)}<strong>${highlight(entry.title)}</strong></div><code>${highlight(entry.url)}</code><p>${highlight(summary.slice(0,180))}</p><small>${highlight(entry.groupTitle)}</small></a></li>`;
    }).join('');
    $('#search-status').textContent=matches.length?`${matches.length} 个匹配接口 · ↑↓ 选择，Enter 打开`:'没有匹配的接口，试试路径片段、参数名或其他关键词。';
    sidebarScroll.scrollTop=0;
  }
  function clearSearch() {input.value='';methodFilter.value='';groupFilter.value='';search();}
  function closeSearch() {clearSearch();input.blur();}
  function focusSearch() {
    if(window.innerWidth<900) toggleNav(true);
    input.focus();input.select();
  }
  function toggleNav(open) {
    document.body.classList.toggle('nav-open',open);
    $('[data-toggle-nav]').setAttribute('aria-expanded',String(open));$('.nav-backdrop').hidden=!open;
    if(!open) $('[data-toggle-nav]').focus();
  }
  async function copy(value,button) {
    try {
      if(navigator.clipboard?.writeText && window.isSecureContext) await navigator.clipboard.writeText(value);
      else {
        const field=document.createElement('textarea');field.value=value;field.className='clipboard-fallback';document.body.appendChild(field);field.select();
        const ok=document.execCommand('copy');field.remove();if(!ok) throw new Error('copy');button?.focus({preventScroll:true});
      }
      $('#toast').textContent='已复制';
    } catch {$('#toast').textContent='复制失败，请手动选择内容复制';}
    $('#toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('#toast').hidden=true,1800);
  }
  document.addEventListener('click',event=>{
    const target=event.target.closest('button,a,[data-close-nav]');if(!target) return;
    if(target.matches('[data-focus-search]')) focusSearch();
    if(target.matches('[data-clear-search]')) {clearSearch();input.focus();}
    if(target.matches('[data-toggle-nav]')) toggleNav(!document.body.classList.contains('nav-open'));
    if(target.matches('[data-close-nav]')) toggleNav(false);
    if(target.matches('[data-retry]')) route();
    if(target.matches('[data-copy]')) copy(target.dataset.copy==='path'?currentEntry.url:target.closest('.code-block').querySelector('code').textContent,target);
    if(target.matches('#permalink')) {event.preventDefault();copy(location.href,target);}
    if(target.matches('[data-expand-code]')) {const expanded=target.getAttribute('aria-expanded')!=='true';target.setAttribute('aria-expanded',String(expanded));target.closest('.code-block').classList.toggle('expanded',expanded);target.textContent=expanded?'收起长示例':'展开完整示例';}
    if(target.matches('#print-current')) window.print();
    if(target.matches('a[href^="#"]') && !target.matches('#permalink,.skip-link')) {
      // Native hash navigation preserves shareable legacy links and browser history.
      focusAfterRoute=true;
      if(target.closest('#search-results')) closeSearch();
      if(target.hash===location.hash) {event.preventDefault();route();}
    }
  });
  document.addEventListener('toggle',event=>{
    const node=event.target;
    if(node.matches('details.lazy-example') && node.open) {const template=node.querySelector('template');if(template){node.appendChild(template.content);template.remove();}}
  },true);
  input.addEventListener('input',()=>{if(!composing){clearTimeout(searchTimer);searchTimer=setTimeout(search,60);}});
  input.addEventListener('compositionstart',()=>{composing=true;clearTimeout(searchTimer);});
  input.addEventListener('compositionend',()=>{composing=false;search();});
  methodFilter.addEventListener('change',search);groupFilter.addEventListener('change',search);
  document.addEventListener('change',event=>{if(event.target.id==='version-select'){location.hash=currentEntry.id+'-'+event.target.value;window.scrollTo(0,0);}});
  document.addEventListener('keydown',event=>{
    if(event.isComposing || composing) return;
    const editing=event.target.matches('input,textarea,select,[contenteditable="true"]');
    if((event.key==='/' && !editing && !event.ctrlKey && !event.metaKey && !event.altKey) || ((event.ctrlKey || event.metaKey) && event.key.toLowerCase()==='k')) {event.preventDefault();focusSearch();return;}
    if(event.key==='Escape') {if(searchActive) closeSearch();else if(document.body.classList.contains('nav-open')) toggleNav(false);else input.blur();return;}
    if(document.activeElement===input && ['ArrowDown','ArrowUp','Enter'].includes(event.key)) {
      event.preventDefault();if(searchTimer) search();
      if(event.key==='Enter') {const match=matches[resultIndex<0?0:resultIndex];if(match){focusAfterRoute=true;closeSearch();location.hash=match.entry.id;}}
      else activeResult(resultIndex<0?(event.key==='ArrowDown'?0:matches.length-1):resultIndex+(event.key==='ArrowDown'?1:-1));
    }
  });
  window.addEventListener('hashchange',()=>route({restore:!focusAfterRoute}));
  let printStates=[];
  window.addEventListener('beforeprint',()=>{
    printStates=[...main.querySelectorAll('details')].map(node=>[node,node.open]);
    for(const [node] of printStates) {
      const template=node.querySelector('template');
      if(template){node.appendChild(template.content);template.remove();}
      node.open=true;
    }
  });
  window.addEventListener('afterprint',()=>{for(const [node,open] of printStates) node.open=open;printStates=[];});
  const mobile=matchMedia('(max-width:899px)');
  mobile.addEventListener('change',event=>{if(!event.matches){document.body.classList.remove('nav-open');$('[data-toggle-nav]').setAttribute('aria-expanded','false');$('.nav-backdrop').hidden=true;}});
  if('scrollRestoration' in history) history.scrollRestoration='manual';
  $('.project-version').textContent='v'+catalog.project.version;
  renderNav();route();
})();
