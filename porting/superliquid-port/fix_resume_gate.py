from pathlib import Path
p=Path(__file__).parent/'port-dex/smali/btm/m/ported/superliquid/io/github/liuran001/mmliquidglass/LiquidGlassModule.smali'
s=p.read_text(encoding='utf-8')
i=s.index('.method private static synthetic lambda$onModuleLoaded$1(')
j=s.index('.end method',i)
body=s[i:j]
marker='    move-result-object v2\n'
assert marker in body
body=body.replace(marker,marker+'''
    sget-boolean v3, Lbtm/m/ported/superliquid/io/github/liuran001/mmliquidglass/GlassConfig;->enabled:Z
    if-nez v3, :port_enabled
    return-object v2
    :port_enabled
''',1)
s=s[:i]+body+s[j:]
s=s.replace('not a main process we dress up, detach','HyperChanger port: skipping unrelated process')
s=s.replace('onModuleLoaded BUILD=0.4.8-r19','HyperChanger port onModuleLoaded BUILD=0.4.8-r19')
p.write_text(s,encoding='utf-8')
