from pathlib import Path
R=Path(__file__).parent
p=R/'port-dex/smali/btm/m/ported/superliquid/io/github/liuran001/mmliquidglass/GlassConfig.smali'
s=p.read_text(encoding='utf-8').replace('"superliquid_port"','"hyper_system_ui_hook"')
call='    invoke-static {p0}, Lbtm/m/ported/superliquid/io/github/liuran001/mmliquidglass/GlassConfig;->apply(Landroid/content/SharedPreferences;)V'
s=s.replace(call,call+'\n    invoke-static {p0}, Lbtm/m/ported/superliquid/io/github/liuran001/mmliquidglass/NavigationPreferences;->apply(Landroid/content/SharedPreferences;)V')
p.write_text(s,encoding='utf-8')
p=R/'hyper-smali/smali/ig.smali'
s=p.read_text(encoding='utf-8')
sig='.method public static final b(Ljava/lang/String;)Z\n    .locals 3\n'
assert sig in s
s=s.replace(sig,sig+'''
    const-string v0, "mm_"
    invoke-virtual {p0, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v0
    if-nez v0, :port_config
    const-string v0, "heybox_"
    invoke-virtual {p0, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :port_existing
    :port_config
    const/4 v0, 0x1
    return v0
    :port_existing
''',1)
p.write_text(s,encoding='utf-8')
