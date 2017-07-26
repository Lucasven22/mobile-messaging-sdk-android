package org.infobip.mobile.messaging.notification;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import org.infobip.mobile.messaging.BroadcastParameter;
import org.infobip.mobile.messaging.ConfigurationException;
import org.infobip.mobile.messaging.Message;
import org.infobip.mobile.messaging.MobileMessagingCore;
import org.infobip.mobile.messaging.MobileMessagingProperty;
import org.infobip.mobile.messaging.NotificationSettings;
import org.infobip.mobile.messaging.app.ActivityLifecycleMonitor;
import org.infobip.mobile.messaging.dal.bundle.InteractiveCategoryBundleMapper;
import org.infobip.mobile.messaging.dal.bundle.MessageBundleMapper;
import org.infobip.mobile.messaging.logging.MobileMessagingLogger;
import org.infobip.mobile.messaging.util.PreferenceHelper;
import org.infobip.mobile.messaging.util.ResourceLoader;
import org.infobip.mobile.messaging.util.StringUtils;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;
import java.util.Set;

import static org.infobip.mobile.messaging.BroadcastParameter.EXTRA_NOTIFICATION_ID;
import static org.infobip.mobile.messaging.BroadcastParameter.EXTRA_TRIGGERED_ACTION_ID;
import static org.infobip.mobile.messaging.BroadcastParameter.EXTRA_TRIGGERED_CATEGORY;

/**
 * @author sslavin
 * @since 15/09/16.
 */
public class NotificationHandlerImpl implements NotificationHandler {

    private static final int DEFAULT_NOTIFICATION_ID = 0;

    private final Context context;

    public NotificationHandlerImpl(Context context) {
        this.context = context;
    }

    /**
     * {@inheritDoc}
     */
    public void displayNotification(Message message) {
        int notificationId = getNotificationId(message);
        NotificationCompat.Builder builder = notificationCompatBuilder(message, notificationId);
        if (builder == null) return;

        //issue: http://stackoverflow.com/questions/13602190/java-lang-securityexception-requires-vibrate-permission-on-jelly-bean-4-2
        try {
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            Notification notification = builder.build();
            MobileMessagingLogger.v("NOTIFY FOR MESSAGE", message);
            notificationManager.notify(notificationId, notification);
        } catch (SecurityException se) {
            MobileMessagingLogger.e("Unable to vibrate", new ConfigurationException(ConfigurationException.Reason.MISSING_REQUIRED_PERMISSION, Manifest.permission.VIBRATE));
            MobileMessagingLogger.d(Log.getStackTraceString(se));
        }
    }

    private NotificationCompat.Builder notificationCompatBuilder(Message message, int notificationId) {
        NotificationSettings notificationSettings = notificationSettings(message);
        if (notificationSettings == null) return null;

        String category = message.getCategory();
        InteractiveCategory triggeredInteractiveCategory = triggeredInteractiveCategory(category);

        String title = StringUtils.isNotBlank(message.getTitle()) ? message.getTitle() : notificationSettings.getDefaultTitle();
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(context)
                .setContentTitle(title)
                .setContentText(message.getBody())
                .setAutoCancel(notificationSettings.isNotificationAutoCancel())
                .setContentIntent(createPendingIntent(notificationSettings, message))
                .setWhen(message.getReceivedTimestamp());

        setNotificationStyle(notificationBuilder, message, title);
        setNotificationSoundAndVibrate(notificationBuilder, message);
        setNotificationIcon(notificationBuilder, message);
        setNotificationActions(notificationBuilder, triggeredInteractiveCategory, notificationId);

        return notificationBuilder;
    }

    private void setNotificationActions(NotificationCompat.Builder notificationBuilder,
                                        InteractiveCategory triggeredInteractiveCategory,
                                        int notificationId) {
        if (triggeredInteractiveCategory == null) {
            return;
        }

        NotificationAction[] notificationActions = triggeredInteractiveCategory.getNotificationActions();
        for (NotificationAction notificationAction : notificationActions) {
            PendingIntent pendingIntent = createPendingIntent(triggeredInteractiveCategory, notificationAction.getId(), notificationId);
            notificationBuilder.addAction(
                    new NotificationCompat.Action(notificationAction.getIcon(), notificationAction.getTitle(), pendingIntent));
        }
    }

    private InteractiveCategory triggeredInteractiveCategory(String category) {
        if (StringUtils.isBlank(category)) {
            return null;
        }

        Set<InteractiveCategory> storedInteractiveCategories = MobileMessagingCore.getInstance(context).getInteractiveNotificationCategories();
        if (storedInteractiveCategories == MobileMessagingProperty.INTERACTIVE_CATEGORIES.getDefaultValue()) {
            return null;
        }

        for (InteractiveCategory interactiveCategory : storedInteractiveCategories) {
            if (category.equals(interactiveCategory.getCategoryId())) {
                return interactiveCategory;
            }
        }

        return null;
    }

    private void setNotificationStyle(NotificationCompat.Builder notificationBuilder, Message message, String title) {
        String contentUrl = message.getContentUrl();
        Bitmap notificationPicture = fetchNotificationPicture(contentUrl);

        if (notificationPicture == null) {
            notificationBuilder.setStyle(new NotificationCompat.BigTextStyle()
                    .bigText(message.getBody())
                    .setBigContentTitle(title));
            return;
        }

        notificationBuilder.setLargeIcon(notificationPicture);
        notificationBuilder.setStyle(new NotificationCompat.BigPictureStyle()
                .bigPicture(notificationPicture)
                .bigLargeIcon(null)
                .setBigContentTitle(title)
                .setSummaryText(message.getBody()));
    }

    @Nullable
    private static Bitmap downloadBitmap(@NonNull String contentUrl) {
        try {
            URL url = new URL(contentUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            InputStream input = connection.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            input.close();
            return bitmap;

        } catch (Exception e) {
            MobileMessagingLogger.e(e.getMessage());
            return null;
        }
    }

    @Nullable
    @VisibleForTesting
    Bitmap fetchNotificationPicture(String contentUrl) {
        if (contentUrl == null) return null;

        int maxRetries = PreferenceHelper.findInt(context, MobileMessagingProperty.DEFAULT_MAX_RETRY_COUNT);
        for (int i = 0; i < maxRetries; i++) {
            Bitmap bitmap = downloadBitmap(contentUrl);
            if (bitmap != null) {
                return bitmap;
            }
        }

        return null;
    }

    @SuppressWarnings("WrongConstant")
    @NonNull
    private PendingIntent createPendingIntent(NotificationSettings notificationSettings, Message message) {
        Intent intent = new Intent(context, NotificationTapReceiver.class);
        intent.putExtra(BroadcastParameter.EXTRA_MESSAGE, MessageBundleMapper.messageToBundle(message));
        intent.putExtra(MobileMessagingProperty.EXTRA_INTENT_FLAGS.getKey(), notificationSettings.getIntentFlags());
        return PendingIntent.getBroadcast(context, 0, intent, notificationSettings.getPendingIntentFlags());
    }

    @SuppressWarnings("WrongConstant")
    @NonNull
    private PendingIntent createPendingIntent(InteractiveCategory interactiveCategory, String triggeredActionId, int notificationId) {
        Intent intent = new Intent(context, NotificationActionReceiver.class);
        intent.setAction(triggeredActionId);
        intent.putExtra(EXTRA_TRIGGERED_ACTION_ID, triggeredActionId);
        intent.putExtra(EXTRA_TRIGGERED_CATEGORY, InteractiveCategoryBundleMapper.interactiveCategoryToBundle(interactiveCategory));
        intent.putExtra(EXTRA_NOTIFICATION_ID, notificationId);
        return PendingIntent.getBroadcast(context, notificationId, intent, PendingIntent.FLAG_CANCEL_CURRENT);
    }

    private NotificationSettings notificationSettings(Message message) {
        NotificationSettings notificationSettings = MobileMessagingCore.getInstance(context).getNotificationSettings();
        if (null == notificationSettings) {
            return null;
        }

        if (!notificationSettings.isDisplayNotificationEnabled() ||
                null == notificationSettings.getCallbackActivity()) {
            return null;
        }

        if (StringUtils.isBlank(message.getBody())) {
            return null;
        }

        if (ActivityLifecycleMonitor.isForeground() && notificationSettings.isForegroundNotificationDisabled()) {
            return null;
        }

        return notificationSettings;
    }

    private void setNotificationIcon(NotificationCompat.Builder notificationBuilder, Message message) {
        NotificationSettings notificationSettings = notificationSettings(message);
        if (notificationSettings == null) return;

        int icon;
        if (StringUtils.isNotBlank(message.getIcon())) {
            icon = ResourceLoader.loadResourceByName(context, "drawable", message.getIcon());
        } else {
            icon = notificationSettings.getDefaultIcon();
        }
        notificationBuilder.setSmallIcon(icon);
    }

    private void setNotificationSoundAndVibrate(NotificationCompat.Builder notificationBuilder, Message message) {
        int notificationDefaults = Notification.DEFAULT_ALL;
        if (!message.isVibrate()) {
            notificationDefaults &= ~Notification.DEFAULT_VIBRATE;
        } else if (message.isVibrate() && ContextCompat.checkSelfPermission(context, Manifest.permission.VIBRATE) == PackageManager.PERMISSION_DENIED) {
            notificationDefaults &= ~Notification.DEFAULT_VIBRATE;
            MobileMessagingLogger.e("Unable to vibrate", new ConfigurationException(ConfigurationException.Reason.MISSING_REQUIRED_PERMISSION, Manifest.permission.VIBRATE));
        }
        if (!message.isDefaultSound()) {
            notificationDefaults &= ~Notification.DEFAULT_SOUND;
        }
        notificationBuilder.setDefaults(notificationDefaults);

        String sound = message.getSound();
        if (message.isDefaultSound() || StringUtils.isBlank(sound)) {
            return;
        }

        Uri soundUri = Uri.parse("android.resource://" + context.getPackageName() + "/raw/" + sound);
        if (soundUri == null) {
            MobileMessagingLogger.e("Cannot create uri for sound:" + sound + " messageId:" + message.getMessageId());
            return;
        }

        notificationBuilder.setSound(soundUri);
    }

    private int getNotificationId(Message message) {
        NotificationSettings settings = notificationSettings(message);
        if (settings == null) {
            return DEFAULT_NOTIFICATION_ID;
        }

        boolean areMultipleNotificationsEnabled = settings.areMultipleNotificationsEnabled();
        return areMultipleNotificationsEnabled ? new Random().nextInt() : DEFAULT_NOTIFICATION_ID;
    }
}
