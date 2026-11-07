from pathlib import Path
import zipfile
R=Path(__file__).parent
out=R/'unsigned.apk'
with zipfile.ZipFile(R/'hyperchanger-before.apk') as base,zipfile.ZipFile(R/'ui-patched.apk') as ui,zipfile.ZipFile(out,'w') as z:
    for info in base.infolist():
        n=info.filename
        if n.startswith('META-INF/') and n.upper().endswith(('.RSA','.DSA','.SF','.MF')):continue
        data=base.read(n)
        if n=='classes.dex':data=ui.read(n)
        if n=='META-INF/xposed/java_init.list':data=data.rstrip()+b'\nbtm.m.ported.superliquid.io.github.liuran001.mmliquidglass.NeteaseKanadeModule\n'
        z.writestr(info,data)
    z.writestr('classes7.dex',(R/'dex/classes.dex').read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
with zipfile.ZipFile(R/'hyperchanger-before.apk') as old,zipfile.ZipFile(out) as new:
    for n in old.namelist():
        if n.startswith('lib/') or n in ['resources.arsc','AndroidManifest.xml','classes2.dex','classes3.dex','classes4.dex','classes5.dex','classes6.dex']:
            assert old.read(n)==new.read(n),n
print('Preserved native libraries, resources and dex 2–6, including WeChat and Heybox.')
