/*
 * Copyright (C) 2026 The XPerience Project
 * SPDX-License-Identifier: Apache-2.0
 */
package mx.xperience.updater.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import mx.xperience.updater.R;

/**
 * NestedScrollView that behaves like wrap_content until a configurable maximum height.
 * Once the content exceeds that height, scrolling is kept inside this view instead of
 * making the parent updater screen grow indefinitely.
 */
public class MaxHeightNestedScrollView extends NestedScrollView {

    private int mMaxHeight = Integer.MAX_VALUE;

    public MaxHeightNestedScrollView(@NonNull Context context) {
        this(context, null);
    }

    public MaxHeightNestedScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MaxHeightNestedScrollView(
            @NonNull Context context,
            @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(
                    attrs, R.styleable.MaxHeightNestedScrollView, defStyleAttr, 0);
            mMaxHeight = a.getDimensionPixelSize(
                    R.styleable.MaxHeightNestedScrollView_maxHeight, Integer.MAX_VALUE);
            a.recycle();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mMaxHeight != Integer.MAX_VALUE) {
            heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(
                    mMaxHeight, View.MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
