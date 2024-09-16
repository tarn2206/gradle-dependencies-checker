package com.github.tarn2206;

import java.util.List;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "DependenciesCheckerSettings", storages = @Storage("dependencies-checker.xml"))
@Getter @Setter
public class AppSettings implements PersistentStateComponent<AppSettings>
{
    private List<Repo> repos;
    private boolean ignoreUnstable = true;
    private String unstablePatterns;

    public static AppSettings getInstance()
    {
        var settings = ApplicationManager.getApplication().getService(AppSettings.class);
        if (settings.repos == null || settings.repos.isEmpty())
        {
            settings.repos = List.of(new Repo(true, "Maven Central", "https://repo.maven.apache.org/maven2"),
                                     new Repo(true, "Google’s Android", "https://dl.google.com/dl/android/maven2"));
        }
        if (settings.ignoreUnstable && StringUtils.isBlank(settings.unstablePatterns))
        {
            settings.unstablePatterns = "alpha, beta, -M, incubator, rc, snapshot";
        }
        return settings;
    }

    @Override
    public @Nullable AppSettings getState()
    {
        return this;
    }

    @Override
    public void loadState(@NotNull AppSettings state)
    {
        XmlSerializerUtil.copyBean(state, this);
    }

    @Getter @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Repo
    {
        private boolean active;
        private String name;
        private String url;
    }
}
