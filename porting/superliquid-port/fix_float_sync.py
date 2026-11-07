from pathlib import Path
p=Path(__file__).parent/'hyper-smali/smali/jg.smali'
s=p.read_text(encoding='utf-8')
marker='    :cond_2\n    instance-of v2, v0, Ljava/lang/String;'
assert marker in s
s=s.replace(marker,'''    :cond_2
    instance-of v2, v0, Ljava/lang/Float;
    if-eqz v2, :port_not_float
    check-cast v0, Ljava/lang/Number;
    invoke-virtual {v0}, Ljava/lang/Number;->floatValue()F
    move-result v0
    invoke-interface {p0, v1, v0}, Landroid/content/SharedPreferences$Editor;->putFloat(Ljava/lang/String;F)Landroid/content/SharedPreferences$Editor;
    goto :goto_0
    :port_not_float
    instance-of v2, v0, Ljava/lang/String;''',1)
p.write_text(s,encoding='utf-8')
