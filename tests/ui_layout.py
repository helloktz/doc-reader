"""Exercise the reader chrome at small/large sizes on the test emulator only."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'test-output'
ADB = str(Path(os.environ.get('ANDROID_HOME', r'D:\Developer\tools\AndroidSDK')) / 'platform-tools/adb.exe')
COMPONENT = 'io.github.helloktz.docreader/.MainActivity'


def adb(*args):
    return subprocess.check_output([ADB, '-s', 'emulator-5554', *args], encoding='utf-8', errors='replace')


def hierarchy():
    for _ in range(3):
        if 'dumped to:' in adb('shell', 'uiautomator', 'dump', '/sdcard/reader-layout.xml'):
            return list(ET.fromstring(adb('shell', 'cat', '/sdcard/reader-layout.xml')).iter('node'))
    raise AssertionError('Could not inspect emulator UI')


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds'))))


def named(nodes, value):
    return next(n for n in nodes if n.get('content-desc') == value or n.get('text') == value)


def tap(node):
    x1, y1, x2, y2 = bounds(node)
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def shot(name):
    adb('shell', 'screencap', '-p', '/sdcard/reader-layout.png')
    adb('pull', '/sdcard/reader-layout.png', str(OUT / (name + '.png')))


def launch(document=False):
    args = ['shell', 'am', 'start', '-S', '-W', '-n', COMPONENT]
    if document:
        args += ['-a', 'android.intent.action.VIEW', '-d',
                 'file:///sdcard/Download/reader-layout.docx',
                 '-t', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document']
    else:
        args += ['-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-f', '0x10008000']
    adb(*args)


def assert_controls(nodes, width, height):
    for node in nodes:
        if node.get('class') == 'android.widget.ImageButton' and node.get('package') == 'io.github.helloktz.docreader':
            x1, y1, x2, y2 = bounds(node)
            assert x2 - x1 >= 96 and y2 - y1 >= 96, ('48dp target', node.attrib)
            assert 0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height, ('clipped control', node.attrib)
            assert node.get('content-desc'), 'Unnamed icon'


def setting(namespace, key):
    return adb('shell', 'settings', 'get', namespace, key).strip()


originals = {(ns, key): setting(ns, key) for ns, key in [
    ('system', 'font_scale'), ('global', 'animator_duration_scale'),
    ('global', 'transition_animation_scale'), ('global', 'window_animation_scale')]}
old_size = re.search(r'Override size: (\d+x\d+)', adb('shell', 'wm', 'size'))
old_density = re.search(r'Override density: (\d+)', adb('shell', 'wm', 'density'))
old_night = adb('shell', 'cmd', 'uimode', 'night').strip().split()[-1]
cases = [('phone-375', 750, 1500, '1.0'), ('small-large-text', 640, 1280, '2.0'), ('compact-window', 480, 1400, '1.0'),
         ('phone-landscape', 1500, 750, '1.0'), ('tablet-portrait', 1600, 2400, '1.0'),
         ('tablet-landscape', 2400, 1600, '1.0')]
if len(sys.argv) > 1:
    cases = [case for case in cases if case[0] in sys.argv[1:]]
    assert cases, 'No matching layout cases'
passed = []
try:
    # Explicit file intent uses the emulator app's already-granted file access;
    # shell cannot grant a DocumentsProvider URI that it does not own.
    adb('push', str(ROOT / 'app/src/androidTest/assets/sample.docx'), '/sdcard/Download/reader-layout.docx')
    for _, key in [k for k in originals if k[0] == 'global']:
        adb('shell', 'settings', 'put', 'global', key, '0')
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    adb('shell', 'wm', 'density', '320')
    for name, width, height, scale in cases:
        adb('shell', 'wm', 'size', f'{width}x{height}')
        adb('shell', 'settings', 'put', 'system', 'font_scale', scale)
        launch()
        nodes = hierarchy()
        assert [n.get('text') for n in nodes if n.get('text')] == ['打开文件']
        if name == 'small-large-text':
            shot('layout-home-large-text')
        launch(document=True)
        for _ in range(4):
            nodes = hierarchy()
            if any(n.get('text') == '1 / 2' for n in nodes):
                break
        assert_controls(nodes, width, height)
        counter = named(nodes, '1 / 2')
        cx1, cy1, cx2, cy2 = bounds(counter)
        for node in nodes:
            if node.get('class') == 'android.widget.ImageButton':
                x1, y1, x2, y2 = bounds(node)
                assert cx1 >= x2 or cx2 <= x1 or cy1 >= y2 or cy2 <= y1, 'Page counter overlaps an action'
        shot('layout-' + name)
        tap(named(nodes, '适应文字'))
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            nodes = hierarchy()
            if named(nodes, '适应文字').get('selected') == 'true':
                break
        else:
            raise AssertionError('Text fitting did not finish')
        assert_controls(nodes, width, height)
        if name in ['phone-375', 'small-large-text']:
            shot('layout-text-fit-' + name)
        assert named(nodes, '解锁尺寸').get('selected') == 'true'
        assert named(nodes, '恢复默认').get('enabled') == 'false'
        assert named(nodes, '适应文字').get('enabled') == 'false'
        tap(named(nodes, '大纲'))
        nodes = hierarchy()
        assert_controls(nodes, width, height)
        assert all(n.get('selected') == 'true' for n in nodes if n.get('content-desc') == '大纲')
        named(nodes, '第二页 / Page Two')
        if name == 'small-large-text':
            shot('layout-outline-large-text')
        tap(named(nodes, '页面预览'))
        nodes = hierarchy()
        assert_controls(nodes, width, height)
        assert all(n.get('selected') == 'true' for n in nodes if n.get('content-desc') == '页面预览')
        if name == 'tablet-landscape':
            shot('layout-tablet-pages')
        passed.append(name)
        print('PASS:', name, flush=True)
finally:
    adb('shell', 'wm', 'size', old_size.group(1) if old_size else 'reset')
    adb('shell', 'wm', 'density', old_density.group(1) if old_density else 'reset')
    for (ns, key), value in originals.items():
        if value == 'null':
            adb('shell', 'settings', 'delete', ns, key)
        else:
            adb('shell', 'settings', 'put', ns, key, value)
    adb('shell', 'cmd', 'uimode', 'night', old_night)
    launch()

(OUT / ('layout-results-selected.json' if len(sys.argv) > 1 else 'layout-results.json')).write_text(json.dumps({'result': 'PASS', 'cases': passed,
    'conditions': ['system dark mode', 'system animations disabled', 'font scale up to 2.0', '48dp targets']}, indent=2), encoding='utf-8')
print('PASS: layout and selected states; emulator settings restored', flush=True)
