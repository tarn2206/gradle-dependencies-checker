package com.github.tarn2206.actions;

import com.github.tarn2206.AppSettings;
import com.github.tarn2206.ui.DependenciesView;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import org.jetbrains.annotations.NotNull;

public class SortAction extends AnAction {
    private final DependenciesView view;

    public SortAction(DependenciesView view) {
        super("Sort By", "Sort dependencies in the tree", AllIcons.ObjectBrowser.Sorted);
        this.view = view;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        var group = new DefaultActionGroup();
        for (var order : AppSettings.SortOrder.values()) {
            group.add(new SetSortOrderAction(order, view));
        }
        var popup = JBPopupFactory.getInstance()
                .createActionGroupPopup("Sort By", group, e.getDataContext(),
                        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true);

        var input = e.getInputEvent();
        if (input != null && input.getComponent() != null) {
            popup.showUnderneathOf(input.getComponent());
        } else {
            popup.showInBestPositionFor(e.getDataContext());
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /** One entry per {@link AppSettings.SortOrder}. Checkmark shows current selection. */
    private static class SetSortOrderAction extends ToggleAction {
        private final AppSettings.SortOrder order;
        private final DependenciesView view;

        SetSortOrderAction(AppSettings.SortOrder order, DependenciesView view) {
            super(order.getDisplayName());
            this.order = order;
            this.view = view;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return AppSettings.getInstance().getSortOrder() == order;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            if (state && AppSettings.getInstance().getSortOrder() != order) {
                AppSettings.getInstance().setSortOrder(order);
                view.applyCurrentSort();
            }
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }
}