from pathlib import Path
import re, json

ROOT = Path(__file__).resolve().parent
src = ROOT / 'super-smali/smali'
dst = ROOT / 'super-smali/smali'
prefix = 'btm/m/ported/superliquid/'
classes = {}
for p in src.rglob('*.smali'):
    text = p.read_text(encoding='utf-8')
    name = re.search(r'^\.class (?:.* )?(L[^;]+;)', text, re.M).group(1)
    classes[name] = (p, text)
core = 'Lio/github/liuran001/mmliquidglass/'
needed = {n for n in classes if n.startswith(core) and '/' not in n[len(core):] and not n.startswith(core+'R')}
pending = list(needed)
while pending:
    name = pending.pop()
    for ref in re.findall(r'L[\w/$-]+;', classes[name][1]):
        if ref in classes and ref not in needed:
            needed.add(ref)
            pending.append(ref)
mapping = {n: 'L'+prefix+n[1:] for n in needed}
out = ROOT / 'port-dex/smali'
out.mkdir(parents=True, exist_ok=True)
for name in sorted(needed):
    text = classes[name][1]
    if name == core+'HostApp;':
        for method in ('forPackage', 'forProcess'):
            signature = '.method public static '+method+'(Ljava/lang/String;)'+core+'HostApp;'
            body = signature+'''
    .locals 2
    const-string v0, "com.tencent.mm"
    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :try_heybox
    sget-object v0, '''+core+'''HostApp;->WECHAT:'''+core+'''HostApp;
    return-object v0
    :try_heybox
    const-string v0, "com.max.xiaoheihe"
    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :skip
    sget-object v0, '''+core+'''HostApp;->HEYBOX:'''+core+'''HostApp;
    return-object v0
    :skip
    const/4 v0, 0x0
    return-object v0
.end method'''
            text, count = re.subn(re.escape(signature)+r'.*?\.end method', lambda _:body, text, flags=re.S)
            assert count == 1
    if name == core+'LiquidGlassModule;':
        # A second entry point must never detach other HyperChanger entry points.
        text, count = re.subn(r'    invoke-virtual \{p0\}, Lio/github/libxposed/api/XposedModule;->detach\(\)V\n', '', text)
        if not count:
            text, count = re.subn(r'    invoke-virtual \{p0\}, '+re.escape(core)+r'LiquidGlassModule;->detach\(\)V\n', '', text)
        assert count == 1
    if name == core+'GlassConfig;':
        text = text.replace('dongder.super.liquid', 'btm.m.os4.systemuihook')
        text = text.replace('"superliquid"', '"superliquid_port"')
    text = re.sub(r'L[\w/$-]+;', lambda m:mapping.get(m[0], m[0]), text)
    target = out / (mapping[name][1:-1]+'.smali')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf-8')

# Assemble only the modified HyperChanger dex; retain its other original dex files.
hyper = ROOT / 'hyper-smali'
p = hyper / 'smali/ze.smali'
text = p.read_text(encoding='utf-8')
sig = '.method public static b(Lio/github/libxposed/api/XposedModule;Ljava/lang/ClassLoader;Lbe;ILjava/lang/String;Ljava/lang/String;ZLjava/lang/String;ILjava/util/Set;ZI)V\n    .locals 11\n'
assert sig in text
text = text.replace(sig, sig+'''
    sget-object v0, Lze;->n:Lbe;
    if-ne p2, v0, :port_other_app
    return-void
    :port_other_app
''', 1)
p.write_text(text, encoding='utf-8')

# Add the target to HyperChanger's existing app-navigation list.
p = hyper / 'smali/hg.smali'
text = p.read_text(encoding='utf-8')
marker = '    filled-new-array/range {v0 .. v14}, [Lhf;'
assert marker in text
text = text.replace(marker, '''    new-instance v15, Lhf;
    const-string v16, "小黑盒"
    const-string p0, "com.max.xiaoheihe"
    invoke-direct {v15, v16, p0}, Lhf;-><init>(Ljava/lang/String;Ljava/lang/String;)V
    filled-new-array/range {v0 .. v15}, [Lhf;''', 1)
# <clinit> has no parameters; use locals v17 rather than p0.
text = text.replace('    .locals 17\n', '    .locals 18\n', 1)
text = text.replace('const-string p0, "com.max.xiaoheihe"', 'const-string v17, "com.max.xiaoheihe"').replace('{v15, v16, p0}', '{v15, v16, v17}')
# invoke-direct 35c only supports v0..v15. Use scratch registers after preserving tabs via array.
start = text.index('    new-instance v15, Lhf;')
end = text.index('    .line 141', start)
text = text[:start]+'''    filled-new-array/range {v0 .. v14}, [Lhf;
    move-result-object v0
    invoke-static {v0}, Lb74;->U([Ljava/lang/Object;)Ljava/util/List;
    move-result-object v0
    new-instance v1, Ljava/util/ArrayList;
    invoke-direct {v1, v0}, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
    new-instance v2, Lhf;
    const-string v3, "小黑盒"
    const-string v4, "com.max.xiaoheihe"
    invoke-direct {v2, v3, v4}, Lhf;-><init>(Ljava/lang/String;Ljava/lang/String;)V
    invoke-virtual {v1, v2}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    sput-object v1, Lhg;->a:Ljava/util/List;
    return-void
.end method
'''+text[text.index('.method public static final a(', end):]
p.write_text(text, encoding='utf-8')

# Raw manifest/resources are not rebuilt for the payload dex.
(ROOT/'port-dex/AndroidManifest.xml').write_bytes((ROOT/'super-smali/AndroidManifest.xml').read_bytes())
(ROOT/'port-dex/resources.arsc').write_bytes((ROOT/'super-smali/resources.arsc').read_bytes())
(ROOT/'port-dex/apktool.yml').write_text((ROOT/'super-smali/apktool.yml').read_text(), encoding='utf-8')
(ROOT/'class-map.json').write_text(json.dumps(mapping, indent=2), encoding='utf-8')
print(f'Prepared {len(needed)} relocated dependency classes')
