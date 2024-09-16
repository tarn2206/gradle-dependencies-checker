package com.github.tarn2206.tooling;

import java.util.Objects;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class Dependency
{
    private final String group;
    private final String name;
    private final String version;
    private String latestVersion;
    private String status;
    private String error;

    public Dependency(String name)
    {
        this(null, name, null);
    }

    public Dependency(String group, String name, String version)
    {
        this.group = group;
        this.name = name;
        this.version = version != null && version.contains(" ") ? version.substring(0, version.indexOf(' ')) : version;
    }

    public boolean hasGroup()
    {
        return group != null && !group.equals("project ");
    }

    public boolean sameModule(Dependency o)
    {
        return o != null && Objects.equals(group, o.group) && Objects.equals(name, o.name);
    }

    @Override
    public String toString()
    {
        var s = new StringBuilder();
        if (hasGroup())
        {
            s.append(group).append(":");
        }
        s.append(name);
        if (version != null)
        {
            s.append(":").append(version);
        }
        return s.toString();
    }
}
