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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WeatherFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final String TAG = "WeatherFace";
    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WeatherFace.Engine> mWeakReference;

        public EngineHandler(WeatherFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WeatherFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine
            implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {


        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimeTextPaint;
        Paint mTempsTextPaint;
        Paint mDateTextPaint;
        Bitmap mConditionArt;

        Calendar mCalendar;
        boolean mAmbient;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        int mTapCount;

        float mXOffsetTime;
        float mXOffsetDate;
        float mXOffsetTemp;
        float mXOffsetArt;

        float mYOffsetTime;
        float mYOffsetDate;
        float mLineHeight;
        float mYOffsetWeather;
        float mYOffsetArt;

        float timeWidth;
        float dateWidth;
        float tempsWidth;

        int mWeatherId;
        String mTempHi;
        String mTempLow;
        String mShortDesc;
        String mUnitFormat;
        String localNodeId;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(WeatherFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API).build();

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());


            Resources resources = WeatherFace.this.getResources();


            mYOffsetTime = resources.getDimension(R.dimen.digital_y_offset);
            mYOffsetDate = resources.getDimension(R.dimen.digital_y_offset_date);
            mYOffsetWeather = resources.getDimension(R.dimen.digital_y_offset_weather);
            mYOffsetArt = resources.getDimension(R.dimen.digital_y_offset_art);

            mBackgroundPaint = new Paint();

            //Set Background color to Sunshine Blue
            mBackgroundPaint.setColor(resources.getColor(R.color.sunshine_blue));

            mTimeTextPaint = new Paint();
            mTimeTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTempsTextPaint = new Paint();
            mTempsTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDateTextPaint = new Paint();
            mDateTextPaint = createTextPaint(resources.getColor(R.color.digital_text));


            mTimeTextPaint.setTextSize(resources.getDimension(R.dimen.digital_time_size));
            mTempsTextPaint.setTextSize(resources.getDimension(R.dimen.digital_temp_size));
            mDateTextPaint.setTextSize(resources.getDimension(R.dimen.digital_date_size));

            mCalendar = Calendar.getInstance();

            timeWidth = mTimeTextPaint.measureText("11:56");
            dateWidth = mDateTextPaint.measureText("MON, FEB 13, 2017");
            tempsWidth = mTempsTextPaint.measureText("50 | 50");
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
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
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
            WeatherFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WeatherFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
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
                    mTimeTextPaint.setAntiAlias(!inAmbientMode);

                    mTempsTextPaint.setAntiAlias(!inAmbientMode);
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
            Resources resources = WeatherFace.this.getResources();
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
                            R.color.sunshine_blue : R.color.sunshine_blue));
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

            float centerX = bounds.width() / 2f;

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String text = String.format("%d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY), mCalendar.get(Calendar.MINUTE));

            mXOffsetTime = centerX - (timeWidth / 2f);
            canvas.drawText(text, mXOffsetTime, mYOffsetTime, mTimeTextPaint);

            // Date
            String dayName = mCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault());
            String monthName = mCalendar.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault());
            int dayOfMonth = mCalendar.get(Calendar.DAY_OF_MONTH);
            int year = mCalendar.get(Calendar.YEAR);
            String dateText = String.format("%s, %s %d, %d", dayName.toUpperCase(), monthName.toUpperCase(), dayOfMonth, year);

            mXOffsetDate = centerX - (dateWidth / 2f);
            canvas.drawText(dateText, mXOffsetDate, mYOffsetDate, mDateTextPaint);


            if (!mAmbient) {
                if (mTempHi != null && mTempLow != null) {
                    String temps = mTempHi + " | " + mTempLow;
                    mXOffsetTemp = centerX - (mTempsTextPaint.measureText(temps) / 2f);
                    canvas.drawText(temps, mXOffsetTemp, mYOffsetWeather, mTempsTextPaint);
                }
                if (mConditionArt != null && !mAmbient) {
                    String temps = mTempHi + " | " + mTempLow;
                    mXOffsetArt = centerX - (getResources().getDimension(R.dimen.weather_icon_size) / 2);
                    canvas.drawBitmap(mConditionArt, mXOffsetArt, mYOffsetArt, null);
                }

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

        private void extractWeatherData(DataMap data) {
            Context context = getBaseContext();
            if (data != null) {
                mWeatherId = data.getInt(getString(R.string.wear_cond_key));
                mTempHi = data.getString(getString(R.string.wear_hi_key));
                mTempLow = data.getString(getString(R.string.wear_low_key));
                mShortDesc = data.getString(getString(R.string.wear_short_desc_key));
                mUnitFormat = data.getString(getString(R.string.wear_units_key));

                //Set the Bitmap for Weather Condition
                BitmapDrawable weatherDrawable =
                        (BitmapDrawable) getResources().getDrawable(WeatherFaceUtility.getArtResourceForWeatherCondition(mWeatherId), null);

                Bitmap origBitmap = null;
                if (weatherDrawable != null) {
                    origBitmap = weatherDrawable.getBitmap();
                    Log.d(TAG, "extractWeatherData: decoded a bitmap for the weather art");
                }


                float scaledSize = getResources().getDimension(R.dimen.weather_icon_size);

                mConditionArt = Bitmap.createScaledBitmap(origBitmap,
                        WeatherFaceUtility.dipToPixels(context, scaledSize),
                        WeatherFaceUtility.dipToPixels(context, scaledSize),
                        true);
                invalidate();
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "onConnected: attaching Listener");

            //Attach Listener
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);

            //Get Local Node info
            Wearable.NodeApi.getLocalNode(mGoogleApiClient).setResultCallback(
                    new ResultCallback<NodeApi.GetLocalNodeResult>() {
                        @Override
                        public void onResult(@NonNull NodeApi.GetLocalNodeResult getLocalNodeResult) {
                            localNodeId = getLocalNodeResult.getNode().getId();
                            Uri uri = new Uri.Builder()
                                    .scheme("wear")
                                    .path(getString(R.string.wear_weather_path))
                                    .build();

                            Wearable.DataApi.getDataItems(mGoogleApiClient, uri).setResultCallback(
                                    new ResultCallback<DataItemBuffer>() {
                                        @Override
                                        public void onResult(@NonNull DataItemBuffer dataItemBuffer) {
                                            if (dataItemBuffer.getStatus().isSuccess() &&
                                                    dataItemBuffer.getCount() != 0) {

                                                DataItem item = dataItemBuffer.get(0);

                                                if (item != null) {
                                                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                                                    extractWeatherData(dataMap);
                                                }

                                            }

                                            dataItemBuffer.release();
                                        }
                                    }

                            );
                        }
                    });

        }

        @Override
        public void onConnectionSuspended(int cause) {
            Log.d(TAG, "onConnectionSuspended: " + cause);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d(TAG, "onDataChanged: ");

            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().equals(getString(R.string.wear_weather_path))) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        extractWeatherData(dataMap);
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                }
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(TAG, "onConnectionFailed: " + connectionResult);
        }
    }
}
