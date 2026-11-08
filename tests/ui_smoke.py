"""End-to-end release checks with real file picker and Documents export."""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
output = root / 'test-output'
output.mkdir(exist_ok=True)
adb = str(Path(os.environ.get('ANDROID_HOME', r'D:\Developer\tools\AndroidSDK')) / 'platform-tools/adb.exe')
stem = f'doc-reader-ui-{int(time.time())}'
docx_name = stem + '.docx'

def run(*args):
    return subprocess.check_output([adb, *args], encoding='utf-8', errors='replace')

def tree():
    for _ in range(3):
        result = run('shell', 'uiautomator', 'dump', '/sdcard/doc-reader-ui.xml')
        if 'dumped to:' in result:
            return ET.fromstring(run('shell', 'cat', '/sdcard/doc-reader-ui.xml'))
        time.sleep(.3)
    raise AssertionError('Could not obtain a fresh UI hierarchy')

def find(text, timeout=15, resource=None):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for node in tree().iter('node'):
            if resource and not node.get('resource-id', '').endswith(resource):
                continue
            if any(''.join(node.get(key, '').split()) == ''.join(text.split()) for key in ['text', 'content-desc']):
                return node
        time.sleep(.2)
    raise AssertionError(f'UI item not found: {text}')

def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    run('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def shot(name):
    run('shell', 'screencap', '-p', '/sdcard/doc-reader-shot.png')
    run('pull', '/sdcard/doc-reader-shot.png', str(output / name))

def pick(name, location='Downloads'):
    tap(find('打开文件'))
    # The picker remembers its previous root; search is scoped to that root.
    tap(find('Show roots'))
    tap(find(location, resource='android:id/title'))
    tap(find('Search'))
    edit = next(n for n in tree().iter('node') if n.get('resource-id', '').endswith('search_src_text'))
    tap(edit)
    run('shell', 'input', 'text', name)
    run('shell', 'input', 'keyevent', '66')
    tap(find(name, resource='android:id/title'))
    find('返回')
    find('1 / 2', timeout=30)

def fetch_pdf(name, local):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        try:
            run('pull', '/sdcard/Documents/' + name, str(local))
            data = local.read_bytes()
            if data.startswith(b'%PDF-') and b'%%EOF' in data[-128:]:
                return
        except subprocess.CalledProcessError:
            pass
        time.sleep(.3)
    raise AssertionError('Export did not produce a complete PDF in Documents: ' + name)

run('push', str(root / 'app/src/androidTest/assets/sample.docx'), '/sdcard/Download/' + docx_name)
run('shell', 'am', 'start', '-S', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER',
    '-f', '0x10008000', '-n', 'io.github.helloktz.docreader/.MainActivity')
find('打开文件')
assert [n.get('text') for n in tree().iter('node') if n.get('text')] == ['打开文件']
shot('release-home.png')
pick(docx_name)
for icon in ['恢复默认', '适应文字', '锁定尺寸', '大纲', '页面预览', '导出 PDF']:
    assert find(icon).get('class') == 'android.widget.ImageButton'
shot('release-docx.png')

tap(find('适应文字'))
deadline = time.monotonic() + 20
while time.monotonic() < deadline:
    if find('适应文字').get('selected') == 'true':
        break
else:
    raise AssertionError('DOCX text fit did not select the fitted viewport')
shot('release-text-fit.png')
assert find('解锁尺寸').get('selected') == 'true'
assert find('恢复默认').get('enabled') == 'false'
tap(find('解锁尺寸'))
tap(find('恢复默认'))
assert find('恢复默认').get('selected') == 'true'

tap(find('页面预览'))
find('第 2 页')
time.sleep(.5)
shot('release-pages.png')
tap(find('第 2 页'))
find('2 / 2')
tap(find('大纲'))
tap(find('打开，即阅读。'))
find('1 / 2')
tap(find('大纲'))
find('第二页 / Page Two')
shot('release-outline.png')
tap(find('第二页 / Page Two'))
find('2 / 2')
tap(find('恢复默认'))
tap(find('锁定尺寸'))
find('解锁尺寸')
tap(find('解锁尺寸'))

tap(find('导出 PDF'))
state = tree()
switches = [n for n in state.iter('node') if n.get('package') == 'com.android.settings' and n.get('checkable') == 'true']
if switches:
    if switches[0].get('checked') != 'true':
        tap(switches[0])
    run('shell', 'input', 'keyevent', '4')
find('返回')
fetch_pdf(stem + '.pdf', output / 'exported-final.pdf')
assert run('shell', 'test', '!', '-e', '/sdcard/Download/' + stem + '.pdf') == ''

# Exercise overwrite with a known non-PDF sentinel.
sentinel = output / 'export-sentinel.pdf'
sentinel.write_bytes(b'old target')
run('push', str(sentinel), '/sdcard/Documents/' + stem + '.pdf')
tap(find('导出 PDF'))
tap(find('覆盖'))
fetch_pdf(stem + '.pdf', output / 'overwritten-final.pdf')
tap(find('导出 PDF'))
tap(find('都保存'))
fetch_pdf(stem + ' (1).pdf', output / 'both-final.pdf')
tap(find('返回'))
pick(stem + '.pdf', 'Documents')
assert not any(n.get('content-desc') == '导出 PDF' for n in tree().iter('node'))
shot('release-pdf.png')
tap(find('返回'))
run('pull', '/sdcard/Download/' + docx_name, str(output / 'original-after.docx'))
assert (output / 'original-after.docx').read_bytes() == (root / 'app/src/androidTest/assets/sample.docx').read_bytes()
report = {'result': 'PASS', 'checks': ['minimal home', 'icon toolbar', 'outline jump', 'thumbnail jump',
    'text fit auto-lock', 'Documents export', 'overwrite', 'keep both suffix', 'PDF open', 'source unchanged']}
(output / 'release-ui-test.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(report)
