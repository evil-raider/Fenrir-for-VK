#!/usr/bin/env python3
"""JVM contract tests of actual production algorithms/store/logger; no Android SDK/NDK.
Android stubs do NOT exercise real Media3 event ordering or AtomicFile crash guarantees.
Downloads Kotlin tooling from Maven Central into a cache; run: python3 ci/test-playback.py
"""
from pathlib import Path
import os, subprocess, tempfile, urllib.request
from concurrent.futures import ThreadPoolExecutor
ROOT = Path(__file__).resolve().parent.parent
CACHE = Path(os.environ.get('PLAYBACK_KOTLIN_CACHE', Path.home()/'.cache/fenrir-playback-kotlin'))
CACHE.mkdir(parents=True, exist_ok=True)
ARTIFACTS = [
 ('org/jetbrains/kotlin', 'kotlin-compiler-embeddable', '2.3.20'),
 ('org/jetbrains/kotlin', 'kotlin-stdlib', '2.3.20'),
 ('org/jetbrains/kotlin', 'kotlin-script-runtime', '2.3.20'),
 ('org/jetbrains/kotlin', 'kotlin-reflect', '1.6.10'),
 ('org/jetbrains/kotlin', 'kotlin-daemon-embeddable', '2.3.20'),
 ('org/jetbrains/kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
 ('org/jetbrains', 'annotations', '13.0'),
 ('org/jetbrains/kotlin', 'kotlin-serialization-compiler-plugin-embeddable', '2.3.20'),
 ('org/jetbrains/kotlinx', 'kotlinx-serialization-core-jvm', '1.10.0'),
 ('org/jetbrains/kotlinx', 'kotlinx-serialization-json-jvm', '1.10.0'),
 ('org/json', 'json', '20250517'),
]
def get(item):
    group, name, version = item
    dest = CACHE/f'{name}-{version}.jar'
    if not dest.exists():
        tmp = dest.with_suffix('.tmp')
        urllib.request.urlretrieve(f'https://repo.maven.apache.org/maven2/{group}/{name}/{version}/{dest.name}', tmp)
        tmp.rename(dest)
    return str(dest)
with ThreadPoolExecutor(max_workers=6) as pool: jars = list(pool.map(get, ARTIFACTS))
classpath = os.pathsep.join(jars)
source = ROOT/'app_fenrir/src/main/kotlin/dev/ragnarok/fenrir'
with tempfile.TemporaryDirectory(prefix='fenrir-playback-') as temp:
    temp = Path(temp)
    text = (source/'media/music/MusicPlaybackService.kt').read_text()
    # Verbatim class extraction: tests fail on production algorithm changes, not a copy.
    body = text[text.index('    class MusicShuffleOrder '):text.index('    private class ServiceStub(')]
    (temp/'Order.kt').write_text('package dev.ragnarok.fenrir.media.music\n'
        'import androidx.media3.exoplayer.source.ShuffleOrder\nimport kotlin.random.Random\n'
        'object C { const val INDEX_UNSET = -1 }\nclass MusicPlaybackService {\n'+body+'}\n')
    text = (source/'media/music/PlaybackResume.kt').read_text()
    text = text[:text.index('/**\n * FENRIR-CI: ленивое')]
    text = '\n'.join(line for line in text.splitlines() if not any(line.startswith('import '+prefix)
        for prefix in ['dev.ragnarok.fenrir.domain.', 'dev.ragnarok.fenrir.settings.', 'kotlinx.coroutines.']))
    (temp/'Resume.kt').write_text(text)
    files = list((ROOT/'ci/playback-tests').glob('*.kt')) + [temp/'Order.kt', temp/'Resume.kt',
        source/'util/PersistentShuffle.kt', source/'util/PlaybackTrace.kt']
    plugin = next(j for j in jars if 'serialization-compiler-plugin' in j)
    subprocess.run(['java', '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', classpath, f'-Xplugin={plugin}',
        '-d', str(temp/'test.jar'), *map(str, files)], check=True)
    subprocess.run(['java', '-cp', str(temp/'test.jar')+os.pathsep+classpath,
        'dev.ragnarok.fenrir.media.music.TestsKt'], check=True, timeout=120)
