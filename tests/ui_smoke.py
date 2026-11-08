"""Exercise system document picking/export against an already installed build."""
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
export_name = f'doc-reader-ui-{int(time.time())}.pdf'

def run(*args):
    return subprocess.check_output([adb, *args], encoding='utf-8', errors='replace')

def tree():
    for _ in range(3):
        result = run('shell', 'uiautomator', 'dump', '/sdcard/doc-reader-ui.xml')
        if 'dumped to:' in result:
            return ET.fromstring(run('shell', 'cat', '/sdcard/doc-reader-ui.xml'))
        time.sleep(.3)
    raise AssertionError('Could not obtain a fresh UI hierarchy')

def find(text, timeout=15, cls=None):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for node in tree().iter('node'):
            if ''.join(node.get('text', '').split()) == ''.join(text.split()) and (not cls or node.get('class') == cls):
                return node
        time.sleep(.2)
    raise AssertionError(f'UI text not found: {text}')

def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    run('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def shot(name):
    run('shell', 'screencap', '-p', '/sdcard/doc-reader-shot.png')
    run('pull', '/sdcard/doc-reader-shot.png', str(output / name))

run('push', str(root / 'app/src/androidTest/assets/sample.docx'), '/sdcard/Download/reading-test.docx')
run('push', str(output / 'sample.pdf'), '/sdcard/Download/reading-test.pdf')
run('shell', 'am', 'force-stop', 'io.github.helloktz.docreader')
run('shell', 'am', 'start', '-n', 'io.github.helloktz.docreader/.MainActivity')
find('打开文件')
shot('release-home.png')
visible_text = [n.get('text') for n in tree().iter('node') if n.get('text')]
assert visible_text == ['打开文件'], visible_text
tap(find('打开文件'))
tap(find('reading-test.docx'))
tap(find('导出 PDF', timeout=30))
field = next(n for n in tree().iter('node') if n.get('class') == 'android.widget.EditText')
tap(field)
run('shell', 'input', 'keyevent', '123')
run('shell', 'input', 'keyevent', *(['67'] * 50))
run('shell', 'input', 'text', export_name)
tap(find('SAVE'))
# Repeated test runs may ask to replace the same generated destination.
try:
    export = find('导出 PDF', timeout=5)
except AssertionError:
    tap(find('REPLACE'))
    export = find('导出 PDF')
time.sleep(.5)
run('pull', '/sdcard/Download/' + export_name, str(output / 'exported-final.pdf'))
assert (output / 'exported-final.pdf').read_bytes().startswith(b'%PDF-')
tap(find('‹ 返回'))
tap(find('打开文件'))
tap(find('reading-test.pdf'))
find('reading-test.pdf')
assert not any(n.get('text') == '导出 PDF' for n in tree().iter('node'))
shot('release-pdf.png')
tap(find('‹ 返回'))
tap(find('打开文件'))
tap(find('reading-test.docx'))
find('导出 PDF', timeout=30)
find('reading-test.docx')
time.sleep(.5)
shot('release-docx.png')
run('shell', 'input', 'swipe', '540', '1900', '540', '500', '500')
find('2 / 2')
tap(find('‹ 返回'))
run('pull', '/sdcard/Download/reading-test.docx', str(output / 'original-after.docx'))
assert (output / 'original-after.docx').read_bytes() == (root / 'app/src/androidTest/assets/sample.docx').read_bytes()
report = {'result': 'PASS', 'checks': ['minimal home', 'SAF PDF export', 'SAF PDF open', 'SAF DOCX open', 'page scrolling', 'source unchanged']}
(output / 'release-ui-test.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(report)
