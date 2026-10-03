package com.aiusage.monitor.provider.deepseek;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aiusage.monitor.model.Balance;
import com.aiusage.monitor.model.Metric;
import com.aiusage.monitor.model.UsageError;
import com.aiusage.monitor.model.UsageResult;
import com.aiusage.monitor.model.UsageStatus;
import com.aiusage.monitor.provider.UsageException;

import org.junit.Test;

/**
 * Host-JVM tests for {@link DeepSeekBalanceParser}.
 *
 * <p>Lives in {@code com.aiusage.monitor.provider.deepseek} because the parser is
 * package-private by design: parsing is an implementation detail of
 * {@link DeepSeekProvider} and must not become part of the app's API.
 *
 * <p>Every assertion below mirrors a branch of the parser, including the two
 * documented failure modes that both surface as {@link UsageError#UNKNOWN}
 * (unparseable body, no CNY entry) and the documented non-failure for a
 * non-numeric amount (0 with the raw platform text preserved).
 */
public class DeepSeekBalanceParserTest {

    private static final double EPSILON = 1e-9d;
    private static final String ACCOUNT_ID = "acct-deepseek-1";

    @Test
    public void parsesRealShapedCnyResponse() throws Exception {
        String body = "{\"is_available\":true,\"balance_infos\":["
                + "{\"currency\":\"CNY\",\"total_balance\":\"38.52\","
                + "\"granted_balance\":\"0.00\",\"topped_up_balance\":\"38.52\"}]}";

        UsageResult result = DeepSeekBalanceParser.parse(ACCOUNT_ID, body);

        assertNotNull(result);
        assertEquals(ACCOUNT_ID, result.getAccountId());
        assertEquals("deepseek", result.getProviderId());
        assertEquals(UsageStatus.OK, result.getStatus());
        assertEquals(UsageResult.Source.DIRECT_API, result.getSource());
        assertTrue(result.getUpdatedAt() > 0L);

        Balance balance = result.getBalance();
        assertNotNull(balance);
        assertEquals(38.52d, balance.getAmount(), EPSILON);
        assertEquals("CNY", balance.getCurrency());
        assertEquals("38.52", balance.getRawText());

        Metric available = result.findMetric(DeepSeekBalanceParser.METRIC_IS_AVAILABLE);
        assertNotNull(available);
        assertEquals("is_available", available.getKey());
        assertEquals(1d, available.getValue(), EPSILON);

        // A balance response carries no quota windows.
        assertTrue(result.getQuotaWindows().isEmpty());
    }

    @Test
    public void unavailableFlagParsesToZeroMetricAndStillSucceeds() throws Exception {
        String body = "{\"is_available\":false,\"balance_infos\":["
                + "{\"currency\":\"CNY\",\"total_balance\":\"0.00\"}]}";

        UsageResult result = DeepSeekBalanceParser.parse(ACCOUNT_ID, body);

        assertEquals(UsageStatus.OK, result.getStatus());
        Metric available = result.findMetric(DeepSeekBalanceParser.METRIC_IS_AVAILABLE);
        assertNotNull(available);
        assertEquals(0d, available.getValue(), EPSILON);

        Balance balance = result.getBalance();
        assertNotNull(balance);
        assertEquals(0d, balance.getAmount(), EPSILON);
        assertEquals("0.00", balance.getRawText());
    }

    @Test
    public void picksTheCnyEntryWhenItIsNotFirst() throws Exception {
        String body = "{\"is_available\":true,\"balance_infos\":["
                + "{\"currency\":\"USD\",\"total_balance\":\"9.99\"},"
                + "{\"currency\":\"CNY\",\"total_balance\":\"38.52\"}]}";

        UsageResult result = DeepSeekBalanceParser.parse(ACCOUNT_ID, body);

        Balance balance = result.getBalance();
        assertNotNull(balance);
        assertEquals("CNY", balance.getCurrency());
        assertEquals(38.52d, balance.getAmount(), EPSILON);
        assertEquals("38.52", balance.getRawText());
    }

    @Test
    public void missingBalanceInfosThrowsUnknown() {
        String body = "{\"is_available\":true}";

        try {
            DeepSeekBalanceParser.parse(ACCOUNT_ID, body);
            fail("expected UsageException for a body without balance_infos");
        } catch (UsageException exception) {
            assertEquals(UsageError.UNKNOWN, exception.getError());
        }
    }

    @Test
    public void balanceInfosWithoutCnyThrowsUnknown() {
        String body = "{\"is_available\":true,\"balance_infos\":["
                + "{\"currency\":\"USD\",\"total_balance\":\"9.99\"}]}";

        try {
            DeepSeekBalanceParser.parse(ACCOUNT_ID, body);
            fail("expected UsageException for a body with no CNY entry");
        } catch (UsageException exception) {
            assertEquals(UsageError.UNKNOWN, exception.getError());
        }
    }

    @Test
    public void nonJsonBodyThrowsUnknown() {
        try {
            DeepSeekBalanceParser.parse(ACCOUNT_ID, "<html>");
            fail("expected UsageException for a non-JSON body");
        } catch (UsageException exception) {
            assertEquals(UsageError.UNKNOWN, exception.getError());
        }
    }

    @Test
    public void nonNumericTotalBalanceYieldsZeroAmountAndKeepsRawText() throws Exception {
        String body = "{\"is_available\":true,\"balance_infos\":["
                + "{\"currency\":\"CNY\",\"total_balance\":\"abc\"}]}";

        UsageResult result = DeepSeekBalanceParser.parse(ACCOUNT_ID, body);

        // Documented behaviour: the amount degrades to 0, the platform's own text
        // survives so the UI still shows what DeepSeek said, and the call succeeds.
        Balance balance = result.getBalance();
        assertNotNull(balance);
        assertEquals(0d, balance.getAmount(), EPSILON);
        assertEquals("CNY", balance.getCurrency());
        assertEquals("abc", balance.getRawText());
        assertEquals(UsageStatus.OK, result.getStatus());
    }

    @Test
    public void totalBalanceWithSurroundingWhitespaceIsTrimmed() throws Exception {
        String body = "{\"is_available\":true,\"balance_infos\":["
                + "{\"currency\":\"CNY\",\"total_balance\":\"  38.52  \"}]}";

        UsageResult result = DeepSeekBalanceParser.parse(ACCOUNT_ID, body);

        Balance balance = result.getBalance();
        assertNotNull(balance);
        assertEquals(38.52d, balance.getAmount(), EPSILON);
        assertEquals("38.52", balance.getRawText());
    }

    @Test
    public void amountIsRoundedToTwoDecimals() throws Exception {
        String body = "{\"is_available\":true,\"balance_infos\":["
                + "{\"currency\":\"CNY\",\"total_balance\":\"38.525\"}]}";

        UsageResult result = DeepSeekBalanceParser.parse(ACCOUNT_ID, body);

        Balance balance = result.getBalance();
        assertNotNull(balance);
        // BigDecimal.setScale(2, HALF_UP) on the parsed text.
        assertEquals(38.53d, balance.getAmount(), EPSILON);
        assertEquals("38.525", balance.getRawText());
    }

    @Test
    public void missingTotalBalanceDefaultsToZero() throws Exception {
        String body = "{\"is_available\":true,\"balance_infos\":["
                + "{\"currency\":\"CNY\"}]}";

        UsageResult result = DeepSeekBalanceParser.parse(ACCOUNT_ID, body);

        Balance balance = result.getBalance();
        assertNotNull(balance);
        assertEquals(0d, balance.getAmount(), EPSILON);
        assertEquals("0", balance.getRawText());
    }

    @Test
    public void resultHasNoQuotaWindowsAndNoExtraMetrics() throws Exception {
        String body = "{\"is_available\":true,\"balance_infos\":["
                + "{\"currency\":\"CNY\",\"total_balance\":\"38.52\"}]}";

        UsageResult result = DeepSeekBalanceParser.parse(ACCOUNT_ID, body);

        assertTrue(result.getQuotaWindows().isEmpty());
        assertEquals(1, result.getMetrics().size());
        assertNull(result.findMetric("today_usage"));
    }
}
