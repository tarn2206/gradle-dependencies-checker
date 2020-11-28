package com.github.tarn2206.ui;

import javax.swing.tree.DefaultMutableTreeNode;

public class Item
{
    final DefaultMutableTreeNode node;
    final String path;

    Item(DefaultMutableTreeNode node, String path)
    {
        this.node = node;
        this.path = path;
    }
}
