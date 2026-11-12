#include <jni.h>
#include <cmath>
#include <cstdint>
#include <vector>

namespace wci_biometric_cpp {

// High-Precision L2 Normalization of a 128-D Facial Feature Embedding Vector
void normalizeL2Vector(float* vec, size_t len) noexcept {
    double sumSq = 0.0;
    for (size_t i = 0; i < len; ++i) {
        sumSq += static_cast<double>(vec[i]) * static_cast<double>(vec[i]);
    }
    double invNorm = (sumSq > 1e-12) ? (1.0 / std::sqrt(sumSq)) : 0.0;
    for (size_t i = 0; i < len; ++i) {
        vec[i] = static_cast<float>(static_cast<double>(vec[i]) * invNorm);
    }
}

// Aadhaar-Standard Cosine Similarity: cos(theta) = (A . B) / (||A|| * ||B||)
float computeCosineSimilarity128D(const float* vecA, const float* vecB, size_t len) noexcept {
    if (len == 0) return 0.0f;
    double dot = 0.0;
    double normA = 0.0;
    double normB = 0.0;
    for (size_t i = 0; i < len; ++i) {
        double a = static_cast<double>(vecA[i]);
        double b = static_cast<double>(vecB[i]);
        dot += a * b;
        normA += a * a;
        normB += b * b;
    }
    double denom = std::sqrt(normA) * std::sqrt(normB);
    if (denom < 1e-12) return 0.0f;
    double cosine = dot / denom;
    if (cosine > 1.0) cosine = 1.0;
    if (cosine < -1.0) cosine = -1.0;
    return static_cast<float>(cosine);
}

} // namespace wci_biometric_cpp

extern "C"
JNIEXPORT jfloat JNICALL
Java_com_waqar_institute_biometric_FaceBiometricVerificationEngine_nativeComputeCosineSimilarity(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray liveVec,
    jfloatArray enrolledVec) {
    if (liveVec == nullptr || enrolledVec == nullptr) return 0.0f;
    jsize lenA = env->GetArrayLength(liveVec);
    jsize lenB = env->GetArrayLength(enrolledVec);
    jsize len = (lenA < lenB) ? lenA : lenB;
    if (len <= 0) return 0.0f;
    std::vector<float> bufA(static_cast<size_t>(len));
    std::vector<float> bufB(static_cast<size_t>(len));
    env->GetFloatArrayRegion(liveVec, 0, len, bufA.data());
    env->GetFloatArrayRegion(enrolledVec, 0, len, bufB.data());
    return wci_biometric_cpp::computeCosineSimilarity128D(
        bufA.data(),
        bufB.data(),
        static_cast<size_t>(len)
    );
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_waqar_institute_biometric_FaceBiometricVerificationEngine_nativeNormalizeEmbedding(
    JNIEnv* env,
    jobject /* thiz */,
    jfloatArray rawVec) {
    if (rawVec == nullptr) return env->NewFloatArray(0);
    jsize len = env->GetArrayLength(rawVec);
    if (len <= 0) return env->NewFloatArray(0);
    std::vector<float> buf(static_cast<size_t>(len));
    env->GetFloatArrayRegion(rawVec, 0, len, buf.data());
    wci_biometric_cpp::normalizeL2Vector(buf.data(), static_cast<size_t>(len));
    jfloatArray out = env->NewFloatArray(len);
    env->SetFloatArrayRegion(out, 0, len, buf.data());
    return out;
}
