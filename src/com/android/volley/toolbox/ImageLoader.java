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

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.os.Handler;
import android.os.Looper;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.Response.ErrorListener;
import com.android.volley.Response.Listener;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Helper that handles loading and caching images from remote URLs.
 */
public class ImageLoader {
    /** RequestQueue for dispatching ImageRequests onto. */
    private final RequestQueue mRequestQueue;

    /** Amount of time to wait after first response arrives before delivering all responses. */
    private static final int BATCH_RESPONSE_DELAY_MS = 100;

    /** The cache implementation to be used as an L1 cache before calling into volley. */
    private final ImageCache mCache;

    /** HashMap of Cache keys -> RequestListenerWrappers used to track in-flight requests.*/
    private final HashMap<String, RequestListenerWrapper> mInFlightRequests =
            new HashMap<String, RequestListenerWrapper>();

    /** HashMap of the currently pending responses (waiting to be delivered). */
    private final HashMap<String, RequestListenerWrapper> mBatchedResponses =
            new HashMap<String, RequestListenerWrapper>();

    /** Handler to the main thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** Runnable for in-flight response delivery. */
    private Runnable mRunnable;

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
     * Constructs a new ImageLoader.
     * @param queue The RequestQueue to use for making image requests.
     * @param imageCache The cache to use as an L1 cache.
     */
    public ImageLoader(RequestQueue queue, ImageCache imageCache) {
        mRequestQueue = queue;
        mCache = imageCache;
    }

    /**
     * Interface for the response handlers on Bitmap requests.
     */
    public interface BitmapLoadedHandler extends Response.Listener<BitmapContainer> {
        /**
         * Handles the load completion for an image request.
         * A null response indicates a failure occurred, so implementors should be robust to such
         * situations.
         */
        @Override
        void onResponse(BitmapContainer result);
    }

    /**
     * Returns a BitmapContainer for the requested URL.
     *
     * The BitmapContainer will contain either the specified default bitmap or the loaded bitmap.
     * If the default was returned, the {@link BitmapLoadedHandler} will be invoked when the
     * request is fulfilled.
     *
     * @param requestUrl The URL of the image to be loaded.
     * @param defaultImage Optional default image to return until the actual image is loaded.
     * @param bitmapLoadedHandler The handler to call when the state of the image changes. The
     * BitmapContainer will be updated with the latest bitmap (either the default or the
     * loaded image).
     */
    public BitmapContainer get(String requestUrl, Bitmap defaultImage,
            final BitmapLoadedHandler bitmapLoadedHandler) {
        return get(requestUrl, defaultImage, bitmapLoadedHandler, 0, 0);
    }

    /**
     * Issues a bitmap request with the given URL if that image is not available
     * in the cache, and returns a bitmap container that contains all of the data
     * relating to the request (as well as the default image if the requested
     * image is not available).
     * @param requestUrl The url of the remote image
     * @param defaultImage The default image to associate with this request
     * @param bitmapLoadedHandler The callback handler to call when the remote image is loaded
     * @param maxWidth The maximum width of the returned image.
     * @param maxHeight The maximum height of the returned image.
     * @return A container object that contains all of the properties of the request, as well as
     *     the currently available image (default if remote is not loaded).
     */
    public BitmapContainer get(final String requestUrl, Bitmap defaultImage,
            final BitmapLoadedHandler bitmapLoadedHandler, int maxWidth, int maxHeight) {
        final String cacheKey = getCacheKey(requestUrl, maxWidth, maxHeight);

        // Try to look up the request in the cache of remote images.
        Bitmap cachedBitmap = mCache.getBitmap(cacheKey);
        if (cachedBitmap != null) {
            // Return the cached bitmap.
            return new BitmapContainer(cachedBitmap, requestUrl, null, null);
        }

        // The bitmap did not exist in the cache, fetch it!
        final BitmapContainer bitmapContainer =
                new BitmapContainer(defaultImage, requestUrl, cacheKey, bitmapLoadedHandler);

        // Check to see if a request is already in-flight.
        RequestListenerWrapper wrapper = mInFlightRequests.get(cacheKey);
        if (wrapper != null) {
            // If it is, add this request to the list of listeners.
            wrapper.addHandler(bitmapContainer);
            return bitmapContainer;
        }

        // The request is not already in flight, make a new request.
        Request<?> newRequest =
            new ImageRequest(requestUrl, new Listener<Bitmap>() {
                @Override
                public void onResponse(Bitmap response) {
                    onGetImageSuccess(cacheKey, response);
                }
            }, maxWidth, maxHeight,
            Config.RGB_565, new ErrorListener() {
                @Override
                public void onErrorResponse(VolleyError error) {
                    onGetImageError(cacheKey);
                }
            });

        // Send the request off to the network.
        mRequestQueue.add(newRequest);

        // Track the in-flight request.
        mInFlightRequests.put(cacheKey,
                new RequestListenerWrapper(newRequest, bitmapContainer));

        // return the container.
        return bitmapContainer;
    }

    /**
     * Handler for when an image was successfully loaded.
     * @param cacheKey The cache key that is associated with the image request.
     * @param response The bitmap that was returned from the network.
     */
    private void onGetImageSuccess(final String cacheKey, Bitmap response) {
        // cache the image that was fetched.
        mCache.putBitmap(cacheKey, response);

        // remove the wrapper from the list of in-flight requests.
        RequestListenerWrapper wrapper = mInFlightRequests.remove(cacheKey);

        if (wrapper != null) {
            // Update the response bitmap.
            wrapper.responseBitmap = response;

            // Send the batched response
            batchResponse(cacheKey, wrapper);
        }
    }

    /**
     * Handler for when an image failed to load.
     * @param cacheKey The cache key that is associated with the image request.
     */
    private void onGetImageError(final String cacheKey) {
        // Notify the requesters that something failed via a null result.
        // Remove this request from the list of in-flight requests.
        RequestListenerWrapper wrapper = mInFlightRequests.remove(cacheKey);

        if (wrapper != null) {
            // Send the batched response
            batchResponse(cacheKey, wrapper);
        }
    }

    /**
     * Container object for all of the data surrounding a bitmap request.
     */
    public class BitmapContainer {
        /**
         * The most relevant bitmap for the container. If the image was in cache, the
         * container will be initialized with the real image loaded, and not the default.
         */
        private Bitmap mBitmap;

        /** Handler to use for delivering future updates (if necessary). */
        private BitmapLoadedHandler mBitmapLoaded;

        /** The cache key that was associated with the request */
        private String mCacheKey;

        /** The request URL that was specified */
        private final String mRequestUrl;

        /**
         * Constructs a BitmapContainer object.
         * @param bitmap The bitmap to use for the initial state of the container.
         * @param requestUrl The requested URL for this container.
         * @param cacheKey The cache key that identifies the requested URL for this container.
         * @param handler the callback handler that was passed in for the request.
         */
        public BitmapContainer(Bitmap bitmap, String requestUrl,
                String cacheKey, BitmapLoadedHandler handler) {
            mBitmap = bitmap;
            mRequestUrl = requestUrl;
            mCacheKey = cacheKey;
            mBitmapLoaded = handler;
        }

        /**
         * Releases interest in the in-flight request (and cancels it if no one else is listening).
         */
        public void cancelRequest() {
            if (mBitmapLoaded == null) {
                return;
            }

            RequestListenerWrapper wrapper = mInFlightRequests.get(mCacheKey);
            if (wrapper != null) {
                boolean canceled = wrapper.removeHandlerAndCancelIfNecessary(this);
                if (canceled) {
                    mInFlightRequests.remove(mCacheKey);
                }
            } else {
                // check to see if it is already batched for delivery.
                wrapper = mBatchedResponses.get(mCacheKey);
                if (wrapper != null) {
                    wrapper.removeHandlerAndCancelIfNecessary(this);
                    if (wrapper.handlers.size() == 0) {
                        mBatchedResponses.remove(mCacheKey);
                    }
                }
            }
        }

        /**
         * Returns the currently available bitmap (default will be returned if
         * the requested bitmap is not yet available).
         */
        public Bitmap getBitmap() {
            return mBitmap;
        }

        /**
         * Returns the requested URL for this container.
         */
        public String getRequestUrl() {
            return mRequestUrl;
        }
    }

    /**
     * Wrapper class used to map a Request to the set of active BitmapContainer objects that are
     * interested in its results.
     */
    private class RequestListenerWrapper {
        /** The request being tracked */
        private Request<?> request;

        /** The result of the request being tracked by this item */
        private Bitmap responseBitmap;

        /** List of all of the active BitmapContainers that are interested in the request */
        private List<BitmapContainer> handlers = new ArrayList<BitmapContainer>();

        /**
         * Constructs a new RequestListenerWrapper object
         * @param request The request being tracked
         * @param container The BitmapContainer of the person who initiated the request.
         */
        public RequestListenerWrapper(Request<?> request, BitmapContainer container) {
            this.request = request;
            this.handlers.add(container);
        }

        /**
         * Adds another BitmapContainer to the list of those interested in the results of
         * the request.
         */
        public void addHandler(BitmapContainer container) {
            handlers.add(container);
        }

        /**
         * Detatches the bitmap container from the request and cancels the request if no one is
         * left listening.
         * @param container The container to remove from the list
         * @return True if the request was canceled, false otherwise.
         */
        public boolean removeHandlerAndCancelIfNecessary(BitmapContainer container) {
            handlers.remove(container);
            if (handlers.size() == 0) {
                request.cancel();
                return true;
            }
            return false;
        }
    }

    /**
     * Starts the runnable for batched delivery of responses if it is not already started.
     * @param cacheKey The cacheKey of the response being delivered.
     * @param wrapper The wrapper for the request and response objects.
     */
    private void batchResponse(String cacheKey, RequestListenerWrapper wrapper) {
        mBatchedResponses.put(cacheKey, wrapper);
        if (mRunnable == null) {
            mRunnable = new Runnable() {
                @Override
                public void run() {
                    for (RequestListenerWrapper wrapper : mBatchedResponses.values()) {
                        for (BitmapContainer container : wrapper.handlers) {
                            container.mBitmap = wrapper.responseBitmap;
                            container.mBitmapLoaded.onResponse(container);
                        }
                    }
                    mBatchedResponses.clear();
                    mRunnable = null;
                }

            };
            // Post the runnable.
            mHandler.postDelayed(mRunnable, BATCH_RESPONSE_DELAY_MS);
        }
    }

    /**
     * Creates a cache key for use with the L1 cache.
     * @param url The URL of the request.
     * @param maxWidth The max-width of the output.
     * @param maxHeight The max-height of the output.
     */
    private static String getCacheKey(String url, int maxWidth, int maxHeight) {
        return new StringBuilder(256).append("#W").append(maxWidth)
                .append("#H").append(maxHeight).append(url).toString();
    }
}
