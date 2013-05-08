/**
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.volley.toolbox;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.widget.ImageView;

import com.android.volley.toolbox.ImageLoader.BitmapContainer;
import com.android.volley.toolbox.ImageLoader.BitmapLoadedHandler;

import java.lang.ref.SoftReference;

/**
 * Handles fetching an image from a URL as well as the life-cycle of the
 * associated request.
 */
public class NetworkImageView extends ImageView {
    /** The URL of the network image to load */
    private String mUrl;

    private int mDefaultImageId;

    private int mErrorImageId;

    /** Track whether the bitmap has been loaded or not. */
    private boolean mLoaded;

    /** Local copy of the ImageLoader. */
    private ImageLoader mImageLoader;

    /** Use a SparseArray to optimize frequently accessed placeholder bitmaps. */
    private static SparseArray<SoftReference<Bitmap>> sDefaultBitmaps =
            new SparseArray<SoftReference<Bitmap>>();

    public NetworkImageView(Context context) {
        this(context, null);
    }

    public NetworkImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NetworkImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setImageUrl(String url, ImageLoader imageLoader) {
        mUrl = url;
        mImageLoader = imageLoader;
        setLoaded(false);
        // The URL has potentially changed. See if we need to load it.
        loadImageIfNecessary();
    }

    public void setDefaultImage(int defaultImage) {
        mDefaultImageId = defaultImage;
    }

    public synchronized boolean isLoaded() {
        return mLoaded;
    }

    /**
     * Updates the loaded state of the image. Note that when the default bitmap is used,
     * the state is "not loaded", but if the error bitmap is used, the state should be "loaded".
     */
    protected synchronized void setLoaded(boolean isLoaded) {
        mLoaded = isLoaded;
    }

    /**
     * Loads the specified resource into the in-memory cache of quick-access default bitmaps.
     */
    protected static Bitmap getDefaultOrErrorBitmap(Resources res, int bitmapToLoad) {
        // if the resource was not specified, return null.
        if (bitmapToLoad == 0) {
            return null;
        }

        // if the specified bitmap isn't present in the cache, load it.
        if (sDefaultBitmaps.get(bitmapToLoad) == null) {
            sDefaultBitmaps.put(bitmapToLoad,
                    new SoftReference<Bitmap>(BitmapFactory.decodeResource(res, bitmapToLoad)));
        }
        // return the cached bitmap.
        return sDefaultBitmaps.get(bitmapToLoad).get();
    }

    /**
     * Loads the image for the view if it isn't already loaded.
     */
    private void loadImageIfNecessary() {
        int width = getWidth();
        int height = getHeight();

        // if the view's bounds aren't known yet, hold off on loading the image.
        if (width == 0 && height == 0) {
            return;
        }

        // if the URL to be loaded in this view is empty, cancel any old requests and clear the
        // currently loaded image.
        if (TextUtils.isEmpty(mUrl)) {
            BitmapContainer oldContainer = (BitmapContainer) getTag();
            if (oldContainer != null) {
                oldContainer.cancelRequest();
                setImageBitmap(null);
            }
            return;
        }

        // get the placeholder image (if it exists).
        final Bitmap defaultImage = getDefaultOrErrorBitmap(getResources(), mDefaultImageId);

        BitmapContainer oldContainer = (BitmapContainer) getTag();
        // if there was an old request in this view, check if it needs to be canceled.
        if (oldContainer != null && oldContainer.getRequestUrl() != null) {
            if (oldContainer.getRequestUrl().equals(mUrl)) {
                // if the request is from the same URL, update the loaded state of this bitmap.
                Bitmap bitmap = oldContainer.getBitmap();
                boolean isLoaded = (bitmap != null && bitmap != defaultImage);
                setLoaded(isLoaded);
                return;
            } else {
                // if there is a pre-existing request, cancel it if it's fetching a different URL.
                oldContainer.cancelRequest();
                setImageBitmap(null);
            }
        }

        // The pre-existing content of this view didn't match the current URL. Load the new image
        // from the network.
        BitmapContainer newContainer =
                mImageLoader.get(mUrl, defaultImage, new BitmapLoadedHandler() {
            @Override
            public void onResponse(BitmapContainer result) {
                Bitmap response = result.getBitmap();
                boolean isLoaded;
                if (response == null) {
                    // if there was no response and there is an error image to show,
                    // update the image.
                    if (mErrorImageId != 0) {
                        setImageResource(mErrorImageId);
                    }
                    // mark the view as loaded in all cases.
                    isLoaded = true;
                } else {
                    setImageBitmap(response);
                    // if the response was non-null and it wasn't the default image, then
                    // it must be loaded!
                    isLoaded = response != defaultImage;
                }
                // update the state to reflect the new bound bitmap.
                setLoaded(isLoaded);
            }
        });

        // update the tag to be the new bitmap container.
        setTag(newContainer);

        // look at the contents of the new container. if there is a bitmap, load it.
        final Bitmap bitmap = newContainer.getBitmap();
        if (bitmap != null) {
            setImageBitmap(bitmap);
            setLoaded(bitmap != defaultImage);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        loadImageIfNecessary();
    }

    @Override
    protected void onDetachedFromWindow() {
        BitmapContainer oldContainer = (BitmapContainer) getTag();
        if (oldContainer != null) {
            // If the view was bound to an image request, cancel it and clear
            // out the image from the view.
            oldContainer.cancelRequest();
            setImageBitmap(null);
            // also clear out the tag so we can reload the image if necessary.
            setTag(null);
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }
}
