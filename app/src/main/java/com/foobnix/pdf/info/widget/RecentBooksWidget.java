package com.foobnix.pdf.info.widget;

import static android.util.TypedValue.COMPLEX_UNIT_PX;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.IntentCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.foobnix.LibreraApp;
import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.LOG;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppData;
import com.foobnix.librerax.LibreraX;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.IMG;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.search.activity.HorizontalViewActivity;
import com.foobnix.sys.ImageExtractor;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.MainTabs2;

import org.ebookdroid.ui.viewer.VerticalViewActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RecentBooksWidget extends AppWidgetProvider {

    public static final String TEST_LOCALE_POS = "TEST_LOCALE_POS";
    private static final String ACTION_MY = "my";
    private Context context;

    private static final int COVER_TIMEOUT_SEC = 10;
    private static final int BYTES_PER_PIXEL = 4;

    @SuppressLint("NewApi")
    @Override
    public void onReceive(Context context, Intent intent) {
        this.context = context;
        AppProfile.init(context);

        LOG.d("RecentBooksWidget", intent, intent.getData(), intent.getExtras());
        if (intent.getAction().equals(ACTION_MY)) {

            Class clazz = LibreraX.readerClass();

            Intent nintent = new Intent(Intent.ACTION_VIEW, IntentCompat.getParcelableExtra(intent, "uri", Uri.class));
            nintent.setClassName(context, clazz.getName());
            PendingIntent pendingIntent;

            pendingIntent = PendingIntent.getActivity(context, 0, nintent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            try {
                pendingIntent.send();
            } catch (CanceledException e) {
            }

        }

        if (intent.getAction().equals("android.appwidget.action.APPWIDGET_UPDATE")) {
            int[] appWidgetIds = AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, RecentBooksWidget.class));
            if (Build.VERSION.SDK_INT >= 16 && AppState.get().widgetType == AppState.WIDGET_GRID) {
                AppWidgetManager.getInstance(context).notifyAppWidgetViewDataChanged(appWidgetIds, R.id.gridView1);
            }
            onUpdate(context, AppWidgetManager.getInstance(context), appWidgetIds);
        }

        super.onReceive(context, intent);
    }


    @Override
    public synchronized void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {

        try {
            for (int widgetId : appWidgetIds) {
                RemoteViews remoteViews = null;
                if (Build.VERSION.SDK_INT >= 16 && AppState.get().widgetType == AppState.WIDGET_GRID) {
                    remoteViews = new RemoteViews(context.getPackageName(), R.layout.recent_images_widget_grid);
                    // remoteViews.setInt(R.id.gridView1, "setColumnWidth", 100);
                    updateGrid(remoteViews, appWidgetManager, widgetId);


                    appWidgetManager.updateAppWidget(widgetId, remoteViews);
                } else {
                    remoteViews = new RemoteViews(context.getPackageName(), R.layout.recent_images_widget_list);
                    updateList(remoteViews, appWidgetManager, widgetId);
                }

            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, Bundle newOptions) {
        Intent intent = new Intent(context, RecentBooksWidget.class);
        intent.setAction("android.appwidget.action.APPWIDGET_UPDATE");
        context.sendBroadcast(intent);
    }

    @SuppressLint("NewApi")
    private void updateGrid(RemoteViews remoteViews, AppWidgetManager appWidgetManager, int appWidgetId) {
        Intent intent = new Intent(context, StackGridWidgetService.class);
        remoteViews.setRemoteAdapter(R.id.gridView1, intent);

        Intent toastIntent = new Intent(context, RecentBooksWidget.class);
        toastIntent.setAction(ACTION_MY);
        PendingIntent toastPendingIntent = PendingIntent.getBroadcast(context, 0, toastIntent, PendingIntent.FLAG_MUTABLE);
        remoteViews.setPendingIntentTemplate(R.id.gridView1, toastPendingIntent);
    }

    private void updateList(final RemoteViews remoteViews, AppWidgetManager appWidgetManager, int appWidgetId) {
        final Context c = context.getApplicationContext();
        AppsConfig.executorService.execute(() -> loadAndUpdateWidget(c, remoteViews, appWidgetManager, appWidgetId));
    }

    /**
     * Loads every cover first, then sends the widget once.
     *
     * It used to clear the row and add each cover as it arrived, sending the widget after each
     * one: a cover that failed to load was missing for good, and a cold start - the app frozen
     * or killed, as Boox readers do - could leave an empty row or the sample-books picture.
     * Now a load that finds nothing keeps the covers the widget already shows.
     */
    private static void loadAndUpdateWidget(Context c, RemoteViews remoteViews, AppWidgetManager manager, int appWidgetId) {
        final List<FileMeta> recent = new ArrayList<>();
        try {
            if (AppState.get().isStarsInWidget) {
                recent.addAll(AppData.get().getAllFavoriteFiles(false));
            } else {
                recent.addAll(AppData.get().getAllRecent(false));
            }
            AppDB.removeClouds(recent);
        } catch (Exception e) {
            LOG.e(e);
            return;
        }

        // The widget host refuses an update whose bitmaps outgrow the screen: stop short of it
        final long budget = bitmapBudget(c);
        long used = 0;

        String className = LibreraX.readerClass().getName();
        List<RemoteViews> items = new ArrayList<>();
        for (int i = 0; i < recent.size() && items.size() < AppState.get().widgetItemsCount; i++) {
            FileMeta fileMeta = recent.get(i);

            Bitmap cover = loadCover(c, fileMeta.getPath());
            if (cover == null) {
                continue;
            }
            used += cover.getByteCount();
            if (used > budget) {
                break;
            }

            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_list_image);
            v.setImageViewBitmap(R.id.imageView1, cover);

            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.fromFile(new File(fileMeta.getPath())));
            intent.setClassName(c, className);
            v.setOnClickPendingIntent(R.id.imageView1, PendingIntent.getActivity(c, i, intent, PendingIntent.FLAG_IMMUTABLE));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                v.setViewLayoutHeight(R.id.imageView1, IMG.getImageSize(), COMPLEX_UNIT_PX);
            }
            v.setViewPadding(R.id.imageView1, Dips.DP_2, Dips.DP_2, Dips.DP_2, Dips.DP_2);
            items.add(v);
        }

        // Books listed but no cover loaded: a transient failure, keep what is shown
        if (items.isEmpty() && !recent.isEmpty()) {
            LOG.d("RecentBooksWidget no cover loaded, keeping the current row");
            return;
        }

        // Nothing read yet: the app icon on a clear background opens the library
        if (items.isEmpty()) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_list_image);
            v.setImageViewResource(R.id.imageView1, R.mipmap.icon_pdf_pro);
            Intent intent = new Intent(c, MainTabs2.class);
            v.setOnClickPendingIntent(R.id.imageView1, PendingIntent.getActivity(c, 0, intent, PendingIntent.FLAG_IMMUTABLE));
            items.add(v);
        }

        remoteViews.removeAllViews(R.id.linearLayout);
        for (RemoteViews v : items) {
            remoteViews.addView(R.id.linearLayout, v);
        }
        try {
            manager.updateAppWidget(appWidgetId, remoteViews);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    private static Bitmap loadCover(Context c, String path) {
        try {
            return IMG.getCoverPageWithEffect(c, path, null).submit().get(COVER_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (Throwable e) {
            LOG.d("RecentBooksWidget cover failed", path);
            return null;
        }
    }

    /** Half of what the widget host accepts: screen width x height x 4 bytes x 1.5. */
    private static long bitmapBudget(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        return (long) m.widthPixels * m.heightPixels * BYTES_PER_PIXEL * 3 / 4;
    }

    public Bitmap scaleCenterCrop(Bitmap source, int newHeight, int newWidth) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();

        // Compute the scaling factors to fit the new height and width,
        // respectively.
        // To cover the final image, the final scaling will be the bigger
        // of these two.
        float xScale = (float) newWidth / sourceWidth;
        float yScale = (float) newHeight / sourceHeight;
        float scale = Math.max(xScale, yScale);

        // Now get the size of the source bitmap when scaled
        float scaledWidth = scale * sourceWidth;
        float scaledHeight = scale * sourceHeight;

        // Let's find out the upper left coordinates if the scaled bitmap
        // should be centered in the new size give by the parameters
        float left = (newWidth - scaledWidth) / 2;
        float top = (newHeight - scaledHeight) / 2;

        // The target rectangle for the new, scaled version of the source bitmap
        // will now
        // be
        RectF targetRect = new RectF(left, top, left + scaledWidth, top + scaledHeight);

        // Finally, we create a new bitmap of the specified size and draw our
        // new,
        // scaled bitmap onto it.
        Bitmap dest = Bitmap.createBitmap(newWidth, newHeight, source.getConfig());
        Canvas canvas = new Canvas(dest);
        canvas.drawBitmap(source, null, targetRect, null);

        return dest;
    }
}
