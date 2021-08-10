//
// C
// reated by green sun on 2020/2/13.
//
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <publisher.h>
#include <logger.h>
#include <lyjplayer.h>
#include <map>

template<class T>
int arrayLen(T &array) {
    return (sizeof(array) / sizeof(array[0]));
}

#ifdef __cplusplus
extern "C" {
#endif

const char *cls_publish = "com/lyj/learnffmpeg/Publisher";
const char *cls_player = "com/lyj/learnffmpeg/LyjPlayer";

Publisher *publisher = nullptr;

void publisher_init(JNIEnv *env, jobject thiz) {
    if (publisher == nullptr) {
        publisher = new Publisher();
        env->GetJavaVM(&publisher->vm);
    }
}

void publisher_release(JNIEnv *env, jobject thiz) {
    if (publisher != nullptr) {
        env->DeleteGlobalRef(publisher->callback);
        publisher->release();
        delete publisher;
        publisher = nullptr;
    }
}

/**
 * 推流
 * @param path
 * @return
 */
int publisher_start_publish(JNIEnv *env, jobject thiz, jstring path, jint width, jint height,
                            jint orientation) {
    const char *p_path = nullptr;
    p_path = env->GetStringUTFChars(path, nullptr);
    if (publisher) {
        publisher->startPublish(p_path, width, height, orientation);
    }
    env->ReleaseStringUTFChars(path, p_path);
    return 0;
}

/**
 * 设置监听
 * @param env
 * @param thiz
 * @param callback
 * @return
 */
void publisher_set_callback(JNIEnv *env, jobject thiz, jobject callback) {
    if (publisher) {
        if (publisher->callback) {
            env->DeleteGlobalRef(publisher->callback);
        }
        publisher->callback = env->NewGlobalRef(callback);
    }
}

/**
 * 接收数据流
 * @param env
 * @param thiz
 * @param data
 * @return
 */
int publisher_publish_data(JNIEnv *env, jobject thiz, jbyteArray data) {
    if (publisher && publisher->isPublish()) {
        int len = env->GetArrayLength(data);
        jbyte *buffer = new jbyte[len];
        env->GetByteArrayRegion(data, 0, len, buffer);
        //jbyte *buffer = env->GetByteArrayElements(data, nullptr);
        if (publisher) {
            publisher->pushData((unsigned char *) (buffer));
        }
        //env->ReleaseByteArrayElements(data, buffer, 0);
    }
    return 0;
}

/**
 * 结束推流
 * @param path
 * @return
 */
int publisher_stop_publish(JNIEnv *env, jobject thiz) {
    if (publisher) {
        publisher->stopPublish();
    }
    return 0;
}


map<string, LyjPlayer *> player_map;

/**
 * 根据java object生成key对应native层对象
 * @param env
 * @param obj
 * @return
 */
const char* getKey(JNIEnv *env, jobject obj) {
    jclass cls = env->GetObjectClass(obj);
    jmethodID mid = env->GetMethodID(cls, "toString", "()Ljava/lang/String;");
    jstring jstr = static_cast<jstring>(env->CallObjectMethod(obj, mid, nullptr));
    return env->GetStringUTFChars(jstr, nullptr);
}

void player_init_play(JNIEnv *env, jobject obj) {
    string key = getKey(env, obj);
    LyjPlayer *player = new LyjPlayer();
    env->GetJavaVM(&player->vm);
    player_map[key] = player;
    player->init();
}

void player_set_surface(JNIEnv *env, jobject obj, jobject surface) {
    string key = getKey(env, obj);
    LyjPlayer *player = player_map[key];
    if (player) {
        ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
        if (!window) {
            LOGE("window null");
        } else {
            player->window = window;
        }
    }
}

/**
 * 设置回调
 * @param env
 * @param obj
 * @param callback
 */
void player_set_callback(JNIEnv *env, jobject obj, jobject callback) {
    string key = getKey(env, obj);
    LyjPlayer *player = player_map[key];
    if (player) {
        if (player->callback) {
            env->DeleteGlobalRef(player->callback);
        }
        player->callback = env->NewGlobalRef(callback);
    }
}

/**
 * 开始播放
 * @param env
 * @param obj
 * @param url
 * @param surface
 * @return
 */
int player_start_play(JNIEnv *env, jobject obj, jstring url) {
    const char *path = nullptr;
    path = env->GetStringUTFChars(url, nullptr);
    string key = getKey(env, obj);
    LyjPlayer *player = player_map[key];
    if (player) {
        player->stopPlay();
        player->startPlay(path);
    } else {
        LOGE("cant not find player");
    }
    env->ReleaseStringUTFChars(url, path);
    return 0;
}

/**
 * 停止播放释放资源
 * @param env
 * @param obj
 * @return
 */
int player_stop_play(JNIEnv *env, jobject obj) {
    string key = getKey(env, obj);
    LyjPlayer *player = player_map[key];
    if (player) {
        player->stopPlay();
    }
    return 0;
}

void player_release(JNIEnv *env, jobject obj) {
    string key = getKey(env, obj);
    LyjPlayer *player = player_map[key];
    if (player) {
        env->DeleteGlobalRef(player->callback);
        player->release();
        player_map.erase(key);
        delete player;
    }
}


JNINativeMethod publisher_methods[] = {
        // 参数类型jstring
        {"initPublish",  "()V",                                      (void *) publisher_init},
        {"setCallBack",  "(Lcom/lyj/learnffmpeg/PublishCallBack;)V", (void *) publisher_set_callback},
        {"release",      "()V",                                      (void *) publisher_release},
        {"startPublish", "(Ljava/lang/String;III)I",                 (void *) publisher_start_publish},
        {"stopPublish",  "()I",                                      (void *) publisher_stop_publish},
        {"publishData",  "([B)I",                                    (void *) publisher_publish_data}
};

JNINativeMethod player_methods[] = {
        // 参数类型jstring
        {"initPlayer",       "()V",                                    (void *) player_init_play},
        {"setSurface",       "(Landroid/view/Surface;)V",              (void *) player_set_surface},
        {"setVideoCallBack", "(Lcom/lyj/learnffmpeg/VideoCallBack;)V", (void *) player_set_callback},
        {"startPlay",        "(Ljava/lang/String;)I",                  (void *) player_start_play},
        {"stopPlay",         "()I",                                    (void *) player_stop_play},
        {"release",          "()V",                                    (void *) player_release}
};

int jniRegisterNativeMethods(JNIEnv *env, const char *className, const JNINativeMethod *methods,
                             int count) {
    int res = -1;
    jclass cls = env->FindClass(className);
    if (cls != nullptr) {
        int ret = env->RegisterNatives(cls, methods, count);
        if (ret > 0) {
            res = 0;
        }
    }
    env->DeleteLocalRef(cls);
    return res;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    jint result = -1;
    if (vm->GetEnv((void **) (&env), JNI_VERSION_1_6) != JNI_OK) {
        return result;
    }
    jniRegisterNativeMethods(env, cls_publish, publisher_methods, arrayLen(publisher_methods));
    jniRegisterNativeMethods(env, cls_player, player_methods, arrayLen(player_methods));
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM *jvm, void *reserved) {

}

#ifdef __cplusplus
}
#endif

