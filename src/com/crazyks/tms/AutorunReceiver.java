package com.crazyks.tms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AutorunReceiver extends BroadcastReceiver {

	@Override
	public final void onReceive(final Context context, final Intent intent) {
		context.startService(new Intent(context, TimeSyncService.class));
	}

}
