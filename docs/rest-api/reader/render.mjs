import Prism from 'prismjs';
import 'prismjs/components/prism-json.js';
import 'prismjs/components/prism-http.js';
import 'prismjs/components/prism-bash.js';
import 'prismjs/components/prism-python.js';

export const escape = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
export const plain = value => String(value ?? '').replace(/<[^>]*>/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/\s+/g, ' ').trim();
export const routeId = entry => `api-${entry.group}-${entry.name}`;
export const fieldGroups = value => Array.isArray(value) ? { '': value } : value?.fields ?? {};
export const fieldsOf = entry => ['parameter', 'header', 'query', 'body', 'success', 'error', 'info'].flatMap(key => Object.values(fieldGroups(entry[key])).flat());
export const examplesOf = entry => [...(entry.examples ?? []), ...['header','parameter','success','error','info'].flatMap(key => entry[key]?.examples ?? [])];

export function renderFields(value, response = false) {
  return Object.entries(fieldGroups(value)).filter(([, fields]) => fields.length).map(([group, fields]) => {
    const rows = fields.map(field => {
      const depth = Math.min((field.field.match(/\./g) ?? []).length, 4);
      const constraints = [field.defaultValue !== undefined ? `默认：<code>${escape(field.defaultValue)}</code>` : '', field.size ? `范围：${escape(field.size)}` : '', field.allowedValues?.length ? `可选值：${field.allowedValues.map(x => `<code>${escape(x)}</code>`).join('、')}` : ''].filter(Boolean).join(' · ');
      return `<tr><th scope="row" style="--depth:${depth}"><code>${escape(field.field)}</code></th><td><code class="field-type">${escape(field.type || '—')}</code>${response ? '' : `<span class="requirement ${field.optional ? 'optional' : ''}">${field.optional ? '可选' : '必填'}</span>`}</td><td>${field.description || ''}${constraints ? `<div class="constraints">${constraints}</div>` : ''}</td></tr>`;
    }).join('');
    return `${group && !['Parameter','Header','Body','Query','Success 200','Error 4xx'].includes(group) ? `<h3>${escape(group)}</h3>` : ''}<div class="table-scroll" tabindex="0" role="region" aria-label="${escape(group || '字段说明')}"><table><thead><tr><th>字段</th><th>类型${response ? '' : ' / 要求'}</th><th>说明</th></tr></thead><tbody>${rows}</tbody></table></div>`;
  }).join('');
}

function code(example, printable) {
  const language = ({shell:'bash',curl:'bash',url:'http'})[example.type] || example.type;
  const grammar = Prism.languages[language];
  const content = grammar ? Prism.highlight(example.content, grammar, language) : escape(example.content);
  return `<div class="code-block"><div class="code-toolbar"><span>${escape(example.type || 'text')}</span>${printable ? '' : '<button type="button" data-copy="code">复制代码</button>'}</div><pre tabindex="0"><code>${content}</code></pre>${!printable && example.content.split('\n').length > 16 ? '<button class="expand-code" type="button" aria-expanded="false" data-expand-code>展开完整示例</button>' : ''}</div>`;
}

function renderExamples(examples = [], printable = false) {
  return examples.map((example, i) => {
    const heading = escape(example.title || '示例');
    if (!i || printable) return `<div class="example"><h3>${heading}</h3>${code(example, printable)}</div>`;
    return `<details class="example lazy-example"><summary>${heading}</summary><template>${code(example, false)}</template></details>`;
  }).join('');
}

export function renderArticle(entry, {printable = false} = {}) {
  const id = routeId(entry);
  const prefix = printable ? `${id}-` : '';
  const sections = [];
  const section = (key, title, body) => {
    if (body) sections.push({id:prefix+key,title,html:`<section class="doc-section" id="${escape(prefix+key)}"><h2>${title}</h2>${body}</section>`});
  };
  section('overview','用途与调用说明',`${entry.deprecated ? '<aside class="notice">此接口已弃用。'+(entry.deprecated.content || '')+'</aside>' : ''}${entry.description || ''}${entry.permission?.length ? '<h3>权限</h3>'+entry.permission.map(x=>`<p>${escape(x.name)} ${x.description || ''}</p>`).join('') : ''}`);
  section('request-example','请求示例',renderExamples([...(entry.examples || []),...(entry.parameter?.examples || [])], printable));
  section('headers','认证与请求头',renderFields(entry.header)+renderExamples(entry.header?.examples,printable));
  section('parameters','路径与通用参数',renderFields(entry.parameter));
  section('query','查询参数',renderFields(entry.query));
  section('body','请求体参数',renderFields(entry.body));
  section('success','成功响应',renderExamples(entry.success?.examples,printable)+renderFields(entry.success,true));
  section('errors','错误与处理',renderFields(entry.error,true)+renderExamples(entry.error?.examples,printable));
  section('additional','补充说明',renderFields(entry.info,true)+renderExamples(entry.info?.examples,printable));
  return {
    toc: sections.map(({id,title})=>({id,title})),
    html:`<article data-api-id="${escape(id)}"${printable ? ` id="${escape(id)}"` : ''}><header class="article-header"><p class="eyebrow">${escape(entry.groupTitle || entry.group)} · v${escape(entry.version)}</p><h1 tabindex="-1">${escape(entry.title || entry.name)}</h1><div class="endpoint"><span class="method ${escape(entry.type)}">${escape(entry.type.toUpperCase())}</span><code>${escape(entry.url)}</code>${printable ? '' : '<button type="button" data-copy="path" aria-label="复制接口路径">复制路径</button>'}</div></header>${sections.map(x=>x.html).join('')}</article>`
  };
}
