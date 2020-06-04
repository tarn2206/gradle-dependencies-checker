package com.github.tarn2206.tooling;

import java.util.ArrayList;
import java.util.List;
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

    @Override
    public String toString()
    {
        return version == null ? group + ':' + name : group + ':' + name + ':' + version;
    }

    public boolean hasLatestVersion()
    {
        if (latestVersion == null) return false;

        List<Integer> current = extractVersion(version);
        List<Integer> latest = extractVersion(latestVersion);
        int min = Math.min(current.size(), latest.size());
        for (int i = 0; i < min; i++)
        {
            if (latest.get(i).equals(current.get(i))) continue;
            return latest.get(i) > current.get(i);
        }
        return false;
    }

    private List<Integer> extractVersion(String version)
    {
        List<Integer> list = new ArrayList<>();
        String[] a = version.split("\\.");
        for (String s : a)
        {
            try
            {
                int n = Integer.parseInt(s);
                list.add(n);
            }
            catch (Exception e)
            {
                break;
            }
        }
        return list;
    }
}
