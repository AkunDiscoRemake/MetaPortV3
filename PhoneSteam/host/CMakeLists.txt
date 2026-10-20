cmake_minimum_required(VERSION 3.20)
project(driver_phonesteam LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(PHONESTEAM_WITH_FFMPEG "Codifica vídeo com FFmpeg (necessário para streaming)" ON)
set(FFMPEG_DIR "" CACHE PATH "Pasta do FFmpeg 6.1+ (com include/ e lib/), ex.: build 'shared' do gyan.dev ou BtbN")

include(FetchContent)
# Só os headers do OpenVR são necessários para um driver.
FetchContent_Declare(openvr
    GIT_REPOSITORY https://github.com/ValveSoftware/openvr.git
    GIT_TAG v2.5.1
    GIT_SHALLOW TRUE
    SOURCE_SUBDIR headers-only-no-build)
FetchContent_Declare(json
    URL https://github.com/nlohmann/json/releases/download/v3.11.3/json.tar.xz)
FetchContent_MakeAvailable(openvr json)

add_library(driver_phonesteam SHARED
    src/driver_main.cpp
    src/device_provider.cpp
    src/hmd_device.cpp
    src/controller_device.cpp
    src/phone_server.cpp
    src/net.cpp
    src/encoder.cpp
    src/d3d11_capture.cpp
    src/settings.cpp
    src/steam_apps.cpp)

target_include_directories(driver_phonesteam PRIVATE ${openvr_SOURCE_DIR}/headers)
target_link_libraries(driver_phonesteam PRIVATE nlohmann_json::nlohmann_json)
set_target_properties(driver_phonesteam PROPERTIES PREFIX "" CXX_VISIBILITY_PRESET hidden)

if(WIN32)
    target_compile_definitions(driver_phonesteam PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN _CRT_SECURE_NO_WARNINGS)
    target_link_libraries(driver_phonesteam PRIVATE ws2_32 d3d11 dxgi shell32 advapi32)
    set(PHONESTEAM_PLATFORM win64)
else()
    find_package(Threads REQUIRED)
    target_link_libraries(driver_phonesteam PRIVATE Threads::Threads)
    set(PHONESTEAM_PLATFORM linux64)
endif()

if(PHONESTEAM_WITH_FFMPEG)
    find_path(AVCODEC_INCLUDE libavcodec/avcodec.h HINTS ${FFMPEG_DIR}/include)
    find_library(AVCODEC_LIB avcodec HINTS ${FFMPEG_DIR}/lib)
    find_library(AVUTIL_LIB avutil HINTS ${FFMPEG_DIR}/lib)
    find_library(SWSCALE_LIB swscale HINTS ${FFMPEG_DIR}/lib)
    if(NOT AVCODEC_INCLUDE OR NOT AVCODEC_LIB OR NOT AVUTIL_LIB OR NOT SWSCALE_LIB)
        message(FATAL_ERROR "FFmpeg não encontrado. Use -DFFMPEG_DIR=<pasta> ou -DPHONESTEAM_WITH_FFMPEG=OFF")
    endif()
    target_compile_definitions(driver_phonesteam PRIVATE PHONESTEAM_WITH_FFMPEG)
    target_include_directories(driver_phonesteam PRIVATE ${AVCODEC_INCLUDE})
    target_link_libraries(driver_phonesteam PRIVATE ${AVCODEC_LIB} ${AVUTIL_LIB} ${SWSCALE_LIB})
endif()

# Monta a pasta do driver pronta para o SteamVR: driver/phonesteam/bin/<plataforma>/
set(PHONESTEAM_DRIVER_DIR ${CMAKE_SOURCE_DIR}/driver/phonesteam)
set_target_properties(driver_phonesteam PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY ${PHONESTEAM_DRIVER_DIR}/bin/${PHONESTEAM_PLATFORM}
    RUNTIME_OUTPUT_DIRECTORY_RELEASE ${PHONESTEAM_DRIVER_DIR}/bin/${PHONESTEAM_PLATFORM}
    LIBRARY_OUTPUT_DIRECTORY ${PHONESTEAM_DRIVER_DIR}/bin/${PHONESTEAM_PLATFORM}
    LIBRARY_OUTPUT_DIRECTORY_RELEASE ${PHONESTEAM_DRIVER_DIR}/bin/${PHONESTEAM_PLATFORM})

# Teste de protocolo sem SteamVR (Linux/Windows): cmake -DPHONESTEAM_BUILD_SELFTEST=ON
option(PHONESTEAM_BUILD_SELFTEST "Compila tests/selftest_host" OFF)
if(PHONESTEAM_BUILD_SELFTEST)
    add_executable(phonesteam_selftest tests/selftest_host.cpp src/phone_server.cpp src/net.cpp src/encoder.cpp)
    target_include_directories(phonesteam_selftest PRIVATE ${openvr_SOURCE_DIR}/headers)
    get_target_property(_defs driver_phonesteam COMPILE_DEFINITIONS)
    get_target_property(_incs driver_phonesteam INCLUDE_DIRECTORIES)
    get_target_property(_libs driver_phonesteam LINK_LIBRARIES)
    target_compile_definitions(phonesteam_selftest PRIVATE ${_defs})
    target_include_directories(phonesteam_selftest PRIVATE ${_incs})
    target_link_libraries(phonesteam_selftest PRIVATE ${_libs})
endif()
