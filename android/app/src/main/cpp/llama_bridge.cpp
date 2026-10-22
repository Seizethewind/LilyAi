// JNI bridge between Kotlin (LlamaNative) and llama.cpp.
//
// Design notes:
//  - The model is loaded once and kept; a fresh context is created for every
//    generate() call, so each answer starts from a clean state.
//  - Text crosses JNI as UTF-8 byte arrays (not jstring), because JNI strings use
//    a modified UTF-8 that mishandles emoji. Output is only sent in chunks that
//    end on a complete character.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "LilyAiLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model * model = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_ctx = 2048;
    int n_threads = 4;
    std::atomic<bool> cancel{false};
};

bool g_backend_ready = false;

// Length of the longest prefix of s that does not end in the middle of a UTF-8 character.
size_t valid_utf8_prefix(const std::string & s) {
    const size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        const unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) {  // continuation byte, keep looking back for the lead byte
            --i;
            ++back;
            continue;
        }
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        const size_t have = n - (i - 1);
        return have >= need ? n : (i - 1);
    }
    return n;
}

// Sends bytes to TokenCallback.onToken. Returns false if generation should stop.
bool emit(JNIEnv * env, jobject callback, jmethodID method, const std::string & bytes) {
    jbyteArray array = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (array == nullptr) return false;
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<const jbyte *>(bytes.data()));
    const jboolean keep_going = env->CallBooleanMethod(callback, method, array);
    env->DeleteLocalRef(array);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return keep_going == JNI_TRUE;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_seizethewind_lilyai_inference_LlamaNative_load(JNIEnv * env, jobject /*thiz*/, jstring path,
                                                        jint n_ctx, jint n_threads) {
    if (!g_backend_ready) {
        llama_backend_init();
        g_backend_ready = true;
    }

    const char * chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return 0;
    const std::string model_path(chars);
    env->ReleaseStringUTFChars(path, chars);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    llama_model * model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (model == nullptr) {
        LOGE("failed to load model: %s", model_path.c_str());
        return 0;
    }

    auto * session = new Session();
    session->model = model;
    session->vocab = llama_model_get_vocab(model);
    session->n_ctx = n_ctx;
    session->n_threads = n_threads;
    LOGI("model loaded (n_ctx=%d, threads=%d)", n_ctx, n_threads);
    return reinterpret_cast<jlong>(session);
}

// Return values: number of tokens generated, or a negative error code:
//  -1 bad handle, -2 prompt too long, -3 tokenize failed, -4 context failed,
//  -5 decode failed, -6 callback failed.
JNIEXPORT jint JNICALL
Java_com_seizethewind_lilyai_inference_LlamaNative_generate(JNIEnv * env, jobject /*thiz*/, jlong handle,
                                                            jbyteArray prompt_bytes, jint max_tokens,
                                                            jfloat temperature, jfloat top_p, jint top_k,
                                                            jobject callback) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return -1;
    s->cancel.store(false);

    const jsize prompt_len = env->GetArrayLength(prompt_bytes);
    std::string prompt(static_cast<size_t>(prompt_len), '\0');
    env->GetByteArrayRegion(prompt_bytes, 0, prompt_len, reinterpret_cast<jbyte *>(&prompt[0]));

    // Count tokens first (a negative result is minus the number of tokens needed), then tokenize.
    const int n_prompt = -llama_tokenize(s->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                         nullptr, 0, true, true);
    if (n_prompt <= 0) return -3;
    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt));
    if (llama_tokenize(s->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(),
                       static_cast<int32_t>(tokens.size()), true, true) < 0) {
        return -3;
    }
    if (n_prompt >= s->n_ctx - 8) return -2;
    const int max_new = std::min(static_cast<int>(max_tokens), s->n_ctx - n_prompt - 1);

    jclass callback_class = env->FindClass("com/seizethewind/lilyai/inference/TokenCallback");
    if (callback_class == nullptr) return -6;
    jmethodID on_token = env->GetMethodID(callback_class, "onToken", "([B)Z");
    if (on_token == nullptr) return -6;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(s->n_ctx);
    ctx_params.n_threads = s->n_threads;
    ctx_params.n_threads_batch = s->n_threads;
    llama_context * ctx = llama_init_from_model(s->model, ctx_params);
    if (ctx == nullptr) return -4;

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    int result = 0;
    int generated = 0;

    // 1) Feed the prompt in chunks no bigger than the batch size.
    const int chunk = static_cast<int>(llama_n_batch(ctx));
    for (int i = 0; i < n_prompt && result == 0; i += chunk) {
        if (s->cancel.load()) { result = 0; max_tokens = 0; break; }
        const int n = std::min(chunk, n_prompt - i);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, n);
        if (llama_decode(ctx, batch) != 0) result = -5;
    }

    // 2) Generate one token at a time, sending finished characters to Kotlin.
    std::string pending;
    const int limit = (max_tokens == 0) ? 0 : max_new;
    while (result == 0 && generated < limit) {
        if (s->cancel.load()) break;

        llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(s->vocab, token)) break;

        char piece[256];
        const int len = llama_token_to_piece(s->vocab, token, piece, sizeof(piece), 0, false);
        if (len > 0) pending.append(piece, static_cast<size_t>(len));

        const size_t keep = valid_utf8_prefix(pending);
        if (keep > 0) {
            const std::string out = pending.substr(0, keep);
            pending.erase(0, keep);
            if (!emit(env, callback, on_token, out)) {
                ++generated;
                break;
            }
        }

        ++generated;
        llama_batch next = llama_batch_get_one(&token, 1);
        if (llama_decode(ctx, next) != 0) result = -5;
    }

    if (result == 0 && !pending.empty()) {
        const size_t keep = valid_utf8_prefix(pending);
        if (keep > 0) emit(env, callback, on_token, pending.substr(0, keep));
    }

    llama_sampler_free(sampler);
    llama_free(ctx);
    return result < 0 ? result : generated;
}

JNIEXPORT void JNICALL
Java_com_seizethewind_lilyai_inference_LlamaNative_cancel(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s != nullptr) s->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_seizethewind_lilyai_inference_LlamaNative_free(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    if (s->model != nullptr) llama_model_free(s->model);
    delete s;
}

}  // extern "C"
