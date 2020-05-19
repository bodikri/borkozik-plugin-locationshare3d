package com.borkozic.plugin.locationshare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.preference.PreferenceManager;
import android.util.Log;

public class Executor extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.e("Executor", action);
        if (action.equals("com.borkozic.plugins.action.INITIALIZE") )
        {
            PreferenceManager.setDefaultValues(context, R.xml.preferences, true);
            //PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("maptrek", action.equals("mobi.maptrek.plugins.action.INITIALIZE")).apply();
        } else if (action.equals("com.borkozic.plugins.action.FINALIZE") )
        {
            context.stopService(new Intent(context, SharingService.class));
        }
    }
}
