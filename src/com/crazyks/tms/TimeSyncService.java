package com.crazyks.tms;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

public class TimeSyncService extends Service {
	private static final String TAG = "TimeSyncService";
	private static final boolean DEBUG = true;

	public static final String ACTION_SYNC_TIME_ON = "com.crazyks.action.AUTO_SYNC_TIME_ON";
	public static final String ACTION_SYNC_TIME_OFF = "com.crazyks.action.AUTO_SYNC_TIME_OFF";
	public static final String SP_NAME = "config";
	public static final String KEY_AUTO_SYNC = "auto_sync";
	private static final String SNTP_SERVER_CFG = "/system/etc/sntp-server.cfg";
	private static final String DEFAULT_SERVER = "2.android.pool.ntp.org";
	private static final int RETRY_COUNT = 5;
	private static final int REQUEST_TIMEOUT_MS = 20000; // 20 seconds
	private static final int RESYNC_DELAY_S = 86400; // 24 hours
	private static final long TICK_MS = 1000; // 1 seconds

	private static final int MSG_SYNC = 0;
	private static final int MSG_TICK = 1;
	private static final int MSG_QUIT = 2;

	private ArrayList<String> mServers = null;
	private Thread mWorkingThread = null;
	private Handler mHandler = null;
	private int mTickCount = 0;
	private boolean mRunningSync = false;
	private boolean mAutoSyncOn = true;

	private ITimeSyncService.Stub mBinder = new ITimeSyncService.Stub() {

		@Override
		public void setTimeSyncStatus(boolean onoff) throws RemoteException {
			setAutoSyncStatus(mAutoSyncOn = onoff);
			if (mAutoSyncOn) {
				doSync();
			}
		}

		@Override
		public boolean getTimeSyncStatus() throws RemoteException {
			return mAutoSyncOn;
		}
	};

	private BroadcastReceiver mReceiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
				ConnectivityManager cm = (ConnectivityManager) context
						.getSystemService(Context.CONNECTIVITY_SERVICE);
				NetworkInfo info = cm.getActiveNetworkInfo();
				if (info != null && info.getState() == State.CONNECTED) {
					doSync();
				}
			} else if (ACTION_SYNC_TIME_ON.equals(action)) {
				setAutoSyncStatus(mAutoSyncOn = true);
				doSync();
			} else if (ACTION_SYNC_TIME_OFF.equals(action)) {
				setAutoSyncStatus(mAutoSyncOn = false);
			}
		}
	};

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	@Override
	public void onCreate() {
		init();
		start();
		registerBroadcastReceiver();
		super.onCreate();
	}

	@Override
	public void onDestroy() {
		unregisterBroadcastReceiver();
		stop();
		super.onDestroy();
	}

	private void init() {
		mTickCount = 0;
		mAutoSyncOn = getAutoSyncStatus();
		mServers = loadServers(SNTP_SERVER_CFG);
		mWorkingThread = new Thread() {

			@Override
			public void run() {
				Looper.prepare();
				mHandler = new Handler(Looper.myLooper()) {

					@Override
					public void handleMessage(Message msg) {
						switch (msg.what) {
						case MSG_SYNC:
							if (mAutoSyncOn) {
								mTickCount = 0;
								syncTime();
							}
							sendMsgDelayed(MSG_TICK, TICK_MS);
							break;
						case MSG_TICK:
							mTickCount++;
							if (mTickCount >= RESYNC_DELAY_S) {
								mTickCount = 0;
								sendMsg(MSG_SYNC);
							} else {
								sendMsgDelayed(MSG_TICK, TICK_MS);
							}
							break;
						case MSG_QUIT:
							getLooper().quit();
							break;
						default:
							break;
						}
						super.handleMessage(msg);
					}
				};
				sendMsg(MSG_TICK);
				Looper.loop();
				super.run();
			}
		};
	}

	private void start() {
		mWorkingThread.start();
	}

	private void stop() {
		sendMsg(MSG_QUIT);
	}

	private void registerBroadcastReceiver() {
		IntentFilter filter = new IntentFilter(
				ConnectivityManager.CONNECTIVITY_ACTION);
		filter.addAction(ACTION_SYNC_TIME_ON);
		filter.addAction(ACTION_SYNC_TIME_OFF);
		registerReceiver(mReceiver, filter);
	}

	private void unregisterBroadcastReceiver() {
		unregisterReceiver(mReceiver);
	}

	private void doSync() {
		if (!mRunningSync) {
			sendMsg(MSG_SYNC);
		}
	}

	private void sendMsg(int what) {
		if (mHandler != null) {
			mHandler.removeMessages(what);
			mHandler.sendEmptyMessage(what);
		}
	}

	private void sendMsgDelayed(int what, long delayMillis) {
		if (mHandler != null) {
			mHandler.removeMessages(what);
			mHandler.sendEmptyMessageDelayed(what, delayMillis);
		}
	}

	private void setAutoSyncStatus(boolean onoff) {
		getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).edit()
				.putBoolean(KEY_AUTO_SYNC, onoff).commit();
	}

	private boolean getAutoSyncStatus() {
		return getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).getBoolean(
				KEY_AUTO_SYNC, true);
	}

	private synchronized boolean syncTime() {
		mRunningSync = true;
		SntpClient client = new SntpClient();
		for (int i = 0; i < RETRY_COUNT; i++) {
			for (String server : mServers) {
				if (client.requestTime(server, REQUEST_TIMEOUT_MS)) {
					long now = client.getNtpTime()
							+ SystemClock.elapsedRealtime()
							- client.getNtpTimeReference();
					if (DEBUG) {
						Log.i(TAG, "Request time from " + server + ", now is "
								+ now);
					}
					boolean ret = SystemClock.setCurrentTimeMillis(now);
					if (DEBUG) {
						if (ret) {
							Log.d(TAG, "Set current time succeed");
						} else {
							Log.d(TAG, "Set current time failed!");
						}
					}
					mRunningSync = false;
					return true;
				}
			}
		}
		if (DEBUG) {
			Log.d(TAG, "Request time failed!");
		}
		mRunningSync = false;
		return false;
	}

	private ArrayList<String> loadServers(final String configPath) {
		File configFile = new File(configPath);
		ArrayList<String> list = new ArrayList<String>();
		if (configFile.exists()) {
			BufferedReader reader = null;
			String tmpPath = null;
			try {
				reader = new BufferedReader(new FileReader(configFile));
				while ((tmpPath = reader.readLine()) != null) {
					if (!tmpPath.trim().equals("")) {
						list.add(tmpPath);
					}
				}
			} catch (Exception e) {
				Log.e(TAG, e.getMessage());
			} finally {
				if (reader != null) {
					try {
						reader.close();
					} catch (IOException e) {
						Log.e(TAG, e.getMessage());
					}
				}
			}
		}
		if (list.isEmpty()) {
			list.add(DEFAULT_SERVER);
		}
		return list;
	}

}
