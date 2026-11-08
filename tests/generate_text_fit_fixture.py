"""Small PDF fixtures with known text positions, mixed page sizes and graphics."""
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / 'app/src/androidTest/assets'


def write(name, pages):
    # One shared standard font and a 1px image (neither needs external assets).
    objects = ['', '', '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
               '<< /Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Length 3 >>\nstream\nabc\nendstream']
    kids = []
    for width, height, metadata, content in pages:
        page_id = len(objects) + 1
        kids.append(f'{page_id} 0 R')
        objects += [f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {width} {height}] {metadata} '
                    f'/Resources << /Font << /F1 3 0 R >> /XObject << /Im0 4 0 R >> >> /Contents {page_id+1} 0 R >>',
                    f'<< /Length {len(content)} >>\nstream\n{content}\nendstream']
    objects[0] = '<< /Type /Catalog /Pages 2 0 R >>'
    objects[1] = f'<< /Type /Pages /Kids [{" ".join(kids)}] /Count {len(pages)} >>'
    data = bytearray(b'%PDF-1.7\n')
    offsets = [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data += f'{i} 0 obj\n{obj}\nendobj\n'.encode('ascii')
    xref = len(data)
    data += f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode('ascii')
    for offset in offsets[1:]:
        data += f'{offset:010d} 00000 n \n'.encode('ascii')
    data += f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode('ascii')
    (OUT / name).write_bytes(data)


write('text-fit.pdf', [
    (400, 600, '', 'BT /F1 16 Tf 140 400 Td (First) Tj ET'),
    (800, 600, '', 'BT /F1 16 Tf 80 400 Td (Left) Tj 420 0 Td (Other) Tj ET'),
    (400, 600, '', 'BT /F1 16 Tf 320 400 Td (H) Tj ET'),
    (400, 600, '', '0 0 400 600 re f q 400 0 0 600 0 0 cm /Im0 Do Q'),
])
write('text-fit-rotated.pdf', [(400, 600, '/CropBox [100 100 300 500] /Rotate 90',
    'BT /F1 20 Tf 150 200 Td (H) Tj ET BT /F1 20 Tf 20 550 Td (Outside) Tj ET')])
write('text-fit-empty.pdf', [(400, 600, '', '0 0 400 600 re f q 400 0 0 600 0 0 cm /Im0 Do Q')])
write('text-fit-narrow.pdf', [(400, 600, '', 'BT /F1 16 Tf 200 300 Td (H) Tj ET')])
write('text-fit-full.pdf', [(400, 600, '', 'BT /F1 60 Tf -20 300 Td (H) Tj 410 0 Td (H) Tj ET')])
