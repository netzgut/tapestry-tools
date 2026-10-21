package net.netzgut.eclipse.tapestry.internal.ui;

import java.util.List;
import java.util.function.Consumer;

import org.eclipse.jface.dialogs.DialogSettings;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.PopupDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import net.netzgut.eclipse.tapestry.internal.core.NameMatch;

/// A filterable list of related files in a borderless popup, like JDT's Quick Outline (Ctrl+O).
/// Focus stays in the filter field: type to filter, Up/Down to move, Enter to open (or create), Esc
/// to close.
class QuickSwitchPopup extends PopupDialog {

    private final List<Object>     elements;
    private final Consumer<Object> opener;

    // Disposed by the viewer (through DelegatingStyledCellLabelProvider) together with the table
    private final RelatedElementLabelProvider labels = new RelatedElementLabelProvider();

    private Text        filter;
    private TableViewer viewer;

    QuickSwitchPopup(Shell parent, List<Object> elements, String info, Consumer<Object> opener) {
        super(parent,
              PopupDialog.INFOPOPUPRESIZE_SHELLSTYLE,
              true, // take focus
              true, // remember size
              true, // remember location
              false,
              false,
              "", // a (non-null) title makes PopupDialog call createTitleControl, where the filter goes
              info);
        this.elements = elements;
        this.opener = opener;
    }

    @Override
    protected Control createTitleControl(Composite parent) {
        this.filter = new Text(parent, SWT.SINGLE);
        this.filter.setMessage("Filter related files");

        // PopupDialog lays out its own title label; a replacement must bring layout data or it gets its minimum width
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.CENTER).grab(true, false).applyTo(this.filter);
        this.filter.addModifyListener(_ -> {
            this.viewer.refresh();
            selectFirst();
        });

        this.filter.addKeyListener(KeyListener.keyPressedAdapter(this::filterKeyPressed));

        return this.filter;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        this.viewer = new TableViewer(parent, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        this.viewer.setContentProvider(ArrayContentProvider.getInstance());
        this.viewer.setLabelProvider(new DelegatingStyledCellLabelProvider(this.labels));
        this.viewer.addFilter(new ViewerFilter() {

            @Override
            public boolean select(Viewer v, Object parentElement, Object element) {
                String query = QuickSwitchPopup.this.filter.getText().strip();
                String name = QuickSwitchPopup.this.labels.name(element);
                return NameMatch.matches(query, name);
            }
        });

        this.viewer.addOpenListener(_ -> openSelection()); // double click, Enter in the table
        this.viewer.setInput(this.elements);

        selectFirst();

        return this.viewer.getControl();
    }

    private void filterKeyPressed(KeyEvent event) {
        Table table = this.viewer.getTable();
        switch (event.keyCode) {
            case //
                SWT.ARROW_DOWN, //
                SWT.ARROW_UP -> {
                int count = table.getItemCount();
                if (count > 0) {
                    int step = -1;
                    if (event.keyCode == SWT.ARROW_DOWN) {
                        step = 1;
                    }

                    int selectedIndex = table.getSelectionIndex();
                    int index = Math.floorMod(selectedIndex + step, count); // wraps around
                    Object element = this.viewer.getElementAt(index);
                    this.viewer.setSelection(new StructuredSelection(element), true);
                }
                event.doit = false; // don't move the cursor in the text field
            }

            case //
                SWT.CR, //
                SWT.KEYPAD_CR -> openSelection();

            default -> {
                // typing goes to the filter
            }
        }
    }

    private void selectFirst() {
        Object first = this.viewer.getElementAt(0);
        StructuredSelection selection;
        if (first == null) {
            selection = StructuredSelection.EMPTY;
        }
        else {
            selection = new StructuredSelection(first);
        }

        this.viewer.setSelection(selection, true);
    }

    private void openSelection() {
        Object selected = this.viewer.getStructuredSelection().getFirstElement();
        close();
        if (selected != null) {
            this.opener.accept(selected);
        }
    }

    @Override
    protected Control getFocusControl() {
        return this.filter;
    }

    @Override
    protected IDialogSettings getDialogSettings() {
        Bundle bundle = FrameworkUtil.getBundle(getClass());
        IDialogSettings settings = PlatformUI.getDialogSettingsProvider(bundle).getDialogSettings();
        return DialogSettings.getOrCreateSection(settings, "QuickSwitchPopup");
    }

    @Override
    protected Point getDefaultSize() {
        Point size = super.getDefaultSize();
        // TODO: use clamp
        int width = Math.max(560, Math.min(size.x, 900));
        int height = Math.min(size.y, 420);
        return new Point(width, height);
    }
}
