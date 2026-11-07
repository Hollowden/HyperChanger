from pathlib import Path
p=Path(__file__).parent/'hyper-smali/smali/hg.smali'
s=p.read_text(encoding='utf-8')
needle='    sput-object v1, Lhg;->a:Ljava/util/List;'
assert s.count(needle)==1
s=s.replace(needle,'''    new-instance v2, Lhf;
    const-string v3, "网易云音乐"
    const-string v4, "com.netease.cloudmusic"
    invoke-direct {v2, v3, v4}, Lhf;-><init>(Ljava/lang/String;Ljava/lang/String;)V
    invoke-virtual {v1, v2}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
'''+needle)
p.write_text(s,encoding='utf-8')
