#!/usr/bin/env python3
"""Run after the standalone build. Needs JSON_JAR (org.json 20240303), Java 17 and build env vars."""
import os, subprocess, zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
build=root/'build-standalone'
api=build/'android-api-only.jar'
# Remove Android's java.* stubs to avoid conflicts with Java 17 modules.
with zipfile.ZipFile(os.environ['ANDROID_JAR']) as source, zipfile.ZipFile(api,'w') as target:
    for name in source.namelist():
        if name.startswith('android/') and name.endswith('.class'):
            target.writestr(name,source.read(name))
classes=build/'jvm-tests';classes.mkdir(exist_ok=True)
cp=os.pathsep.join(map(str,[build/'classes',os.environ['JSON_JAR'],api]))
subprocess.run(['java','-jar',os.environ['ECJ_JAR'],'-source','17','-target','17','-nowarn','-classpath',cp,'-d',str(classes),str(root/'validation/StremioIntegrationTest.java')],check=True)
subprocess.run(['java','--add-modules','jdk.httpserver','-cp',str(classes)+os.pathsep+cp,'com.carstream.app.StremioIntegrationTest',str(root/'validation/stremio-results.json')],check=True)
