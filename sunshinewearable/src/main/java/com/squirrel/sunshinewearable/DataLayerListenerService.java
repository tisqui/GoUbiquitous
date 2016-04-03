package com.squirrel.sunshinewearable;

import android.net.Uri;
import android.util.Log;

import com.google.android.gms.common.data.FreezableUtils;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

import java.util.List;

/**
 * Created by squirrel on 4/3/16.
 */
public class DataLayerListenerService extends WearableListenerService {
    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        super.onDataChanged(dataEvents);
        Log.d("DATAAAAAAA!!!", "IS COOOOMINGGGGG");
        final List<DataEvent> events = FreezableUtils.freezeIterable(dataEvents);
        for(DataEvent event : events) {
            final Uri uri = event.getDataItem().getUri();
            final String path = uri!=null ? uri.getPath() : null;
            if("/sunshine-weather".equals(path)) {
                final DataMap map = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                // read your values from map:
                int weatherId = map.getInt("weather_id");
                double low = map.getDouble("low");
                double high = map.getInt("high");
            }
        }
    }
}
