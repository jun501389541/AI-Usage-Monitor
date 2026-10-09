package com.aiusage.monitor.notification;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NotificationDeliveryPolicyTest {
    @Test public void allowsEventUntilOneHourAfterTrigger() {
        long event = 10_000_000L;
        assertTrue(NotificationDeliveryPolicy.shouldDeliver(event, event, 0, event));
        assertTrue(NotificationDeliveryPolicy.shouldDeliver(event, event - 1,
                0, event + NotificationDeliveryPolicy.MISSED_EVENT_GRACE_MS));
        assertFalse(NotificationDeliveryPolicy.shouldDeliver(event, event,
                0, event + NotificationDeliveryPolicy.MISSED_EVENT_GRACE_MS + 1));
    }

    @Test public void doesNotBackfillEventsFromBeforeEnableOrRepeatDeliveredEvent() {
        long event = 10_000_000L;
        assertFalse(NotificationDeliveryPolicy.shouldDeliver(event, event + 1, 0, event + 100));
        assertFalse(NotificationDeliveryPolicy.shouldDeliver(event, event,
                event, event + 100));
    }

    @Test public void futureEventIsNotDue() {
        assertFalse(NotificationDeliveryPolicy.shouldDeliver(20, 10, 0, 19));
    }
}
