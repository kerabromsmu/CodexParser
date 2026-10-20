/*
 * Copyright 2026 Dmitrii Sapelkin (Dimage)
 * SPDX-License-Identifier: Apache-2.0
 */
package se.dimage.codexparser;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import java.nio.file.Path;

/** Dialog boundary allows repeatable workflows without automating OS-owned pickers. */
interface DesktopDialogs {
    Path chooseDirectory(Shell parent, String title);

    Path chooseMarkdownFile(Shell parent, String filename);

    void showText(Shell parent, String title, String text);

    void showError(Shell parent, String title, Throwable error);

    final class Native implements DesktopDialogs {
        @Override
        public Path chooseDirectory(Shell parent, String title) {
            var dialog = new DirectoryDialog(parent);
            dialog.setText(title);
            dialog.setMessage(title);
            String selected = dialog.open();
            return selected == null ? null : Path.of(selected);
        }

        @Override
        public Path chooseMarkdownFile(Shell parent, String filename) {
            var dialog = new FileDialog(parent, SWT.SAVE);
            dialog.setText("Export conversation as Markdown");
            dialog.setFilterNames("Markdown (*.md)");
            dialog.setFilterExtensions("*.md");
            dialog.setFileName(filename);
            // ExportService preserves existing files by adding a numeric suffix.
            dialog.setOverwrite(false);
            String selected = dialog.open();
            return selected == null ? null : Path.of(selected);
        }

        @Override
        public void showText(Shell parent, String title, String text) {
            var dialog = new Shell(parent, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
            dialog.setText(title);
            dialog.setLayout(new GridLayout());
            var area = new Text(dialog, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
            area.setText(text);
            area.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            var close = new Button(dialog, SWT.PUSH);
            close.setText("Close");
            close.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
            close.addListener(SWT.Selection, _ -> dialog.close());
            dialog.setDefaultButton(close);
            dialog.addListener(SWT.Traverse, event -> {
                if (event.detail == SWT.TRAVERSE_ESCAPE) dialog.close();
            });
            dialog.setSize(800, 460);
            dialog.open();
            var display = parent.getDisplay();
            while (!dialog.isDisposed()) {
                if (!display.readAndDispatch()) display.sleep();
            }
        }

        @Override
        public void showError(Shell parent, String title, Throwable error) {
            var dialog = new MessageBox(parent, SWT.OK | SWT.ICON_ERROR);
            dialog.setText(title);
            dialog.setMessage(error.getMessage() == null ? error.toString() : error.getMessage());
            dialog.open();
        }
    }
}
