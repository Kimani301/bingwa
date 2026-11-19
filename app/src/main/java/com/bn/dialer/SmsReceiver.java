package com.bn.dialer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

/** Hands every incoming SMS (with the SIM slot it arrived on) to the Bn page. */
public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            SmsMessage[] ms = Telephony.Sms.Intents.getMessagesFromIntent(i);
            if (ms == null || ms.length == 0) return;
            String from = ms[0].getOriginatingAddress();
            StringBuilder body = new StringBuilder();
            for (SmsMessage m : ms) body.append(m.getMessageBody());

            String slot = "null";
            try {
                int sub = i.getIntExtra("subscription", -1);
                SubscriptionManager sm = (SubscriptionManager) c.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                SubscriptionInfo si = sm.getActiveSubscriptionInfo(sub);
                if (si != null) slot = String.valueOf(si.getSimSlotIndex());
            } catch (Exception ignored) {
            }

            Bridge.sms(c, "window.onSmsReceived(" + Bridge.q(from) + "," + Bridge.q(body.toString()) + "," + slot + ")");
        } catch (Exception ignored) {
        }
    }
}
