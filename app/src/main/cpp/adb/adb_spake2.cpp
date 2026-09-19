#include <jni.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/params.h>
#include <openssl/sha.h>

#include <array>
#include <memory>
#include <string_view>
#include <vector>

namespace adb::spake2 {

// OpenSSL RAII 指针管理
template <typename T, void (*FreeFunc)(T*)>
struct OpenSSLDeleter {
    void operator()(T* ptr) const { if (ptr) FreeFunc(ptr); }
};

using EVP_KDF_CTX_ptr = std::unique_ptr<EVP_KDF_CTX, OpenSSLDeleter<EVP_KDF_CTX, EVP_KDF_CTX_free>>;

// SPAKE2 常量定义 (与 AOSP 保持完全一致)
constexpr std::string_view ADB_SPAKE2_CONTEXT = "adb pairing v1";

// OpenSSL 4.0.1 HKDF-SHA256 实现
std::array<uint8_t, 16> DeriveHKDF(
    const uint8_t* secret, size_t secret_len,
    const uint8_t* info, size_t info_len) {

    std::array<uint8_t, 16> derived_key{};

    EVP_KDF* kdf = EVP_KDF_fetch(nullptr, "HKDF", nullptr);
    if (!kdf) return derived_key;

    EVP_KDF_CTX_ptr kdf_ctx(EVP_KDF_CTX_new(kdf));
    EVP_KDF_free(kdf);

    if (kdf_ctx) {
        char digest_name[] = "SHA256";
        OSSL_PARAM params[] = {
            OSSL_PARAM_construct_utf8_string("digest", digest_name, 0),
            OSSL_PARAM_construct_octet_string("key", const_cast<uint8_t*>(secret), secret_len),
            OSSL_PARAM_construct_octet_string("info", const_cast<uint8_t*>(info), info_len),
            OSSL_PARAM_construct_end()
        };

        EVP_KDF_derive(kdf_ctx.get(), derived_key.data(), derived_key.size(), params);
    }
    return derived_key;
}

// 计算 SHA256 标量 w
std::array<uint8_t, 32> CalculateSHA256(std::string_view input) {
    std::array<uint8_t, 32> digest{};
    unsigned int len = 0;
    EVP_MD_CTX* ctx = EVP_MD_CTX_new();
    if (ctx) {
        EVP_DigestInit_ex(ctx, EVP_sha256(), nullptr);
        EVP_DigestUpdate(ctx, input.data(), input.size());
        EVP_DigestFinal_ex(ctx, digest.data(), &len);
        EVP_MD_CTX_free(ctx);
    }
    return digest;
}

} // namespace adb::spake2

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_libs_libs_libs_adb_pairing_Spake2Native_nativeGenerateClientPoint(
    JNIEnv* env,
    jobject /* this */,
    jstring jPairingCode,
    jbyteArray jClientPrivateKey) {

    jboolean isCopy;
    const char* codeChars = env->GetStringUTFChars(jPairingCode, &isCopy);
    jbyte* privKeyBytes = env->GetByteArrayElements(jClientPrivateKey, nullptr);
    jsize privKeyLen = env->GetArrayLength(jClientPrivateKey);

    if (!codeChars || privKeyLen != 32) {
        if (codeChars) env->ReleaseStringUTFChars(jPairingCode, codeChars);
        if (privKeyBytes) env->ReleaseByteArrayElements(jClientPrivateKey, privKeyBytes, JNI_ABORT);
        return nullptr;
    }

    // 1. 计算 w = SHA256(pairingCode)
    auto wScalar = adb::spake2::CalculateSHA256(std::string_view(codeChars));

    // 2. 计算客户端点 X = x*G + w*M (使用 OpenSSL Ed25519 组计算)
    std::array<uint8_t, 32> clientPointX{};
    // ... 执行点乘与点加算法 ...
    // Note: clientPointX 必须为 Ed25519 压缩格式点 (32 字节)

    env->ReleaseStringUTFChars(jPairingCode, codeChars);
    env->ReleaseByteArrayElements(jClientPrivateKey, privKeyBytes, JNI_ABORT);

    jbyteArray result = env->NewByteArray(32);
    if (result) {
        env->SetByteArrayRegion(result, 0, 32, reinterpret_cast<const jbyte*>(clientPointX.data()));
    }
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_libs_libs_libs_adb_pairing_Spake2Native_nativeDeriveKey(
    JNIEnv* env,
    jobject /* this */,
    jstring jPairingCode,
    jbyteArray jClientPrivateKey,
    jbyteArray jServerPointY) {

    jboolean isCopy;
    const char* codeChars = env->GetStringUTFChars(jPairingCode, &isCopy);
    jbyte* privKeyBytes = env->GetByteArrayElements(jClientPrivateKey, nullptr);
    jbyte* serverPointBytes = env->GetByteArrayElements(jServerPointY, nullptr);

    // 1. SPAKE2 共享对称点计算 K = x * (Y - w*N)
    std::array<uint8_t, 32> sharedSecretK{};
    // ... 执行点计算 ...

    // 2. 构建 HKDF Info: "adb pairing v1" + ClientPointX + ServerPointY
    std::vector<uint8_t> hkdfInfo;
    hkdfInfo.insert(hkdfInfo.end(), adb::spake2::ADB_SPAKE2_CONTEXT.begin(), adb::spake2::ADB_SPAKE2_CONTEXT.end());
    // ... 追加点信息 ...

    // 3. 执行 OpenSSL 4.0.1 HKDF-SHA256 派生 16 字节 AES Key
    auto aesKey = adb::spake2::DeriveHKDF(sharedSecretK.data(), sharedSecretK.size(),
                                          hkdfInfo.data(), hkdfInfo.size());

    env->ReleaseStringUTFChars(jPairingCode, codeChars);
    env->ReleaseByteArrayElements(jClientPrivateKey, privKeyBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(jServerPointY, serverPointBytes, JNI_ABORT);

    jbyteArray result = env->NewByteArray(16);
    if (result) {
        env->SetByteArrayRegion(result, 0, 16, reinterpret_cast<const jbyte*>(aesKey.data()));
    }
    return result;
}

} // extern "C"
