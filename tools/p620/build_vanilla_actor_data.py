#!/usr/bin/env python3
"""Reproduce the private 26.2 numeric models and data-only item/equipment resource pack.

Needs the owned official client and an explicit launcher-library classpath; no client/game code is
shipped in the result. Run it locally; generated assets are intentionally ignored by git.
"""
from pathlib import Path
import argparse,hashlib,json,subprocess,zipfile,re
p=argparse.ArgumentParser();p.add_argument('--client',type=Path,required=True);p.add_argument('--classpath-file',type=Path,required=True);p.add_argument('--api',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
repo=Path(__file__).resolve().parents[2];out=a.output.resolve()
if out.exists() or out==repo or out.is_relative_to(repo):raise SystemExit('Choose a new output directory outside the checkout')
client=a.client.resolve()
if hashlib.sha1(client.read_bytes()).hexdigest()!='2dc72797acbc1b63fc16a11c4ac393605f453754':raise SystemExit('Exact official 26.2 client required')
out.mkdir(parents=True);classes=out/'classes';classes.mkdir();cp=str(client)+':'+a.classpath_file.read_text().strip();subprocess.run(['javac','-cp',cp,'-d',str(classes),str(repo/'tools/p620/ExportVanillaActors.java')],check=True)
logconfig=out/'log4j.xml';logconfig.write_text('<Configuration><Appenders><Console name="Console" target="SYSTEM_OUT"><PatternLayout pattern="%m%n"/></Console></Appenders><Loggers><Root level="warn"><AppenderRef ref="Console"/></Root></Loggers></Configuration>')
with(out/'extraction.txt').open('w') as log:subprocess.run(['java','-Djava.awt.headless=true','-Dlog4j.configurationFile='+str(logconfig),'-Xmx768M','-cp',str(classes)+':'+cp,'baseline.actors.ExportVanillaActors',str(out/'models-26.2.json')],cwd=out,stdout=log,stderr=subprocess.STDOUT,check=True)
listing=out/'ListLiving.java';listing.write_text('import org.bukkit.entity.*; public class ListLiving {public static void main(String[] a){for(var t:EntityType.values())if(t.getEntityClass()!=null && LivingEntity.class.isAssignableFrom(t.getEntityClass()))System.out.println(t.name());}}')
api_cp=str(a.api.resolve())+':'+a.classpath_file.read_text().strip();types=subprocess.check_output(['java','-cp',api_cp,str(listing)],cwd=out,text=True).splitlines();assert len(types)==93
bindings=dict(re.findall(r'Map.entry\("([A-Z_]+)","([a-z_/]+)"\)',(repo/'src/main/java/com/wenkrang/famara/render/scene/VanillaActorRegistry.java').read_text()));assert set(types)==set(bindings)
doc=json.loads((out/'models-26.2.json').read_text());doc['livingTypes']=types;(out/'models-26.2.json').write_text(json.dumps(doc,separators=(',',':'))+'\n')
archive=out/'vanilla-assets-26.2.zip';prefixes=['assets/minecraft/blockstates/','assets/minecraft/textures/','assets/minecraft/models/','assets/minecraft/items/','assets/minecraft/equipment/']
with zipfile.ZipFile(client) as source,zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as target:
 for name in sorted(source.namelist()):
  if any(name.startswith(prefix) for prefix in prefixes) and not name.endswith('/'):
   entry=zipfile.ZipInfo(name,(1980,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;target.writestr(entry,source.read(name))
 for texture in bindings.values():assert 'assets/minecraft/textures/entity/'+texture+'.png' in source.namelist()
manifest={'format':1,'version':'26.2','sourceSha1':hashlib.sha1(client.read_bytes()).hexdigest(),'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'bytes':archive.stat().st_size,'containsGameClasses':False};(out/'manifest-26.2.json').write_text(json.dumps(manifest,indent=2)+'\n');print(json.dumps(manifest,indent=2))
