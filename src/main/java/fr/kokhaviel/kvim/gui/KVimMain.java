package fr.kokhaviel.kvim.gui;

import fr.kokhaviel.kvim.api.actions.RecentFile;
import fr.kokhaviel.kvim.api.actions.file.KVimOpen;
import fr.kokhaviel.kvim.api.gui.KVimTab;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;

import static fr.kokhaviel.kvim.KVim.kVimProperties;
import static fr.kokhaviel.kvim.api.props.DefaultKVimProperties.*;

public class KVimMain extends JFrame {

	private KVimTab currentTabIndex;

	public static List<KVimTab> tabs = new ArrayList<>();
	public static KVimMain kVimMain;

	Path file;
	String height = kVimProperties.getLastParams().getProperty("height");
	String width = kVimProperties.getLastParams().getProperty("width");
	String x = kVimProperties.getLastParams().getProperty("x");
	String y = kVimProperties.getLastParams().getProperty("y");

	public KVimMain() throws HeadlessException {
		super("Untitled - KVim");
		this.file = null;
		tabs.add(new KVimTab(null, 0));
		initFrame();
	}

	public KVimMain(Path file) throws HeadlessException, IOException {
		super(file.getFileName() + " - KVim");
		this.file = file;
		tabs.add(new KVimTab(file, 0));
		tabs.get(0).setText(KVimOpen.getFileContent(file));
		KVimOpen.updateRecent(new RecentFile(file.toFile().getName(), file.getParent()));
		initFrame();
	}

	public void initFrame() {
		this.setBackground(new Color(R, G, B));
		this.setIconImage(new ImageIcon(ClassLoader.getSystemResource("kvim/kvim-104x93.png")).getImage());
		this.setSize(Integer.parseInt(width), Integer.parseInt(height));
		this.setLocation(Integer.parseInt(x), Integer.parseInt(y));
		this.setLayout(new BorderLayout());
		this.setMinimumSize(new Dimension(480, 325));
		this.setJMenuBar(new KVimMenuBar(tabs.get(0)));
		this.getContentPane().add(new KVimTabNav(), BorderLayout.NORTH);
		this.getContentPane().add(tabs.get(0), BorderLayout.CENTER);
		this.setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
		this.addWindowListener(new KVimCloseApp());
		kVimMain = this;
	}

	public void updateTabNav(int index) {
		this.add(new KVimTabNav(), BorderLayout.NORTH);
		this.setJMenuBar(new KVimMenuBar(tabs.get(index)));
	}

	public void updateTab(int index, boolean up) {
		this.getContentPane().removeAll();
		updateTabNav(index);
		final KVimTab kVimTab = tabs.get(index);
		if(up) kVimTab.setCaretPosition(0);
		JScrollPane sp = new JScrollPane(kVimTab);
		this.add(sp, BorderLayout.CENTER);
		this.currentTabIndex = kVimTab;
		this.getContentPane().revalidate();
		this.getContentPane().repaint();
	}

	public class KVimCloseApp extends WindowAdapter {

		@Override
		public void windowClosing(WindowEvent windowEvent) {
			int clickedButton = JOptionPane.showConfirmDialog(KVimMain.this,
					"Are you sure to close app ? All unsaved modifications will be LOST !", "Close KVim x'(", JOptionPane.YES_NO_OPTION);

			if(clickedButton == JOptionPane.YES_OPTION) {
				kVimProperties.getLastParams().replace("height", String.valueOf(KVimMain.this.getHeight()));
				kVimProperties.getLastParams().replace("width", String.valueOf(KVimMain.this.getWidth()));
				kVimProperties.getLastParams().replace("x", String.valueOf(KVimMain.this.getX()));
				kVimProperties.getLastParams().replace("y", String.valueOf(KVimMain.this.getY()));
				try {
					kVimProperties.getLastParams().store(Files.newOutputStream(Paths.get(kVimProperties.getPropsDir() + "/last_params.properties")), null);
				} catch(IOException ignored) {
				}
				KVimMain.this.dispose();
				System.exit(0);
			}
		}
	}
}
