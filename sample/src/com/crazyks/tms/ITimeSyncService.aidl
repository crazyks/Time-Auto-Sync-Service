package com.crazyks.tms;

interface ITimeSyncService {
	void setTimeSyncStatus(in boolean onoff);
	boolean getTimeSyncStatus();
}