package com.github.tarn2206;

import java.util.List;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "DependenciesCheckerSettings", storages = @Storage("dependencies-checker.xml"))
public class AppSettings implements PersistentStateComponent<AppSettings>
{
    public List<Repo> repos;
    public boolean ignoreUnstable = true;
    public String unstablePatterns;

    public static AppSettings getInstance()
    {
        var settings = ApplicationManager.getApplication().getService(AppSettings.class);
        if (settings.repos == null || settings.repos.isEmpty())
        {
            settings.repos = List.of(new AppSettings.Repo(true, "Maven Central", "https://repo.maven.apache.org/maven2"),
                                     new AppSettings.Repo(true, "Google’s Android", "https://dl.google.com/dl/android/maven2"));
        }
        if (settings.ignoreUnstable && StringUtils.isBlank(settings.unstablePatterns))
        {
            settings.unstablePatterns = "alpha, beta, -M, incubator, rc, snapshot";
        }
        return settings;
    }

    @Nullable
    @Override
    public AppSettings getState()
    {
        return this;
    }

    @Override
    public void loadState(@NotNull AppSettings state)
    {
        XmlSerializerUtil.copyBean(state, this);
    }

    public static class Repo
    {
        public boolean active;
        public String name;
        public String url;

        public Repo() {}

        public Repo(boolean active, String name, String url)
        {
            this.active = active;
            this.name = name;
            this.url = url;
        }
    }
}
