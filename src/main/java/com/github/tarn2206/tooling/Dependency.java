package com.github.tarn2206.tooling;

import java.util.Objects;

public class Dependency
{
    public String group;
    public String name;
    public String version;
    public String latestVersion;
    public String error;

    public Dependency(String group, String name, String version)
    {
        this.group = group;
        this.name = name;
        this.version = version;
    }

    public String key()
    {
        return group + ':' + name;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Dependency that = (Dependency)o;
        return Objects.equals(group, that.group) && Objects.equals(name, that.name) && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(group, name, version);
    }

    public boolean hasLatestVersion()
    {
        return latestVersion != null && (latestVersion.length() != version.length() || latestVersion.compareTo(version) > 0);
    }

    @Override
    public String toString()
    {
        return version == null ? group + ':' + name : group + ':' + name + ':' + version;
    }
}
