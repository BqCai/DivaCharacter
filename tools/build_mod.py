"""Compile with the registered JDK and the user's installed game/framework APIs."""
import argparse
import hashlib
import json
import os
import subprocess
import zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
JDK=Path(os.environ.get('DIVA_JDK','C:/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot'))/'bin'
CSC=Path('C:/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe')


def main(preview=False,output=None,public=False):
    cfg=json.loads((ROOT/'project.local.json').read_text())
    game=Path(cfg['paths']['pz'])/'projectzomboid.jar'
    buddy=Path(cfg['paths']['zombiebuddy'])/'libs/ZombieBuddy.jar'
    sources=list((ROOT/'src/main/java').rglob('*.java'))
    if not public:
        sources+=list((ROOT/'src/test/java').rglob('*.java'))
    fingerprint=hashlib.sha256(b''.join(p.read_bytes() for p in sorted(sources))).hexdigest()[:16]
    classes=ROOT/'build/classes'/fingerprint;classes.mkdir(parents=True,exist_ok=True)
    args=['--release','25','-encoding','UTF-8','-classpath',str(game)+';'+str(buddy),'-d',str(classes)]+[str(p) for p in sources]
    argfile=ROOT/'build/javac.args'
    argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
    subprocess.run([str(JDK/'javac.exe'),'@'+str(argfile)],check=True)
    importer=ROOT/'build/import-tool/DivaImport.exe'
    importer.parent.mkdir(parents=True,exist_ok=True)
    importer_sources=[ROOT/'tools/DivaImport.cs',ROOT/'tools/DivaPv.cs']
    importer_config=(ROOT/'tools/DivaImport.exe.config').read_bytes()
    subprocess.run([str(CSC),'/nologo','/target:exe','/platform:x64',f'/out:{importer}',
                    '/reference:System.Numerics.dll','/reference:System.Web.Extensions.dll','/reference:System.Windows.Forms.dll',
                    f"/reference:{Path(cfg['paths']['mml'])/'MikuMikuLibrary.dll'}",*[str(p) for p in importer_sources]],check=True)
    importer.with_suffix('.exe.config').write_bytes(importer_config)
    resources=[]
    if not public:
        physics=ROOT/'build/bundled-physics'
        subprocess.run([str(importer),'physics',cfg['paths']['diva'],str(physics),'0'],check=True)
        physics_index=physics/'physics.json'
        resources.extend((physics,p) for p in [physics_index,*[physics/part['physicsFile']
            for part in json.loads(physics_index.read_text(encoding='utf-8'))]])
        faces=ROOT/'build/bundled-faces'
        subprocess.run([str(importer),'faces',cfg['paths']['diva'],str(faces)],check=True)
        face_index=faces/'faces.json'
        metadata=json.loads(face_index.read_text(encoding='utf-8'))
        files=[file for mesh in metadata['meshes'] for file in (mesh['vertexFile'],mesh['indexFile'])]
        files.extend(file for texture in metadata['textures'].values() for file in texture['data'])
        resources.extend((faces,p) for p in [face_index,faces/'gameplay.bin',*[faces/file for file in files]])
    jar=Path(output).resolve() if output else ROOT/'mod/DivaCharacter/42/media/java/client/DivaCharacter.jar';jar.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
        roots=(classes,ROOT/'src/main/resources') if public else (classes,ROOT/'src/main/resources',ROOT/'build/resources')
        for root in roots:
            for p in sorted(root.rglob('*')):
                if p.is_file():
                    info=zipfile.ZipInfo(p.relative_to(root).as_posix(),date_time=(1980,1,1,0,0,0))
                    info.compress_type=zipfile.ZIP_DEFLATED
                    z.writestr(info,p.read_bytes())
        info=zipfile.ZipInfo('divachar/tools/DivaImport.exe',date_time=(1980,1,1,0,0,0))
        z.writestr(info,importer.read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
        info=zipfile.ZipInfo('divachar/tools/DivaImport.exe.config',date_time=(1980,1,1,0,0,0))
        z.writestr(info,importer_config,compress_type=zipfile.ZIP_DEFLATED)
        info=zipfile.ZipInfo('divachar/tools/importer.version',date_time=(1980,1,1,0,0,0))
        z.writestr(info,hashlib.sha256(b''.join(p.read_bytes() for p in importer_sources)+importer_config).hexdigest())
        for root,p in resources:
            info=zipfile.ZipInfo('divachar/'+p.relative_to(root).as_posix(),date_time=(1980,1,1,0,0,0))
            z.writestr(info,p.read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
    print('Built',jar,jar.stat().st_size,'bytes')
    if public:
        return
    subprocess.run([str(JDK/'java.exe'),'--enable-native-access=ALL-UNNAMED','-Djava.awt.headless=true',
                    '-cp',';'.join(str(p) for p in (jar,game,buddy,Path(cfg['paths']['viewpoint'])/'42/media/java/client/Viewpoint.jar')),
                    'divachar.CompatibilityCheck'],check=True,cwd=ROOT)
    if preview:
        subprocess.run([str(JDK/'java.exe'),'--enable-native-access=ALL-UNNAMED','-Djava.awt.headless=true',
                        '-cp',str(jar)+';'+str(game),'divachar.GpuPreview',str(ROOT/'reports/miku-gpu-preview.png')],check=True,cwd=ROOT)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--preview',action='store_true');parser.add_argument('--output',type=Path);args=parser.parse_args();main(args.preview,args.output)
