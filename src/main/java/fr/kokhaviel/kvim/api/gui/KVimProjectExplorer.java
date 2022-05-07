package fr.kokhaviel.kvim.api.gui;

import fr.kokhaviel.kvim.api.actions.file.KVimNewFile;
import fr.kokhaviel.kvim.api.actions.file.KVimOpen;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Vector;

import static fr.kokhaviel.kvim.gui.KVimMain.tabs;

public class KVimProjectExplorer extends JPanel {

	public KVimProjectExplorer(File dir) {
		setLayout(new BorderLayout());

		JTree tree = new JTree(addNodes(null, dir));

		tree.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				if(mouseEvent.getClickCount() == 2) {
					TreePath tp = tree.getPathForLocation(mouseEvent.getX(), mouseEvent.getY());

					StringBuilder builder = new StringBuilder(dir.toPath().getParent().toString() + "/");
					int i = 0;
					for(int var3 = tp.getPathCount(); i < var3; ++i) {
						if (i > 0) {
							builder.append("/");
						}

						builder.append(tp.getPathComponent(i));
					}

					KVimNewFile.createNewTab(builder.toString());
					try {
						tabs.get(tabs.size() - 1).setText(KVimOpen.getFileContent(Paths.get(builder.toString())));
					} catch(IOException e) {
						throw new RuntimeException(e);
					}
				}
			}
		});

		JScrollPane scrollpane = new JScrollPane();
		scrollpane.getViewport().add(tree);
		add(BorderLayout.CENTER, scrollpane);
	}

	DefaultMutableTreeNode addNodes(DefaultMutableTreeNode curTop, File dir) {
		String curPath = dir.getName();
		DefaultMutableTreeNode curDir = new DefaultMutableTreeNode(curPath);
		if(curTop != null) {
			curTop.add(curDir);
		}
		Vector<String> ol = new Vector<>();
		String[] tmp = dir.list();

		if(tmp == null) return curDir;

		for(String s : tmp) {
			ol.addElement(s);
		}
		ol.sort(String.CASE_INSENSITIVE_ORDER);
		File f;
		Vector<String> files = new Vector<>();
		for(int i = 0; i < ol.size(); i++) {
			String thisObject = ol.elementAt(i);
			String newPath;
			if(dir.getPath().equals("."))
				newPath = thisObject;
			else
				newPath = dir.getPath() + File.separator + thisObject;

			if((f = new File(newPath)).isDirectory())
				addNodes(curDir, f);
			else
				files.addElement(thisObject);
		}
		for(int fnum = 0; fnum < files.size(); fnum++)
			curDir.add(new DefaultMutableTreeNode(files.elementAt(fnum)));

		return curDir;
	}

	public Dimension getMinimumSize() {
		return new Dimension(200, 400);
	}

	public Dimension getPreferredSize() {
		return new Dimension(200, 400);
	}
}
