package com.foobnix.pdf.info.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.dao2.FileMeta;
import com.foobnix.librerax.LibreraX;
import com.foobnix.model.AppData;
import com.foobnix.model.AppProfile;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.IMG;
import com.foobnix.pdf.info.R;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.MainTabs2;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The last opened book on the home screen: cover, title, author and progress. A tap opens it
 * where it was left. Refreshed by {@link RecentUpates#updateAll()} whenever the recent list
 * changes, so it always follows the newest book.
 *
 * <pre>
 * +--------+---------------------+
 * |        | Title               |
 * | cover  | Author              |
 * |        | 42%                 |
 * +--------+---------------------+
 * </pre>
 */
public class ContinueReadingWidget extends AppWidgetProvider {

    private static final int PERCENT = 100;

    private static final Handler handler = new Handler(Looper.getMainLooper());

    /** Asks every placed widget to redraw. */
    public static void refresh(Context c) {
        Intent intent = new Intent(c, ContinueReadingWidget.class);
        intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        int[] ids = AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, ContinueReadingWidget.class));
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
        c.sendBroadcast(intent);
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null || ids.length == 0) {
            return;
        }
        final Context c = context.getApplicationContext();
        AppProfile.init(c);

        // The recent list reads files and the database: off the main thread
        AppsConfig.executorService.execute(() -> {
            final FileMeta last = lastBook(c);
            handler.post(() -> show(c, manager, ids, last));
        });
    }

    private static FileMeta lastBook(Context c) {
        try {
            List<FileMeta> recent = new ArrayList<>(AppData.get().getAllRecent(true));
            AppDB.removeClouds(recent);
            return recent.isEmpty() ? null : recent.get(0);
        } catch (Exception e) {
            LOG.e(e);
            return null;
        }
    }

    private static void show(Context c, AppWidgetManager manager, int[] ids, FileMeta last) {
        // Nothing read yet: the widget opens the library
        if (last == null) {
            RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_continue_reading);
            Intent intent = new Intent(c, MainTabs2.class);
            views.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 0, intent, PendingIntent.FLAG_IMMUTABLE));
            manager.updateAppWidget(ids, views);
            return;
        }

        IMG.getCoverPageWithEffect(c, last.getPath(), null).into(new CustomTarget<Bitmap>() {

            @Override public void onResourceReady(@NonNull Bitmap cover, @Nullable Transition<? super Bitmap> transition) {
                update(c, manager, ids, last, cover);
            }

            @Override public void onLoadFailed(@Nullable Drawable errorDrawable) {
                update(c, manager, ids, last, null);
            }

            @Override public void onLoadCleared(@Nullable Drawable placeholder) {
            }
        });
    }

    private static void update(Context c, AppWidgetManager manager, int[] ids, FileMeta last, Bitmap cover) {
        try {
            RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_continue_reading);
            if (cover != null) {
                views.setImageViewBitmap(R.id.cover, cover);
            }

            String title = TxtUtils.isNotEmpty(last.getTitle()) ? last.getTitle() : new File(last.getPath()).getName();
            views.setTextViewText(R.id.title, title);
            views.setTextViewText(R.id.author, TxtUtils.nullToEmpty(last.getAuthor()));
            views.setTextViewText(R.id.progress, percent(last));

            // The reader restores the place the book was closed at
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.fromFile(new File(last.getPath())));
            intent.setClassName(c, LibreraX.readerClass().getName());
            views.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

            manager.updateAppWidget(ids, views);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** "42%", or empty for a book not started. */
    private static String percent(FileMeta meta) {
        Float progress = meta.getIsRecentProgress();
        if (progress == null || progress <= 0f) {
            return "";
        }
        return Math.round(PERCENT * progress) + "%";
    }
}
