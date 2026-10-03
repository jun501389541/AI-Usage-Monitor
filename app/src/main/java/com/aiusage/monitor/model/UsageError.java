package com.aiusage.monitor.model;

/**
 * Normalised failure categories. Spec §49.
 *
 * <p>Providers map their raw failures onto these values so that the UI and the
 * widget can render a consistent message. A provider's raw error text must
 * never reach the widget directly. Spec §49 / §53 rule 23.
 *
 * <p>Each constant carries the exact user-facing Chinese text the upstream
 * DeepSeek app already showed, so that introducing this layer does not change a
 * single visible string. Where a message needs a value that only the provider
 * knows (an HTTP status code, for example), the provider passes the finished
 * text to {@code UsageException} instead of relying on the default here.
 */
public enum UsageError {

    INVALID_CREDENTIAL("API Key 无效或已失效"),
    AUTH_EXPIRED("登录状态已过期，请重新登录"),
    PERMISSION_DENIED("当前密钥没有余额查询权限"),
    NETWORK_ERROR("网络连接失败，请检查网络后重试"),
    RATE_LIMITED("请求过于频繁，请稍后再试"),
    SERVICE_UNAVAILABLE("服务暂时不可用，请稍后再试"),
    BRIDGE_OFFLINE("电脑离线，显示的是上次数据"),
    BRIDGE_UNAUTHORIZED("电脑端授权已失效，请重新配对"),
    /**
     * The account points at a computer this phone no longer has a pairing for —
     * the row is gone, or the account was never paired at all. Distinct from
     * {@link #BRIDGE_UNAUTHORIZED}, which is the computer answering "no" to a token:
     * here no request can even be addressed, and telling the user their authorisation
     * expired would send them to re-run a pairing that has not happened. Phase 7
     * step 9, and Spec §53 rule 19's "撤销/过期 must stay distinguishable from
     * 电脑离线" taken one step further.
     */
    BRIDGE_PAIRING_REQUIRED("还没有与这台电脑配对，或配对已被撤销"),
    UNSUPPORTED("当前服务不支持该操作"),
    UNKNOWN("数据解析失败，请稍后重试");

    private final String message;

    UsageError(String message) {
        this.message = message;
    }

    /** Default user-facing text. Providers may override with a more specific one. */
    public String getMessage() {
        return message;
    }

    /**
     * Maps an HTTP status code onto a category.
     *
     * <p>Codes without a dedicated category fall back to {@link #UNKNOWN}, whose
     * caller is expected to build a status-specific message.
     */
    public static UsageError fromHttpStatus(int statusCode) {
        switch (statusCode) {
            case 401:
                return INVALID_CREDENTIAL;
            case 403:
                return PERMISSION_DENIED;
            case 429:
                return RATE_LIMITED;
            default:
                if (statusCode >= 500) {
                    return SERVICE_UNAVAILABLE;
                }
                return UNKNOWN;
        }
    }
}
