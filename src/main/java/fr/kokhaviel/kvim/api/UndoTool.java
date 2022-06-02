package fr.kokhaviel.kvim.api;

import javax.swing.*;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.awt.event.ActionEvent;

public class UndoTool {
	private static final String REDO_KEY = "redo";
	private static final String UNDO_KEY = "undo";

	private final JTextComponent component;
	private final KeyStroke undo = KeyStroke.getKeyStroke("control Z");
	private final KeyStroke redo = KeyStroke.getKeyStroke("control Y");

	public UndoTool(JTextComponent component) {
		this.component = component;
	}

	public static void addUndoFunctionality(JTextComponent component) {
		UndoTool tool = new UndoTool(component);
		UndoManager undo = tool.createAndBindUndoManager();
		tool.bindUndo(undo);
		tool.bindRedo(undo);
	}

	public UndoManager createAndBindUndoManager() {
		UndoManager manager = new UndoManager();
		Document document = component.getDocument();
		document.addUndoableEditListener(event -> manager.addEdit(event.getEdit()));
		return manager;
	}

	public void bindRedo(UndoManager manager) {
		component.getActionMap().put(REDO_KEY, new AbstractAction(REDO_KEY) {
			@Override
			public void actionPerformed(ActionEvent evt) {
				try {
					if (manager.canRedo()) {
						manager.redo();
					}
				} catch (CannotRedoException ignore) {
				}
			}
		});
		component.getInputMap().put(redo, REDO_KEY);
	}

	public void bindUndo(UndoManager manager) {
		component.getActionMap().put(UNDO_KEY, new AbstractAction(UNDO_KEY) {
			@Override
			public void actionPerformed(ActionEvent evt) {
				try {
					if (manager.canUndo()) {
						manager.undo();
					}
				} catch (CannotUndoException ignore) {
				}
			}
		});
		component.getInputMap().put(undo, UNDO_KEY);
	}
}
