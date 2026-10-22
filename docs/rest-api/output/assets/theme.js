/* ==========================================================================
   白泽 API 文档 — 现代主题增强脚本 (apidoc modern theme)
   --------------------------------------------------------------------------
   只做「渐进增强」，不改动 apidoc 的渲染逻辑与数据：
     1. 侧栏顶部加品牌标题（取自页面标题）
     2. 把「方法徽章 + 路径」合并成一条端点栏  -> .endpoint-bar
     3. 为代码块与端点路径加复制按钮
     4. 搜索框占位符中文化、" / " 快捷键聚焦
   所有操作都幂等（带 data 标记与存在性判断），可安全重复执行；
   失败时静默降级，不影响文档本身的可用性。
   ========================================================================== */

(function () {
  'use strict';

  var COPY_TEXT = '复制';
  var COPIED_TEXT = '已复制';
  var COPIED_RESET_MS = 1600;

  /* ---------------------------------------------------------------- utils */

  function directChild(parent, selector) {
    var kids = parent.children;
    for (var i = 0; i < kids.length; i++) {
      if (kids[i].matches && kids[i].matches(selector)) return kids[i];
    }
    return null;
  }

  function textOf(node) {
    return (node && node.textContent ? node.textContent : '').replace(/\s+$/, '');
  }

  function copyToClipboard(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(text);
    }
    return new Promise(function (resolve, reject) {
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.position = 'fixed';
      ta.style.top = '-1000px';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      try {
        document.execCommand('copy');
        resolve();
      } catch (err) {
        reject(err);
      } finally {
        document.body.removeChild(ta);
      }
    });
  }

  function makeCopyButton(getText) {
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'copy-btn';
    btn.textContent = COPY_TEXT;
    btn.setAttribute('aria-label', COPY_TEXT);

    btn.addEventListener('click', function (event) {
      event.preventDefault();
      event.stopPropagation();
      copyToClipboard(getText()).then(function () {
        btn.textContent = COPIED_TEXT;
        btn.classList.add('is-copied');
        window.setTimeout(function () {
          btn.textContent = COPY_TEXT;
          btn.classList.remove('is-copied');
        }, COPIED_RESET_MS);
      }, function () {
        btn.textContent = '复制失败';
        window.setTimeout(function () { btn.textContent = COPY_TEXT; }, COPIED_RESET_MS);
      });
    });

    return btn;
  }

  /* -------------------------------------------------------------- 1. brand */

  function enhanceBrand() {
    var search = document.querySelector('#scrollingNav .sidenav-search');
    if (!search || search.querySelector('.theme-brand')) return;

    var title = textOf(document.querySelector('#project h1')) || textOf(document.title);
    if (!title) return;

    var brand = document.createElement('div');
    brand.className = 'theme-brand';

    var mark = document.createElement('span');
    mark.className = 'theme-brand__mark';

    var name = document.createElement('span');
    name.className = 'theme-brand__name';
    name.textContent = title;
    name.title = title;

    brand.appendChild(mark);
    brand.appendChild(name);
    search.insertBefore(brand, search.firstChild);
  }

  /* ------------------------------------------------- 2. endpoint bar wrap */

  function enhanceEndpoint(article) {
    if (directChild(article, '.endpoint-bar')) return;

    var method = directChild(article, 'span.method');
    var pre = directChild(article, 'pre');
    if (!method || !pre) return;

    var bar = document.createElement('div');
    bar.className = 'endpoint-bar';
    article.insertBefore(bar, method);
    bar.appendChild(method);
    bar.appendChild(pre);

    // 路径用纯文本展示，避免 Prism 暗色 token 落在浅色底上
    var code = pre.querySelector('code');
    if (code) {
      code.textContent = code.textContent;
      code.className = '';
    }
    pre.className = (pre.className || '').replace(/\blanguage-\S+/g, '').trim();
    pre.setAttribute('data-endpoint-path', '');

    bar.appendChild(makeCopyButton(function () {
      return code ? code.textContent.trim() : pre.textContent.trim();
    }));
  }

  /* ---------------------------------------------------- 3. copy on <pre> */

  function enhanceCodeBlock(pre) {
    if (pre.hasAttribute('data-endpoint-path')) return;
    if (pre.closest('.endpoint-bar')) return;

    var parent = pre.parentElement;
    if (parent && parent.classList.contains('code-wrap')) return;

    var wrap = document.createElement('div');
    wrap.className = 'code-wrap';
    pre.parentNode.insertBefore(wrap, pre);
    wrap.appendChild(pre);

    wrap.appendChild(makeCopyButton(function () {
      var code = pre.querySelector('code');
      return textOf(code || pre).trim();
    }));
  }

  /* -------------------------------------------------------- 4. search box */

  function enhanceSearch() {
    var input = document.querySelector('#scrollingNav .sidenav-search input.search');
    if (!input) return;

    // apidoc 每次加载都会自动聚焦搜索框，这里取消一次，避免出现无意义的高亮光圈
    if (document.body.getAttribute('data-theme-focus-cleared') !== 'on') {
      document.body.setAttribute('data-theme-focus-cleared', 'on');
      if (document.activeElement === input && input.blur) input.blur();
    }

    if (/^\s*filter\b/i.test(input.placeholder || '')) {
      input.placeholder = '搜索接口…';
    }

    if (document.body.getAttribute('data-theme-shortcut') === 'on') return;
    document.body.setAttribute('data-theme-shortcut', 'on');

    document.addEventListener('keydown', function (event) {
      if (event.key !== '/' || event.metaKey || event.ctrlKey || event.altKey) return;
      var el = document.activeElement;
      var tag = el && el.tagName ? el.tagName.toLowerCase() : '';
      if (tag === 'input' || tag === 'textarea' || tag === 'select' || (el && el.isContentEditable)) return;
      event.preventDefault();
      input.focus();
      if (input.select) input.select();
    });
  }

  /* --------------------------------------------------------------- runner */

  function apply() {
    enhanceBrand();
    enhanceSearch();

    var articles = document.querySelectorAll('article');
    for (var i = 0; i < articles.length; i++) enhanceEndpoint(articles[i]);

    var pres = document.querySelectorAll('pre');
    for (var j = 0; j < pres.length; j++) enhanceCodeBlock(pres[j]);
  }

  var observer = null;
  var target = null;

  function watch() {
    if (!observer || !target) return;
    observer.observe(target, { childList: true, subtree: true });
  }

  function safeApply() {
    if (observer) observer.disconnect();
    try {
      apply();
    } catch (err) {
      /* 主题增强失败不应影响文档阅读 */
      if (window.console && console.warn) console.warn('[apidoc-theme]', err);
    }
    watch();
  }

  function start() {
    safeApply();

    // apidoc 的版本切换 / 版本对比会重新挂载节点，这里跟随刷新
    target = document.querySelector('#content');
    if (target && window.MutationObserver) {
      observer = new MutationObserver(function () {
        window.clearTimeout(observer.__t);
        observer.__t = window.setTimeout(safeApply, 60);
      });
      watch();
    }
  }

  if (document.readyState === 'loading') {
    // main.bundle.js 先注册 DOMContentLoaded，因此这里会晚于它执行
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }

  window.addEventListener('load', safeApply);
})();
