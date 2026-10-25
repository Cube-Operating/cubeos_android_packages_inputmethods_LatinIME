/*
 * Copyright (C) 2026 CubeOS
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

package com.android.inputmethod.latin;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;

import java.util.function.Consumer;

/**
 * CubeOS: the keyboard sheet as iOS 26 renders it, a pane of glass over the app behind it.
 *
 * Read from iOS 26.3's UIKitCore (UIKBRenderConfig, keyboard backdrop styles): the app behind is
 * blurred (radius 20 dark, 30 light), then tinted (dark: near-black at 85 %; light: a pale
 * white-grey), the keycaps sit on top, and a hairline of light runs along the sheet's top edge.
 * iOS also halves the blurred content's saturation (0.9 light); SurfaceFlinger's blur has no
 * saturation control, which under an 85 % tint is barely visible.
 *
 * The blur is a region behind the sheet only ({@link BackgroundBlurDrawable}), not the whole
 * keyboard window. When the system turns window blur off (battery saver, unsupported device) the
 * sheet falls back to the solid colour of the theme.
 */
final class CubeKeyboardGlass {

    private static final float BLUR_DARK_DP = 20f;
    private static final float BLUR_LIGHT_DP = 30f;
    private static final int TINT_DARK = 0xD9050505;   // grayscale level 0.02 at 85 %
    private static final int TINT_LIGHT = 0xC4E9EBEF;  // white tint with burn and darkening

    private CubeKeyboardGlass() {}

    /**
     * Makes [frame] (suggestion strip + keys) the glass sheet. [strip] and [keys] lose their own
     * backgrounds while the glass is on, so the blur shows through them.
     */
    static void apply(final View frame, final View strip, final View keys) {
        final Drawable stripBackground = strip.getBackground();
        final Drawable keysBackground = keys.getBackground();
        final View.OnAttachStateChangeListener glass = new View.OnAttachStateChangeListener() {
            private Consumer<Boolean> mListener;

            @Override
            public void onViewAttachedToWindow(View v) {
                final WindowManager wm = v.getContext().getSystemService(WindowManager.class);
                mListener = enabled -> {
                    android.util.Log.d("CubeGlass", "window blur " + (enabled ? "on" : "off")
                            + (v.getViewRootImpl() == null ? " (not in a window yet)" : ""));
                    if (enabled && v.getViewRootImpl() != null) {
                        v.setBackground(glass(v));
                        strip.setBackground(new Rim(v.getContext()));
                        keys.setBackground(null);
                    } else {
                        v.setBackground(null);
                        strip.setBackground(stripBackground);
                        keys.setBackground(keysBackground);
                    }
                };
                wm.addCrossWindowBlurEnabledListener(v.getContext().getMainExecutor(), mListener);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (mListener == null) return;
                v.getContext().getSystemService(WindowManager.class)
                        .removeCrossWindowBlurEnabledListener(mListener);
                mListener = null;
            }
        };
        frame.addOnAttachStateChangeListener(glass);
        // The keyboard's view may already be in its window by the time the IME hands it over.
        if (frame.isAttachedToWindow()) glass.onViewAttachedToWindow(frame);
    }

    /** True while the sheet is glass, so the navigation bar can match its tint. */
    static boolean isGlass(final View keys) {
        return keys.getBackground() == null;
    }

    static int tint(final Context context) {
        return isDark(context) ? TINT_DARK : TINT_LIGHT;
    }

    private static boolean isDark(final Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static Drawable glass(final View frame) {
        final ViewRootImpl root = frame.getViewRootImpl();
        final Context context = frame.getContext();
        final float density = context.getResources().getDisplayMetrics().density;
        final float corner = context.getResources().getDimension(R.dimen.keyboard_panel_corner_radius_you);
        final BackgroundBlurDrawable blur = root.createBackgroundBlurDrawable();
        blur.setBlurRadius(Math.round((isDark(context) ? BLUR_DARK_DP : BLUR_LIGHT_DP) * density));
        blur.setCornerRadius(corner, corner, 0, 0);
        blur.setColor(tint(context));
        return blur;
    }

    /**
     * The glass rim: a hairline along the sheet's top edge and round its top corners, fading out
     * down the sides.
     */
    private static final class Rim extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final float mCorner;
        private final float mFade;
        private final int mColor;

        Rim(final Context context) {
            mCorner = context.getResources().getDimension(R.dimen.keyboard_panel_corner_radius_you);
            mFade = mCorner * 1.5f;
            mColor = context.getColor(R.color.keyboard_panel_rim_you);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(context.getResources().getDimension(R.dimen.keyboard_panel_rim_you));
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect b) {
            final float half = mPaint.getStrokeWidth() / 2f;
            final RectF r = new RectF(b.left + half, b.top + half, b.right - half, b.bottom + mCorner);
            mPath.reset();
            mPath.addRoundRect(r, new float[] {mCorner, mCorner, mCorner, mCorner, 0, 0, 0, 0},
                    Path.Direction.CW);
            mPaint.setShader(new LinearGradient(0, b.top, 0, b.top + mFade,
                    mColor, mColor & 0x00FFFFFF, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.save();
            canvas.clipRect(getBounds());
            canvas.drawPath(mPath, mPaint);
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) { mPaint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { mPaint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}