"""scripts/api-inventory.py: list Android framework members the app uses that are newer than minSdk, with the file:line of each use.
Source of truth: the SDK's api-versions.xml. Heuristic match: imported android.* class + member name used in that file."""
import re, sys, glob, os, xml.etree.ElementTree as ET
from collections import defaultdict
SDK=os.path.expanduser('~/Library/Android/sdk'); MIN=28
xmlp=sorted(glob.glob(f'{SDK}/platforms/android-*/data/api-versions.xml'))[-1]
root=ET.parse(xmlp).getroot()
classes={}
for c in root.findall('class'):
    name=c.get('name').replace('/','.').replace('$','.'); since=int(float(c.get('since','1')))
    members=defaultdict(lambda:999)
    for m in list(c.findall('method'))+list(c.findall('field')):
        mn=m.get('name').split('(')[0]; s=int(float(m.get('since',since)))
        members[mn]=min(members[mn], s)
    classes[name]=(since,members,[e.get('name').replace('/','.').replace('$','.') for e in c.findall('extends')])
def lookup(cls, member, depth=0):
    if cls not in classes or depth>6: return None
    since,members,ext=classes[cls]
    if member in members: return members[member]
    for e in ext:
        r=lookup(e,member,depth+1)
        if r: return r
    return None
rows=[]
for f in sorted(glob.glob('app/src/main/kotlin/**/*.kt', recursive=True)):
    src=open(f).read(); lines=src.splitlines()
    imps=re.findall(r'^import (android\.[\w.]+)', src, re.M)
    for imp in imps:
        simple=imp.split('.')[-1]
        if imp in classes and classes[imp][0]>MIN:
            ln=next(i for i,l in enumerate(lines,1) if l.startswith('import '+imp))
            rows.append((classes[imp][0], imp, '(class)', f, ln))
    # members: accessor or call on anything, attributed to imported classes that define it
    used=set(re.findall(r'\.(\w+)\b', src)) | set(re.findall(r'override fun (\w+)', src)) | set(re.findall(r'\b(set[A-Z]\w+|get[A-Z]\w+|is[A-Z]\w+)\b', src))
    # Kotlin property syntax: wallpaperFlags -> getWallpaperFlags
    props=set(re.findall(r'\b([a-z]\w+)\b', src))
    owners=[i for i in imps if i in classes] + ['android.service.wallpaper.WallpaperService.Engine','android.service.dreams.DreamService','android.content.Context','android.app.Service']
    for cls in owners:
        if cls not in classes: continue
        for mem,s in classes[cls][1].items():
            if s<=MIN or mem in ('<init>',): continue
            hit = mem in used or ('get'+mem[:1].upper()+mem[1:]) in used
            kprop = mem.startswith('get') and len(mem)>3 and (mem[3].lower()+mem[4:]) in props
            kis = mem.startswith('is') and mem in props
            if hit or kprop or kis:
                pat = re.compile(r'\b(%s|%s)\b' % (re.escape(mem), re.escape(mem[3].lower()+mem[4:]) if mem.startswith('get') and len(mem)>3 else re.escape(mem)))
                ln=next((i for i,l in enumerate(lines,1) if pat.search(l) and not l.lstrip().startswith(('import','//','*','/*'))), None)
                if ln: rows.append((s, cls, mem, f, ln))
seen=set()
for s,cls,mem,f,ln in sorted(rows):
    k=(cls,mem,f)
    if k in seen: continue
    seen.add(k)
    print(f"{s}\t{cls.replace('android.','')}.{mem}\t{f.replace('app/src/main/kotlin/dev/fengine/wallpaper/','')}:{ln}")
