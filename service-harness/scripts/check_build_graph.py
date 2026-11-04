"""Check Harness build order, JAR publication and explicit skill installation."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
doc = ET.parse(root/"build.xml")
targets = {t.attrib['name']: t for t in doc.findall('target')}


def build_order(name):
    order = []

    def visit(current):
        if current in order:
            return
        for dependency in targets[current].attrib.get('depends', '').split(','):
            if dependency.strip():
                visit(dependency.strip())
        order.append(current)

    visit(name)
    return order


for mode, full_build in [('debug', 'build-debug'), ('release', 'build')]:
    chain = ['build-common-' + mode, 'build-harness-' + mode,
             'build-harness-skill-' + mode]
    for entry in [full_build, chain[-1]]:
        order = build_order(entry)
        assert all(name in order for name in chain), (entry, order)
        assert [order.index(name) for name in chain] == sorted(order.index(name) for name in chain)
    for module in ['service-harness', 'service-harness-skill']:
        name = 'build-harness-' + ('skill-' if module.endswith('-skill') else '') + mode
        invocation = targets[name].find('ant')
        assert invocation.attrib['dir'] == '${project-' + module + '.dir}'
        assert invocation.attrib['target'] == 'build-' + mode
        tree = ET.parse(root / module / 'build.xml')
        local_targets = {t.attrib['name']: t for t in tree.findall('target')}
        publish = local_targets['build-debug'].find('copy')
        assert publish.attrib == {'file': '${build.dir}/cube-' + module + '-3.0.jar', 'todir': '../build'}
        assert local_targets['build-release'].attrib['depends'] == 'build-debug'

# Building skills does not install providers or change the selected Cellets.
libs = targets['deploy'].find('copy/fileset')
assert any(e.attrib['name'] == '*service-harness*' for e in libs.findall('exclude'))
assert 'harness.stage.dir' in targets['deploy-with-harness'].find('fail').attrib['unless']
module=ET.parse(root/'service-harness/build.xml')
clean=next(t for t in module.findall('target') if t.attrib['name']=='clean')
assert [e.attrib['dir'] for e in clean.findall('delete')]==['${build.dir}']
print('PASS: full and standalone builds publish both Harness JARs in dependency order; skill installation stays explicit')
