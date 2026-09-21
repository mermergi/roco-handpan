package com.handpan.autoplay;

import android.content.Context;
import android.util.TypedValue;

/** Small shared UI helpers, so the three screens do not each grow their own copy. */
public final class Ui {

    private Ui() {}

    public static int dp(Context c, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                c.getResources().getDisplayMetrics());
    }
}
