import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {escape,routeId,fieldsOf,examplesOf,renderArticle} from './render.mjs';
const require=createRequire(import.meta.url);
const {createIndex,search,normalize}=require('./search.js');
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const parsed=require('apidoc').createDoc({src:[path.join(root,'src')],config:path.join(root,'apidoc.json'),dryRun:true,silent:true});
const definitions=JSON.parse(parsed.data);
const output=path.join(root,'output');
const listFiles=(directory,prefix='')=>fs.readdirSync(directory,{withFileTypes:true}).flatMap(item=>item.isDirectory()?listFiles(path.join(directory,item.name),prefix+item.name+'/'):[prefix+item.name]).sort();
const publishedFiles=listFiles(output);
const catalogScript=publishedFiles.find(file=>/^assets\/catalog\./.test(file));
const context={window:{}};
vm.runInNewContext(fs.readFileSync(path.join(output,catalogScript),'utf8'),context);
const catalog=context.window.CubeApiCatalog;
const index=createIndex(catalog.entries);

test('Chinese names, paths, parameters and mixed method queries find actual interfaces',()=>{
  for(const query of ['专项图片','/specialized-detection/base','POST detection','callbackUrl','uav-fire-smoke','OmniGround','GET /harness']) {
    assert(search(index,query).length>0,query);
  }
  assert.equal(search(index,'/specialized-detection/base/:token')[0].entry.name,'SpecializedDetectionImage');
  assert(search(index,'POST detection',{method:'GET'}).length===0);
  assert(search(index,'',{group:'SpecializedDetection'}).every(x=>x.entry.group==='SpecializedDetection'));
  assert.equal(normalize(' ＰＯＳＴ  Cube '),'post cube');
  assert.equal(search(index,'missing-api-928347').length,0);
  for(const query of ['[','.*','<script>','"','%']) assert.doesNotThrow(()=>search(index,query));
});

test('build preserves every interface, version, field and example from apiDoc',()=>{
  assert.equal(catalog.entries.length,new Set(definitions.map(routeId)).size);
  let versionCount=0;
  for(const entry of catalog.entries) {
    let detail;
    vm.runInNewContext(fs.readFileSync(path.join(output,entry.file),'utf8'),{window:{CubeApiDocs:{define(id,value){assert.equal(id,entry.id);detail=value;}}}});
    versionCount+=detail.versions.length;
    for(const definition of definitions.filter(x=>routeId(x)===entry.id)) {
      const version=detail.versions.find(x=>x.version===definition.version);
      assert(version,entry.id);
      for(const field of fieldsOf(definition)) assert(version.html.includes(escape(field.field)),`${entry.id}: ${field.field}`);
      assert.equal((version.html.match(/class="code-block"/g)||[]).length,examplesOf(definition).length,entry.id);
    }
  }
  assert.equal(versionCount,definitions.length);
});

test('examples are escaped and secondary examples remain inert until opened',()=>{
  const fixture={group:'Test',name:'Example',title:'Example',type:'post',url:'/example',version:'1.0.0',parameter:{examples:[{title:'First',content:'{"x":"</code><script>alert(1)</script>"}',type:'json'},{title:'Second',content:'two',type:'text'}]}};
  const rendered=renderArticle(fixture);
  assert(!rendered.html.includes('<script>'));
  assert(rendered.html.includes('<template>'));
  assert(!renderArticle(fixture,{printable:true}).html.includes('<template>'));
});

test('initial payload is bounded and every generated file is synchronized',()=>{
  const indexHtml=fs.readFileSync(path.join(output,'index.html'),'utf8');
  const scripts=[...indexHtml.matchAll(/<script src="([^"]+)"/g)].map(x=>x[1]);
  assert(scripts.every(file=>!file.includes('main.bundle')));
  assert(scripts.reduce((bytes,file)=>bytes+fs.statSync(path.join(output,file)).size,0)<200*1024);
  assert(!fs.existsSync(path.join(output,'assets/main.bundle.js')));
  const target=path.resolve(root,'../../dispatcher/assets/doc');
  assert.deepEqual(listFiles(target),publishedFiles);
  for(const file of publishedFiles) assert(fs.readFileSync(path.join(output,file)).equals(fs.readFileSync(path.join(target,file))),file);
  const printed=fs.readFileSync(path.join(output,'print.html'),'utf8');
  assert.equal((printed.match(/<article /g)||[]).length,catalog.entries.length);
});

test('publication contains only the unified reader, interface content and static printing',()=>{
  for(const file of publishedFiles) assert(/^(?:index\.html|print\.html|assets\/[^/]+\.(?:css|js)|content\/[^/]+\.js)$/.test(file),file);
  for(const page of ['index.html','print.html']) {
    const html=fs.readFileSync(path.join(output,page),'utf8');
    for(const match of html.matchAll(/(?:href|src)="([^"]+)"/g)) {
      const link=match[1];
      if(/^(?:[a-z][\w+.-]*:|\/\/|#)/i.test(link)) continue;
      const file=path.resolve(output,decodeURIComponent(link.split(/[?#]/)[0]));
      assert(file.startsWith(output+path.sep) && fs.existsSync(file),`${page}: ${link}`);
    }
  }
});
