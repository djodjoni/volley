/*
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

import android.graphics.Bitmap;
import android.widget.ImageView;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.Response.ErrorListener;
import com.android.volley.Response.Listener;

/**
 * Helper class for loading images from the network and placing them in ImageViews.
 */
public class ImageLoader {
    private final RequestQueue mQueue;
    private final ImageCache mCache;

    /**
     * Simple cache adapter interface. If provided to the ImageLoader, it
     * will be used as an L1 cache before dispatch to Volley. Implementations
     * must not block. Implementation with an LruCache is recommended.
     */
    public interface ImageCache {
        public Bitmap getBitmap(String url);
        public void putBitmap(String url, Bitmap bitmap);
    }

    /**
     * Create a new ImageLoader.
     * @param queue The RequestQueue to use for request dispatch
     * @param cache An {@link ImageCache} to use for L1 cache, or null
     */
    public ImageLoader(RequestQueue queue, ImageCache cache) {
        mQueue = queue;
        mCache = cache;
    }

    /**
     * Populate the provided ImageView with an image from the network. The image
     * will automatically be downsized to the measured width of the provided
     * ImageView if necessary, in order to conserve heap space.
     *
     * @param url URL of the image
     * @param imageView ImageView to populate
     * @param defaultResId Resource ID of a drawable to populate during loading
     *            if the image is not already in cache, or -1
     * @param errorResId Resource ID of an image to populate if the image cannot
     *            be loaded, or -1
     * @return A {@link Request} that can be cancelled, or null if the request
     *         was fulfilled from cache
     */
    public Request<?> get(String url, ImageView imageView, int defaultResId, int errorResId) {
        Bitmap bitmap = mCache != null ? mCache.getBitmap(url) : null;
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return null;
        }
        if (defaultResId != -1) {
            imageView.setImageResource(defaultResId);
        }
        LoaderListener listener = new LoaderListener(url, imageView, errorResId);
        ImageRequest request = new ImageRequest(
                url, listener, imageView.getWidth(), 0, Bitmap.Config.RGB_565, listener);
        return mQueue.add(request);
    }

    /**
     * Simple Listener that populates a provided ImageView and adds the
     * returned Bitmap into L1 cache.
     */
    private class LoaderListener implements Listener<Bitmap>, ErrorListener {
        private final String mUrl;
        private final ImageView mImageView;
        private final int mErrorResId;

        public LoaderListener(String url, ImageView imageView, int errorResId) {
            mUrl = url;
            mImageView = imageView;
            mErrorResId = errorResId;
        }

        @Override
        public void onResponse(Bitmap response) {
            if (mCache != null) {
                mCache.putBitmap(mUrl, response);
            }
            mImageView.setImageBitmap(response);
        }

        @Override
        public void onErrorResponse(VolleyError error) {
            if (mErrorResId != -1) {
                mImageView.setImageResource(mErrorResId);
            }
        }
    }
}
