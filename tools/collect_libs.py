#!/usr/bin/env python3
"""Work out which Termux files have to go into the APK's jniLibs, and under which names.

Starting from the executables the app runs (node, git, git-remote-http), follow DT_NEEDED
through Termux's usr/lib and print one line per file:

    <path relative to usr/>\t<name inside jniLibs>

followed by a line "RENAMES old=new old=new ..." for tools/elfpatch.py.

Android only extracts native libraries named lib*.so, so versioned names are folded:
libz.so.1 -> libz1.so, libicuuc.so.78 -> libicuuc78.so (always one character shorter,
which is what elfpatch.py needs). Libraries that are not in usr/lib (libc, libm, libdl,
liblog, ...) come from Android itself and are skipped.

This replaces a hard-coded list, so a newer Termux ICU, OpenSSL or zlib does not break the build.

usage: collect_libs.py USR_DIR
"""
import os
import re
import struct
import sys

DT_NULL, DT_NEEDED, DT_STRTAB = 0, 1, 5
ROOTS = {
    'bin/node': 'libnode.so',
    'bin/git': 'libgit.so',
    'libexec/git-core/git-remote-http': 'libgitremotehttp.so',
}


def needed(path):
    data = open(path, 'rb').read()
    if data[:4] != b'\x7fELF' or data[4] != 2 or data[5] != 1:
        raise SystemExit(f'{path}: not an ELF64 little-endian file')
    e_phoff, = struct.unpack_from('<Q', data, 0x20)
    e_phentsize, e_phnum = struct.unpack_from('<HH', data, 0x36)
    loads, dyn = [], None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, _flags, p_offset, p_vaddr, _paddr, p_filesz = struct.unpack_from('<IIQQQQ', data, off)
        if p_type == 1:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:
            dyn = (p_offset, p_filesz)
    if dyn is None:
        return []

    def v2o(vaddr):
        for va, off, sz in loads:
            if va <= vaddr < va + sz:
                return vaddr - va + off
        raise SystemExit(f'{path}: vaddr {vaddr:#x} not mapped')

    entries = []
    for off in range(dyn[0], dyn[0] + dyn[1], 16):
        tag, val = struct.unpack_from('<qQ', data, off)
        if tag == DT_NULL:
            break
        entries.append((tag, val))
    strtab = v2o(next(val for tag, val in entries if tag == DT_STRTAB))
    out = []
    for tag, val in entries:
        if tag == DT_NEEDED:
            start = strtab + val
            out.append(data[start:data.index(0, start)].decode())
    return out


def packaged_name(soname):
    m = re.fullmatch(r'(lib.+)\.so\.(\d+)(?:\.\d+)*', soname)
    if m:
        return f'{m.group(1)}{m.group(2)}.so'
    if re.fullmatch(r'lib.+\.so', soname):
        return soname
    raise SystemExit(f'do not know how to name {soname}')


def main(usr):
    files = {}      # relative source path -> packaged name
    renames = {}    # soname -> packaged name
    queue = []
    for rel, name in ROOTS.items():
        if not os.path.isfile(os.path.join(usr, rel)):
            raise SystemExit(f'missing {rel} in {usr}')
        files[rel] = name
        queue.append(rel)
    seen = set()
    while queue:
        rel = queue.pop()
        for soname in needed(os.path.join(usr, rel)):
            if soname in seen:
                continue
            seen.add(soname)
            candidate = os.path.join(usr, 'lib', soname)
            if not soname.startswith('lib') or not os.path.exists(candidate):
                continue  # provided by Android (libc, libm, libdl, liblog, the linker, ...)
            real = os.path.realpath(candidate)
            real_rel = os.path.relpath(real, usr)
            name = packaged_name(soname)
            if name != soname:
                renames[soname] = name
            if real_rel not in files:
                files[real_rel] = name
                queue.append(real_rel)
    names = list(files.values())
    if len(names) != len(set(names)):
        raise SystemExit(f'name clash in {names}')
    for rel, name in sorted(files.items(), key=lambda kv: kv[1]):
        print(f'{rel}\t{name}')
    print('RENAMES ' + ' '.join(f'{k}={v}' for k, v in sorted(renames.items())))


if __name__ == '__main__':
    main(sys.argv[1])
