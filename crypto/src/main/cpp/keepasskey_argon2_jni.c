/*
 * KeePasskey Argon2 JNI 桥（自维护，透传层，零密码学逻辑）
 *
 * 设计约定：
 * - 密码学内核为 PHC 官方参考实现 argon2_ctx（CC0 / Apache-2.0，见 argon2/LICENSE）；
 * - 本文件仅做 JNI 参数搬运：KDBX 头 Argon2 参数直透 argon2_context，
 *   支持 KDF secret / associatedData（KDBX 4 完整参数面）；
 * - 敏感输入缓冲（password / secret / AD）在派生结束后立即清零（敏感数据铁律）；
 * - 失败统一返回 NULL，错误语义由 Kotlin 层归一为友好 KdfException。
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "argon2.h"

#define KP_OUT_LEN 32u

/* 安全取参：拷入新分配缓冲，调用方负责 zeroize；长度溢出或为空返回 NULL */
static jbyte *kp_copy_bytes(JNIEnv *env, jbyteArray array, jsize *out_len) {
    if (array == NULL) {
        *out_len = 0;
        return NULL;
    }
    jsize len = (*env)->GetArrayLength(env, array);
    if (len < 0) {
        *out_len = 0;
        return NULL;
    }
    jbyte *buf = (jbyte *) malloc((size_t) len);
    if (buf == NULL) {
        *out_len = 0;
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, array, 0, len, buf);
    *out_len = len;
    return buf;
}

static void kp_wipe(jbyte *buf, jsize len) {
    if (buf != NULL && len > 0) {
        memset(buf, 0, (size_t) len);
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_keepasskey_crypto_kdf_NativeArgon2_deriveKey(
        JNIEnv *env, jobject thiz,
        jbyteArray password, jbyteArray salt,
        jbyteArray secret, jbyteArray associatedData,
        jint iterations, jint memoryKib, jint parallelism,
        jint version, jint type) {
    (void) thiz;

    /* 参数闸门：KDBX 4 合法域（Argon2d=0 / Argon2id=2；版本 0x10 / 0x13） */
    if (password == NULL || salt == NULL) return NULL;
    if (type != Argon2_d && type != Argon2_id) return NULL;
    if (version != ARGON2_VERSION_10 && version != ARGON2_VERSION_13) return NULL;
    if (iterations < 1 || parallelism < 1 || memoryKib < 8 * parallelism) return NULL;

    jsize pwd_len = 0, salt_len = 0, secret_len = 0, ad_len = 0;
    jbyte *pwd = kp_copy_bytes(env, password, &pwd_len);
    jbyte *salt_buf = kp_copy_bytes(env, salt, &salt_len);
    jbyte *secret_buf = (secret != NULL) ? kp_copy_bytes(env, secret, &secret_len) : NULL;
    jbyte *ad_buf = (associatedData != NULL) ? kp_copy_bytes(env, associatedData, &ad_len) : NULL;

    uint8_t out[KP_OUT_LEN];
    argon2_context ctx;
    ctx.out = out;
    ctx.outlen = KP_OUT_LEN;
    ctx.pwd = (uint8_t *) pwd;
    ctx.pwdlen = (uint32_t) pwd_len;
    ctx.salt = (uint8_t *) salt_buf;
    ctx.saltlen = (uint32_t) salt_len;
    ctx.secret = (uint8_t *) secret_buf;
    ctx.secretlen = (uint32_t) secret_len;
    ctx.ad = (uint8_t *) ad_buf;
    ctx.adlen = (uint32_t) ad_len;
    ctx.t_cost = (uint32_t) iterations;
    ctx.m_cost = (uint32_t) memoryKib;
    ctx.lanes = (uint32_t) parallelism;
    ctx.threads = (uint32_t) parallelism;
    ctx.version = (uint32_t) version;
    ctx.allocate_cbk = NULL;
    ctx.free_cbk = NULL;
    ctx.flags = ARGON2_DEFAULT_FLAGS;

    int rc = argon2_ctx(&ctx, (argon2_type) type);

    /* 敏感输入缓冲用毕立即清零（内存归 argon2 内部者由其自释） */
    kp_wipe(pwd, pwd_len);
    kp_wipe(salt_buf, salt_len);
    kp_wipe(secret_buf, secret_len);
    kp_wipe(ad_buf, ad_len);
    free(pwd);
    free(salt_buf);
    free(secret_buf);
    free(ad_buf);

    if (rc != ARGON2_OK) {
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize) KP_OUT_LEN);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) KP_OUT_LEN, (jbyte *) out);
    }
    memset(out, 0, KP_OUT_LEN);
    return result;
}
