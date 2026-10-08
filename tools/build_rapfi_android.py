#!/usr/bin/env python3
"""Rebuild pinned Rapfi Android PIEs with official tools; never invokes Gradle or adb."""
from pathlib import Path
import argparse, hashlib, json, shutil, subprocess, urllib.request, zipfile

COMMIT = '3c94c2a976f24a0dd1c5517623e9ab6fffe66bd7'
SOURCE_SHA256 = '06c73fd9385c4ef65787c26e02cf20d8347b010622e33c8d0d8b543ca52ab141'
TOOLS = [
 ('android-ndk-r30-windows.zip', 'https://dl.google.com/android/repository/android-ndk-r30-windows.zip', 'b830098aaf18b67a42eb831c404e15e5f2990a474f054ac145b0bc957ac6d729', ''),
 ('cmake-4.4.4-windows-x86_64.zip', 'https://github.com/Kitware/CMake/releases/download/v4.4.4/cmake-4.4.4-windows-x86_64.zip', 'bace36e94b31c68ab6fa295f26dfa11219e0701cf7c94b0284a7d1cb13dac536', ''),
 ('ninja-win-1.13.2.zip', 'https://github.com/ninja-build/ninja/releases/download/v1.13.2/ninja-win.zip', '07fc8261b42b20e71d1720b39068c2e14ffcee6396b76fb7a795fb460b78dc65', 'ninja'),
]

def digest(path):
 h = hashlib.sha256()
 with path.open('rb') as data:
  while block := data.read(1024 * 1024): h.update(block)
 return h.hexdigest()

def extract(archive, destination):
 destination.mkdir(parents=True, exist_ok=True)
 with zipfile.ZipFile(archive) as bundle:
  for entry in bundle.namelist():
   if not (destination / entry).resolve().is_relative_to(destination.resolve()):
    raise ValueError('Archive path leaves the build cache')
  bundle.extractall(destination)

def main():
 parser = argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--cache', type=Path, default=Path('D:/DevCaches/RapfiToolchain'))
 parser.add_argument('--prepare-tools', action='store_true', help='Download and verify official Windows tool archives')
 parser.add_argument('--abi', action='append', choices=['x86_64', 'arm64-v8a'])
 parser.add_argument('--jobs', type=int, default=2)
 args = parser.parse_args()
 repo = Path(__file__).resolve().parents[1]
 cache = args.cache.resolve()
 if args.prepare_tools:
  downloads = cache / 'downloads'; downloads.mkdir(parents=True, exist_ok=True)
  for name, url, checksum, subdir in TOOLS:
   archive = downloads / name
   if not archive.is_file():
    print('Downloading official tool:', name, flush=True)
    request = urllib.request.Request(url, headers={'User-Agent': 'jiligulu-native-build'})
    with urllib.request.urlopen(request, timeout=120) as response, archive.open('wb') as output:
     shutil.copyfileobj(response, output)
   if digest(archive) != checksum: raise ValueError('Tool archive checksum mismatch: ' + name)
   extract(archive, cache / subdir)
 cmake = cache / 'cmake-4.4.4-windows-x86_64/bin/cmake.exe'
 ninja = cache / 'ninja/ninja.exe'
 ndk = cache / 'android-ndk-r30'
 llvm = ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin'
 for tool in [cmake, ninja, llvm / 'llvm-strip.exe', ndk / 'build/cmake/android.toolchain.cmake']:
  if not tool.is_file(): raise FileNotFoundError('Run with --prepare-tools first: ' + str(tool))
 upstream = repo / ('third_party/rapfi/upstream-source-' + COMMIT + '.zip')
 if digest(upstream) != SOURCE_SHA256: raise ValueError('Pinned upstream source checksum mismatch')
 extract(upstream, cache / 'source')
 source = cache / ('source/rapfi-' + COMMIT + '/Rapfi')
 cmake_file = source / 'CMakeLists.txt'
 original = cmake_file.read_text(encoding='utf-8')
 needle = '        elseif(CMAKE_SYSTEM_NAME STREQUAL "Darwin" AND CMAKE_CXX_COMPILER_ID MATCHES "Clang|AppleClang")'
 if original.count(needle) != 1: raise ValueError('Pinned Android patch does not apply')
 replacement = '        elseif(ANDROID)\n            # Bionic provides pthread in libc; Android has no separate libpthread.\n            target_link_libraries(rapfi PRIVATE atomic)\n' + needle
 cmake_file.write_text(original.replace(needle, replacement), encoding='utf-8')
 results = []
 for abi in args.abi or ['x86_64', 'arm64-v8a']:
  build = cache / 'build' / abi
  command = [str(cmake), '-S', str(source), '-B', str(build), '-G', 'Ninja',
   '-DCMAKE_MAKE_PROGRAM=' + ninja.as_posix(),
   '-DCMAKE_TOOLCHAIN_FILE=' + (ndk / 'build/cmake/android.toolchain.cmake').as_posix(),
   '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_static',
   '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
   '-DNO_MULTI_THREADING=OFF', '-DNO_COMMAND_MODULES=ON', '-DENABLE_LTO=ON',
   '-DUSE_SSE=' + ('ON' if abi == 'x86_64' else 'OFF'),
   '-DUSE_NEON=' + ('ON' if abi == 'arm64-v8a' else 'OFF'),
   '-DUSE_AVX2=OFF', '-DUSE_AVX512=OFF', '-DUSE_BMI2=OFF', '-DUSE_VNNI=OFF', '-DUSE_NEON_DOTPROD=OFF',
   '-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384']
  subprocess.run(command, check=True)
  subprocess.run([str(cmake), '--build', str(build), '--parallel', str(max(1, args.jobs))], check=True)
  output = repo / 'app/src/main/jniLibs' / abi / 'librapfi.so'
  output.parent.mkdir(parents=True, exist_ok=True)
  shutil.copy2(build / 'pbrain-rapfi', output)
  subprocess.run([str(llvm / 'llvm-strip.exe'), '--strip-all', str(output)], check=True)
  elf = subprocess.check_output([str(llvm / 'llvm-readelf.exe'), '-h', '-l', '-d', '-V', str(output)], text=True)
  machine = 'AArch64' if abi == 'arm64-v8a' else 'X86-64'
  if '/system/bin/linker64' not in elf or 'PIE' not in elf or machine not in elf or 'GLIBC' in elf:
   raise ValueError('Output is not the required Android PIE: ' + abi)
  results.append({'abi': abi, 'bytes': output.stat().st_size, 'sha256': digest(output)})
  print('Verified Android PIE:', results[-1], flush=True)
 (repo / 'third_party/rapfi/rebuilt-binaries.json').write_text(json.dumps(results, indent=2), encoding='utf-8')

if __name__ == '__main__': main()
