#include <jni.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/params.h>
#include <openssl/sha.h>

#include <array>
#include <cstring>
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

// SPAKE2 上下文常量 (与 AOSP 保持完全一致)
constexpr std::string_view ADB_SPAKE2_CONTEXT = "adb pairing v1";

// AOSP 标准 SPAKE2 Ed25519 M 点阵常量 (32 字节压缩格式)
constexpr std::array<uint8_t, 32> SPAKE2_POINT_M = {
    0xd0, 0x48, 0x73, 0x2d, 0x6e, 0xdd, 0xee, 0x8c, 0xc1, 0x3d, 0x5d, 0x1f,
    0x93, 0x3b, 0x7e, 0x18, 0x01, 0x21, 0xa1, 0xc2, 0xd4, 0x0f, 0x47, 0xa9,
    0xa4, 0x32, 0x05, 0x6e, 0xa5, 0xcb, 0x12, 0x40
};

// AOSP 标准 SPAKE2 Ed25519 N 点阵常量 (32 字节压缩格式)
constexpr std::array<uint8_t, 32> SPAKE2_POINT_N = {
    0xe2, 0x98, 0xc0, 0xa5, 0x0d, 0x41, 0x4e, 0xa8, 0x06, 0xb8, 0xd0, 0xb9,
    0xd2, 0x13, 0xd9, 0xa6, 0x5a, 0x2f, 0x87, 0xd6, 0xa1, 0xfc, 0x60, 0x3e,
    0x65, 0xd6, 0xdf, 0xef, 0x31, 0x8c, 0x2a, 0xb9
};

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

// 计算配对码的 SHA256 哈希作为标量源
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

extern "C" {
    // 标量规约: w = sha256_digest mod L
    void x25519_sc_reduce(uint8_t s[64]);
    // 标量乘法与加法: X = x*G + w*M
    void x25519_ge_double_scalarmult_vartime(
        uint8_t r[32], const uint8_t a[32], const uint8_t A[32], const uint8_t b[32]);
    // 共享点计算: K = x * (Y - w*N)
    int x25519_ge_spake2_calculate_k(
        uint8_t out_k[32], const uint8_t x[32], const uint8_t Y[32],
        const uint8_t w[32], const uint8_t N[32]);
}

// 标量计算 w = SHA256(pairingCode) mod L
std::array<uint8_t, 32> DeriveScalarW(std::string_view pairingCode) {
    auto digest = CalculateSHA256(pairingCode);
    uint8_t extended[64] = {0};
    std::memcpy(extended, digest.data(), 32);
    
    // 将 32 字节 SHA256 结果归约到 Ed25519 群阶 L 范围内
    x25519_sc_reduce(extended);
    
    std::array<uint8_t, 32> w{};
    std::memcpy(w.data(), extended, 32);
    return w;
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

    // 1. 计算标量 w = SHA256(pairingCode) mod L
    auto wScalar = adb::spake2::DeriveScalarW(std::string_view(codeChars));

    // 2. 客户端私钥标量 x (确保在 0..L 范围内)
    uint8_t xScalar[64] = {0};
    std::memcpy(xScalar, privKeyBytes, 32);
    adb::spake2::x25519_sc_reduce(xScalar);

    // 3. 计算客户端公钥点 X = x*G + w*M
    std::array<uint8_t, 32> clientPointX{};
    adb::spake2::x25519_ge_double_scalarmult_vartime(
        clientPointX.data(),
        xScalar,
        adb::spake2::SPAKE2_POINT_M.data(),
        wScalar.data()
    );

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
    jsize serverPointLen = env->GetArrayLength(jServerPointY);

    if (!codeChars || serverPointLen != 32) {
        if (codeChars) env->ReleaseStringUTFChars(jPairingCode, codeChars);
        if (privKeyBytes) env->ReleaseByteArrayElements(jClientPrivateKey, privKeyBytes, JNI_ABORT);
        if (serverPointBytes) env->ReleaseByteArrayElements(jServerPointY, serverPointBytes, JNI_ABORT);
        return nullptr;
    }

    // 1. 计算标量 w 和 x
    auto wScalar = adb::spake2::DeriveScalarW(std::string_view(codeChars));
    
    uint8_t xScalar[64] = {0};
    std::memcpy(xScalar, privKeyBytes, 32);
    adb::spake2::x25519_sc_reduce(xScalar);

    // 2. 重新计算客户端公钥点 X = x*G + w*M
    std::array<uint8_t, 32> clientPointX{};
    adb::spake2::x25519_ge_double_scalarmult_vartime(
        clientPointX.data(), xScalar, adb::spake2::SPAKE2_POINT_M.data(), wScalar.data());

    // 3. 计算 SPAKE2 共享点 K = x * (Y - w*N)
    std::array<uint8_t, 32> sharedSecretK{};
    int status = adb::spake2::x25519_ge_spake2_calculate_k(
        sharedSecretK.data(),
        xScalar,
        reinterpret_cast<const uint8_t*>(serverPointBytes),
        wScalar.data(),
        adb::spake2::SPAKE2_POINT_N.data()
    );

    if (status != 0) { // 服务端呈递的 Y 点无效或为零点
        env->ReleaseStringUTFChars(jPairingCode, codeChars);
        env->ReleaseByteArrayElements(jClientPrivateKey, privKeyBytes, JNI_ABORT);
        env->ReleaseByteArrayElements(jServerPointY, serverPointBytes, JNI_ABORT);
        return nullptr;
    }

    // 4. 按照 AOSP 规范构建 HKDF Info 报文:
    // HKDF Info Structure: Context ("adb pairing v1") + ClientPointX (32B) + ServerPointY (32B) + SharedSecretK (32B) + ScalarW (32B)
    std::vector<uint8_t> hkdfInfo;
    hkdfInfo.reserve(adb::spake2::ADB_SPAKE2_CONTEXT.size() + 32 * 4);

    hkdfInfo.insert(hkdfInfo.end(), adb::spake2::ADB_SPAKE2_CONTEXT.begin(), adb::spake2::ADB_SPAKE2_CONTEXT.end());
    hkdfInfo.insert(hkdfInfo.end(), clientPointX.begin(), clientPointX.end());
    hkdfInfo.insert(hkdfInfo.end(), serverPointBytes, serverPointBytes + 32);
    hkdfInfo.insert(hkdfInfo.end(), sharedSecretK.begin(), sharedSecretK.end());
    hkdfInfo.insert(hkdfInfo.end(), wScalar.begin(), wScalar.end());

    // 5. 执行 OpenSSL 4.0.1 HKDF-SHA256 派生 16 字节 AES Key
    auto aesKey = adb::spake2::DeriveHKDF(
        sharedSecretK.data(), sharedSecretK.size(),
        hkdfInfo.data(), hkdfInfo.size()
    );

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
