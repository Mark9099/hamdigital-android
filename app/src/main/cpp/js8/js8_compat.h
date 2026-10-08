// Stand-ins for the three Boost parts JS8Call's decoder (JS8.cpp) used, in plain C++, so Boost need not be built for
// Android (HF Digital Modes; see ANDROID_CHANGES.txt):
//  - augmented_crc<Bits, Poly>: boost::augmented_crc - the remainder of the message (already "augmented" with zero
//    bits where the CRC goes) divided by the polynomial, bits most significant first, no reflection, initial value 0.
//  - cround: boost::math::ccmath::round - round half away from zero, usable in constant expressions.
//  - SyncIndex: the boost::multi_index_container syncjs8() kept its first-pass results in, with the one thing done
//    with it: normalise every sync value by the 40th percentile, then take candidates strongest first, dropping the
//    others within AZ Hz of each one taken, until NMAXCAND or a value below ASYNCMIN (or NaN).
#pragma once
#include <algorithm>                                 // sort, nth_element
#include <cmath>                                     // isnan
#include <cstddef>                                   // size_t
#include <cstdint>                                   // uint32_t
#include <vector>                                    // the container

namespace js8compat
{
    template <std::size_t Bits, std::uint32_t Poly>
    std::uint32_t augmented_crc(void const *buffer, std::size_t byte_count)
    {
        constexpr std::uint32_t mask = (Bits >= 32) ? 0xFFFFFFFFu : ((1u << Bits) - 1u); // the register
        std::uint32_t rem = 0;                       // initial remainder
        auto const *p = static_cast<std::uint8_t const *>(buffer);
        for (std::size_t i = 0; i < byte_count; ++i)
            for (int b = 7; b >= 0; --b) {           // message bits, most significant first
                std::uint32_t const top = (rem >> (Bits - 1)) & 1u; // the bit leaving the register
                rem = ((rem << 1) | ((p[i] >> b) & 1u)) & mask; // shift the next message bit in
                if (top) rem ^= Poly;                // subtract the polynomial
            }
        return rem;
    }

    constexpr long long cround(double x)             // half away from zero
    {
        return x >= 0 ? static_cast<long long>(x + 0.5) : -static_cast<long long>(-x + 0.5);
    }

    template <typename Sync>
    class SyncIndex
    {
        std::vector<Sync> m_v;                       // in insertion order
    public:
        void clear() { m_v.clear(); }
        bool empty() const { return m_v.empty(); }
        std::size_t size() const { return m_v.size(); }
        template <typename... A> void emplace(A &&...a) { m_v.emplace_back(std::forward<A>(a)...); }

        // multi_index: rankIndex.nth(size * 4 / 10)->sync is the value at that rank in ascending sync order; every entry
        // is divided by it. Then, by descending sync (equal values in insertion order, as an ordered_non_unique index
        // keeps them), each candidate taken removes every entry whose frequency is within [freq - az, freq + az].
        std::vector<Sync> candidates(std::size_t max, float min, float az)
        {
            std::vector<Sync> out;
            if (m_v.empty()) return out;
            std::vector<float> s; s.reserve(m_v.size());
            for (auto const &e : m_v) s.push_back(e.sync);
            std::size_t const k = m_v.size() * 4 / 10; // the 40th percentile's rank
            std::nth_element(s.begin(), s.begin() + k, s.end());
            float const norm = s[k];
            for (auto &e : m_v) e.sync /= norm;     // normalise
            std::vector<std::size_t> order(m_v.size()); // by sync, strongest first; ties in insertion order
            for (std::size_t i = 0; i < order.size(); ++i) order[i] = i;
            std::stable_sort(order.begin(), order.end(), [this](std::size_t a, std::size_t b) {
                float const x = m_v[a].sync, y = m_v[b].sync;
                if (std::isnan(x)) return false;     // NaN last (the original stopped at one)
                if (std::isnan(y)) return true;
                return x > y;
            });
            std::vector<char> gone(m_v.size(), 0);   // removed as near-duplicates
            for (std::size_t i : order) {
                if (out.size() >= max) break;
                if (gone[i]) continue;
                Sync const &c = m_v[i];
                if (c.sync < min || std::isnan(c.sync)) break; // below threshold: so is everything after
                out.push_back(c);
                for (std::size_t j = 0; j < m_v.size(); ++j) // drop it and its neighbours in frequency
                    if (!gone[j] && m_v[j].freq >= c.freq - az && m_v[j].freq <= c.freq + az) gone[j] = 1;
            }
            return out;
        }
    };
}
