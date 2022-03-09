package com.github.tarn2206.tooling;

import java.io.File;
import java.util.List;

/**
 * @author tarn on 09 March 2022 16:49
 */
public class ProjectInfo
{
    public final String name;
    public final File buildFile;
    public final List<ProjectInfo> children;

    ProjectInfo(String name, File buildFile, List<ProjectInfo> children)
    {
        this.name = name;
        this.buildFile = buildFile;
        this.children = children;
    }

    @Override
    public String toString()
    {
        return name;
    }
}
