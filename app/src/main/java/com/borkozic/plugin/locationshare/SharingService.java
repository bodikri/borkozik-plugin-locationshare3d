package com.borkozic.plugin.locationshare;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import com.borkozic.data.Situation;
import com.borkozic.util.StringFormatter;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class SharingService extends Service implements OnSharedPreferenceChangeListener {
    /**
     * GPS status code
     */
    public static final int GPS_OFF = 1;
    /**
     * GPS status code
     */
    public static final int GPS_SEARCHING = 2;
    /**
     * GPS status code
     */
    public static final int GPS_OK = 3;

    private static final String TAG = "LocationSharing";
    private static final int NOTIFICATION_ID = 24164;

    public static final String BROADCAST_SITUATION_CHANGED = "com.borkozic.sharingSituationChanged";

    //private mobi.maptrek.location.ILocationRemoteService mMapTrekLocationService = null;
    private com.borkozic.location.ILocationRemoteService mAndrozicLocationService = null;

    private boolean sharingEnabled = false;
    private boolean isSuspended = false;
    private boolean notifyNewSituation = false;

    private PendingIntent contentIntent;

    ThreadPoolExecutor executorThread = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(1));
    private Timer timer;

    private ContentProviderClient contentProvider;

    final Location currentLocation = new Location("fake");
    String session;
    String user;
    private int updateInterval = 10000; // 10 seconds (default)
    int timeoutInterval = 600000; // 10 minutes (default)
    long timeCorrection = 0;
    double speedFactor = 1;
    String speedAbbr = "m/s";
    double elevationFactor = 1;
    String elevationAbbr = "m";
    private final Map<String, Situation> situations = new HashMap<>();
    final List<Situation> situationList = new ArrayList<>();

    // Drawing resources
    private Paint linePaint;
    private Paint textPaint;
    private Paint textFillPaint;
    private int pointWidth;
    //private boolean mMapTrek;
    private Uri mMapObjectsUri;
    private String mMapObjectIdSelection;
    private String[] mMapObjectColumns;
    private int mMapObjectNameColumn;
    private int mMapObjectLatitudeColumn;
    private int mMapObjectLongitudeColumn;
    private int mMapObjectBitmapColumn;
    private int mMapObjectColorColumn;

    @Override
    public void onCreate() {
        super.onCreate();

        // Prepare notification components
        contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, SituationList.class), 0);

        // Initialize drawing resources
        Resources resources = getResources();
        linePaint = new Paint();
        linePaint.setAntiAlias(true);
        linePaint.setStrokeWidth(2);
        linePaint.setStyle(Paint.Style.STROKE);
        //ContextCompat.getColor(getApplicationContext(), R.color.theme_accent_color);
        linePaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.usertag));//resources.getColor(R.color.usertag)
        textPaint = new Paint();
        textPaint.setAntiAlias(true);
        textPaint.setStrokeWidth(2);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Align.LEFT);
        textPaint.setTextSize(20);
        textPaint.setTypeface(Typeface.SANS_SERIF);
        textPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.usertag));//resources.getColor(R.color.usertag)
        textFillPaint = new Paint();
        textFillPaint.setAntiAlias(false);
        textFillPaint.setStrokeWidth(1);
        textFillPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        textFillPaint.setColor(ContextCompat.getColor(getApplicationContext(), R.color.usertagwithalpha));//resources.getColor(R.color.usertagwithalpha)

        // Initialize preferences
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        //mMapTrek = sharedPreferences.getBoolean("maptrek", false);

        onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_session));
        onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_user));
        onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_updateinterval));
        onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_notifications));
        onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_tagcolor));
        onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_tagsize));
        onSharedPreferenceChanged(sharedPreferences, getString(R.string.pref_sharing_timeout));
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);

        // Connect to data provider
        contentProvider = getContentResolver().acquireContentProviderClient(com.borkozic.provider.DataContract.MAPOBJECTS_URI);
        mMapObjectsUri = com.borkozic.provider.DataContract.MAPOBJECTS_URI;
        mMapObjectIdSelection = com.borkozic.provider.DataContract.MAPOBJECT_ID_SELECTION;
        mMapObjectColumns = com.borkozic.provider.DataContract.MAPOBJECT_COLUMNS;
        mMapObjectNameColumn = com.borkozic.provider.DataContract.MAPOBJECT_NAME_COLUMN;
        mMapObjectLatitudeColumn = com.borkozic.provider.DataContract.MAPOBJECT_LATITUDE_COLUMN;
        mMapObjectLongitudeColumn = com.borkozic.provider.DataContract.MAPOBJECT_LONGITUDE_COLUMN;
        mMapObjectBitmapColumn = com.borkozic.provider.DataContract.MAPOBJECT_BITMAP_COLUMN;
        mMapObjectColorColumn = com.borkozic.provider.DataContract.MAPOBJECT_TEXTCOLOR_COLUMN;
        readAndrozicPreferences();
        // Register location service status receiver
        registerReceiver(broadcastReceiver, new IntentFilter("com.borkozic.locatingStatusChanged"));


        if (contentProvider == null)
            stopSelf();

        // Connect to location service
        sharingEnabled = true;
        isSuspended = true;
        connect();

        Log.i(TAG, "Service started");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Disconnect from location service
        unregisterReceiver(broadcastReceiver);
        disconnect();
        stopForeground(true);
        stopTimer();

        // Clear data
        clearSituations();

        // Release data provider
        if (contentProvider != null)
            contentProvider.release();

        contentIntent = null;

        Log.i(TAG, "Service stopped");
    }

    private void clearSituations() {
        String[] args;
        synchronized (situationList) {
            args = new String[situationList.size()];
            int i = 0;
            for (Situation situation : situationList) {
                args[i] = String.valueOf(situation.id);
                i++;
            }
        }
        synchronized (situations) {
            synchronized (situationList) {
                situationList.clear();
            }
            situations.clear();
        }
        // Remove situations from map
        try {
            if (contentProvider != null)
                contentProvider.delete(mMapObjectsUri, mMapObjectIdSelection, args);
        } catch (RemoteException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        sendBroadcast(new Intent(BROADCAST_SITUATION_CHANGED));
    }

    protected void updateSituations() {
        updateNotification(R.mipmap.ic_stat_sharing_out);

        executorThread.getQueue().poll();
        executorThread.execute(new Runnable() {
            public void run() {
                Log.d(TAG, "updateSituation");
                URI URL;
                boolean updated = false;
                try {
                    String query;
                    synchronized (currentLocation) {
                        query = "session=" + URLEncoder.encode(session, "UTF-8") + ";user=" + URLEncoder.encode(user, "UTF-8") + ";lat=" + currentLocation.getLatitude() + ";lon=" + currentLocation.getLongitude()
                                + ";track=" + currentLocation.getBearing() + ";speed=" + currentLocation.getSpeed() + ";ftime=" + currentLocation.getTime() + ";altitude=" + currentLocation.getAltitude();
                    }
                    URL = new URI("http", null, "androzic.com", 80, "/cgi-bin/loc.cgi", query, null);
                    Log.d(TAG, query);
                    HttpClient httpclient = new DefaultHttpClient();
                    HttpResponse response = httpclient.execute(new HttpGet(URL));
                    //updateNotification(R.mipmap.ic_stat_sharing_in);//Процедурка да покаже че има размяна на информация
                    StatusLine statusLine = response.getStatusLine();
                    if (statusLine.getStatusCode() == HttpStatus.SC_OK) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        response.getEntity().writeTo(out);
                        out.close();
                        String responseString = out.toString();
                        Log.d(TAG, responseString);
                        JSONObject sts = new JSONObject(responseString);
                        JSONArray entries = sts.getJSONArray("users");

                        for (int i = 0; i < entries.length(); i++) {
                            JSONObject situation = entries.getJSONObject(i);
                            String name = situation.getString("user");
                            if (name.equals(user))
                                continue;
                            synchronized (situations) {
                                Situation s = situations.get(name);
                                if (s == null) {
                                    s = new Situation(name);
                                    situations.put(name, s);
                                    synchronized (situationList) {
                                        situationList.add(s);
                                    }
                                }
                                s.latitude = situation.getDouble("lat");
                                s.longitude = situation.getDouble("lon");
                                s.speed = situation.getDouble("speed");
                                s.track = situation.getDouble("track");
                                s.time = situation.getLong("ftime");
                                s.altitude= situation.getDouble("altitude");
                            }
                        }
                        updated = true;
                    } else {
                        response.getEntity().getContent().close();
                        throw new IOException(statusLine.getReasonPhrase());
                    }
                } catch (Exception e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }

                synchronized (situations) {
                    long curTime = System.currentTimeMillis() - timeCorrection;
                    for (Situation situation : situations.values()) {
                        situation.silent = situation.time + timeoutInterval < curTime;
                    }
                }

                if (updated)
                    sendBroadcast(new Intent(BROADCAST_SITUATION_CHANGED));

                try {
                    sendMapObjects();
                } catch (RemoteException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }

                updateNotification(R.mipmap.ic_stat_sharing);
            }
        });
    }

    protected void sendNewSituationNotification(Situation situation) {
        Intent i = new Intent("com.borkozic.COORDINATES_RECEIVED");
        i.putExtra("title", session);
        i.putExtra("sender", situation.name);
        i.putExtra("origin", getApplicationContext().getPackageName());
        i.putExtra("lat", situation.latitude);
        i.putExtra("lon", situation.longitude);

        String msg = getString(R.string.notif_newsession, situation.name);

        Notification.Builder builder = new Notification.Builder(this);
        builder.setWhen(situation.time);
        builder.setSmallIcon(R.mipmap.ic_stat_sharing);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, (int) situation.id, i, PendingIntent.FLAG_ONE_SHOT);
        builder.setContentIntent(pendingIntent);
        builder.setContentTitle(getText(R.string.app_name));
        builder.setContentText(msg);
        builder.setTicker(msg);
        //builder.setGroup(mMapTrek ? "maptrek" : "borkozic");
        builder.setAutoCancel(true);
        builder.setDefaults(Notification.DEFAULT_SOUND);
        //builder.setCategory(Notification.CATEGORY_SOCIAL);
        builder.setPriority(Notification.PRIORITY_LOW);
        //builder.setVisibility(Notification.VISIBILITY_PRIVATE);
        //builder.setColor(getResources().getColor(R.color.theme_accent_color));
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify((int) situation.id, builder.build());
    }

    private void sendMapObjects() throws RemoteException {
        synchronized (situationList) {
            for (Situation situation : situationList) {
                byte[] bitmap = getSituationBitmap(situation);
                ContentValues values = new ContentValues();
                // Name is not required if bitmap is used, but we need it for navigation service.
                // See SituationList for navigation initiation code.
                values.put(mMapObjectColumns[mMapObjectNameColumn], situation.name);
                values.put(mMapObjectColumns[mMapObjectLatitudeColumn], situation.latitude);
                values.put(mMapObjectColumns[mMapObjectLongitudeColumn], situation.longitude);
                values.put(mMapObjectColumns[mMapObjectBitmapColumn], bitmap);
                values.put(mMapObjectColumns[mMapObjectColorColumn], linePaint.getColor());
                // If this is a new object insert it
                if (situation.id == 0) {
                    Uri uri = contentProvider.insert(mMapObjectsUri, values);
                    situation.id = ContentUris.parseId(uri);
                    if (notifyNewSituation)
                        sendNewSituationNotification(situation);
                }
                // Otherwise update it
                else {
                    Uri uri = ContentUris.withAppendedId(mMapObjectsUri, situation.id);
                    contentProvider.update(uri, values, null, null);
                }
            }
        }
    }

    private byte[] getSituationBitmap(Situation situation) {
        Bitmap b;
            Rect textRect = new Rect();
            String tag = String.valueOf(Math.round(situation.speed * speedFactor)) + "  " + String.valueOf(Math.round(situation.track));
            textPaint.getTextBounds(tag, 0, tag.length(), textRect);
            Rect rect1 = new Rect();
            textPaint.getTextBounds(situation.name, 0, situation.name.length(), rect1);
            textRect.union(rect1);
            int textHeight = textRect.height();
            textRect.inset(0, -(textHeight + 3) / 2);
            textRect.inset(-2, -2);
            int offset = pointWidth * 3;
            int width = textRect.width() + offset + 3;
            int height = textRect.height() + offset + 3;

            b = Bitmap.createBitmap(width * 2, height * 2, Config.ARGB_8888);
            Canvas bc = new Canvas(b);

            linePaint.setAlpha(situation.silent ? 128 : 255);
            textPaint.setAlpha(situation.silent ? 128 : 255);

            bc.translate(width, height);
            int half = Math.round(pointWidth / 2);
            Rect tagRect = new Rect(-half, -half, +half, +half);
            bc.drawRect(tagRect, linePaint);
            bc.drawLine(0, 0, offset, -offset, linePaint);
            textRect.offsetTo(offset + 3, -offset - textHeight * 2 - 5);
            bc.drawRect(textRect, textFillPaint);
            bc.drawText(tag, offset + 5, -offset, textPaint);
            bc.drawText(situation.name, offset + 5, -offset - textHeight - 3, textPaint);
            bc.save();
            bc.rotate((float) situation.track, 0, 0);
            bc.drawLine(0, 0, 0, -pointWidth * 2, linePaint);
            bc.restore();


        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, stream);
        return stream.toByteArray();
    }

    // This is not used in code, but included to demonstrate, how to remove
    // single map object from Androzic map.
    @SuppressWarnings("unused")
    private void removeMapObject(String name) {
        Situation situation = situations.get(name);
        synchronized (situationList) {
            situationList.remove(situation);
        }
        synchronized (situations) {
            situations.remove(name);
        }
        Uri uri = ContentUris.withAppendedId(mMapObjectsUri, situation.id);
        try {
            contentProvider.delete(uri, null, null);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void connect() {
        Intent intent = new Intent( "com.borkozic.location");
        ResolveInfo ri = getPackageManager().resolveService(intent, 0);
        // This generally can not happen because plugin can be run only from parent application
        if (ri == null)
            return;
        ServiceInfo service = ri.serviceInfo;
        intent.setComponent(new ComponentName(service.applicationInfo.packageName, service.name));
        bindService(intent, mLocationConnection, BIND_AUTO_CREATE);
    }

    private void disconnect() {
        if (mAndrozicLocationService != null) {
            try {
                mAndrozicLocationService.unregisterCallback(mAndrozicLocationCallback);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            unbindService(mLocationConnection);
            mAndrozicLocationService = null;
        }
    }

    private void startTimer() {
        if (timer != null)
            stopTimer();

        timer = new Timer();
        TimerTask updateTask = new UpdateSituationsTask();
        timer.scheduleAtFixedRate(updateTask, 0, updateInterval);
    }

    private void stopTimer() {
        if (timer != null)
            timer.cancel();
        timer = null;
    }

    private void doStart() {
        if (isSuspended) {
            if (sharingEnabled) {
                startForeground(NOTIFICATION_ID, getNotification(R.mipmap.ic_stat_sharing));
                startTimer();
            }
            isSuspended = false;
        }
    }

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.e(TAG, "Broadcast: " + action);
            if (action.equals("com.borkozic.locatingStatusChanged")) {
                boolean isLocating = false;
                try {
                    isLocating = mAndrozicLocationService != null && mAndrozicLocationService.isLocating();
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                if (isLocating) {
                    doStart();
                } else if (sharingEnabled) {
                    stopForeground(true);
                    stopTimer();
                    isSuspended = true;
                }
            }
        }
    };

    private Notification getNotification(int icon) {
        Notification.Builder builder = new Notification.Builder(this);
        builder.setWhen(0);
        builder.setSmallIcon(icon);
        builder.setContentIntent(contentIntent);
        builder.setContentTitle(getText(R.string.pref_sharing_title));
        builder.setContentText(getText(R.string.notif_sharing));
        //builder.setGroup("borkozic");
        //builder.setCategory(NotificationCompat.CATEGORY_SERVICE);
        builder.setPriority(Notification.PRIORITY_LOW);
        //builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        //builder.setColor(getResources().getColor(R.color.theme_accent_color));
        builder.setOngoing(true);
        return builder.build();
    }

    private void updateNotification(int icon) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, getNotification(icon));
    }

    private void readAndrozicPreferences() {
        // Resolve content provider
        ContentProviderClient client = getContentResolver().acquireContentProviderClient(com.borkozic.provider.PreferencesContract.PREFERENCES_URI);

        // Setup preference items we want to read (order is important - it should correlate with the read order later in code)
        int[] fields = new int[]{com.borkozic.provider.PreferencesContract.SPEED_FACTOR, com.borkozic.provider.PreferencesContract.SPEED_ABBREVIATION, com.borkozic.provider.PreferencesContract.DISTANCE_FACTOR, com.borkozic.provider.PreferencesContract.DISTANCE_ABBREVIATION,
                com.borkozic.provider.PreferencesContract.DISTANCE_SHORT_FACTOR, com.borkozic.provider.PreferencesContract.DISTANCE_SHORT_ABBREVIATION};
        // Convert them to strings
        String[] args = new String[fields.length];
        for (int i = 0; i < fields.length; i++) {
            args[i] = String.valueOf(fields[i]);
        }
        try {
            // Request data from preferences content provider
            Cursor cursor = client.query(com.borkozic.provider.PreferencesContract.PREFERENCES_URI, com.borkozic.provider.PreferencesContract.DATA_COLUMNS, com.borkozic.provider.PreferencesContract.DATA_SELECTION, args, null);
            cursor.moveToFirst();
            speedFactor = cursor.getDouble(com.borkozic.provider.PreferencesContract.DATA_COLUMN);
            cursor.moveToNext();
            speedAbbr = cursor.getString(com.borkozic.provider.PreferencesContract.DATA_COLUMN);
            cursor.moveToNext();
            StringFormatter.distanceFactor = cursor.getDouble(com.borkozic.provider.PreferencesContract.DATA_COLUMN);
            cursor.moveToNext();
            StringFormatter.distanceAbbr = cursor.getString(com.borkozic.provider.PreferencesContract.DATA_COLUMN);
            cursor.moveToNext();
            StringFormatter.distanceShortFactor = cursor.getDouble(com.borkozic.provider.PreferencesContract.DATA_COLUMN);
            cursor.moveToNext();
            StringFormatter.distanceShortAbbr = cursor.getString(com.borkozic.provider.PreferencesContract.DATA_COLUMN);
        } catch (RemoteException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        // Notify that the binding is not required anymore
        client.release();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        String oldsession = session;
        String olduser = user;

        if (getString(R.string.pref_sharing_session).equals(key)) {
            session = sharedPreferences.getString(key, "");
        } else if (getString(R.string.pref_sharing_user).equals(key)) {
            user = sharedPreferences.getString(key, "");
        } else if (getString(R.string.pref_sharing_updateinterval).equals(key)) {
            updateInterval = sharedPreferences.getInt(key, getResources().getInteger(R.integer.def_sharing_updateinterval)) * 1000;
            if (timer != null) {
                stopTimer();
                startTimer();
            }
        } else if (getString(R.string.pref_sharing_notifications).equals(key)) {
            notifyNewSituation = sharedPreferences.getBoolean(key, getResources().getBoolean(R.bool.def_notifications));
        } else if (getString(R.string.pref_sharing_tagcolor).equals(key)) {
            linePaint.setColor(sharedPreferences.getInt(key, getResources().getColor(R.color.usertag)));
            textPaint.setColor(sharedPreferences.getInt(key, getResources().getColor(R.color.usertag)));
        } else if (getString(R.string.pref_sharing_tagsize).equals(key)) {
            int width = sharedPreferences.getInt(key, getResources().getInteger(R.integer.def_sharing_tagsize));
                textPaint.setTextSize(width * 10);
                pointWidth = width * 6;

        } else if (getString(R.string.pref_sharing_timeout).equals(key)) {
            timeoutInterval = sharedPreferences.getInt(key, getResources().getInteger(R.integer.def_sharing_timeout)) * 60000;
        }

        if (!session.equals(oldsession) || !user.equals(olduser)) {
            clearSituations();
        }
        if ((session != null && session.trim().equals("")) || (user != null && user.trim().equals("")))
            stopSelf();
    }

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        SharingService getService() {
            return SharingService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private ServiceConnection mLocationConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.e(TAG, "onServiceConnected(" + className.getPackageName() + ")");

                mAndrozicLocationService = com.borkozic.location.ILocationRemoteService.Stub.asInterface(service);
                try {
                    mAndrozicLocationService.registerCallback(mAndrozicLocationCallback);
                    if (mAndrozicLocationService.isLocating())
                        doStart();
                    Log.d(TAG, "Location service connected");
                } catch (RemoteException e) {
                    e.printStackTrace();
                }

        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "Location service disconnected");
            //mMapTrekLocationService = null;
            mAndrozicLocationService = null;
        }
    };

    private com.borkozic.location.ILocationCallback mAndrozicLocationCallback = new com.borkozic.location.ILocationCallback.Stub() {
        @Override
        public void onGpsStatusChanged(String provider, int status, int fsats, int tsats) {
            if (LocationManager.GPS_PROVIDER.equals(provider)) {
                switch (status) {
                    case GPS_OFF:
                    case GPS_SEARCHING:
                        //TODO Send lost location status
                }
            }
        }

        @Override
        public void onLocationChanged(Location loc, boolean continuous, boolean geoid, float smoothspeed, float avgspeed) {
            synchronized (currentLocation) {
                currentLocation.set(loc);
                timeCorrection = System.currentTimeMillis() - currentLocation.getTime();
            }
        }

        @Override
        public void onProviderChanged(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }
    };

    class UpdateSituationsTask extends TimerTask {
        public void run() {
            updateSituations();
        }
    }
}
