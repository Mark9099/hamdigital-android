# Makes app/src/main/cpp/js8/JS8.cpp and JS8.hpp from JS8Call's originals (copied there unchanged first): removes the
# Qt and Boost dependencies, keeping the decoder itself unchanged. Run once after copying; every change is listed in
# js8/ANDROID_CHANGES.txt. Usage: python tools/port_js8.py <js8 folder>
import re, sys                                       # text edits

d = sys.argv[1]                                      # app/src/main/cpp/js8

def edit(text, old, new, count=1):                   # replace exactly `count` occurrences, or stop
    n = text.count(old)
    if n != count: sys.exit(f"expected {count} of {old[:60]!r}, found {n}")
    return text.replace(old, new)

# ---- JS8.cpp ----
s = open(f"{d}/JS8.cpp", encoding="utf-8").read()

# 1. Boost and Qt includes out; the stand-ins in.
s = edit(s, "#include <boost/crc.hpp>\n", "")
s = edit(s, "#include <boost/math/ccmath/round.hpp>\n", "")
s = edit(s, "#include <boost/multi_index_container.hpp>\n", "")
s = edit(s, "#include <boost/multi_index/key.hpp>\n", "")
s = edit(s, "#include <boost/multi_index/ordered_index.hpp>\n", "")
s = edit(s, "#include <boost/multi_index/ranked_index.hpp>\n", "")
s = edit(s, "#include <QDebug>\n", "#include \"js8_compat.h\"     // HF Digital Modes: std stand-ins for the Boost parts used (augmented CRC, constexpr round)\n")

# 2. The Sync index: boost::multi_index -> a plain vector with the same selection (js8_compat.h SyncIndex).
start = s.index("    // Container indexing Sync objects in useful ways, used by syncjs8().")
end = s.index("    >;\n", start) + len("    >;\n")
s = s[:start] + "    // Container of Sync objects, used by syncjs8() (HF Digital Modes: was a boost::multi_index_container; see\n    // SyncIndex in js8_compat.h - the same 40th-percentile normalisation and candidate selection).\n\n    using SyncIndex = js8compat::SyncIndex<Sync>;\n" + s[end:]

old_sel_start = s.index("            // Access the sync indices.")
old_sel_end = s.index("            return candidates;\n", old_sel_start)
s = s[:old_sel_start] + "            // Normalize to the 40th percentile and extract candidates (HF Digital Modes: SyncIndex::candidates does\n            // what the multi_index code here did; see js8_compat.h).\n\n            std::vector<Sync> candidates = sync.candidates(NMAXCAND, ASYNCMIN, Mode::AZ);\n\n" + s[old_sel_end:]

# 3. CRC12 and the constexpr round.
s = edit(s, "return boost::augmented_crc<12, 0xc06>(range.data(),", "return js8compat::augmented_crc<12, 0xc06>(range.data(),")
s = edit(s, "using boost::math::ccmath::round;", "using js8compat::cround;  // (HF Digital Modes: was boost::math::ccmath::round)")
s = edit(s, "static_cast<std::size_t>(round(BASELINE_MIN / Mode::DF));", "static_cast<std::size_t>(cround(BASELINE_MIN / Mode::DF));")
s = edit(s, "static_cast<std::size_t>(round(BASELINE_MAX / Mode::DF));", "static_cast<std::size_t>(cround(BASELINE_MAX / Mode::DF));")

# 4. The Qt Worker and Decoder (thread, semaphore, signals) out; a plain Engine with the same decode pass in.
wstart = s.index("// Worker\n")
wstart = s.rindex("/****", 0, wstart)
enc = s.index("// Public Interface - Encoding", wstart)
enc = s.rindex("/****", 0, enc)
worker = s[wstart:enc]
impl_start = worker.index("        class Impl\n")
impl_end = worker.index("        // Data members\n")
impl = worker[impl_start:impl_end]
impl = impl.replace("        class Impl\n", "    class Engine::Impl\n", 1)
impl = "\n".join(line[4:] if line.startswith("    ") else line for line in impl.split("\n"))
engine = ("/******************************************************************************/\n"
          "// Engine (HF Digital Modes: JS8Call's Worker::Impl without the Qt thread, semaphore and signals around it)\n"
          "/******************************************************************************/\n\n"
          "namespace JS8\n{\n" + impl +
          "\n    Engine::Engine(struct dec_data & data) : m_impl(std::make_unique<Impl>(data)) {}\n"
          "    Engine::~Engine() = default;\n"
          "    void Engine::decode(Event::Emitter emitEvent) { (*m_impl)(std::move(emitEvent)); }\n}\n\n")
s = s[:wstart] + engine + s[enc:]

open(f"{d}/JS8.cpp", "w", encoding="utf-8", newline="\n").write(s)

# ---- JS8.hpp ----
h = open(f"{d}/JS8.hpp", encoding="utf-8").read()
h = edit(h, "#include <QObject>\n", "#include <memory>\n")
h = edit(h, "#include <QSemaphore>\n", "")
h = edit(h, "#include <QThread>\n", "#include \"commons.h\"\n")
h = edit(h, "  Q_NAMESPACE\n", "")
dstart = h.index("  class Worker;")
dend = h.index("};", h.index("class Decoder", dstart)) + 2
h = h[:dstart] + ("  // HF Digital Modes: JS8Call's Qt Decoder / Worker pair is replaced by Engine, which runs the same decoding pass\n"
                  "  // on the caller's thread (JS8.cpp).\n"
                  "  class Engine\n  {\n    class Impl;\n    std::unique_ptr<Impl> m_impl;\n  public:\n"
                  "    explicit Engine(struct dec_data & data);  // decodes from this data (d2 + params)\n"
                  "    ~Engine();\n"
                  "    void decode(Event::Emitter emitEvent);    // one decoding pass over the submodes in data.params.nsubmodes\n  };") + h[dend:]
open(f"{d}/JS8.hpp", "w", encoding="utf-8", newline="\n").write(h)
print("ok")
