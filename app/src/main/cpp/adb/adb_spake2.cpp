#include <jni.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/sha.h>

#include <array>
#include <memory>
#include <string_view>
#include <vector>

namespace adb::spake2 {

// OpenSSL 智能指针 Custom Deleter
template <typename T, void (*FreeFunc)(T*)>
struct OpenSSLDeleter {
    void operator()(T* ptr) const {
        if (ptr) FreeFunc(ptr);
    }
};

using EVP_MD_CTX_ptr = std::unique_ptr<EVP_MD_CTX, OpenSSLDeleter<EVP_MD_CTX, EVP_MD_CTX_free>>;
using EVP_KDF_CTX_ptr = std::unique_ptr<EVP_KDF_CTX, OpenSSLDeleter<EVP_KDF_CTX, EVP_KDF_CTX_free>>;

// JNI String RAII Wrapper (C++17 string_view)
class JNIStringView {
public:
    JNIStringView(JNIEnv* env, jstring jstr) : env_(env), jstr_(jstr) {
        if (jstr_) {
            chars_ = env_->GetStringUTFChars(jstr_, nullptr);
            size_ = static_cast<size_t>(env_->GetStringUTFLength(jstr_));
        }
    }
    ~JNIStringView() {
        if (chars_) {
            env_->ReleaseStringUTFChars(jstr_, chars_);
        }
    }

    [[nodiscard]] std::string_view view() const noexcept { return {chars_, size_}; }

private:
    JNIEnv* env_;
    jstring jstr_;
    const char* chars_{nullptr};
    size_t size_{0};
};

// JNI ByteArray RAII Wrapper
class JNIByteArrayView {
public:
    JNIByteArrayView(JNIEnv* env, jbyteArray jarr) : env_(env), jarr_(jarr) {
        if (jarr_) {
            bytes_ = env_->GetByteArrayElements(jarr_, nullptr);
            size_ = static_cast<size_t>(env_->GetArrayLength(jarr_));
        }
    }
    ~JNIByteArrayView() {
        if (bytes_) {
            env_->ReleaseByteArrayElements(jarr_, bytes_, JNI_ABORT);
        }
    }

    [[nodiscard]] const uint8_t* data() const noexcept {
        return reinterpret_cast<const uint8_t*>(bytes_);
    }
    [[nodiscard]] size_t size() const noexcept { return size_; }

private:
    JNIEnv* env_;
    jbyteArray jarr_;
    jbyte* bytes_{nullptr};
    size_t size_{0};
};

constexpr std::array<uint8_t, 32> M_BYTES = {
    0xd0, 0x48, 0xbe, 0x02, 0x70, 0x7a, 0x51, 0x0e,
    0xc6, 0x1b, 0x01, 0x01, 0xf9, 0x51, 0xb3, 0x58,
    0x26, 0x11, 0xeb, 0x0a, 0xcd, 0x22, 0x3d, 0xd7,
    0x5f, 0x3d, 0x1b, 0x86, 0x26, 0x14, 0xbf, 0x37
};

constexpr std::string_view ADB_SPAKE2_CONTEXT = "adb pairing v1";

// 计算 SHA-256
[[nodiscard]] std::array<uint8_t, 32> CalculateSHA256(std::string_view input) {
    std::array<uint8_t, 32> digest{};
    EVP_MD_CTX_ptr ctx(EVP_MD_CTX_new());
    if (ctx) {
        EVP_DigestInit_ex(ctx.get(), EVP_sha256(), nullptr);
        EVP_DigestUpdate(ctx.get(), input.data(), input.size());
        EVP_DigestFinal_ex(ctx.get(), digest.data(), nullptr);
    }
    return digest;
}

// 基于 OpenSSL 3.0 / 4.0 EVP_KDF 的 HKDF-SHA256 派生
[[nodiscard]] std::array<uint8_t, 16> DeriveHKDF(
    const uint8_t* secret, size_t secret_len,
    const uint8_t* info, size_t info_len) {

    std::array<uint8_t, 16> derived_key{};

    EVP_KDF* kdf = EVP_KDF_fetch(nullptr, "HKDF", nullptr);
    if (!kdf) return derived_key;

    EVP_KDF_CTX_ptr kdf_ctx(EVP_KDF_CTX_new(kdf));
    EVP_KDF_free(kdf); // Context 已经持有引用，可释放 kdf 句柄

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

} // namespace adb::spake2

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_libs_libs_libs_adb_pairing_Spake2Native_nativeGenerateClientPoint(
    JNIEnv* env,
    jobject /* un-used */,
    jstring jPairingCode,
    jbyteArray jClientPrivateKey) {

    using namespace adb::spake2;

    JNIStringView codeView(env, jPairingCode);
    JNIByteArrayView privKeyView(env, jClientPrivateKey);

    if (codeView.view().empty() || privKeyView.size() != 32) {
        return nullptr;
    }

    // 1. 导出 wScalar
    auto wScalar = CalculateSHA256(codeView.view());

    // 2. 执行 Ed25519 标量点加法 X = x*G + w*M
    std::array<uint8_t, 32> clientPointX{};
    
    // 此处调用 OpenSSL 内部或标准的 Curve25519/Ed25519 标量乘与点加算法
    // ... 执行点计算，赋值给 clientPointX ...

    jbyteArray result = env->NewByteArray(32);
    if (result) {
        env->SetByteArrayRegion(result, 0, 32, reinterpret_cast<const jbyte*>(clientPointX.data()));
    }
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_libs_libs_libs_adb_pairing_Spake2Native_nativeDeriveKey(
    JNIEnv* env,
    jobject /* un-used */,
    jstring jPairingCode,
    jbyteArray jClientPrivateKey,
    jbyteArray jServerPointY) {

    using namespace adb::spake2;

    JNIStringView codeView(env, jPairingCode);
    JNIByteArrayView privKeyView(env, jClientPrivateKey);
    JNIByteArrayView serverPointYView(env, jServerPointY);

    if (privKeyView.size() != 32 || serverPointYView.size() != 32) {
        return nullptr;
    }

    // 1. 计算 SPAKE2 共享对称点 K = x * (Y - w*N)
    std::array<uint8_t, 32> sharedSecretK{};
    // ... 执行点计算，赋值给 sharedSecretK ...

    // 2. 构建 HKDF Info: "adb pairing v1" + ClientPointX + ServerPointY
    std::vector<uint8_t> hkdfInfo;
    hkdfInfo.reserve(ADB_SPAKE2_CONTEXT.size() + 64);
    hkdfInfo.insert(hkdfInfo.end(), ADB_SPAKE2_CONTEXT.begin(), ADB_SPAKE2_CONTEXT.end());
    // ... 传入 clientPointX 和 serverPointYView 数据 ...

    // 3. 执行 OpenSSL 3.x/4.x HKDF-SHA256 派生
    auto aesKey = DeriveHKDF(sharedSecretK.data(), sharedSecretK.size(),
                             hkdfInfo.data(), hkdfInfo.size());

    jbyteArray result = env->NewByteArray(16);
    if (result) {
        env->SetByteArrayRegion(result, 0, 16, reinterpret_cast<const jbyte*>(aesKey.data()));
    }
    return result;
}

} // extern "C"
