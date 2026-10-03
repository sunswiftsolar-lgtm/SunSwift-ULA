# AndroidX graphics-path native source

These C++ sources are unchanged from the AndroidX `graphics-path` 1.1.0 release
commit `1c44f0be720ff16cd30357ad2375239bf97a1da8`.

Official source archive:
https://android.googlesource.com/platform/frameworks/support/+archive/1c44f0be720ff16cd30357ad2375239bf97a1da8/graphics/graphics-path/src.tar.gz

Archive SHA-256:
`1f9a8a76d52f0e702a67459a5dc233225d6efafb654f7d74cceff5ee919e824c`

Only `main/cpp` was extracted. Source copyright and Apache 2.0 license notices
are preserved. The Java/Kotlin API remains the official Maven 1.1.0 artifact.

ULA's separate CMake wrapper rebuilds the same native implementation with NDK
28.2 and both 16 KB page-size linker flags, retaining RELRO protection. It does
not patch a binary, disable security protections or change the JNI interface.
CI checks the actual packaged binaries, including RELRO segment alignment.
