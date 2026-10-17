"""Assert release selection and cleanup boundaries without running a deployment."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
doc = ET.parse(root/"build.xml")
targets = {t.attrib['name']: t for t in doc.findall('target')}
for name in ['build','build-debug','build-service-debug','build-service-release','deploy','install','all','clean']:
    target=targets[name]
    assert 'harness' not in target.attrib.get('depends','')
    assert not any('harness' in a.attrib.get('dir','') for a in target.findall('ant'))
for name in ['deploy','clean-deploy']:
    target=targets[name]
    libs=next(target.iter('fileset'))
    assert any(e.attrib['name']=='*service-harness*' for e in libs.findall('exclude'))
assert 'harness.stage.dir' in targets['deploy-with-harness'].find('fail').attrib['unless']
module=ET.parse(root/'service-harness/build.xml')
clean=next(t for t in module.findall('target') if t.attrib['name']=='clean')
assert [e.attrib['dir'] for e in clean.findall('delete')]==['${build.dir}']
print('PASS: original targets remain independent; Harness publication is explicit and staged')
