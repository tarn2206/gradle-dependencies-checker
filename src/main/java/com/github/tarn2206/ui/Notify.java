package com.github.tarn2206.ui;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationDisplayType;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Notify
{
    private static final NotificationGroup NOTIFICATION_GROUP = new NotificationGroup("Dependencies Updates", NotificationDisplayType.BALLOON, true);

    public static void info(@Nullable Project project, @NotNull @Nls(capitalization = Nls.Capitalization.Sentence) String content)
    {
        notify(project, content, NotificationType.INFORMATION);
    }

    public static void warning(@Nullable Project project, @NotNull @Nls(capitalization = Nls.Capitalization.Sentence) String content)
    {
        notify(project, content, NotificationType.WARNING);
    }

    public static void error(@Nullable Project project, @NotNull @Nls(capitalization = Nls.Capitalization.Sentence) String content)
    {
        notify(project, content, NotificationType.ERROR);
    }

    private static Notification notify(Project project, String content, NotificationType type)
    {
        final Notification notification = NOTIFICATION_GROUP.createNotification(content, type);
        notification.notify(project);
        return notification;
    }
}
