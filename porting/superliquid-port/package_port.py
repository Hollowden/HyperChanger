from pathlib import Path
import zipfile, hashlib
R=Path(__file__).parent
out=R/'hyperchanger-superliquid-unsigned.apk'
with zipfile.ZipFile(R/'hyperchanger-before.apk') as base, zipfile.ZipFile(R/'hyper-patched.apk') as patched, zipfile.ZipFile(R/'port-payload.apk') as payload, zipfile.ZipFile(out,'w') as z:
    for info in base.infolist():
        name=info.filename
        if name.startswith('META-INF/') and name.upper().endswith(('.SF','.RSA','.DSA','.MF')): continue
        data=base.read(name)
        if name=='classes.dex': data=patched.read(name)
        if name=='META-INF/xposed/java_init.list': data=data.rstrip()+b'\nbtm.m.ported.superliquid.io.github.liuran001.mmliquidglass.LiquidGlassModule\n'
        if name=='META-INF/xposed/scope.list': data=data.rstrip()+b'\ncom.max.xiaoheihe\n'
        z.writestr(info,data)
    z.writestr('classes5.dex',payload.read('classes.dex'),compress_type=zipfile.ZIP_DEFLATED)
    z.writestr('classes6.dex',(R/'adapter-dex/classes.dex').read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
with zipfile.ZipFile(R/'hyperchanger-before.apk') as old,zipfile.ZipFile(out) as new:
    for name in old.namelist():
        if name.startswith('lib/') or name in ['classes2.dex','classes3.dex','classes4.dex','resources.arsc','AndroidManifest.xml']:
            assert old.read(name)==new.read(name),name
print(out)
print('Original native libraries, secondary dex, manifest, and resources preserved byte for byte.')
