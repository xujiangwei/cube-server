import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
import {escape,plain,routeId,fieldsOf,examplesOf,renderArticle} from './render.mjs';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const reader = path.join(root,'reader');
const hash = value => crypto.createHash('sha256').update(value).digest('hex').slice(0,16);
const serialize = value => JSON.stringify(value).replace(/</g,'\\u003c').replace(/\u2028/g,'\\u2028').replace(/\u2029/g,'\\u2029');
const hasFlag = flag => process.argv.includes(flag);
const stage = fs.mkdtempSync(path.join(root,'.output-'));
const output = path.join(root,'output');
const target = path.resolve(root,'../../dispatcher/assets/doc');
const assets = path.join(stage,'assets');
fs.mkdirSync(assets);
fs.mkdirSync(path.join(stage,'content'));

function asset(name,content) {
  const ext=path.extname(name), file=`assets/${path.basename(name,ext)}.${hash(content)}${ext}`;
  fs.writeFileSync(path.join(stage,file),content);
  return file;
}

function shell(title, body, {script = '', css = '', extra = ''} = {}) {
  return `<!doctype html>\n<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(title)}</title><link rel="stylesheet" href="${css}">${extra}</head><body>${body}${script}</body></html>\n`;
}

// Both destinations are generated publication directories. Mirror the completed build,
// including removal of obsolete assets, source copies and old build bookkeeping.
function publish(destination) {
  if(![output,target].includes(path.resolve(destination))) throw new Error('Invalid publication directory: '+destination);
  if(fs.existsSync(destination) && fs.lstatSync(destination).isSymbolicLink()) throw new Error('Publication directory must not be a link: '+destination);
  fs.mkdirSync(destination,{recursive:true});
  const newFiles=new Set(listFiles(stage));
  function prune(directory,prefix='') {
    for(const item of fs.readdirSync(directory,{withFileTypes:true})) {
      const file=prefix+item.name, resolved=path.resolve(directory,item.name);
      if(!resolved.startsWith(destination+path.sep)) throw new Error('Invalid generated path: '+file);
      if(item.isDirectory()) {
        prune(resolved,file+'/');
        if(!fs.readdirSync(resolved).length) fs.rmdirSync(resolved);
      } else if(item.isSymbolicLink() || !newFiles.has(file)) fs.unlinkSync(resolved);
    }
  }
  prune(destination);
  for(const file of newFiles) {
    fs.mkdirSync(path.dirname(path.join(destination,file)),{recursive:true});
    fs.copyFileSync(path.join(stage,file),path.join(destination,file));
  }
}
function listFiles(directory, prefix='') {
  return fs.readdirSync(directory,{withFileTypes:true}).flatMap(item=>{
    const file=prefix+item.name;
    return item.isDirectory()?listFiles(path.join(directory,item.name),file+'/'):[file];
  }).sort();
}

try {
  const parsed=require('apidoc').createDoc({src:[path.join(root,'src')],config:path.join(root,'apidoc.json'),dryRun:true,silent:true,warnError:true});
  if(!parsed || parsed===true) throw new Error('apiDoc did not produce interface data');
  const entries=JSON.parse(parsed.data), project=JSON.parse(parsed.project);
  const grouped=new Map();
  for(const entry of entries) {
    const id=routeId(entry);
    if(!grouped.has(id)) grouped.set(id,[]);
    grouped.get(id).push(entry);
  }
  const catalog=[];
  for(const [id,versions] of grouped) {
    versions.sort((a,b)=>b.version.localeCompare(a.version,undefined,{numeric:true}));
    const entry=versions[0];
    const detail={id,versions:versions.map(value=>({version:value.version,...renderArticle(value)}))};
    const payload=serialize(detail);
    const file=`content/${hash(id)}.${hash(payload)}.js`;
    fs.writeFileSync(path.join(stage,file),`window.CubeApiDocs.define(${serialize(id)},${payload});\n`);
    catalog.push({id,name:entry.name,title:entry.title || entry.name,group:entry.group,groupTitle:entry.groupTitle || entry.group,method:entry.type.toUpperCase(),url:entry.url,summary:plain(entry.description).slice(0,140),description:plain(entry.description).slice(0,1500),terms:fieldsOf(entry).map(x=>x.field).join(' '),versions:versions.map(x=>x.version),file});
  }
  const collator=new Intl.Collator('zh-CN',{numeric:true});
  catalog.sort((a,b)=>collator.compare(a.groupTitle,b.groupTitle)||collator.compare(a.title,b.title));
  const groups=[...new Set(catalog.map(x=>x.group))].map(id=>({id,title:catalog.find(x=>x.group===id).groupTitle,count:catalog.filter(x=>x.group===id).length}));
  const data={project:{title:project.title || project.name,version:project.version,url:project.url},groups,entries:catalog};
  const catalogFile=asset('catalog.js',`window.CubeApiCatalog=${serialize(data)};\n`);
  const searchFile=asset('search.js',fs.readFileSync(path.join(reader,'search.js'),'utf8'));
  const appFile=asset('app.js',fs.readFileSync(path.join(reader,'app.js'),'utf8'));
  const cssFile=asset('reader.css',fs.readFileSync(path.join(reader,'reader.css'),'utf8'));
  const layout=fs.readFileSync(path.join(reader,'shell.html'),'utf8').replaceAll('__TITLE__',escape(data.project.title));
  fs.writeFileSync(path.join(stage,'index.html'),shell(data.project.title,layout,{css:cssFile,script:`<script src="${catalogFile}"></script><script src="${searchFile}"></script><script src="${appFile}"></script>`}));
  const printed=[...grouped.values()].map(versions=>renderArticle(versions[0],{printable:true}).html).join('\n');
  fs.writeFileSync(path.join(stage,'print.html'),shell('完整打印 · '+data.project.title,`<nav class="print-toolbar"><a href="index.html">返回接口文档</a><button type="button" id="print-button">打印 / 保存 PDF</button></nav><main class="print-main"><h1>${escape(data.project.title)}</h1><p>${catalog.length} 个接口 · v${escape(project.version)}</p>${printed}</main>`,{css:cssFile,script:'<script src="assets/print.js"></script>'}));
  fs.writeFileSync(path.join(assets,'print.js'),"document.getElementById('print-button').addEventListener('click',()=>window.print());\n");
  const examples=entries.reduce((n,x)=>n+examplesOf(x).length,0);
  publish(output);
  if(!hasFlag('--no-sync')) publish(target);
  console.log(`Built ${catalog.length} interfaces, ${groups.length} groups, ${examples} examples.`);
  console.log(`Initial JavaScript: ${[catalogFile,searchFile,appFile].reduce((sum,file)=>sum+fs.statSync(path.join(stage,file)).size,0)} bytes; details load on demand.`);
  console.log(`Output: ${output}${hasFlag('--no-sync')?'':`\nDispatcher: ${target}`}`);
} finally {
  // stage was created by this invocation under the documentation root.
  fs.rmSync(stage,{recursive:true,force:true});
}
