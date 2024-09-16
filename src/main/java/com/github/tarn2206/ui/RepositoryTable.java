package com.github.tarn2206.ui;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import com.github.tarn2206.AppSettings;
import com.intellij.openapi.actionSystem.ActionToolbarPosition;
import com.intellij.ui.TableUtil;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;

public class RepositoryTable extends JPanel
{
    private final List<AppSettings.Repo> repos = new ArrayList<>();
    private final DefaultTableModel model;
    private final JBTable table;

    public RepositoryTable()
    {
        model = new DefaultTableModel(null, new String[] { "", "Name", "URL" })
        {
            @Override
            public int getRowCount()
            {
                return repos.size();
            }

            @Override
            public Class<?> getColumnClass(int columnIndex)
            {
                return columnIndex == 0 ? Boolean.class : String.class;
            }

            @Override
            public Object getValueAt(int row, int column)
            {
                var repo = repos.get(row);
                if (column == 0) return repo.isActive();
                if (column == 1) return repo.getName();
                if (column == 2) return repo.getUrl();
                return null;
            }

            @Override
            public void setValueAt(Object value, int row, int column)
            {
                var repo = repos.get(row);
                if (column == 0) repo.setActive((boolean)value);
                else if (column == 1) repo.setName((String)value);
                else if (column == 2) repo.setUrl((String)value);
            }
        };
        table = new JBTable(model);
        table.setShowGrid(false);
        table.setIntercellSpacing(JBUI.emptySize());
        table.setDragEnabled(false);
        table.getEmptyText().setText("No maven repository");
        table.setShowVerticalLines(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        var tablePanel = ToolbarDecorator.createDecorator(table)
                                         .setAddAction(e -> addEntry())
                                         .setRemoveAction(e -> removeEntry())
                                         .setMoveUpAction(e -> moveUp())
                                         .setMoveDownAction(e -> moveDown())
                                         .setToolbarPosition(ActionToolbarPosition.BOTTOM)
                                         .createPanel();
        setLayout(new BorderLayout());
        add(tablePanel, BorderLayout.CENTER);

        setColumnWidth(0, 30);
        setColumnWidth(1, 200);
    }

    public List<AppSettings.Repo> getRepos()
    {
        return new ArrayList<>(repos);
    }

    public void setRepos(List<AppSettings.Repo> list)
    {
        repos.clear();
        repos.addAll(list);
    }

    private void addEntry()
    {
        var cellEditor = table.getCellEditor();
        if (cellEditor != null) cellEditor.stopCellEditing();

        var row = repos.size();
        repos.add(new AppSettings.Repo(true, "New Repo", "https://"));
        model.fireTableRowsInserted(row, row);
        TableUtil.editCellAt(table, row, 1);
    }

    private void removeEntry()
    {
        var row = table.getSelectedRow();
        repos.remove(row);
        model.fireTableRowsDeleted(row, row);
        if (!repos.isEmpty())
        {
            if (row == repos.size()) row--;
            table.setRowSelectionInterval(row, row);
        }
    }

    private void moveUp()
    {
        var row = table.getSelectedRow();
        swap(row, row - 1);
        model.fireTableRowsUpdated(row - 1, row);
    }

    private void moveDown()
    {
        var row = table.getSelectedRow();
        swap(row, row + 1);
        model.fireTableRowsUpdated(row, row + 1);
    }

    private void swap(int row, int newRow)
    {
        Collections.swap(repos, row, newRow);
        table.setRowSelectionInterval(newRow, newRow);
    }

    private void setColumnWidth(int column, int width)
    {
        var col = table.getColumnModel().getColumn(column);
        col.setWidth(width);
        col.setMinWidth(width);
        col.setMaxWidth(width);
    }
}
