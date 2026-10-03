package com.aiusage.monitor.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * Tests for {@link UsageError}: HTTP status mapping and the exact user-facing
 * text of the upstream-mapped messages.
 *
 * <p>The message assertions are deliberately exact. These strings are shown to
 * the user and are meant to be identical to the upstream DeepSeek app, so a
 * change here is a behaviour change and must fail the build.
 */
public class UsageErrorTest {

    @Test
    public void fromHttpStatusMapsUnauthorizedToInvalidCredential() {
        assertEquals(UsageError.INVALID_CREDENTIAL, UsageError.fromHttpStatus(401));
    }

    @Test
    public void fromHttpStatusMapsForbiddenToPermissionDenied() {
        assertEquals(UsageError.PERMISSION_DENIED, UsageError.fromHttpStatus(403));
    }

    @Test
    public void fromHttpStatusMapsTooManyRequestsToRateLimited() {
        assertEquals(UsageError.RATE_LIMITED, UsageError.fromHttpStatus(429));
    }

    @Test
    public void fromHttpStatusMapsServerErrorToServiceUnavailable() {
        assertEquals(UsageError.SERVICE_UNAVAILABLE, UsageError.fromHttpStatus(500));
    }

    @Test
    public void fromHttpStatusMapsServiceUnavailableToServiceUnavailable() {
        assertEquals(UsageError.SERVICE_UNAVAILABLE, UsageError.fromHttpStatus(503));
    }

    @Test
    public void fromHttpStatusMapsEveryStatusAtOrAboveFiveHundredToServiceUnavailable() {
        assertEquals(UsageError.SERVICE_UNAVAILABLE, UsageError.fromHttpStatus(502));
        assertEquals(UsageError.SERVICE_UNAVAILABLE, UsageError.fromHttpStatus(504));
        assertEquals(UsageError.SERVICE_UNAVAILABLE, UsageError.fromHttpStatus(599));
    }

    @Test
    public void fromHttpStatusMapsUnmappedCodeToUnknown() {
        assertEquals(UsageError.UNKNOWN, UsageError.fromHttpStatus(418));
    }

    @Test
    public void fromHttpStatusMapsSuccessCodeToUnknown() {
        assertEquals(UsageError.UNKNOWN, UsageError.fromHttpStatus(200));
    }

    @Test
    public void fromHttpStatusMapsBadRequestToUnknown() {
        assertEquals(UsageError.UNKNOWN, UsageError.fromHttpStatus(400));
    }

    @Test
    public void fromHttpStatusMapsNotFoundToUnknown() {
        assertEquals(UsageError.UNKNOWN, UsageError.fromHttpStatus(404));
    }

    @Test
    public void fromHttpStatusMapsNegativeCodeToUnknown() {
        assertEquals(UsageError.UNKNOWN, UsageError.fromHttpStatus(-1));
    }

    @Test
    public void everyConstantCarriesANonEmptyMessage() {
        for (UsageError error : UsageError.values()) {
            assertNotNull(error.name() + " has a null message", error.getMessage());
            assertFalse(error.name() + " has an empty message", error.getMessage().isEmpty());
        }
    }

    @Test
    public void invalidCredentialMessageIsStable() {
        assertEquals("API Key 无效或已失效", UsageError.INVALID_CREDENTIAL.getMessage());
    }

    @Test
    public void permissionDeniedMessageIsStable() {
        assertEquals("当前密钥没有余额查询权限", UsageError.PERMISSION_DENIED.getMessage());
    }

    @Test
    public void rateLimitedMessageIsStable() {
        assertEquals("请求过于频繁，请稍后再试", UsageError.RATE_LIMITED.getMessage());
    }

    @Test
    public void networkErrorMessageIsStable() {
        assertEquals("网络连接失败，请检查网络后重试", UsageError.NETWORK_ERROR.getMessage());
    }
}
