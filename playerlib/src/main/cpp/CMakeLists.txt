cmake_minimum_required(VERSION 3.4)
project(lyjplayer)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11 -frtti -fexceptions")
#获取上级目录
get_filename_component(PARENT_DIR "${PROJECT_SOURCE_DIR}" PATH)
get_filename_component(SRC_DIR "${PARENT_DIR}" PATH)
get_filename_component(APP_DIR "${SRC_DIR}" PATH)
# GLOB将所有匹配的文件生成一个list赋值给SRC_LIST
file(GLOB SRC_LIST "${PROJECT_SOURCE_DIR}/src/*.cpp")
# 输出.so库位置
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY
        ${APP_DIR}/output/${CMAKE_ANDROID_ARCH_ABI})
# 第三方库目录
set(LIBS_DIR ${APP_DIR}/libs/${CMAKE_ANDROID_ARCH_ABI})
message(WARNING ${LIBS_DIR})
# 头文件目录
include_directories(
        ${PROJECT_SOURCE_DIR}/include
)
# 生成动态库
add_library(lyjplayer SHARED ${SRC_LIST})
# 编解码(最重要的库)
add_library(avcodec SHARED IMPORTED)
# 设备信息
add_library(avdevice SHARED IMPORTED)
# 滤镜特效处理库
add_library(avfilter SHARED IMPORTED)
# 封装格式处理库
add_library(avformat SHARED IMPORTED)

add_library(avutil SHARED IMPORTED)
# 音频采样数据格式转换库
add_library(swresample SHARED IMPORTED)
# 视频像素数据格式转换
add_library(swscale SHARED IMPORTED)
# 后处理
add_library(postproc SHARED IMPORTED)
add_library(yuv SHARED IMPORTED)

find_library(log-lib log)

set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${LIBS_DIR}/libavcodec.so)
set_target_properties(avdevice PROPERTIES IMPORTED_LOCATION ${LIBS_DIR}/libavdevice.so)
set_target_properties(avfilter PROPERTIES IMPORTED_LOCATION ${LIBS_DIR}/libavfilter.so)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${LIBS_DIR}/libavformat.so)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${LIBS_DIR}/libavutil.so)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${LIBS_DIR}/libswresample.so)
set_target_properties(swscale PROPERTIES IMPORTED_LOCATION ${LIBS_DIR}/libswscale.so)
set_target_properties(postproc PROPERTIES IMPORTED_LOCATION ${LIBS_DIR}/libpostproc.so)
set_target_properties(yuv PROPERTIES IMPORTED_LOCATION ${LIBS_DIR}/libyuv.so)
# 指定生成版本号，VERSION指代动态库版本，SOVERSION指代API版本
set_target_properties(lyjplayer PROPERTIES VERSION 1.2 SOVERSION 1)
target_link_libraries(
        lyjplayer#目标库

        # 依赖库，可以写多个
        log
        android
        avcodec
        avdevice
        avfilter
        avformat
        avutil
        swresample
        swscale
        postproc
        yuv
)