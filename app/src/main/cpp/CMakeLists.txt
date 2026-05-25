cmake_minimum_required(VERSION 3.22.1)

project("adblock_engine")

# ─────────────────────────────────────────────
# Native packet engine library
# ─────────────────────────────────────────────
add_library(
    adblock_engine   # library name
    SHARED           # shared .so
    packet_engine.cpp
)

# Link Android log library
find_library(log-lib log)

target_link_libraries(
    adblock_engine
    ${log-lib}
)

# C++17 for unordered_set, string_view etc.
target_compile_features(adblock_engine PRIVATE cxx_std_17)

# Optimization flags for release
target_compile_options(adblock_engine PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
)
