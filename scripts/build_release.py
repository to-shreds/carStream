#!/usr/bin/env python3
import os,sys,subprocess,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
build=root/'build-standalone';build.mkdir(exist_ok=True)
for name in ['generated','classes','dex']: (build/name).mkdir(exist_ok=True)
tools=Path(os.environ['ANDROID_BUILD_TOOLS']);android=os.environ['ANDROID_JAR']
def run(args): subprocess.run([str(a) for a in args],check=True)
run([tools/'aapt2','compile','--dir',root/'app/src/main/res','-o',build/'resources.zip'])
run([tools/'aapt2','link','-o',build/'resources.apk','--manifest',root/'app/src/main/AndroidManifest.xml','-I',android,'--java',build/'generated','--min-sdk-version','29','--target-sdk-version','29','--version-code','25','--version-name','1.5.0','-A',root/'app/src/main/assets',build/'resources.zip'])
sources=list((root/'app/src/main/java').rglob('*.java'))+list((build/'generated').rglob('*.java'))
run(['java','-jar',os.environ['ECJ_JAR'],'-source','8','-target','8','-nowarn','-classpath',android,'-d',build/'classes',*sources])
with zipfile.ZipFile(build/'classes.jar','w') as z:
 for p in (build/'classes').rglob('*.class'): z.write(p,str(p.relative_to(build/'classes')))
run([tools/'d8','--release','--min-api','29','--lib',android,'--output',build/'dex',build/'classes.jar'])
with zipfile.ZipFile(build/'resources.apk') as source,zipfile.ZipFile(build/'unsigned.apk','w') as target:
 for n in source.namelist():target.writestr(source.getinfo(n),source.read(n))
 target.write(build/'dex/classes.dex','classes.dex',compress_type=zipfile.ZIP_DEFLATED)
run([tools/'zipalign','-f','-p','4',build/'unsigned.apk',build/'aligned.apk'])
out=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else build/'CarStream-v1.5.0.apk'
run([tools/'apksigner','sign','--ks',os.environ['CARSTREAM_KEYSTORE'],'--ks-key-alias',os.environ['CARSTREAM_KEY_ALIAS'],'--ks-pass','env:CARSTREAM_STORE_PASS','--key-pass','env:CARSTREAM_KEY_PASS','--out',out,build/'aligned.apk'])
run([tools/'apksigner','verify','--verbose','--print-certs',out])
print(out)
