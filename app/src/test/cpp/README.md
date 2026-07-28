# Host-side native tests

The suite now lives under [`native/tests/`](../../../../native/tests/).

```bash
cmake -S native/tests -B build/native-tests -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-tests
./build/native-tests/dic_tests
```

This directory only keeps a CMake redirect for older scripts.
