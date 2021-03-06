/*
 * Copyright (C) 2015 Lyft, Inc.
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
package com.lyft.android.scissors;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.ViewTreeObserver;

import java.io.File;
import java.io.OutputStream;
import java.util.concurrent.Future;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

class CropViewExtensions {

    static void pickUsing(Activity activity, int requestCode) {
        activity.startActivityForResult(
                createChooserIntent(),
                requestCode);
    }

    static void pickUsing(Fragment fragment, int requestCode) {
        fragment.startActivityForResult(
                createChooserIntent(),
                requestCode);
    }

    private static Intent createChooserIntent() {
        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        return Intent.createChooser(intent, null);
    }

    public static class LoadRequest {

        private final CropView cropView;
        private BitmapLoader bitmapLoader;

        LoadRequest(CropView cropView) {
            Utils.checkNotNull(cropView, "cropView == null");
            this.cropView = cropView;
        }

        /**
         * Load a {@link Bitmap} using given {@link BitmapLoader}, you must call {@link LoadRequest#load(Object)} afterwards.
         *
         * @param bitmapLoader {@link BitmapLoader} to use
         * @return current request for chaining, you should call {@link #load(Object)} afterwards.
         */
        public LoadRequest using(@Nullable BitmapLoader bitmapLoader) {
            this.bitmapLoader = bitmapLoader;
            return this;
        }

        /**
         * Load a {@link Bitmap} using a {@link BitmapLoader} into {@link CropView}
         *
         * @param model Model used by {@link BitmapLoader} to load desired {@link Bitmap}
         */
        public void load(@Nullable Object model) {
            if (cropView.getWidth() == 0 && cropView.getHeight() == 0) {
                // Defer load until layout pass
                deferLoad(model);
                return;
            }
            performLoad(model);
        }

        void performLoad(Object model) {
            if (bitmapLoader == null) {
                bitmapLoader = resolveBitmapLoader(cropView);
            }
            bitmapLoader.load(model, cropView);
        }

        void deferLoad(final Object model) {
            if (!cropView.getViewTreeObserver().isAlive()) {
                return;
            }
            cropView.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            if (cropView.getViewTreeObserver().isAlive()) {
                                //noinspection deprecation
                                cropView.getViewTreeObserver().removeGlobalOnLayoutListener(this);
                            }
                            performLoad(model);
                        }
                    }
            );
        }
    }

    public static class CropRequest {

        private final CropView cropView;
        private Bitmap.CompressFormat format = Bitmap.CompressFormat.JPEG;
        private int quality = CropViewConfig.DEFAULT_IMAGE_QUALITY;
        private int requestedWidth = 0;
        private int requestedHeight = 0;

        CropRequest(@NonNull CropView cropView) {
            Utils.checkNotNull(cropView, "cropView == null");
            this.cropView = cropView;
        }

        /**
         * Compression format to use, defaults to {@link Bitmap.CompressFormat#JPEG}.
         *
         * @return current request for chaining.
         */
        public CropRequest format(@NonNull Bitmap.CompressFormat format) {
            Utils.checkNotNull(format, "format == null");
            this.format = format;
            return this;
        }

        /**
         * Compression quality to use (must be 0..100), defaults to {@value CropViewConfig#DEFAULT_IMAGE_QUALITY}.
         *
         * @return current request for chaining.
         */
        public CropRequest quality(int quality) {
            Utils.checkArg(quality >= 0 && quality <= 100, "quality must be 0..100");
            this.quality = quality;
            return this;
        }

        /**
         * Maximum dimensions of the output image. By default, the output image is not downscaled.
         *
         * @param width  Maximum width in pixels
         * @param height Maximum height in pixels
         * @return current request for chaining.
         */
        public CropRequest dimensions(int width, int height) {
            Utils.checkArg(width >= 0 && height >= 0, "requested width and height must be >= 0");
            this.requestedWidth = width;
            this.requestedHeight = height;
            return this;
        }

        /**
         * Asynchronously flush cropped bitmap into provided file, creating parent directory if required. This is performed in another
         * thread.
         *
         * @param file Must have permissions to write, will be created if doesn't exist or overwrite if it does.
         * @return {@link Future} used to cancel or wait for this request.
         */
        public Future<Void> into(@NonNull File file) {
            final Bitmap croppedBitmap = cropView.crop(requestedWidth, requestedHeight);
            return Utils.flushToFile(croppedBitmap, format, quality, file);
        }

        /**
         * Asynchronously flush cropped bitmap into provided stream.
         *
         * @param outputStream  Stream to write to
         * @param closeWhenDone wetter or not to close provided stream once flushing is done
         * @return {@link Future} used to cancel or wait for this request.
         */
        public Future<Void> into(@NonNull OutputStream outputStream, boolean closeWhenDone) {
            final Bitmap croppedBitmap = cropView.crop(requestedWidth, requestedHeight);
            return Utils.flushToStream(croppedBitmap, format, quality, outputStream, closeWhenDone);
        }
    }

    final static boolean HAS_PICASSO = canHasClass("com.squareup.picasso.Picasso");
    final static boolean HAS_GLIDE = canHasClass("com.bumptech.glide.Glide");
    final static boolean HAS_UIL = canHasClass("com.nostra13.universalimageloader.core.ImageLoader");

    static BitmapLoader resolveBitmapLoader(CropView cropView) {
        if (HAS_PICASSO) {
            return PicassoBitmapLoader.createUsing(cropView);
        }
        if (HAS_GLIDE) {
            return GlideBitmapLoader.createUsing(cropView);
        }
        if (HAS_UIL) {
            return UILBitmapLoader.createUsing(cropView);
        }
        throw new IllegalStateException("You must provide a BitmapLoader.");
    }

    static boolean canHasClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
        }
        return false;
    }

    static Rect computeTargetSize(int sourceWidth, int sourceHeight, int viewportWidth, int viewportHeight) {

        if (sourceWidth == viewportWidth && sourceHeight == viewportHeight) {
            return new Rect(0, 0, viewportWidth, viewportHeight); // Fail fast for when source matches exactly on viewport
        }

        float scale;
        if (sourceWidth * viewportHeight > viewportWidth * sourceHeight) {
            scale = (float) viewportHeight / (float) sourceHeight;
        } else {
            scale = (float) viewportWidth / (float) sourceWidth;
        }
        final int recommendedWidth = (int) ((sourceWidth * scale) + 0.5f);
        final int recommendedHeight = (int) ((sourceHeight * scale) + 0.5f);
        return new Rect(0, 0, recommendedWidth, recommendedHeight);
    }
}
