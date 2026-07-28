# Host-side native test suite

```bash
# From repo root
cmake -S native/tests -B build/native-tests -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-tests
./build/native-tests/dic_tests
```

See [docs/engine/TESTING.md](../../docs/engine/TESTING.md) for the catalog.
