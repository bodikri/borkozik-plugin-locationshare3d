
package com.borkozic.location;

import android.location.Location;

interface ILocationCallback
{
	void onLocationChanged(in Location loc, boolean continous, boolean geoid, float smoothspeed, float avgspeed);
	void onProviderChanged(String provider);
	void onProviderDisabled(String provider);
	void onProviderEnabled(String provider);
	void onGpsStatusChanged(String provider, int status, int fsats, int tsats);
}
