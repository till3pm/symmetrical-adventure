LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := notag
LOCAL_SRC_FILES := notag.c
include $(BUILD_SHARED_LIBRARY)
