package com.bn.dialer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts Bn in the background after the phone restarts or Bn is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Host.wake(c);
    }
}
