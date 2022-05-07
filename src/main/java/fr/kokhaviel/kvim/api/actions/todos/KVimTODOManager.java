package fr.kokhaviel.kvim.api.actions.todos;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;

public class KVimTODOManager implements Serializable {

	private static final long serialVersionUID = 541236512365L;
	final transient Path todoFilePath;
	List<KVimTODOItem> items = new ArrayList<>();
	HashMap<String, KVimTODOItem> map = new HashMap<>();
	public static KVimTODOManager manager;

	public KVimTODOManager(Path projPath) {
		this.todoFilePath = Paths.get(projPath + "/.kvim/todo.kvim");
		manager = this;
	}

	@SuppressWarnings("unchecked")
	public void read() throws IOException, ClassNotFoundException {
		ObjectInputStream in = new ObjectInputStream(Files.newInputStream(todoFilePath.toFile().toPath()));
		this.items = (List<KVimTODOItem>) in.readObject();

		map.clear();
		items.forEach(item -> map.put(item.getName(), item));
	}

	public void write() throws IOException {
		ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(todoFilePath.toFile().toPath()));
		out.writeObject(items);
		out.close();
	}

	public void addGUI(KVimTODO frame, JPanel updatedPanel) {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Which File ?");
		chooser.setCurrentDirectory(todoFilePath.getParent().toFile());
		int ans = chooser.showOpenDialog(null);

		if(ans == JFileChooser.APPROVE_OPTION) {
			JFrame jFrame = new JFrame("Add TODO to " + chooser.getSelectedFile().getName());
			JPanel jPanel = new JPanel();
			JButton addBtn = new JButton("Add");
			JTextField nameFld = new JTextField();
			JTextField contentsFld = new JTextField();
			JPanel left = new JPanel(new GridLayout(0, 1));
			JPanel right = new JPanel(new GridLayout(0, 1));
			JPanel bot = new JPanel(new BorderLayout());

			jFrame.setLocationRelativeTo(null);
			jFrame.setIconImage(new ImageIcon(ClassLoader.getSystemResource("kvim/kvim-104x93.png")).getImage());
			jPanel.setLayout(new BorderLayout());
			nameFld.setPreferredSize(new Dimension(120, 25));
			contentsFld.setPreferredSize(new Dimension(240, 25));

			jPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
			left.add(new JLabel("Name : "));
			right.add(nameFld);
			left.add(new JLabel("Contents : "));
			right.add(contentsFld);
			bot.add(addBtn, BorderLayout.EAST);

			jPanel.add(left, BorderLayout.WEST);
			jPanel.add(right, BorderLayout.EAST);
			jPanel.add(bot, BorderLayout.SOUTH);
			jFrame.add(jPanel);

			jFrame.pack();
			jFrame.setVisible(true);

			addBtn.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent mouseEvent) {
					try {
						File parent = chooser.getSelectedFile();
						List<File> files;
						List<String> relativePathList = new ArrayList<>();
						relativePathList.add(chooser.getSelectedFile().getName());

						do {
							parent = parent.getParentFile();
							files = Arrays.asList(Objects.requireNonNull(parent.listFiles()));
							relativePathList.add(parent.getName());
						} while(!files.contains(todoFilePath.getParent().toFile()));

						if(Files.exists(todoFilePath)) {
							read();
						}

						StringBuilder relativeArray = new StringBuilder();
						for(int i = relativePathList.size() - 1; i >= 0; i--) {
							relativeArray.append(relativePathList.get(i));
							if(i != 0) relativeArray.append("/");
						}

						items.add(0, new KVimTODOItem(nameFld.getText(), chooser.getSelectedFile(), relativeArray.toString(), contentsFld.getText()));
						write();
						frame.update(updatedPanel);
						jFrame.dispose();
						frame.requestFocusInWindow();
					} catch(IOException | ClassNotFoundException e) {
						throw new RuntimeException(e);
					}
				}
			});
		}

	}

	public void editGUI(KVimTODOItem item, KVimTODO frame, JPanel updatedPanel) throws IOException, ClassNotFoundException {
		JFrame jFrame = new JFrame("Edit TODO Task " + item.getName());
		JPanel jPanel = new JPanel();
		JButton updateBtn = new JButton("Update");
		JTextField nameFld = new JTextField();
		JTextField contentsFld = new JTextField();
		JPanel left = new JPanel(new GridLayout(0, 1));
		JPanel right = new JPanel(new GridLayout(0, 1));
		JPanel bot = new JPanel(new BorderLayout());

		items.remove(item);
		read();

		jFrame.setLocationRelativeTo(null);
		jFrame.setIconImage(new ImageIcon(ClassLoader.getSystemResource("kvim/kvim-104x93.png")).getImage());
		jPanel.setLayout(new BorderLayout());
		nameFld.setPreferredSize(new Dimension(120, 25));
		nameFld.setText(item.getName());
		contentsFld.setPreferredSize(new Dimension(240, 25));
		contentsFld.setText(item.getContents());

		jPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		left.add(new JLabel("Name : "));
		right.add(nameFld);
		left.add(new JLabel("Contents : "));
		right.add(contentsFld);
		bot.add(updateBtn, BorderLayout.EAST);

		jPanel.add(left, BorderLayout.WEST);
		jPanel.add(right, BorderLayout.EAST);
		jPanel.add(bot, BorderLayout.SOUTH);
		jFrame.add(jPanel);

		jFrame.pack();
		jFrame.setVisible(true);

		updateBtn.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent mouseEvent) {
				item.setName(nameFld.getText());
				item.setContents(contentsFld.getText());

				items.add(0, new KVimTODOItem(item.getName(), item.getFile(), item.getRelativePath(), item.getContents()));

				try {
					write();
				} catch(IOException e) {
					throw new RuntimeException(e);
				}

				frame.update(updatedPanel);
				jFrame.dispose();
			}
		});
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder("[");
		for(int i = 0; i < items.size() - 1; i++) {
			builder.append(items.get(i));
			builder.append(',');
		}

		builder.append(items.get(items.size() - 1));
		builder.append("]");
		return builder.toString();
	}
}
