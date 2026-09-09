# KeePasskey 自维护 Argon2 原生库构建
# 密码学内核：PHC 官方参考实现（CC0 / Apache-2.0 双许可，见 argon2/LICENSE），
# 全源码入库可审计，零二进制信任根（替代第三方预编译 argon2kt AAR）。
# 线程支持保持启用：KDBX parallelism 参数经 argon2_context.threads 真实生效。
cmake_minimum_required(VERSION 3.22.1)
project(keepasskey_argon2 C)

set(ARGON2_DIR ${CMAKE_CURRENT_SOURCE_DIR}/argon2)

add_library(keepasskey_argon2 SHARED
    keepasskey_argon2_jni.c
    ${ARGON2_DIR}/src/argon2.c
    ${ARGON2_DIR}/src/core.c
    ${ARGON2_DIR}/src/encoding.c
    ${ARGON2_DIR}/src/ref.c
    ${ARGON2_DIR}/src/thread.c
    ${ARGON2_DIR}/src/blake2/blake2b.c)

target_include_directories(keepasskey_argon2 PRIVATE
    ${ARGON2_DIR}/include
    ${ARGON2_DIR}/src)

# ref 路径（portable，无 x86 SIMD 依赖）+ 多线程；不做激进优化以保证可复现构建
target_compile_options(keepasskey_argon2 PRIVATE -O2 -fvisibility=hidden)
