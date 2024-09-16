package com.github.tarn2206.tooling;

import java.io.File;
import java.util.List;

/**
 * @author tarn on 09 March 2022 16:49
 */
public record ProjectInfo(String name, File buildFile, List<ProjectInfo> children)
{
    @Override
    public String toString()
    {
        return name;
    }
}
