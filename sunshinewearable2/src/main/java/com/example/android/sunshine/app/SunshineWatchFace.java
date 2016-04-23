/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    GoogleApiClient mGoogleApiClient;

    String data[] = {};
    double maxTemp = 20;
    double minTemp = 10;
    Bitmap mBitmap = null;
    boolean dataReceived = false;

    @Override
    public Engine onCreateEngine() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        mGoogleApiClient.connect();
        return new Engine();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Wearable.DataApi.addListener(mGoogleApiClient, new DataApi.DataListener() {
            @Override
            public void onDataChanged(DataEventBuffer dataEventBuffer) {
                for (DataEvent event : dataEventBuffer) {
                    String eventUri = event.getDataItem().getUri().toString();

                    if (eventUri.contains("/wearable")) {
                        DataMapItem dataItem = DataMapItem.fromDataItem(event.getDataItem());
                        Asset profileAsset = dataItem.getDataMap().getAsset("image");
                        new GetAssetFromDataItem(profileAsset).execute();

                        data = dataItem.getDataMap().getStringArray("information");
                        maxTemp = Double.valueOf(data[0]);
                        minTemp = Double.valueOf(data[1]);

                        dataReceived = true;
                    }
                }
            }
        });
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
    }

    class GetAssetFromDataItem extends AsyncTask<Asset, Void, Bitmap> {

        private final Asset asset;

        public GetAssetFromDataItem(Asset asset) {
            this.asset = asset;
        }

        @Override
        protected Bitmap doInBackground(Asset... assets) {
            mBitmap = getResizedBitmap(loadBitmapFromAsset(this.asset), 50, 50);
            return mBitmap;
        }
    }

    public Bitmap loadBitmapFromAsset(Asset asset) {
        if (asset == null) {
            throw new IllegalArgumentException("Asset must be non-null");
        }
        ConnectionResult result =
                mGoogleApiClient.blockingConnect(6000, TimeUnit.MILLISECONDS);
        if (!result.isSuccess()) {
            return null;
        }
        // convert asset into a file descriptor and block until it's ready
        InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                mGoogleApiClient, asset).await().getInputStream();
        mGoogleApiClient.disconnect();

        if (assetInputStream == null) {
            Log.w("WEAR", "Requested an unknown Asset.");
            return null;
        }
        // decode the stream into a bitmap
        return BitmapFactory.decodeStream(assetInputStream);
    }


    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mDatePaint;
        Paint mDelimiterPaint;
        Paint mTempMaxPaint;
        Paint mTempMinPaint;
        Paint mImagePaint;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;

        float mDateXOffset;
        float mDateYOffset;

        float mDelimiterLength;
        float mDelimiterXOffset;
        float mDelimiterYOffset;

        float mTempMaxXOffset;
        float mTempMaxYOffset;

        float mTempMinXOffset;
        float mTempMinYOffset;

        float mImageXOffset;
        float mImageYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

//            if (dataReceived) {
//                invalidate();
//            }

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mDateYOffset = resources.getDimension(R.dimen.date_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_date));

            mDelimiterPaint = new Paint();
            mDelimiterPaint.setColor(resources.getColor(R.color.digital_date));

            mTempMaxPaint = new Paint();
            mTempMaxPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTempMinPaint = new Paint();
            mTempMinPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mImagePaint = new Paint();
            mImagePaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTime = new Time();

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            mTextPaint.setTextSize(textSize);

            mDateXOffset = resources.getDimension(isRound
                    ? R.dimen.date_x_offset_round : R.dimen.date_x_offset);

            float dateSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_size_round : R.dimen.digital_date_size);
            mDatePaint.setTextSize(dateSize);

            mDelimiterXOffset = resources.getDimension(R.dimen.delimiter_x_offset);
            mDelimiterYOffset = resources.getDimension(R.dimen.delimiter_y_offset);
            mDelimiterLength = resources.getDimension(R.dimen.delimiter_w);

            mTempMaxXOffset = resources.getDimension(isRound
                    ? R.dimen.temp_max_x_offset_round : R.dimen.temp_max_x_offset);
            mTempMaxYOffset = resources.getDimension(isRound
                    ? R.dimen.temp_max_y_offset_round : R.dimen.temp_max_y_offset);

            float tempMaxSize = resources.getDimension(R.dimen.temp_size);
            mTempMaxPaint.setTextSize(tempMaxSize);

            mTempMinXOffset = resources.getDimension(isRound
                    ? R.dimen.temp_min_x_offset_round : R.dimen.temp_min_x_offset);
            mTempMinYOffset = resources.getDimension(isRound
                    ? R.dimen.temp_min_y_offset_round : R.dimen.temp_min_y_offset);

            float tempMinSize = resources.getDimension(R.dimen.temp_size);
            mTempMinPaint.setTextSize(tempMinSize);

            mImageXOffset = resources.getDimension(isRound
                    ? R.dimen.image_x_offset_round : R.dimen.image_x_offset);
            mImageYOffset = resources.getDimension(isRound
                    ? R.dimen.image_y_offset_round : R.dimen.image_y_offset);

        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String text = String.format("%d:%02d", mTime.hour, mTime.minute);
            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);

            long currentTime = System.currentTimeMillis();
            SimpleDateFormat shortenedDateFormat = new SimpleDateFormat("EEE, MMM dd yyyy");
            String date = shortenedDateFormat.format(currentTime);
            canvas.drawText(date.toUpperCase(), mDateXOffset, mDateYOffset, mDatePaint);

            if (dataReceived) {

                canvas.drawLine(
                        mDelimiterXOffset,
                        mDelimiterYOffset,
                        mDelimiterXOffset + mDelimiterLength,
                        mDelimiterYOffset,
                        mDelimiterPaint
                );

                if (!isInAmbientMode()) {
                    canvas.drawBitmap(mBitmap, mImageXOffset, mImageYOffset, mImagePaint);
                }

                String maxTemp = String.format(getString(R.string.format_temperature), SunshineWatchFace.this.maxTemp);
                canvas.drawText(maxTemp, mTempMaxXOffset, mTempMaxYOffset, mTempMaxPaint);

                String minTemp = String.format(getString(R.string.format_temperature), SunshineWatchFace.this.minTemp);
                canvas.drawText(minTemp, mTempMinXOffset, mTempMinYOffset, mTempMinPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }

    public Bitmap getResizedBitmap(Bitmap bitmap, int newHeight, int newWidth)
    {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Log.e("WEAR - bitmap w", String.valueOf(width));
        Log.e("WEAR - bitmap h", String.valueOf(height));
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        // create a matrix for the manipulation
        Matrix matrix = new Matrix();
        // resize the bit map
        matrix.postScale(scaleWidth, scaleHeight);
        // recreate the new Bitmap
        Bitmap resizedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false);

        Log.e("WEAR - bitmap w", String.valueOf(resizedBitmap.getWidth()));
        Log.e("WEAR - bitmap h", String.valueOf(resizedBitmap.getHeight()));
        return resizedBitmap;
    }
}
