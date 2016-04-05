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
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
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
import com.google.android.gms.wearable.Wearable;
import com.squirrel.app.R;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = 500;

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

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener
    {
        private final String LOG_TAG = Engine.class.getSimpleName();
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        private final int DEFAULT_WEATHER_ICON = 800;

        boolean mShouldDrawColon = true;

        //colors of the watchface
        private int mBackgroundInteractive;
        private int mBackgroundAmbient;
        private int mTextInteractive;
        private int mTextAmbient;
        private int mSecondaryTextInteractive;
        private int mLineInteractive;

        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mWeatherIconPaint;
        Paint mDatePaint;
        Paint mLinePaint;
        Paint mHighTempPaint;
        Paint mLowTempPaint;

        //weather icon
        private Bitmap mWeatherIconBitmap;
        private Bitmap mWeatherIconNoColorBitmap;

        //low anf high temperature, shown on the screen
        private String mHighTemp;
        private String mLowTemp;

        Time mTime;
        Date mDate;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        int mTapCount;

        float mXOffset;
        float mXCenter;
        float mYOffset;
        float mYCenter;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        boolean mBurnInProtection;
        boolean mAmbient; //if embient - show the dark color sheme
        boolean mRound;

        private GoogleApiClient mGoogleApiClient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            Log.d(LOG_TAG, "OnCreate");

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            //set the colors
            mBackgroundInteractive = resources.getColor(R.color.background);
            mBackgroundAmbient = resources.getColor(R.color.background_ambient);
            mTextInteractive = resources.getColor(R.color.primary_text);
            mTextAmbient = resources.getColor(R.color.text_ambient);

            mSecondaryTextInteractive = resources.getColor(R.color.secondary_text);
            mLineInteractive = resources.getColor(R.color.line_color);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(mBackgroundInteractive);

            mTimePaint = new Paint();
            mTimePaint = createTextPaint(mTextInteractive);

            mWeatherIconPaint = new Paint();

            mDatePaint = new Paint();
            mDatePaint.setColor(mSecondaryTextInteractive);

            mLinePaint = new Paint();
            mLinePaint.setColor(mLineInteractive);

            mHighTempPaint = new Paint();
            mHighTempPaint.setColor(mTextInteractive);
            mHighTempPaint.setTypeface(BOLD_TYPEFACE);

            mLowTempPaint = new Paint();
            mLowTempPaint.setColor(mSecondaryTextInteractive);

            mTime = new Time();
            mDate = new Date();

            //set the initial state to 0 temperature and clean weather
            setWeatherInfo(0,0,DEFAULT_WEATHER_ICON);

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

        }

        private void setWeatherInfo(int highTemp, int lowTemp, int weatherId){
            mHighTemp = String.format("%3s",String.valueOf(highTemp)) + "°";
            mLowTemp = String.format("%3s",String.valueOf(lowTemp)) + "°";

            //set the icon
            int iconId = Utilities.getIconResourceForWeatherCondition(weatherId);
            mWeatherIconBitmap = BitmapFactory.decodeResource(getResources(), iconId);
            makeIconGray();
        }


        //onDataChangedListener will get notified every time there is a change in the data layer
        private final DataApi.DataListener onDataChangedListener = new DataApi.DataListener() {
            @Override
            public void onDataChanged(DataEventBuffer dataEvents) {
                Log.d(LOG_TAG, "Callback onDataChangedListener pulled");
                for (DataEvent event : dataEvents) {
                    if (event.getType() == DataEvent.TYPE_CHANGED) {
                        DataItem item = event.getDataItem();
                        getWeatherData(item);
                    }
                }

                dataEvents.release();
            }
        };

        //onConnectedResultCallback is only notified when the service is firstly connected
        private final ResultCallback<DataItemBuffer> onConnectedResultCallback = new ResultCallback<DataItemBuffer>() {
            @Override
            public void onResult(DataItemBuffer dataItems) {
                Log.d(LOG_TAG, "Callback onConnectedResultCallback pulled" + dataItems.toString());
                for (DataItem item : dataItems) {
                    getWeatherData(item);
                }
                dataItems.release();
            }
        };

        private void getWeatherData(DataItem item){
            if ("/sunshine-weather".equals(item.getUri().getPath())){
                DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                int low = 0;
                int high = 0;
                int weatherId = DEFAULT_WEATHER_ICON;
                if (dataMap.containsKey("weather_id")) {
                    weatherId = dataMap.getInt("weather_id");
                    Log.d(LOG_TAG,"Received weather id");
                }
                if (dataMap.containsKey("low")) {
                    low = (int) Math.round(dataMap.getDouble("low"));
                    Log.d(LOG_TAG,"Received low");

                }
                if (dataMap.containsKey("high")) {
                    high = (int) Math.round(dataMap.getDouble("high"));
                    Log.d(LOG_TAG,"Received high");
                }
                setWeatherInfo(high, low, weatherId);
            }
        }


        @Override
        public void onConnected(Bundle bundle) {
            Log.d(LOG_TAG, "connected GoogleAPI");
            Wearable.DataApi.addListener(mGoogleApiClient, onDataChangedListener);
            Wearable.DataApi.getDataItems(mGoogleApiClient).setResultCallback(onConnectedResultCallback);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "GoogleAPI connection suspended");

        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.d(LOG_TAG, "GoogleAPI connection failed");

        }

        private void releaseGoogleApiClient() {
            if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                mGoogleApiClient.disconnect();
            }
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            releaseGoogleApiClient();
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

                Calendar calendar = Calendar.getInstance();
                calendar.setTimeZone(TimeZone.getDefault());
                mDate = calendar.getTime();

                mGoogleApiClient.connect();
            } else {
                unregisterReceiver();
                releaseGoogleApiClient();
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
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);

            mXCenter = width / 2f;
            mYCenter = height / 2f;

        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);

            float timeSize = resources.getDimension(isRound
                    ? R.dimen.digital_time_size_round : R.dimen.digital_time_size);
            float dateSize = resources.getDimension(mRound
                    ? R.dimen.digital_date_size_round : R.dimen.digital_date_size);
            float tempSize = resources.getDimension(mRound
                    ? R.dimen.digital_temp_size_round : R.dimen.digital_temp_size);

            mTimePaint.setTextSize(timeSize);
            mHighTempPaint.setTextSize(tempSize);
            mLowTempPaint.setTextSize(tempSize);
            mDatePaint.setTextSize(dateSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);

            //if BurnInProtection - should remove the bold text
            mHighTempPaint.setTypeface(NORMAL_TYPEFACE);
            mTimePaint.setTypeface(NORMAL_TYPEFACE);

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
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mHighTempPaint.setAntiAlias(!inAmbientMode);
                    mLowTempPaint.setAntiAlias(!inAmbientMode);
                    mTimePaint.setAntiAlias(!inAmbientMode);
                }
                if(mAmbient){
                    mBackgroundPaint.setColor(mBackgroundAmbient);
                    mTimePaint.setColor(mTextAmbient);
                    mHighTempPaint.setColor(mTextAmbient);
                    mLowTempPaint.setColor(mTextAmbient);
                    mDatePaint.setColor(mTextAmbient);
                    mLinePaint.setColor(Color.TRANSPARENT);
                    mTimePaint.setTypeface(NORMAL_TYPEFACE);
                    mHighTempPaint.setTypeface(NORMAL_TYPEFACE);
                    mHighTempPaint.setTypeface(NORMAL_TYPEFACE);
                    mDatePaint.setTypeface(NORMAL_TYPEFACE);
                }else{
                    mBackgroundPaint.setColor(mBackgroundInteractive);
                    mTimePaint.setColor(mTextInteractive);
                    mHighTempPaint.setColor(mTextInteractive);
                    mLowTempPaint.setColor(mSecondaryTextInteractive);
                    mLinePaint.setColor(mSecondaryTextInteractive);
                    mDatePaint.setColor(mSecondaryTextInteractive);
//                    mTimePaint.setTypeface(BOLD_TYPEFACE);
                    mHighTempPaint.setTypeface(BOLD_TYPEFACE);

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
//                    mTapCount++;
//                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
//                            R.color.background : R.color.background2));
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

            // Show colons for the first half of each second so the colons blink on when the time
            // updates.
            mShouldDrawColon = (System.currentTimeMillis() % 1000) < 500;

            // Draw HH:MMtim in ambient and interactive mode
            mTime.setToNow();
            long now = System.currentTimeMillis();
            mDate.setTime(now);

//            String time = String.format("%d:%02d", mTime.hour, mTime.minute);
            String dots = ":";
            String hours = String.format("%d", mTime.hour);
            String min = String.format("%02d", mTime.minute);

            canvas.drawText(hours, mXOffset, mYOffset, mTimePaint);
            float dotsOffset = gettextWidth(dots, mTimePaint);
            float x = mXOffset + gettextWidth(hours, mTimePaint) + dotsOffset * 2;
            //always show dots if in ambient mode
            if (mAmbient || mShouldDrawColon) {
                canvas.drawText(dots, x, mYOffset, mTimePaint);
            }
            x += dotsOffset * 2;
            canvas.drawText(min, x, mYOffset, mTimePaint);


            float y = mYOffset + getTextHeight(hours, mTimePaint);

            String date = Utilities.getFormattedDateString(mDate);
            canvas.drawText(date, mXOffset, y, mDatePaint);

            float dateTextHeight = getTextHeight(date, mDatePaint);
            y+= dateTextHeight;

            canvas.drawLine(mXCenter - 20, y, mXCenter + 20, y, mLinePaint);

            y+= dateTextHeight;

            if(!mAmbient){
                canvas.drawBitmap(mWeatherIconBitmap, mXOffset, y, mWeatherIconPaint);
            } else {
                canvas.drawBitmap(mWeatherIconNoColorBitmap, mXOffset, y, mWeatherIconPaint);
            }
            y+= dateTextHeight * 2;
            canvas.drawText(mHighTemp,
                    mXOffset + mWeatherIconBitmap.getWidth(),
                    y,
                    mHighTempPaint);
            canvas.drawText(mLowTemp,
                    mXOffset + mWeatherIconBitmap.getWidth() + gettextWidth(mHighTemp, mHighTempPaint),
                    y,
                    mLowTempPaint);

        }

        /**
         * solution found http://stackoverflow.com/questions/14277058/get-the-text-height-including-the-font-size-and-set-that-height
         * @param text
         * @param paint
         * @return
         */
        private float getTextHeight(String text, Paint paint) {

            Rect rect = new Rect();
            paint.getTextBounds(text, 0, text.length(), rect);
            return rect.height();
        }

        private float gettextWidth(String text, Paint paint){
            Rect rect = new Rect();
            paint.getTextBounds(text, 0, text.length(), rect);
            return rect.width();
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

        private void makeIconGray(){
            mWeatherIconNoColorBitmap = Bitmap.createBitmap(
                    mWeatherIconBitmap.getWidth(),
                    mWeatherIconBitmap.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas( mWeatherIconNoColorBitmap);
            Paint grayPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            grayPaint.setColorFilter(filter);
            canvas.drawBitmap(mWeatherIconBitmap, 0, 0, grayPaint);
        }
    }
}
