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
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    // For logging messages
    public static final String LOG_TAG = SunshineWatchFace.class.getSimpleName();

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

    @Override
    public Engine onCreateEngine() {
        return new Engine();
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

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {
        // Constants for Data Layer Api
        public static final String WEAR_DATA_PATH = "/wear_weather";
        public static final String WEAR_MAX_TEMP_KEY = "high_temp";
        public static final String WEAR_MIN_TEMP_KEY = "low_temp";
        public static final String WEAR_WEATHER_CONDITION_KEY = "weather_id";

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mLinePaint;
        Paint mMaxTempPaint;
        Paint mMinTempPaint;

        boolean mAmbient;

        Calendar mCalendar;

        private String mMaxTemp;
        private String mMinTemp;
        private int mWeatherID;

        private GoogleApiClient mClient;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        float mTimeHalfXLength;
        float mTimeYOffset;
        float mDateHalfXLength;
        float mDateYOffset;
        float mDividerHalfXLength;
        float mDividerYOffset;
        float mTempXLength;
        float mWeatherYOffset;

        Bitmap mWeatherIcon;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = SunshineWatchFace.this.getResources();

            // Setup paint styles
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mLinePaint = new Paint();
            mLinePaint.setColor(resources.getColor(R.color.line));

            mTimePaint = createTextPaint(resources.getColor(R.color.time_text), resources.getDimension(R.dimen.time_text_size));
            mDatePaint = createTextPaint(resources.getColor(R.color.date_text), resources.getDimension(R.dimen.date_text_size));

            mMaxTempPaint = createTextPaint(resources.getColor(R.color.max_temp), resources.getDimension(R.dimen.weather_text_size));
            mMinTempPaint = createTextPaint(resources.getColor(R.color.min_temp), resources.getDimension(R.dimen.weather_text_size));

            // Set up offsets
            mTimeHalfXLength = mTimePaint.measureText("22:00") / 2;
            mTimeYOffset = resources.getDimension(R.dimen.time_y_offset);

            mDateHalfXLength = mDatePaint.measureText("FRI, JUL 14 2015") / 2;
            mDateYOffset = resources.getDimension(R.dimen.date_y_offset);

            mDividerHalfXLength = resources.getDimension(R.dimen.divider_half_length);
            mDividerYOffset = resources.getDimension(R.dimen.divider_y_offset);

            mTempXLength = mMaxTempPaint.measureText("37°");
            mWeatherYOffset = resources.getDimension(R.dimen.weather_y_offset);

            mCalendar = Calendar.getInstance();

            mClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, float textSize) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setTextSize(textSize);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());

            } else {
                if (mClient != null && mClient.isConnected()) {
                    Wearable.DataApi.removeListener(mClient, this);
                    mClient.disconnect();
                }

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

                Resources resources = getResources();

                mTimePaint.setAntiAlias(!mLowBitAmbient);
                mDatePaint.setAntiAlias(!mLowBitAmbient);
                mMaxTempPaint.setAntiAlias(!mLowBitAmbient);
                mMinTempPaint.setAntiAlias(!mLowBitAmbient);

                if (inAmbientMode) {
                    mDatePaint.setColor(resources.getColor(R.color.time_text));
                    mLinePaint.setColor(resources.getColor(R.color.time_text));
                    mMinTempPaint.setColor(resources.getColor(R.color.time_text));
                } else {
                    mDatePaint.setColor(resources.getColor(R.color.date_text));
                    mLinePaint.setColor(resources.getColor(R.color.line));
                    mMinTempPaint.setColor(resources.getColor(R.color.min_temp));
                }

                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            mCalendar.setTimeInMillis(System.currentTimeMillis());

            // Draws time
            String time = String.format("%02d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY), mCalendar.get(Calendar.MINUTE));
            canvas.drawText(time, bounds.centerX() - mTimeHalfXLength, mTimeYOffset, mTimePaint);

            // Draws date
            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM dd yyyy");
            String date = dateFormat.format(new Date(mCalendar.getTimeInMillis()));
            canvas.drawText(date, bounds.centerX() - mDateHalfXLength, mDateYOffset, mDatePaint);

            if (mMinTemp != null && mMaxTemp != null) {
                // Draws divider
                canvas.drawLine(bounds.centerX() - mDividerHalfXLength, mDividerYOffset,
                        bounds.centerX() + mDividerHalfXLength, mDividerYOffset, mLinePaint);

                float tempMaxXOffset, tempMinXOffset;

                if (mAmbient) {
                    tempMaxXOffset = bounds.centerX() - mTempXLength - 10;
                    tempMinXOffset = bounds.centerX() + 20;
                } else {
                    tempMaxXOffset = bounds.centerX() - (mTempXLength / 2);
                    tempMinXOffset = bounds.centerX() + (mTempXLength / 2) + 20;

                    // DrawBitmap only in interactive mode
                    float weatherXOffset = bounds.centerX() - (mTempXLength / 2 + mWeatherIcon.getWidth() + 30);
                    canvas.drawBitmap(mWeatherIcon, weatherXOffset, mWeatherYOffset - mWeatherIcon.getHeight() + 10, null);
                }
                canvas.drawText(mMaxTemp, tempMaxXOffset, mWeatherYOffset, mMaxTempPaint);
                canvas.drawText(mMinTemp, tempMinXOffset, mWeatherYOffset, mMinTempPaint);
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

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "Connection Suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(LOG_TAG, "Connection Failded: " + connectionResult.getErrorMessage());
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();

                    if (dataItem.getUri().getPath().equals(WEAR_DATA_PATH)) {
                        DataMap map = DataMapItem.fromDataItem(dataItem).getDataMap();
                        mMaxTemp = map.getString(WEAR_MAX_TEMP_KEY);
                        mMinTemp = map.getString(WEAR_MIN_TEMP_KEY);
                        mWeatherID = map.getInt(WEAR_WEATHER_CONDITION_KEY);

                        // Scale and store the bitmap right away to avoid expensive work in OnDraw
                        Bitmap bitmap = ((BitmapDrawable) getResources().getDrawable(WeatherUtility.getIconResourceForWeatherCondition(mWeatherID))).getBitmap();
                        float scaledWidth = (mMaxTempPaint.getTextSize() / bitmap.getHeight()) * bitmap.getWidth();
                        mWeatherIcon = Bitmap.createScaledBitmap(bitmap, (int) scaledWidth, (int) mMaxTempPaint.getTextSize(), true);

                        // Display new data by redrawing
                        invalidate();
                    }
                }
            }
        }
    }
}
