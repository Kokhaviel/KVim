package fr.kokhaviel.kvim.api.actions.todos;

import fr.kokhaviel.kvim.api.gui.KVimTab;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;

public class KVimTODO extends JFrame implements Serializable {

	public KVimTODOManager manager;

	public KVimTODO(KVimTab tab) throws HeadlessException, IOException, ClassNotFoundException {
		super("TODO Manager");
		this.setSize(400, 200);
		this.setLocationRelativeTo(null);
		this.setIconImage(new ImageIcon(ClassLoader.getSystemResource("kvim/kvim-104x93.png")).getImage());

		manager = new KVimTODOManager(tab.getRootProjPath());

		final JButton add_todo = new JButton("Add TODO");
		final JButton edit_todo = new JButton("Edit TODO");
		final JButton rm_todo = new JButton("Remove TODO");
		JPanel jPanel = new JPanel(new BorderLayout());
		JPanel left = new JPanel(new GridLayout(0, 1));
		JPanel right = new JPanel(new GridLayout(0, 1));


		if(Files.exists(manager.todoFilePath)) {
			manager.read();
		}

		DefaultListModel<String> model = new DefaultListModel<>();

		JList<String> todoList = new JList<>();
		if(manager.items.size() > 0) {
			manager.items.forEach(item -> model.addElement(item.getName()));
		}

		todoList.setModel(model);

		JScrollPane scrollPane = new JScrollPane(todoList);
		left.add(scrollPane);


		jPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		right.add(add_todo);
		right.add(edit_todo);
		right.add(rm_todo);
		jPanel.add(left, BorderLayout.WEST);
		jPanel.add(right, BorderLayout.EAST);
		this.getContentPane().add(jPanel);

		add_todo.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				manager.addGUI(KVimTODO.this, left);
				update(left);
				if(!manager.items.isEmpty()) {
					todoList.setSelectedIndex(0);
				}
			}
		});

		edit_todo.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				if(todoList.isSelectionEmpty()) return;

				try {
					manager.items.remove(manager.map.get(todoList.getSelectedValue()));
					manager.write();
					manager.editGUI(manager.map.get(todoList.getSelectedValue()), KVimTODO.this, left);
				} catch(IOException | ClassNotFoundException e) {
					throw new RuntimeException(e);
				}
			}
		});

		rm_todo.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				if(todoList.isSelectionEmpty()) return;

				try {
					manager.items.remove(manager.map.get(todoList.getSelectedValue()));
					manager.write();
					manager.read();
					update(left);
				} catch(IOException | ClassNotFoundException e) {
					throw new RuntimeException(e);
				}

			}
		});

		todoList.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				if(mouseEvent.getClickCount() == 2) {
					int index = todoList.locationToIndex(mouseEvent.getPoint());
					if(index >= 0) {
						String o = todoList.getModel().getElementAt(index);
						new KVimTODOShow(manager.map.get(o)).setVisible(true);
					}
				}
			}
		});

		this.pack();
	}

	public void update(JPanel panel) {

		panel.removeAll();

		DefaultListModel<String> model = new DefaultListModel<>();
		JList<String> todoList = new JList<>();
		if(KVimTODOManager.manager.items.size() > 0) {
			KVimTODOManager.manager.items.forEach(item -> model.addElement(item.getName()));
		}

		todoList.setModel(model);

		todoList.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				if(mouseEvent.getClickCount() == 2) {
					int index = todoList.locationToIndex(mouseEvent.getPoint());
					if(index >= 0) {
						String o = todoList.getModel().getElementAt(index);
						new KVimTODOShow(KVimTODOManager.manager.map.get(o)).setVisible(true);
					}
				}
			}
		});

		JScrollPane scrollPane = new JScrollPane(todoList);
		panel.add(scrollPane);

		this.revalidate();
		this.pack();
		this.repaint();
	}
}
