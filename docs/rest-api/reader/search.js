(function (root) {
  'use strict';
  const normalize = text => String(text || '').normalize('NFKC').toLocaleLowerCase().replace(/\s+/g, ' ').trim();
  function createIndex(entries) {
    return entries.map(entry => ({entry, fields: [entry.url,entry.title,entry.name,entry.groupTitle,entry.group,entry.method,entry.terms,entry.description].map(normalize)}));
  }
  function search(index, query, {method = '', group = ''} = {}) {
    const normalized = normalize(query), tokens = normalized.split(' ').filter(Boolean);
    const result = [];
    for (const item of index) {
      const entry = item.entry;
      if (method && entry.method !== method || group && entry.group !== group) continue;
      let score = 0, matches = true;
      for (const token of tokens) {
        let best = 0;
        for (let i = 0; i < item.fields.length; i++) {
          const field = item.fields[i], offset = field.indexOf(token);
          if (offset >= 0) best = Math.max(best, (8-i)*10 + (field === token ? 90 : offset === 0 ? 35 : 0));
        }
        if (!best) { matches = false; break; }
        score += best;
      }
      if (matches) result.push({entry,score:score+(item.fields[0]===normalized && normalized ? 200 : 0)});
    }
    return result.sort((a,b)=>b.score-a.score || a.entry.title.localeCompare(b.entry.title,'zh-CN'));
  }
  const api = {normalize,createIndex,search};
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.CubeApiSearch = api;
})(typeof window !== 'undefined' ? window : globalThis);
