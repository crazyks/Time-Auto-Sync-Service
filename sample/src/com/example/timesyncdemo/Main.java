package com.example.timesyncdemo;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.TextView;

import com.crazyks.tms.ITimeSyncService;

public class Main extends Activity {

	private CheckBox mCheckBox;
	private TextView mStatus;
	private ITimeSyncService mService = null;

	private ServiceConnection conn = new ServiceConnection() {

		@Override
		public void onServiceDisconnected(ComponentName name) {
			mService = null;
			mStatus.setText(R.string.disconnected);
			mCheckBox.setEnabled(false);
		}

		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			mService = ITimeSyncService.Stub.asInterface(service);
			mStatus.setText(R.string.connected);
			mCheckBox.setEnabled(true);
			boolean onoff = false;
			try {
				onoff = mService.getTimeSyncStatus();
			} catch (RemoteException e) {
			}
			mCheckBox.setChecked(onoff);
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		mCheckBox = (CheckBox) findViewById(R.id.checkBox1);
		mCheckBox.setOnCheckedChangeListener(new OnCheckedChangeListener() {

			@Override
			public void onCheckedChanged(CompoundButton buttonView,
					boolean isChecked) {
				if (mService != null) {
					try {
						mService.setTimeSyncStatus(isChecked);
					} catch (RemoteException e) {
					}
				}
			}
		});
		mStatus = (TextView) findViewById(R.id.textView1);
		mCheckBox.setEnabled(false);
		mStatus.setText(R.string.connecting);
		Intent service = new Intent("com.crazyks.tms.TimeSyncService");
		bindService(service, conn, Context.BIND_AUTO_CREATE);
	}

	@Override
	protected void onDestroy() {
		unbindService(conn);
		super.onDestroy();
	}
}
