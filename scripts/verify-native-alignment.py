"""Read-only ELF segment alignment check for an APK's bundled native libraries."""
import struct
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    libraries = [name for name in apk.namelist() if name.endswith('.so')]
    incompatible = []
    required_count = 0
    relro_warnings = []
    for name in libraries:
        binary = apk.read(name)
        if binary[:4] != b'\x7fELF':
            raise ValueError(f'Not an ELF library: {name}')
        is_64 = binary[4] == 2
        endian = '<' if binary[5] == 1 else '>'
        header = struct.unpack_from(endian + ('HHIQQQIHHHHHH' if is_64 else 'HHIIIIIHHHHHH'), binary, 16)
        offset, entry_size, count = header[4], header[8], header[9]
        alignments = []
        relro_ends = []
        for index in range(count):
            segment = struct.unpack_from(endian + ('IIQQQQQQ' if is_64 else 'IIIIIIII'), binary, offset + index * entry_size)
            if segment[0] == 1:
                alignments.append(segment[-1])
            if segment[0] == 0x6474e552:
                relro_ends.append(segment[3] + segment[6] if is_64 else segment[2] + segment[5])
        required = name.startswith(('lib/arm64-v8a/', 'lib/x86_64/'))
        if required:
            required_count += 1
            if not alignments or any(value < 16384 for value in alignments):
                incompatible.append(name)
            if any(end % 16384 for end in relro_ends):
                relro_warnings.append(name)
        print(f'{name}: LOAD {alignments}; RELRO aligned={all(end % 16384 == 0 for end in relro_ends)}; 16KB required={required}')
    print(f'16 KB 64-bit LOAD alignment: {required_count - len(incompatible)}/{required_count} compatible')
    print(f'RELRO follow-up required: {len(relro_warnings)} libraries. Verify on a 16 KB emulator before Play upload.')
    sys.exit(bool(incompatible or ('--require-relro' in sys.argv and relro_warnings)))
