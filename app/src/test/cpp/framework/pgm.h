#ifndef INDICVISION_TEST_PGM_H
#define INDICVISION_TEST_PGM_H

// Minimal binary-PGM (P5) reader for host tests — the host build has no image
// codec. Pillow writes "P5\n<w> <h>\n<max>\n<raw bytes>" with no comments, so
// `>>` (which skips whitespace) parses the header; one get() consumes the final
// separator before the pixel data.

#include <cstdint>
#include <fstream>
#include <string>
#include <vector>

namespace dictest {

    struct Pgm {
        int w = 0, h = 0;
        std::vector<uint8_t> px;
    };

    inline bool load_pgm(const std::string &path, Pgm &out) {
        std::ifstream f(path, std::ios::binary);
        if (!f) return false;
        std::string magic;
        f >> magic;
        if (magic != "P5") return false;
        int w = 0, h = 0, maxv = 0;
        if (!(f >> w >> h >> maxv)) return false;
        if (w <= 0 || h <= 0 || maxv != 255) return false;
        f.get(); // the single whitespace between the header and the pixel data
        out.w = w;
        out.h = h;
        out.px.resize((size_t) w * h);
        f.read(reinterpret_cast<char *>(out.px.data()), (std::streamsize) out.px.size());
        return f.gcount() == (std::streamsize) out.px.size();
    }

} // namespace dictest

#endif // INDICVISION_TEST_PGM_H
