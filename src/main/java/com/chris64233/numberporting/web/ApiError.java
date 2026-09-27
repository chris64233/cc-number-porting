package com.chris64233.numberporting.web;

/**
 * 统一错误响应：稳定 errorCode + 人类可读 message，便于调用方在并发竞态下
 * 判定唯一可解释的最终状态。
 */
public record ApiError(String errorCode, String message) {
}
