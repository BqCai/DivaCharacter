"""Stage the public test package without copying the development reference assets."""
import argparse
import hashlib
import io
import json
import tempfile
import zipfile
from pathlib import Path

import build_mod

VERSION='v0.1.0-alpha.1'
RELEASE_DATE=(2026,11,7,0,0,0)
MOD=build_mod.ROOT/'mod/DivaCharacter/42'
INCLUDED_MEDIA=('media/lua/client/DivaCharacter.lua',
                'media/fonts/divaCatalogSymbols.fnt','media/fonts/divaCatalogSymbols.png')
JAR_RESOURCES={
    'divachar/tools/DivaImport.exe','divachar/tools/DivaImport.exe.config','divachar/tools/importer.version',
    'divachar/shaders/local-material.vert','divachar/shaders/local-material.frag',
}


def entry(archive,name,data):
    info=zipfile.ZipInfo(name,date_time=RELEASE_DATE)
    archive.writestr(info,data,compress_type=zipfile.ZIP_DEFLATED)


def verify_jar(data):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        names=jar.namelist()
        code={name for name in names if name.startswith('divachar/') and name.endswith('.class')}
        if 'divachar/Main.class' not in code or len(names)!=len(set(names)) or set(names)-code!=JAR_RESOURCES:
            raise ValueError('Unexpected files in public JAR: '+', '.join(sorted(set(names)-code-JAR_RESOURCES)))


def build(output):
    output=output.resolve()
    output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='build-release-',dir=output.parent) as directory:
        staging=Path(directory)
        jar=staging/'DivaCharacter.jar'
        build_mod.main(output=jar,public=True)
        data=jar.read_bytes()
        verify_jar(data)
        info=(MOD/'mod.info').read_text(encoding='utf-8').splitlines()
        replace={'name':'DIVA Character','description':'Hatsune Miku for Project Viewpoint. Imports supported outfits and PVs from your local copy of Mega Mix+.'}
        metadata='\n'.join(f'{line.partition("=")[0]}={replace[line.partition("=")[0]]}'
            if line.partition('=')[0] in replace else line for line in info)+'\n'
        tuning=json.loads((MOD/'media/divachar-tuning.json').read_text(encoding='utf-8'))
        for key in ('capture','shaderReload','sourcePose'):
            tuning.pop(key,None)
        package=staging/output.name
        with zipfile.ZipFile(package,'w') as release:
            entry(release,'DivaCharacter/common/',b'')
            entry(release,'DivaCharacter/42/mod.info',metadata)
            entry(release,'DivaCharacter/42/media/java/client/DivaCharacter.jar',data)
            entry(release,'DivaCharacter/42/media/divachar-tuning.json',json.dumps(tuning,indent=2)+'\n')
            for name in INCLUDED_MEDIA:
                entry(release,'DivaCharacter/42/'+name,(MOD/name).read_bytes())
            entry(release,'DivaCharacter/INSTALL.md',
                  (build_mod.ROOT/'docs/releases'/f'{VERSION}.md').read_bytes())
            for name in ('README.md','LICENSE','THIRD-PARTY-NOTICES.md','licenses/ReDIVA-MIT.txt'):
                entry(release,'DivaCharacter/'+name,(build_mod.ROOT/name).read_bytes())
        package.replace(output)
    print(hashlib.sha256(output.read_bytes()).hexdigest(),output)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=build_mod.ROOT/'build/release'/f'DivaCharacter-{VERSION}.zip')
    build(parser.parse_args().output)
