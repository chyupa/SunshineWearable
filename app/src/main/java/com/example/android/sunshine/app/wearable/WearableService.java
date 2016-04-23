package com.example.android.sunshine.app.wearable;

import android.app.Service;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.util.Date;

/**
 * Created by chyupa on 21-Apr-16.
 */
public class WearableService extends Service
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener  {

    private GoogleApiClient mGoogleApiClient;

    private static final String[] WEATHER_INFORMATION = new String[] {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_SHORT_DESC
    };

    private final static int COL_WEATHER_ID = 0;
    private final static int COL_MAX_TEMP = 1;
    private final static int COL_MIN_TEMP = 2;
    private final static int COL_SHORT_DESC = 3;

    @Override
    public void onCreate() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        mGoogleApiClient.connect();

    }

    @Override
    public void onConnected(Bundle bundle) {

        double high = 0;
        double min = 0;
        String shortDesc = "";
        Bitmap image = null;

        String locationQuery = Utility.getPreferredLocation(getApplicationContext());

        Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());

        Cursor cursor = getApplicationContext().getContentResolver().query(weatherUri, WEATHER_INFORMATION, null, null, null);

        if (cursor.moveToFirst()) {
            int weatherId = cursor.getInt(COL_WEATHER_ID);
            high = cursor.getDouble(COL_MAX_TEMP);
            min = cursor.getDouble(COL_MIN_TEMP);
            shortDesc = cursor.getString(COL_SHORT_DESC);

            int iconId = Utility.getIconResourceForWeatherCondition(weatherId);
            Resources resources = getApplicationContext().getResources();
            image = BitmapFactory.decodeResource(resources, iconId);
        }

        cursor.close();

        String[] info = new String[]{Double.toString(high), Double.toString(min), shortDesc};
        new WeatherAsyncTask(info, image).execute();

    }

    class WeatherAsyncTask extends AsyncTask<Node, Void, Void> {

        private final String[] info;
        private final Bitmap image;

        public WeatherAsyncTask(String[] info, Bitmap image) {
            this.info = info;
            this.image = image;
        }

        @Override
        protected Void doInBackground(Node... nodes) {
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create("/wearable");
            putDataMapRequest.getDataMap().putStringArray("information", this.info);
            putDataMapRequest.getDataMap().putLong("time", new Date().getTime());
            Asset asset = null;

            try {
                asset = createAssetFromBitmap(this.image);
            } catch (Exception e) {
                Log.e("EXCEPTION", e.getMessage());
            }
            putDataMapRequest.getDataMap().putAsset("image", asset);

            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi
                    .putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.e("WEAR-SERVICE", "failed");
                            } else {
                                Log.e("WEAR-SERVICE", "success");
                            }
                        }
                    });

            return null;
        }
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {

    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static Asset createAssetFromBitmap(Bitmap bitmap) {
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
        return Asset.createFromBytes(byteStream.toByteArray());
    }
}
